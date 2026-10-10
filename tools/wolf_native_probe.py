#!/usr/bin/env python3
"""Read-only structural probe for unprotected Wolf RPG maps and common events.

This is a format inspector, not a game runtime. It decodes map/common-event
containers, layers, event pages, and complete command records; it does not
execute them.
The reader uses only the Python standard library and leaves game files intact.

Format references (MIT): Sinflower/WolfTL, commit
bfc38fc2735ab4f7f7ddbec8d57b7fef65f0712f, WolfRPG/{Map,Command,
RouteCommand,FileCoder,CommonEvents}.hpp; djytw/wolf-rpg-formats, commit
5c70e643693e08f99bf299a2ca598f7eaa02c542, mps.ksy and event_command.ksy.
LZ4 decoding is independently implemented from the block-format specification:
https://github.com/lz4/lz4/blob/v1.10.0/doc/lz4_Block_format.md
See docs/wolf-native/FORMATS_RESEARCH.md for observations and coverage limits.

The format-reading structure is derived from the above MIT references:
Copyright (c) 2024 Sinflower; Copyright (c) 2024 djytw.
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:
The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
"""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import struct
import sys

MAX_BYTES = 128 * 1024 * 1024
MAX_RECORDS = 1_000_000
MAP_MAGIC = bytes(10) + b"WOLFM\0\0\0\0\0"
COMMON_MAGIC = b"\0W\0\0OL\0FC\0"
OPCODE_NAMES = {
    0: "Blank", 99: "Checkpoint", 101: "Message", 102: "Choices",
    103: "Comment", 105: "ForceStopMessage", 106: "DebugMessage",
    107: "ClearDebugText", 111: "VariableCondition", 112: "StringCondition",
    121: "SetVariable", 122: "SetString", 123: "InputKey",
    124: "SetVariableEx", 125: "AutoInput", 126: "BanInput",
    130: "Teleport", 140: "Sound", 150: "Picture", 151: "ChangeColor",
    160: "SetTransition", 161: "PrepareTransition", 162: "ExecuteTransition",
    170: "StartLoop", 171: "BreakLoop", 172: "BreakEvent", 173: "EraseEvent",
    174: "ReturnToTitle", 175: "EndGame", 176: "StartLoop2",
    177: "StopNonPic", 178: "ResumeNonPic", 179: "LoopTimes", 180: "Wait",
    201: "Move", 202: "WaitForMove", 210: "CommonEvent",
    211: "CommonEventReserve", 212: "SetLabel", 213: "JumpLabel",
    220: "SaveLoad", 221: "LoadGame", 222: "SaveGame",
    230: "MoveDuringEventOn", 231: "MoveDuringEventOff", 240: "Chip",
    241: "ChipSet", 242: "OverwriteMapChips", 250: "Database",
    251: "ImportDatabase", 270: "Party", 280: "MapEffect",
    281: "ScrollScreen", 290: "Effect", 300: "CommonEventByName",
    401: "ChoiceCase", 402: "SpecialChoiceCase", 420: "ElseCase",
    421: "CancelCase", 498: "LoopEnd", 499: "BranchEnd",
    999: "Default", 1000: "ProFeature",
}


class FormatError(ValueError):
    """Unsupported or structurally invalid game data; never a VM error."""


class Reader:
    def __init__(self, data: bytes, encoding: str = "cp932", label: str = "map"):
        if len(data) > MAX_BYTES:
            raise FormatError(f"{label}: exceeds {MAX_BYTES}-byte probe limit")
        self.data = data
        self.pos = 0
        self.encoding = encoding
        self.label = label

    def fail(self, message: str):
        raise FormatError(f"{self.label}@0x{self.pos:x}: {message}")

    def take(self, size: int) -> bytes:
        if size < 0 or size > len(self.data) - self.pos:
            self.fail(f"truncated record: requested {size}, remaining {len(self.data) - self.pos}")
        start = self.pos
        self.pos += size
        return self.data[start:self.pos]

    def u8(self) -> int:
        return self.take(1)[0]

    def u32(self) -> int:
        return struct.unpack("<I", self.take(4))[0]

    def i32(self) -> int:
        return struct.unpack("<i", self.take(4))[0]

    def count(self, kind: str, minimum_bytes: int = 1) -> int:
        size = self.u32()
        if size > MAX_RECORDS or size * minimum_bytes > len(self.data) - self.pos:
            self.fail(f"invalid {kind} count {size}")
        return size

    def expect(self, expected: bytes):
        start = self.pos
        found = self.take(len(expected))
        if found != expected:
            self.fail(f"marker mismatch at 0x{start:x}: expected {expected.hex()}, found {found.hex()}")

    def string(self) -> str:
        size = self.u32()
        if not 1 <= size <= MAX_BYTES:
            self.fail(f"invalid string byte length {size}")
        raw = self.take(size)
        if raw[-1] != 0 or b"\0" in raw[:-1]:
            self.fail("string must have exactly one trailing NUL")
        try:
            return raw[:-1].decode(self.encoding, errors="strict")
        except UnicodeDecodeError as exc:
            self.fail(f"invalid {self.encoding} string: {exc}")

    def eof(self):
        if self.pos != len(self.data):
            self.fail(f"unexpected {len(self.data) - self.pos} trailing bytes")


