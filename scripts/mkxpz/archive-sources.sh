#!/usr/bin/env bash
set -euo pipefail
source_root="${1:-/work/corresponding-sources}"
test ! -e "$source_root"
mkdir -p "$source_root"
# The cache is made by export-clean-cache.py from committed Git trees and
# verified tarballs, so it contains no compiled objects or generated configs.
tar -xzf /out/source-cache.tar.gz -C "$source_root"
python3 /scripts/patch-upstream.py "$source_root"
mkdir "$source_root/astra-build"
cp /scripts/*.py /scripts/*.sh /scripts/*.json /scripts/Dockerfile /scripts/README.md "$source_root/astra-build/"
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -czf /out/mkxpz-corresponding-sources.tar.gz -C "$source_root" .
sha256sum /out/mkxpz-corresponding-sources.tar.gz > /out/mkxpz-corresponding-sources.SHA256
