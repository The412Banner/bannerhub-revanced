#!/usr/bin/env bash
# Build the legacy-GLES2 wrapper `libxserver_shim.so` (arm64-v8a only) and stage
# it into the renderer patch resources. Reproducible; no Gradle native hook (the
# repo ships prebuilt .so binaries the same way it ships the 6.0.2 legacy pair).
#
# Toolchain: the NDK's arm64 sysroot (headers + stub libs) driven by whatever
# clang is on PATH. The NDK's own prebuilt clang is a linux-x86_64 binary and
# will not run on an arm64 device, so on-device we use the native clang and point
# it at the NDK sysroot. clang major version must match the NDK's bundled clang.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$HERE/../../patches/src/main/resources/legacyrenderer"
OUT="$OUT_DIR/libxserver_shim.so"

: "${NDK:=$HOME/android-sdk/ndk/29.0.14206865}"
: "${CC:=clang}"
PREBUILT="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
SR="$PREBUILT/sysroot"
UNWIND="$PREBUILT/lib/clang/21/lib/linux/aarch64"   # match NDK clang major

"$CC" --target=aarch64-linux-android30 --sysroot="$SR" \
    -shared -fPIC -O2 -Wall -Wextra -fvisibility=hidden \
    -Wl,-z,max-page-size=16384 -Wl,--build-id=none \
    -o "$OUT" "$HERE/xserver_shim.c" \
    -L"$SR/usr/lib/aarch64-linux-android/30" -L"$UNWIND" -llog -ldl

"${STRIP:-llvm-strip}" --strip-unneeded "$OUT" 2>/dev/null || true
echo "built $OUT"
md5sum "$OUT" 2>/dev/null || md5 "$OUT"