def decode_lz4_block(block: bytes, expected_size: int) -> bytes:
    """Decode a raw block with exact input/output bounds, including overlaps."""
    if not 0 <= expected_size <= MAX_BYTES:
        raise FormatError(f"LZ4: invalid expanded size {expected_size}")
    source = Reader(block, label="LZ4")
    output = bytearray()

    def length(nibble: int) -> int:
        value = nibble
        if nibble == 15:
            while True:
                extra = source.u8()
                value += extra
                if value > expected_size:
                    source.fail("sequence exceeds expanded-size bound")
                if extra != 255:
                    break
        return value

    while source.pos < len(block):
        token = source.u8()
        literals = length(token >> 4)
        if literals > expected_size - len(output):
            source.fail("literal output exceeds expanded-size bound")
        output.extend(source.take(literals))
        if source.pos == len(block):
            break
        offset = struct.unpack("<H", source.take(2))[0]
        if offset == 0 or offset > len(output):
            source.fail(f"invalid match offset {offset}")
        match_size = length(token & 15) + 4
        if match_size > expected_size - len(output):
            source.fail("match output exceeds expanded-size bound")
        # Slice copies alone are wrong for overlapping references. Repeat the
        # existing window, then copy precisely the requested output length.
        window = output[-offset:]
        repeats, remainder = divmod(match_size, offset)
        output.extend(window * repeats)
        output.extend(window[:remainder])
    source.eof()
    if len(output) != expected_size:
        raise FormatError(f"LZ4: expanded size mismatch: {len(output)} != {expected_size}")
    return bytes(output)


def route(reader: Reader) -> list[dict]:
    result = []
    for _ in range(reader.count("route", minimum_bytes=4)):
        opcode = reader.u8()
        argument_count = reader.u8()
        arguments = [reader.i32() for _ in range(argument_count)]
        reader.expect(b"\x01\x00")
        result.append({"opcode": opcode, "arguments": arguments})
    return result


def command(reader: Reader, extended: bool) -> dict:
    offset = reader.pos
    word_count = reader.u8()
    if word_count == 0:
        reader.fail("zero command word count is outside the validated dialect")
    opcode = reader.u32()
    arguments = [reader.i32() for _ in range(word_count - 1)]
    indent = reader.u8()
    strings = [reader.string() for _ in range(reader.u8())]
    has_route = reader.u8()
    if has_route not in (0, 1):
        reader.fail(f"invalid command route marker {has_route}")
    result = {
        "offset": offset, "opcode": opcode,
        "opcodeName": OPCODE_NAMES.get(opcode, "Unknown"),
        "arguments": arguments, "indent": indent, "strings": strings,
    }
    if has_route:
        result["routeHeaderHex"] = reader.take(5).hex()
        result["routeFlags"] = reader.u8()
        result["route"] = route(reader)
    if extended:
        result["extensionHex"] = reader.take(reader.u8()).hex()
    result["size"] = reader.pos - offset
    return result


def page(reader: Reader, extended: bool, index: int) -> dict:
    start = reader.pos
    reader.expect(b"\x79")
    graphic_tile = reader.u32()
    graphic_file = reader.string()
    graphic = reader.take(4)
    conditions = reader.take(37)
    movement = reader.take(4)
    flags = reader.u8()
    route_flags = reader.u8()
    movement_route = route(reader)
    commands = [command(reader, extended) for _ in range(reader.count("command", minimum_bytes=8))]
    feature_count = reader.u32()
    features = reader.take(3)
    page_transfer = reader.u8() if feature_count > 3 else None
    reader.expect(b"\x7a")
    return {
        "index": index, "offset": start, "size": reader.pos - start,
        "graphicTile": graphic_tile, "graphicFile": graphic_file,
        "graphicDirection": graphic[0], "graphicFrame": graphic[1],
        "graphicOpacity": graphic[2], "graphicBlend": graphic[3],
        "trigger": conditions[0], "conditionsHex": conditions.hex(),
        "movementHex": movement.hex(), "flags": flags,
        "routeFlags": route_flags, "route": movement_route,
        "featureCount": feature_count, "featuresHex": features.hex(),
        "pageTransfer": page_transfer, "commands": commands,
    }


