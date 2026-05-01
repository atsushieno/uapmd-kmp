#!/usr/bin/env bash
# build-android-native.sh
#
# Builds libuapmd-c-api.so for each Android ABI and places the outputs under:
#   external/uapmd/build-android/<abi>/libuapmd-c-api.so
#
# Prerequisites:
#   - Android NDK installed (ANDROID_NDK_HOME or ndk-bundle in $ANDROID_HOME)
#   - CMake 3.22+ on PATH
#
# Usage:
#   ./build-android-native.sh [arm64-v8a] [x86_64]   (defaults to both)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UAPMD_SRC="$SCRIPT_DIR/external/uapmd"
OUTPUT_BASE="$UAPMD_SRC/build-android"

# Locate NDK
NDK="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" ]]; then
    # Try latest installed side-by-side NDK
    NDK_LATEST=$(ls -1d "$ANDROID_HOME/ndk/"*/ 2>/dev/null | sort -V | tail -1)
    NDK="${NDK_LATEST%/}"
fi
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
    echo "ERROR: Android NDK not found. Set ANDROID_NDK_HOME or ANDROID_HOME." >&2
    exit 1
fi
echo "Using NDK: $NDK"

TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
ABIS=("${@:-arm64-v8a x86_64}")
if [[ $# -eq 0 ]]; then
    ABIS=(arm64-v8a x86_64 armeabi-v7a x86)
else
    ABIS=("$@")
fi

for ABI in "${ABIS[@]}"; do
    echo ""
    echo "=== Building for $ABI ==="
    BUILD_DIR="$OUTPUT_BASE/build-$ABI"
    OUT_DIR="$OUTPUT_BASE/$ABI"

    mkdir -p "$BUILD_DIR" "$OUT_DIR"

    cmake -S "$UAPMD_SRC/android/app/src/main/cpp" \
          -B "$BUILD_DIR" \
          -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
          -DANDROID_ABI="$ABI" \
          -DANDROID_PLATFORM=android-26 \
          -DANDROID_STL=c++_shared \
          -DCMAKE_BUILD_TYPE=RelWithDebInfo \
          -DUAPMD_BUILD_C_API_SHARED=ON \
          -DMIDICCI_SKIP_TOOLS=ON \
          -DCMAKE_INSTALL_PREFIX="$OUT_DIR"

    cmake --build "$BUILD_DIR" --target uapmd-c-api --parallel

    # Copy the shared library to the expected location
    SO=$(find "$BUILD_DIR" -name "libuapmd-c-api.so" | head -1)
    if [[ -z "$SO" ]]; then
        echo "WARNING: libuapmd-c-api.so not found in build dir for $ABI" >&2
    else
        cp "$SO" "$OUT_DIR/libuapmd-c-api.so"
        echo "Installed: $OUT_DIR/libuapmd-c-api.so"
    fi
done

echo ""
echo "Done. Built ABIs: ${ABIS[*]}"
echo "Output: $OUTPUT_BASE"
