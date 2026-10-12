"""Prepare a disposable Wolf 2.2961/3.729 official sample for real save/load tests.

No original executable or data is shipped in this script. Obtain the official
sample separately. Example (outputs stay ignored under build/):
  python scripts/wolf-runtime/create-official-save-fixture.py --source <sample>
    --dest build/wolf-research/save-fixture-w2 --mode save

save: CommonEvent48.init appends var17=271828, SaveLoad[0,1], Wait[10].
load: CommonEvent48.init appends only SaveLoad[1,1]. Copy the save to test
      into the new destination's Save/SaveData01.sav before launching.
The destination must not exist. The source and original EXE remain unchanged.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from tools.wolf_native_probe import decode_lz4_block, parse_common_events

OFFICIAL = {
    '795203a6e875618d7fbdc7dd1f409a445c60262c911745553d040081bedb6ac0': '2.2961',
    '4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e': '3.729',
}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def command(op: int, args: list[int], packed: bool) -> bytes:
    # W3 packed command records have an extension-size byte after route=0.
    return (bytes([len(args) + 1]) + struct.pack('<i', op)
            + b''.join(struct.pack('<i', value) for value in args)
            + b'\0\0\0' + (b'\0' if packed else b''))


def literal_lz4(data: bytes) -> bytes:
    """A valid all-literals raw LZ4 block, requiring no external compressor."""
    output = bytearray([min(15, len(data)) << 4])
    if len(data) >= 15:
        remaining = len(data) - 15
        while remaining >= 255:
            output.append(255)
            remaining -= 255
        output.append(remaining)
    output.extend(data)
    return bytes(output)


def edit_init(raw: bytes, mode: str, marker: int, slot: int) -> tuple[bytes, dict]:
    parsed = parse_common_events(raw)
    events = [event for event in parsed['events'] if event['id'] == 48]
    if len(events) != 1:
        raise ValueError('Exactly one official init common event48 is required.')
    event = events[0]
    commands = event['commands']
    if not commands or commands[-1]['opcode'] != 0:
        raise ValueError('Common event48 must end with the observed blank command.')
    packed = parsed['compressed']
    if packed:
        expanded, compressed = struct.unpack_from('<II', raw, 11)
        payload = decode_lz4_block(raw[19:19 + compressed], expanded)
    else:
        payload = raw[11:]
    count_at = commands[0]['offset'] - 4
    insert_at = commands[-1]['offset']
    count = struct.unpack_from('<I', payload, count_at)[0]
    if count != len(commands):
        raise ValueError('Command-count offset is inconsistent.')
    added = ([(121, [2_000_017, marker, 0, 0]), (220, [0, slot]), (180, [10])]
             if mode == 'save' else [(220, [1, slot])])
    extra = b''.join(command(op, args, packed) for op, args in added)
    modified = bytearray(payload[:insert_at] + extra + payload[insert_at:])
    struct.pack_into('<I', modified, count_at, count + len(added))
    if packed:
        block = literal_lz4(modified)
        result = raw[:11] + struct.pack('<II', len(modified), len(block)) + block
    else:
        result = raw[:11] + modified
    checked = parse_common_events(result)
    edited = next(item for item in checked['events'] if item['id'] == 48)
    expected_tail = [(op, args) for op, args in added] + [(0, [])]
    actual_tail = [(item['opcode'], item['arguments']) for item in edited['commands'][-len(expected_tail):]]
    if actual_tail != expected_tail or len(checked['events']) != len(parsed['events']):
        raise ValueError('Edited init command sequence failed structural verification.')
    return result, dict(commonEvent=48, sourceCompressed=packed, mode=mode,
                        insertedCommands=[dict(opcode=op, arguments=args) for op, args in added])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--source', type=Path, required=True, help='Official sample game directory containing Game.exe/Data')
    parser.add_argument('--dest', type=Path, required=True, help='New disposable directory; existing paths are refused')
    parser.add_argument('--mode', choices=['save', 'load'], default='save')
    parser.add_argument('--marker-value', type=int, default=271828)
    parser.add_argument('--slot', type=int, default=1)
    args = parser.parse_args()
    source, destination = args.source.resolve(), args.dest.resolve()
    if destination.exists():
        parser.error(f'Destination already exists; nothing was changed: {destination}')
    if destination == source or destination.is_relative_to(source):
        parser.error('Destination must be outside the original sample directory.')
    if not -2_147_483_648 <= args.marker_value <= 2_147_483_647 or not 1 <= args.slot <= 99:
        parser.error('Marker must be int32 and slot in1..99.')
    image = source / 'Game.exe'
    common = source / 'Data/BasicData/CommonEvent.dat'
    if not image.is_file() or not common.is_file():
        parser.error('Source must contain the official Game.exe and unpacked Data/BasicData/CommonEvent.dat.')
    image_hash = digest(image.read_bytes())
    if image_hash not in OFFICIAL:
        parser.error('Unknown executable identity; no CommonEvent offsets will be guessed.')
    original_data = common.read_bytes()
    modified_data, changes = edit_init(original_data, args.mode, args.marker_value, args.slot)
    # Validate everything before making the destination. Never delete/move originals.
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.mkdir()
    for name in ['Game.exe', 'Game.ini', 'GdiPlus.dll', 'GuruGuruSMF4.dll']:
        if (source / name).is_file():
            shutil.copyfile(source / name, destination / name)
    shutil.copytree(source / 'Data', destination / 'Data')
    (destination / 'Data/BasicData/CommonEvent.dat').write_bytes(modified_data)
    (destination / 'Save').mkdir(exist_ok=True)
    if digest((destination / 'Game.exe').read_bytes()) != image_hash or common.read_bytes() != original_data:
        raise AssertionError('Original/EXE preservation verification failed.')
    manifest = dict(source=str(source), destination=str(destination), version=OFFICIAL[image_hash],
                    executableSha256=image_hash, officialExecutableUnchanged=True,
                    sourceCommonSha256=digest(original_data), fixtureCommonSha256=digest(modified_data),
                    sourceCommonUnchanged=True, **changes)
    (destination / 'astra-fixture-manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print(json.dumps(manifest), flush=True)


if __name__ == '__main__':
    main()