def parse_map(data: bytes, label: str = "map") -> dict:
    source = Reader(data, label=label)
    magic = source.take(20)
    normalized = bytearray(magic)
    encoding_marker = normalized[16]
    normalized[16] = 0
    if bytes(normalized) != MAP_MAGIC or encoding_marker not in (0, 0x55):
        source.fail("unsupported map magic or encoding marker (protected archives are not decoded)")
    wire_version = source.u32()
    if wire_version not in (100, 101, 102, 103):
        source.fail(f"unsupported map wire version {wire_version}")
    layout_marker = source.u8()
    packed = wire_version >= 101
    if packed:
        expanded_size, compressed_size = source.u32(), source.u32()
        compressed = source.take(compressed_size)
        source.eof()
        payload = decode_lz4_block(compressed, expanded_size)
    else:
        payload = source.take(len(data) - source.pos)
        expanded_size, compressed_size = len(payload), None
    reader = Reader(payload, "utf-8" if encoding_marker == 0x55 else "cp932", f"{label}:payload")
    title = reader.string()
    tileset, width, height = reader.u32(), reader.u32(), reader.u32()
    event_count = reader.count("event", minimum_bytes=26)
    unknown_v35 = reader.u32() if wire_version >= 103 else None
    layer_count = reader.u32() if wire_version >= 103 else 3
    if not 1 <= width <= 10000 or not 1 <= height <= 10000 or not 1 <= layer_count <= 64:
        reader.fail(f"unsupported dimensions {width}x{height}x{layer_count}")
    tile_bytes = width * height * layer_count * 4
    if tile_bytes > MAX_BYTES:
        reader.fail("tile matrix exceeds probe byte limit")
    tile_count = width * height * layer_count
    tiles = b""
    if reader.encoding == "utf-8" and reader.data[reader.pos:reader.pos + 4] == b"\xff" * 4:
        reader.take(4)
        tile_count = 0
    else:
        tiles = reader.take(tile_bytes)
    events = []
    for _ in range(event_count):
        reader.expect(b"\x6f\x39\x30\0\0")
        event_id, name, x, y = reader.u32(), reader.string(), reader.u32(), reader.u32()
        page_count = reader.count("page", minimum_bytes=64)
        reader.expect(bytes(4))
        pages = [page(reader, wire_version >= 103, i) for i in range(page_count)]
        reader.expect(b"\x70")
        events.append({"id": event_id, "name": name, "x": x, "y": y, "pages": pages})
    reader.expect(b"\x66")
    reader.eof()
    return {
        "file": label, "sha256": hashlib.sha256(data).hexdigest(),
        "fileBytes": len(data), "wireVersion": wire_version,
        "layoutMarker": layout_marker, "encoding": reader.encoding,
        "compressed": packed, "compressedBytes": compressed_size,
        "expandedPayloadBytes": expanded_size, "title": title,
        "tilesetId": tileset, "width": width, "height": height,
        "layerCount": layer_count, "unknownV35": unknown_v35,
        "tileCount": tile_count, "tileBytes": len(tiles),
        "tileSha256": hashlib.sha256(tiles).hexdigest(),
        "events": events, "payloadFullyConsumed": True,
    }


