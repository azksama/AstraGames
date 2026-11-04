#!/usr/bin/env python3
"""Inspect every native library in an APK. Alignment is not runtime validation.

Uses only Python's standard library. Complements SDK zipalign and llvm-readelf.
Compressed libraries are extracted by PackageManager; their ZIP offsets are not
used for mmap. Uncompressed libraries must also have a page-aligned data offset.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
PF_W = 2


def program_headers(data):
    if data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError("Expected a little-endian ELF")
    if data[4] == 2:
        header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
        offset, size, count = header[5], header[9], header[10]
        fmt = "<IIQQQQQQ"
    elif data[4] == 1:
        header = struct.unpack_from("<16sHHIIIIIHHHHHH", data)
        offset, size, count = header[5], header[9], header[10]
        fmt = "<IIIIIIII"
    else:
        raise ValueError("Unknown ELF class")
    if size < struct.calcsize(fmt) or count == 0xFFFF:
        raise ValueError("Unsupported program-header table")
    result = []
    for index in range(count):
        values = struct.unpack_from(fmt, data, offset + index * size)
        if data[4] == 2:
            kind, flags, off, va, _, filesz, memsz, align = values
        else:
            kind, off, va, _, filesz, memsz, flags, align = values
        result.append(dict(type=kind, flags=flags, offset=off, vaddr=va,
                           filesz=filesz, memsz=memsz, align=align))
    return result


def intersects(start, end, other_start, other_end):
    return start < other_end and other_start < end


def inspect_elf(data, page):
    headers = program_headers(data)
    loads = [h for h in headers if h["type"] == PT_LOAD]
    relros = [h for h in headers if h["type"] == PT_GNU_RELRO]
    errors = []
    warnings = []
    if not loads:
        errors.append("No PT_LOAD segment")
    for segment in loads:
        alignment = segment["align"]
        segment["pageCompatible"] = (
            alignment >= page and alignment & (alignment - 1) == 0
            and (segment["vaddr"] - segment["offset"]) % page == 0
        )
        if not segment["pageCompatible"]:
            errors.append(f"PT_LOAD at {segment['vaddr']:#x}: align={alignment:#x}, "
                          "or virtual/file offset incongruence")
    # Retain RELRO, and ensure rounding its boundaries to actual device pages
    # cannot make unrelated writable data read-only. LLD may pad RELRO beyond
    # its containing LOAD, so equality of their ends is not required.
    if not relros:
        warnings.append("No PT_GNU_RELRO: no RELRO page conflict, but this library lacks RELRO protection")
    for relro in relros:
        start, end = relro["vaddr"], relro["vaddr"] + relro["memsz"]
        rounded_start, rounded_end = start // page * page, (end + page - 1) // page * page
        conflicts = []
        for segment in loads:
            if not segment["flags"] & PF_W:
                continue
            lo, hi = segment["vaddr"], segment["vaddr"] + segment["memsz"]
            for outside_lo, outside_hi in ((lo, min(hi, start)), (max(lo, end), hi)):
                if outside_lo < outside_hi and intersects(rounded_start, rounded_end, outside_lo, outside_hi):
                    conflicts.append([outside_lo, outside_hi])
        relro["end"] = end
        relro["endPageAligned"] = end % page == 0
        relro["roundedPageStart"] = rounded_start
        relro["roundedPageEnd"] = rounded_end
        relro["writableConflicts"] = conflicts
        relro["pageCompatible"] = not conflicts
        if conflicts:
            errors.append(f"PT_GNU_RELRO end {end:#x}: rounded protection overlaps writable data")
    return dict(loads=loads, relro=relros, errors=errors, warnings=warnings)


def inspect_apk(apk, page):
    entries = []
    with zipfile.ZipFile(apk) as archive, apk.open("rb") as raw:
        for entry in sorted(archive.infolist(), key=lambda item: item.filename):
            if not entry.filename.endswith(".so"):
                continue
            data = archive.read(entry)
            raw.seek(entry.header_offset)
            header = struct.unpack("<4s5H3I2H", raw.read(30))
            if header[0] != b"PK\x03\x04":
                raise ValueError("Invalid ZIP local header")
            data_offset = entry.header_offset + 30 + header[-2] + header[-1]
            compressed = entry.compress_type != zipfile.ZIP_STORED
            try:
                elf = inspect_elf(data, page)
            except (ValueError, struct.error, IndexError) as error:
                elf = dict(loads=[], relro=[], errors=[str(error)], warnings=[])
            zip_ok = compressed or data_offset % page == 0
            if not zip_ok:
                elf["errors"].append("Uncompressed ZIP entry has an unaligned data offset")
            entries.append(dict(path=entry.filename, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
                                zipCompression=entry.compress_type, zipDataOffset=data_offset,
                                zipMapping="extracted" if compressed else "direct",
                                zipPageCompatible=zip_ok, **elf))
    return dict(apk=str(apk.resolve()), apkSha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                pageSize=page, libraryCount=len(entries),
                passCount=sum(not item["errors"] for item in entries),
                passed=bool(entries) and all(not item["errors"] for item in entries),
                scope="ELF LOAD/RELRO and ZIP inspection only; runtime qualification is separate",
                libraries=entries)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--page-size", type=int, default=16384)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.page_size <= 0 or args.page_size & (args.page_size - 1):
        parser.error("page size must be a positive power of two")
    report = inspect_apk(args.apk, args.page_size)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    for item in report["libraries"]:
        print(("PASS " if not item["errors"] else "FAIL ") + item["path"])
        for error in item["errors"]:
            print("     " + error)
        for warning in item["warnings"]:
            print("     WARNING: " + warning)
    print(f"{report['passCount']}/{report['libraryCount']} libraries pass {args.page_size}-byte inspection")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
