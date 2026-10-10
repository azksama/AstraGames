"""Format-boundary tests, with optional read-only official-corpus integration."""
from contextlib import redirect_stderr
import importlib.util
import io
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("wolf_native_probe", ROOT / "tools" / "wolf_native_probe.py")
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


def u32(value):
    return struct.pack("<I", value)


def string(value, encoding="cp932"):
    raw = value.encode(encoding) + b"\0"
    return u32(len(raw)) + raw


def literal_lz4(data):
    if len(data) < 15:
        return bytes([len(data) << 4]) + data
    extra, tail = divmod(len(data) - 15, 255)
    return b"\xf0" + b"\xff" * extra + bytes([tail]) + data


def map_fixture(extended=False, command_bytes=None, event_count=1):
    # Numeric command framing: words include opcode; branch depth; string
    # count; route flag; then v3.50+ extension length and extension bytes.
    command_bytes = command_bytes if command_bytes is not None else (
        b"\x01" + u32(101) + b"\0\x01" + string("test") + b"\0" + (b"\x01\x01" if extended else b"")
    )
    page = b"\x79" + u32(0) + string("") + b"\x02\0\xff\0"
    page += bytes(37) + bytes(4) + bytes(2) + u32(0)
    page += u32(1) + command_bytes + u32(3) + bytes(3) + b"\x7a"
    event = b"\x6f\x39\x30\0\0" + u32(4) + string("event") + u32(0) * 2 + u32(1) + bytes(4)
    event += page + b"\x70"
    payload = string("map") + u32(0) + u32(1) * 2 + u32(event_count)
    payload += (u32(3) + u32(4)) if extended else b""
    payload += u32(17) * (4 if extended else 3) + event + b"\x66"
    magic = bytearray(probe.MAP_MAGIC)
    if extended:
        magic[16] = 0x55
        block = literal_lz4(payload)
        return bytes(magic) + u32(103) + b"\x69" + u32(len(payload)) + u32(len(block)) + block
    return bytes(magic) + u32(100) + b"\x65" + payload


def common_fixture(extended=False):
    commands = b"\x01" + u32(0) + bytes(3) + (b"\0" if extended else b"")
    event = b"\x8e" + u32(0) * 2 + bytes(7) + string("common") + u32(1) + commands
    event += string("") * 2 + b"\x8f" + u32(0) * 5 + b"\x90" + u32(0)
    event += string("") * 100 + b"\x91" + string("") + b"\x92" + string("") + u32(0) + b"\x92"
    payload = u32(1) + event + (b"\x92" if extended else b"\x8f")
    magic = bytearray(probe.COMMON_MAGIC)
    if extended:
        magic[6] = 0x55
        block = literal_lz4(payload)
        return bytes(magic) + b"\x93" + u32(len(payload)) + u32(len(block)) + block
    return bytes(magic) + b"\x8f" + payload