def parse_common_events(data: bytes, label: str = "CommonEvent.dat") -> dict:
    source = Reader(data, label=label)
    magic = bytearray(source.take(10))
    encoding_marker = magic[6]
    magic[6] = 0
    if bytes(magic) != COMMON_MAGIC or encoding_marker not in (0, 0x55):
        source.fail("unsupported common-event magic or encoding marker")
    version = source.u8()
    if version not in (0x8F, 0x93):
        source.fail(f"unsupported common-event wire version {version}")
    packed = version == 0x93
    if packed:
        expanded_size, compressed_size = source.u32(), source.u32()
        payload = decode_lz4_block(source.take(compressed_size), expanded_size)
        source.eof()
    else:
        payload = source.take(len(data) - source.pos)
        expanded_size, compressed_size = len(payload), None
    reader = Reader(payload, "utf-8" if encoding_marker == 0x55 else "cp932", f"{label}:payload")
    events = []
    for index in range(reader.count("common event", minimum_bytes=50)):
        start = reader.pos
        reader.expect(b"\x8e")
        event_id = reader.u32()
        condition_word = reader.u32()
        argument_settings = reader.take(7)
        name = reader.string()
        commands = [command(reader, packed) for _ in range(reader.count("command", minimum_bytes=8))]
        metadata_string = reader.string()
        description = reader.string()
        reader.expect(b"\x8f")
        argument_names = [reader.string() for _ in range(reader.count("argument name", minimum_bytes=5))]
        argument_modes = reader.take(reader.count("argument mode"))
        string_options = [
            [reader.string() for _ in range(reader.count("argument string", minimum_bytes=5))]
            for _ in range(reader.count("argument string table", minimum_bytes=4))
        ]
        numeric_options = [
            [reader.i32() for _ in range(reader.count("argument value", minimum_bytes=4))]
            for _ in range(reader.count("argument value table", minimum_bytes=4))
        ]
        defaults = [reader.i32() for _ in range(reader.count("argument default", minimum_bytes=4))]
        reader.expect(b"\x90")
        color = reader.u32()
        local_names = [reader.string() for _ in range(100)]
        reader.expect(b"\x91")
        metadata_extra = reader.string()
        end_marker = reader.u8()
        return_name = None
        return_value = None
        if end_marker == 0x92:
            return_name = reader.string()
            return_value = reader.i32()
            reader.expect(b"\x92")
        elif end_marker != 0x91:
            reader.fail(f"unexpected common-event end marker {end_marker}")
        events.append({
            "index": index, "id": event_id, "name": name,
            "offset": start, "size": reader.pos - start,
            "conditionWord": condition_word, "argumentSettingsHex": argument_settings.hex(),
            "metadataString": metadata_string, "description": description,
            "argumentNames": argument_names, "argumentModesHex": argument_modes.hex(),
            "argumentStringOptions": string_options, "argumentNumericOptions": numeric_options,
            "argumentDefaults": defaults, "color": color,
            "localVariableNames": local_names, "metadataExtra": metadata_extra,
            "returnName": return_name, "returnValue": return_value,
            "commands": commands,
        })
    terminator = reader.u8()
    # The 3.729 compressed file has header 0x93 but footer 0x92; they are
    # distinct fields, not a repeated version. Restrict to observed dialects.
    expected_footer = 0x92 if packed else 0x8F
    if terminator != expected_footer:
        reader.fail(f"common-event footer mismatch: expected {expected_footer}, found {terminator}")
    reader.eof()
    return {
        "file": label, "sha256": hashlib.sha256(data).hexdigest(),
        "fileBytes": len(data), "wireVersion": version, "encoding": reader.encoding,
        "compressed": packed, "compressedBytes": compressed_size,
        "expandedPayloadBytes": expanded_size, "events": events,
        "payloadFullyConsumed": True,
    }


def summarize_common_events(parsed: dict, include_commands: bool = False) -> dict:
    result = {key: value for key, value in parsed.items() if key != "events"}
    histogram, extension_histogram, route_histogram = Counter(), Counter(), Counter()
    result["events"] = []
    command_count = 0
    for item in parsed["events"]:
        commands = item["commands"]
        command_count += len(commands)
        for line in commands:
            histogram[line["opcode"]] += 1
            if "extensionHex" in line:
                extension_histogram[line["extensionHex"] or "<empty>"] += 1
            for route_command in line.get("route", []):
                route_histogram[route_command["opcode"]] += 1
        result["events"].append({
            **{key: item[key] for key in ("index", "id", "name", "offset", "size", "conditionWord", "argumentSettingsHex")},
            "commandCount": len(commands), "argumentCount": len(item["argumentNames"]),
            **({"commands": commands} if include_commands else {}),
        })
    result["eventCount"] = len(parsed["events"])
    result["commandCount"] = command_count
    result["opcodeHistogram"] = [{"opcode": k, "name": OPCODE_NAMES.get(k, "Unknown"), "count": v}
                                 for k, v in sorted(histogram.items())]
    result["extensionHistogram"] = dict(sorted(extension_histogram.items()))
    result["routeOpcodeHistogram"] = [{"opcode": k, "count": v} for k, v in sorted(route_histogram.items())]
    return result


