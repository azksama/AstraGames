#!/usr/bin/env python3
"""Record exact upstream revisions and retain their licenses beside binaries."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import struct
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else '/work/upstream')
out = Path('/out')
license_dir = out / 'licenses'
license_dir.mkdir(exist_ok=True)
ndk = Path('/opt/android-ndk-r29')
for src, dst in [
    ('toolchains/llvm/prebuilt/linux-x86_64/NOTICE', 'NDK-toolchain-NOTICE.txt'),
    ('sources/android/cpufeatures/NOTICE', 'NDK-cpufeatures-NOTICE.txt'),
]:
    shutil.copy2(ndk / src, license_dir / dst)

def git(path, *args):
    return subprocess.check_output(['git', '-C', str(path), *args], text=True).strip()

lock = json.loads((Path(__file__).parent / 'sources-lock.json').read_text())
sources = []
for path in [root, *sorted((root / 'app/jni').iterdir())]:
    if not path.is_dir() or not (path / '.git').exists():
        continue
    entry = dict(name=path.name, url=git(path, 'remote', 'get-url', 'origin'), revision=git(path, 'rev-parse', 'HEAD'))
    if (path / '.gitmodules').exists():
        entry['submodules'] = git(path, 'submodule', 'status', '--recursive').splitlines()
    sources.append(entry)

names = {'COPYING', 'COPYING.LIB', 'COPYING.LESSER', 'COPYRIGHT', 'LICENSE', 'LICENSE.TXT', 'LICENSE.md', 'LICENSE.txt', 'LICENCE', 'LICENCE.md', 'OFL.txt', 'FTL.TXT', 'GPLv2.TXT'}
for path in (root / 'app/jni').rglob('*'):
    if path.is_file() and path.name in names and not any(x.startswith('build-') or x in {'.git', '.libs', 'cmakebuild'} for x in path.parts):
        relative = path.relative_to(root / 'app/jni')
        dst = license_dir / relative
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dst)

font = root / 'app/jni/mkxp-z/assets/liberation.ttf'
data = font.read_bytes()
count = struct.unpack_from('>H', data, 4)[0]
for i in range(count):
    tag, _, offset, _ = struct.unpack_from('>4sIII', data, 12 + i*16)
    if tag != b'name':
        continue
    _, n, strings = struct.unpack_from('>HHH', data, offset)
    records = []
    for j in range(n):
        platform, encoding, language, name_id, size, at = struct.unpack_from('>HHHHHH', data, offset+6+j*12)
        if name_id in (0, 1, 5, 13, 14):
            raw = data[offset+strings+at:offset+strings+at+size]
            records.append(raw.decode('utf-16-be' if platform in (0,3) else 'latin1', errors='replace'))
    (license_dir / 'Liberation-font-metadata.txt').write_text('\n\n'.join(dict.fromkeys(records)))

if sources:
    lock['upstream'] = sources
lock['embedded_font_sha256'] = hashlib.sha256(data).hexdigest()
(out / 'sources-lock.json').write_text(json.dumps(lock, indent=2) + '\n')