class StructuralProbeTests(unittest.TestCase):
    def test_old_map_consumes_layers_pages_commands_and_footer(self):
        result = probe.parse_map(map_fixture())
        self.assertEqual((result["width"], result["height"], result["layerCount"]), (1, 1, 3))
        self.assertTrue(result["payloadFullyConsumed"])
        self.assertEqual(result["events"][0]["pages"][0]["commands"][0]["strings"], ["test"])

    def test_new_map_consumes_four_layers_and_extension_bytes(self):
        result = probe.parse_map(map_fixture(extended=True))
        self.assertEqual(result["layerCount"], 4)
        self.assertEqual(result["events"][0]["pages"][0]["commands"][0]["extensionHex"], "01")

    def test_every_truncation_of_old_fixture_is_rejected(self):
        data = map_fixture()
        for length in range(len(data)):
            with self.subTest(length=length), self.assertRaises(probe.FormatError):
                probe.parse_map(data[:length])

    def test_missing_new_extension_is_rejected(self):
        line = b"\x01" + u32(101) + b"\0\x01" + string("test") + b"\0"
        with self.assertRaises(probe.FormatError):
            probe.parse_map(map_fixture(extended=True, command_bytes=line))

    def test_map_footer_and_trailing_bytes_are_rejected(self):
        for corrupted in (map_fixture()[:-1] + b"\x70", map_fixture() + b"\0"):
            with self.subTest(data=corrupted[-2:]), self.assertRaises(probe.FormatError):
                probe.parse_map(corrupted)

    def test_declared_event_count_mismatch_is_rejected(self):
        with self.assertRaises(probe.FormatError):
            probe.parse_map(map_fixture(event_count=0))

    def test_unknown_command_is_preserved_not_executed(self):
        line = b"\x01" + u32(0xFFFF) + bytes(3)
        result = probe.summarize(probe.parse_map(map_fixture(command_bytes=line)))
        self.assertEqual(result["opcodeHistogram"], [{"opcode": 0xFFFF, "name": "Unknown", "count": 1}])

    def test_invalid_command_word_count_and_route_marker(self):
        for line in (b"\0", b"\x01" + u32(101) + b"\0\0\x02"):
            with self.subTest(line=line), self.assertRaises(probe.FormatError):
                probe.parse_map(map_fixture(command_bytes=line))

    def test_cp932_and_utf8_are_strict(self):
        self.assertEqual(probe.Reader(string("日本"), encoding="cp932").string(), "日本")
        self.assertEqual(probe.Reader(string("日本", "utf-8"), encoding="utf-8").string(), "日本")
        with self.assertRaises(probe.FormatError):
            probe.Reader(u32(2) + b"\xff\0", encoding="utf-8").string()

    def test_strings_reject_zero_length_missing_nul_and_oversize(self):
        for encoded in (u32(0), u32(2) + b"ab", u32(4) + b"a\0b\0", u32(probe.MAX_BYTES + 1)):
            with self.subTest(encoded=encoded), self.assertRaises(probe.FormatError):
                probe.Reader(encoded).string()

    def test_lz4_repeated_overlapping_window(self):
        block = b"\x44abcd\x04\0\x50" + b"12345"
        self.assertEqual(probe.decode_lz4_block(block, 17), b"abcdabcdabcd12345")

    def test_lz4_rejects_zero_and_out_of_range_offsets(self):
        for block in (b"\x10a\0\0", b"\x10a\x02\0"):
            with self.subTest(block=block), self.assertRaises(probe.FormatError):
                probe.decode_lz4_block(block, 5)

    def test_lz4_rejects_size_mismatch_truncation_and_bomb(self):
        cases = [(b"\x10a", 2), (b"\xf0\xff", 1), (b"\x20a", 2),
                 (b"\x10a", probe.MAX_BYTES + 1), (b"\x1fa\x01\0\xff\xff\0", 32)]
        for block, expanded in cases:
            with self.subTest(block=block, expanded=expanded), self.assertRaises(probe.FormatError):
                probe.decode_lz4_block(block, expanded)

    def test_compressed_envelope_rejects_extra_data_and_wrong_size(self):
        original = map_fixture(extended=True)
        wrong_size = original[:25] + u32(probe.MAX_BYTES + 1) + original[29:]
        for data in (original + b"\0", wrong_size):
            with self.subTest(size=len(data)), self.assertRaises(probe.FormatError):
                probe.parse_map(data)

    def test_invalid_encoding_magic_and_wire_version_are_rejected(self):
        data = map_fixture()
        mutations = [data[:16] + b"\x01" + data[17:], data[:20] + u32(999) + data[24:]]
        for mutated in mutations:
            with self.subTest(mutated=mutated[:25]), self.assertRaises(probe.FormatError):
                probe.parse_map(mutated)

    def test_route_terminator_is_checked(self):
        self.assertEqual(probe.route(probe.Reader(u32(1) + b"\x1d\x01" + u32(2) + b"\x01\0")),
                         [{"opcode": 29, "arguments": [2]}])
        with self.assertRaises(probe.FormatError):
            probe.route(probe.Reader(u32(1) + b"\x1d\x00\0\0"))

    def test_both_observed_common_event_dialects(self):
        for extended in (False, True):
            with self.subTest(extended=extended):
                result = probe.parse_common_events(common_fixture(extended))
                self.assertTrue(result["payloadFullyConsumed"])
                self.assertEqual(result["events"][0]["name"], "common")
                self.assertEqual(result["events"][0]["commands"][0]["opcode"], 0)

    def test_common_event_footer_and_truncation_are_rejected(self):
        data = common_fixture()
        for mutated in (data[:-1], data[:-1] + b"\x93", data + b"\0"):
            with self.subTest(length=len(mutated)), self.assertRaises(probe.FormatError):
                probe.parse_common_events(mutated)

    def test_output_inside_input_directory_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            original = map_fixture()
            path = directory / "Map.mps"
            path.write_bytes(original)
            for output in (path, directory / "report.json"):
                with self.subTest(output=output.name), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failure:
                    probe.main([str(directory), "--output", str(output)])
                self.assertEqual(failure.exception.code, 2)
            self.assertEqual(path.read_bytes(), original)
            self.assertFalse((directory / "report.json").exists())

    def test_symlink_junction_check_applies_to_parent_components(self):
        for kind in ("is_symlink", "is_junction"):
            with self.subTest(kind=kind), \
                    patch.object(Path, "is_symlink", side_effect=lambda path: kind == "is_symlink" and path.name == "source", autospec=True), \
                    patch.object(Path, "is_junction", side_effect=lambda path: kind == "is_junction" and path.name == "source", autospec=True), \
                    self.assertRaises(probe.FormatError):
                probe.reject_links(Path("source/Map.mps"))

    def test_official_local_corpus_when_present(self):
        folders = [ROOT / "build/wolf-research/sample22961/WOLF_RPG_Editor2",
                   ROOT / "build/wolf-research/sample3729/WOLF_RPG_Editor3"]
        if not all(folder.is_dir() for folder in folders):
            self.skipTest("official packages are local ignored research fixtures")
        for folder in folders:
            maps = sorted((folder / "Data/MapData").glob("*.mps"))
            self.assertEqual(len(maps), 4)
            for path in maps:
                with self.subTest(file=path.name, version=folder.name):
                    result = probe.parse_map(path.read_bytes(), str(path))
                    self.assertTrue(result["payloadFullyConsumed"])
            path = folder / "Data/BasicData/CommonEvent.dat"
            result = probe.parse_common_events(path.read_bytes(), str(path))
            self.assertTrue(result["payloadFullyConsumed"])
            self.assertGreater(len(result["events"]), 200)


if __name__ == "__main__":
    unittest.main()