def reject_links(path: Path):
    """Never read or overwrite through a symlink/junction, including parents."""
    for component in (path.absolute(), *path.absolute().parents):
        if component.is_symlink() or component.is_junction():
            raise FormatError(f"symlink/junction is outside probe scope: {component}")


def summarize(parsed: dict, include_commands: bool = False) -> dict:
    result = {key: value for key, value in parsed.items() if key != "events"}
    command_histogram, route_histogram, extension_histogram = Counter(), Counter(), Counter()
    event_summaries = []
    page_total = command_total = 0
    for event in parsed["events"]:
        event_summary = {key: value for key, value in event.items() if key != "pages"}
        event_summary["pages"] = []
        for item in event["pages"]:
            page_total += 1
            command_total += len(item["commands"])
            page_summary = {key: value for key, value in item.items() if key not in ("commands", "route")}
            page_summary["commandCount"] = len(item["commands"])
            page_summary["routeCount"] = len(item["route"])
            for route_command in item["route"]:
                route_histogram[route_command["opcode"]] += 1
            for line in item["commands"]:
                command_histogram[line["opcode"]] += 1
                if "extensionHex" in line:
                    extension_histogram[line["extensionHex"] or "<empty>"] += 1
                for route_command in line.get("route", []):
                    route_histogram[route_command["opcode"]] += 1
            if include_commands:
                page_summary["commands"] = item["commands"]
                page_summary["route"] = item["route"]
            event_summary["pages"].append(page_summary)
        event_summaries.append(event_summary)
    result.update({
        "eventCount": len(parsed["events"]), "pageCount": page_total,
        "commandCount": command_total,
        "opcodeHistogram": [{"opcode": k, "name": OPCODE_NAMES.get(k, "Unknown"), "count": v}
                            for k, v in sorted(command_histogram.items())],
        "routeOpcodeHistogram": [{"opcode": k, "count": v} for k, v in sorted(route_histogram.items())],
        "extensionHistogram": dict(sorted(extension_histogram.items())),
        "events": event_summaries,
    })
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("paths", nargs="+", type=Path, help=".mps, CommonEvent.dat, MapData directory, or game directory")
    parser.add_argument("--commands", action="store_true", help="include raw command arguments and strings")
    parser.add_argument("--common-events", action="store_true", help="also inspect CommonEvent.dat in game directories")
    parser.add_argument("--output", "-o", type=Path, help="write JSON here; default: stdout")
    args = parser.parse_args(argv)
    paths = []
    input_directories = []
    for path in args.paths:
        try:
            reject_links(path)
        except FormatError as exc:
            parser.error(str(exc))
        if path.is_dir():
            map_dir = path / "Data" / "MapData" if (path / "Data" / "MapData").is_dir() else path
            try:
                reject_links(map_dir)
            except FormatError as exc:
                parser.error(str(exc))
            input_directories.append(path.resolve())
            paths.extend(sorted(map_dir.glob("*.mps")))
            if args.common_events:
                common_path = path / "Data" / "BasicData" / "CommonEvent.dat"
                if common_path.exists():
                    paths.append(common_path)
        else:
            paths.append(path)
    output = {"schemaVersion": 1, "scope": "unprotected maps/common events; structural inspection; no event execution",
              "maps": [], "commonEvents": [], "errors": []}
    for path in dict.fromkeys(paths):
        try:
            reject_links(path)
            if path.stat().st_size > MAX_BYTES:
                raise FormatError(f"file exceeds {MAX_BYTES}-byte limit")
            if path.name.lower() == "commonevent.dat":
                parsed = parse_common_events(path.read_bytes(), path.as_posix())
                output["commonEvents"].append(summarize_common_events(parsed, args.commands))
            else:
                output["maps"].append(summarize(parse_map(path.read_bytes(), path.as_posix()), args.commands))
        except (OSError, FormatError) as exc:
            output["errors"].append({"file": path.as_posix(), "error": str(exc)})
    if not paths:
        output["errors"].append({"error": "no map/common-event files found"})
    serialized = json.dumps(output, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        try:
            reject_links(args.output)
        except FormatError as exc:
            parser.error(str(exc))
        if args.output.resolve() in {path.resolve() for path in paths} or any(
            args.output.resolve().is_relative_to(directory) for directory in input_directories
        ):
            parser.error("output must be outside source directories and must not overwrite source files")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized, encoding="utf-8")
    else:
        sys.stdout.reconfigure(encoding="utf-8")
        print(serialized, end="")
    return 1 if output["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
