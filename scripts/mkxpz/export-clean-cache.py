#!/usr/bin/env python3
"""Export pristine pinned Git trees from an existing local cache, never .o files."""
import json
from pathlib import Path
import subprocess
import sys

cache, output = map(lambda p: Path(p).resolve(), sys.argv[1:3])
if output.exists():
    raise SystemExit(f'Output must be new: {output}')
output.mkdir(parents=True)
repos = [cache]
repos += sorted(p for p in (cache / 'app/jni').iterdir() if p.is_dir() and (p / '.git').exists())
for repo in list(repos):
    if (repo / '.gitmodules').exists():
        nested = subprocess.check_output(['git', '-C', str(repo), 'submodule', 'foreach', '--quiet', '--recursive', 'pwd'], text=True)
        repos += [Path(line) for line in nested.splitlines()]
for index, repo in enumerate(repos):
    destination = output / repo.relative_to(cache)
    destination.mkdir(parents=True, exist_ok=True)
    archive = output.parent / f'.component-{index}.tar'
    subprocess.run(['git', '-C', str(repo), 'archive', '--format=tar', '-o', str(archive), 'HEAD'], check=True)
    subprocess.run(['tar', '-xf', str(archive), '-C', str(destination)], check=True)
    archive.unlink()
for name, version in [('libtheora', '1.1.1'), ('libiconv', '1.17')]:
    archive = Path('/out') / f'{name}-{version}.tar.gz'
    subprocess.run(['tar', '-xzf', str(archive), '-C', str(output / 'app/jni')], check=True)
    (output / 'app/jni' / f'{name}-{version}').rename(output / 'app/jni' / name)
