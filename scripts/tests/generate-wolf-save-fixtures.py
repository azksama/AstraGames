"""Synthetic Wolf save fixtures, not game assets.

Layout reference: Sinflower/WolfSave @537ceba4 (MIT). This generator deliberately uses
the Windows MSVCRT DLL's rand(), independently of the Kotlin cipher under test.
Run on Windows with Python; it never launches a game, emulator or MTool binary.
"""
from __future__ import annotations

import ctypes
import hashlib
import json
import struct
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "app/src/test/resources/saves/wolf"
CRT = ctypes.CDLL("msvcrt.dll")
CRT.srand.argtypes = [ctypes.c_uint]
CRT.rand.argtypes = []
CRT.rand.restype = ctypes.c_int


class Writer:
    def __init__(self, version: int, utf8: bool):
        self.version, self.utf8 = version, utf8
        self.data = bytearray()
        self.sections, self.numbers = {}, {}

    def value(self, value: int, width: int = 4):
        self.data.extend(value.to_bytes(width, "little", signed=value < 0))

    def zeros(self, size: int):
        self.data.extend(bytes(size))

    def text(self, text: str, width: int):
        encoded = text.encode("utf-8" if self.utf8 else "cp932") + b"\0"
        self.value(len(encoded), width)
        self.data.extend(encoded)

    def record_section(self, name: str, fn):
        start = len(self.data)
        fn()
        self.sections[name] = [start, len(self.data)]

    def movement(self):
        self.zeros(6)
        self.value(1)
        self.value(0x12, 1)
        self.value(2, 1)
        self.value(7654321)
        self.value(-17)
        self.value(2, 1)
        self.value(3, 1)
        self.value(4, 1)

    def character(self):
        v = self.version
        self.zeros(21)
        self.text("character.png", 2)
        self.zeros(10)
        self.movement()
        self.movement()
        self.zeros(15)
        self.value(2)
        self.value(19)
        self.value(20)
        if v >= 0x70: self.zeros(2)
        if v >= 0x73: self.zeros(16)
        if v >= 0x78: self.zeros(4)
        if v >= 0x85: self.zeros(12)
        if v >= 0x8A:
            self.value(2, 2)
            self.text("A", 2)
            self.text("B", 2)
            self.zeros(13)
        if v >= 0x8B: self.zeros(4)
        if v >= 0x8C: self.zeros(4)

    def part1(self):
        v = self.version
        self.zeros(12)
        if v >= 0x69:
            self.value(1)
            self.value(2)
            for n in range(6): self.value(n + 10)
        self.text("map.dat", 4)
        for n in range(31 if v >= 0x8A else 15 if v >= 0x73 else 7): self.text(str(n), 4)
        self.value(2)
        self.value(1, 4 if v >= 0x64 else 1)
        self.value(2, 4 if v >= 0x64 else 1)
        self.value(1)
        self.character()
        if v >= 0x72: self.zeros(44)
        self.value(1)
        self.value(127)

    def part2(self):
        v = self.version
        # Fixed scalar fields 1..49 in the reference, in their declared widths.
        widths = [1, 1] + [4] * 19 + [2] * 3 + [4] + [2] * 3 + [4] * 9 + [1, 2] + [4] * 2 + [2] * 4 + [4] * 3 + [1]
        assert len(widths) == 49
        for width in widths: self.value(0, width)
        if v <= 96: self.value(0)
        self.value(0)
        if v >= 98: self.zeros(8)
        if v >= 100:
            self.zeros(9)
            self.value(1)
            self.text("config", 4)
            self.value(2)
            self.value(1)
            self.value(2)
            self.zeros(20)
        if v >= 101: self.zeros(24)
        if v >= 102: self.zeros(2)
        if v >= 103:
            self.text("font", 2)
            self.text("fallback", 2)
        if v >= 104: self.zeros(4)
        if v >= 106: self.zeros(12)
        if v >= 108:
            self.text("image", 2)
            self.zeros(12)
            self.text("sound", 2)
            self.zeros(12)
        if v >= 109:
            self.text("M", 2)
            self.text("N", 2)
            self.zeros(1)
        if v >= 110: self.zeros(1)
        if v >= 119:
            for n in range(3): self.text(str(n), 2)
        for threshold in (121, 122, 124, 126, 128):
            if v >= threshold: self.zeros(4)
        if v >= 129: self.zeros(8)
        if v >= 130: self.zeros(4)
        if v >= 131: self.zeros(4)
        if v >= 132: self.zeros(16)
        if v >= 134: self.zeros(1)
        if v >= 136: self.zeros(1)
        if v >= 137:
            self.zeros(16)
            for n in range(24): self.value(n + 1)
        if v >= 0x8A:
            self.zeros(16)
            self.text("config.ext", 4)
            self.value(1)
            self.text("extra", 4)
        if v >= 0x8D:
            self.zeros(14)
            self.data.extend(range(256))
            self.zeros(2)
        if v >= 0x8E: self.zeros(4)

    def part3(self):
        self.value(123)
        self.value(1)
        self.value(1)
        self.value(2, 1)
        self.value(456)
        self.value(789)
        self.value(1)
        self.value(2)
        self.value(1)
        self.value(2)
        self.value(1)
        self.text("event string", 2 if self.version < 0x6F else 4)
        self.value(1)
        self.value(1, 1)
        self.value(888)
        self.value(1)
        self.value(1, 1)
        self.text("nested text", 2 if self.version < 0x6F else 4)

    def part4(self):
        self.character()
        self.value(1)
        self.character()
        self.zeros(2)
        self.value(1)
        self.value(54)
        if self.version >= 0x8A:
            self.value(1)
            self.value(0x10000000001, 8)

    def picture(self):
        v = self.version
        self.zeros(10)
        self.text("picture.png", 2)
        self.zeros((12 + 6) * 4)
        if v >= 0x69: self.zeros(18 * 4)
        if v >= 0x6B: self.zeros(1)
        if v >= 0x72: self.zeros(2 + 8 * 4)
        if v >= 0x73:
            self.zeros(4 * 4)
            if v >= 0x74: self.zeros(5 * 4)
            if v >= 0x75: self.zeros(6 * 4)
            self.zeros(6 * 4)
        if v >= 0x76: self.zeros(3 * 4)
        if v >= 0x81:
            self.zeros(21 * 4)
            if v >= 0x87: self.zeros(5 * 4)
            if v >= 0x89:
                self.value(1)
                self.value(2)
                self.value(61)
                self.value(62)
                self.zeros(7 * 4)

    def part5(self):
        self.value(1, 2)
        self.picture()

    def database(self):
        self.value(0, 1)
        self.value(2)
        self.value(-2)
        self.value(1)
        self.value(4)
        for definition in (0, 2000, 3, 2001): self.value(definition)
        self.value(2)
        for row, nums in enumerate(((100, -5), (2147483647, -2147483648))):
            for field, number in zip((0, 2), nums):
                path = f"root.variableDatabase.types[0].rows[{row}].fields[{field}]"
                self.numbers[path] = {"offset": len(self.data), "value": number}
                self.value(number)
            self.text("日本語" if row == 0 else "text", 4)
            self.text("kept exactly", 4)
        self.value(1)
        self.value(1999)
        self.value(1)
        path = "root.variableDatabase.types[1].rows[0].fields[0]"
        self.numbers[path] = {"offset": len(self.data), "value": 77}
        self.value(77)

    def part7(self):
        self.value(1, 1)
        self.value(2)
        self.value(0x12, 1)
        self.value(3, 1)
        self.value(0xFA, 1)
        self.value(876543)

    def build(self):
        self.data.extend(bytes((19, 11, 0, 37, 41, 2, 0x55 if self.utf8 else 0x53, 0, 13, 179, 61, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        self.value(0x19, 1)
        self.text("Astra Test 日本語", 2)
        self.value(self.version, 2)
        for n in range(1, 6): self.record_section(str(n), getattr(self, f"part{n}"))
        self.record_section("6", self.database)
        self.record_section("7", self.part7)
        self.value(0x19, 1)
        self.data[2] = sum(self.data[20:]) & 0xFF
        return bytes(self.data)


def encrypt(data: bytes) -> bytes:
    output = bytearray(data)
    for offset, stride in ((9, 5), (3, 2), (0, 1)):
        CRT.srand(data[offset])
        for pos in range(20, len(output), stride): output[pos] ^= CRT.rand() >> 12
    return bytes(output)


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    manifest = {"origin": "Synthetic fixtures, not game assets", "layout": "Sinflower/WolfSave @537ceba4262368c61bfb9c4fa5333341a115c662, MIT", "cipher": "Native Windows msvcrt.dll srand/rand", "fixtures": {}}
    for version in (96, 100, 105, 111, 114, 115, 118, 128, 129, 137, 138, 139, 140, 141, 142):
        for utf8 in (True, False):
            writer = Writer(version, utf8)
            plain = writer.build()
            name = f"format-{version:04x}-{'utf8' if utf8 else 'cp932'}.sav"
            encrypted = encrypt(plain)
            (OUTPUT / name).write_bytes(encrypted)
            manifest["fixtures"][name] = {"format": version, "utf8": utf8, "size": len(plain), "sha256": hashlib.sha256(encrypted).hexdigest(), "sections": writer.sections, "numbers": writer.numbers}
    vector = bytes(range(64))
    manifest["cipherVector"] = {"plaintextHex": vector.hex(), "ciphertextHex": encrypt(vector).hex()}
    (OUTPUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(manifest['fixtures'])} synthetic fixtures with native MSVCRT encryption.")


if __name__ == "__main__":
    main()
