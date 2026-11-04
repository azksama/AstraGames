#!/usr/bin/env bash
set -euo pipefail
abi="${1:-x86_64}"
case "$abi" in
  x86_64) target=x86_64-linux-android ;;
  arm64-v8a) target=aarch64-linux-android ;;
  *) echo "Unsupported ABI: $abi" >&2; exit 2 ;;
esac
cd "${MKXP_SOURCE_ROOT:-/work/upstream}/app/jni"
export HOST="$target" TARGET="$target" ABI="$abi" API=26
make -j1
# The upstream aggregate Makefile only tracks ruby/Makefile, not Ruby's C
# sources. Re-enter Ruby's own dependency graph after an adapter patch.
make -C ruby -j6
make -C ruby install
(cd mkxp-z && bash make_xxd.sh)
cd ..
"$ANDROID_NDK_HOME/ndk-build" -j6 NDK_PROJECT_PATH="$PWD" APP_BUILD_SCRIPT="$PWD/jni/Android.mk" NDK_APPLICATION_MK="$PWD/jni/Application.mk" APP_ABI="$abi" APP_PLATFORM=android-26 APP_OPTIM=release APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true 'APP_LDFLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
mkdir -p "/out/libs/$abi"
cp libs/"$abi"/*.so "/out/libs/$abi/"
sha256sum /out/libs/"$abi"/*.so > "/out/libs/$abi/SHA256SUMS"
(
    flock 9
    mkdir -p /out/ruby-stdlib
    cp -a "jni/build-$abi/lib/ruby/." /out/ruby-stdlib/
    python3 /scripts/package-stdlib.py /out/ruby-stdlib /out/ruby-stdlib.zip
) 9>/out/ruby-stdlib.lock
