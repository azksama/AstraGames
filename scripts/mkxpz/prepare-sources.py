#!/usr/bin/env python3
"""Fetch the exact revisions used by the prototype into a new build directory."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import urllib.request

scripts = Path(__file__).resolve().parent
lock = json.loads((scripts / 'sources-lock.json').read_text())
root = Path(sys.argv[1]).resolve()
if root.exists() and any(root.iterdir()):
    raise SystemExit(f'Refusing to replace nonempty source directory: {root}')

def run(*args, cwd=None):
    subprocess.run(args, cwd=cwd, check=True)

for entry in lock['upstream']:
    dst = root if entry['name'] == 'upstream' else root / 'app/jni' / entry['name']
    dst.mkdir(parents=True, exist_ok=True)
    run('git', 'init', '-q', str(dst))
    run('git', 'remote', 'add', 'origin', entry['url'], cwd=dst)
    run('git', 'fetch', '--depth=1', 'origin', entry['revision'], cwd=dst)
    run('git', '-c', 'advice.detachedHead=false', 'checkout', '--detach', 'FETCH_HEAD', cwd=dst)
    # SDL's committed submodule references pin codec dependencies. OpenSSL's
    # optional test-only submodules were not used by this build.
    if entry['name'] in ('SDL2_image', 'SDL2_ttf'):
        run('git', 'submodule', 'update', '--init', '--recursive', cwd=dst)

tarballs = {
    'libtheora': ('libtheora-1.1.1', '40952956c47811928d1e7922cda3bc1f427eb75680c3c37249c91e949054916b'),
    'libiconv': ('libiconv-1.17', '8f74213b56238c85a50a5329f77e06198771e70dd9a739779f4c02f65d971313'),
}
for name, (folder, digest) in tarballs.items():
    target = root / 'app/jni' / (folder + '.tar.gz')
    with urllib.request.urlopen(lock['tarballs'][name]) as response:
        target.write_bytes(response.read())
    if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
        raise SystemExit(f'Checksum mismatch for {name}')
    run('tar', '-xzf', str(target), '-C', str(target.parent))
    (target.parent / folder).rename(target.parent / name)
run('python3', str(scripts / 'patch-upstream.py'), str(root))
