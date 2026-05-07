#!/usr/bin/env bash
# build-android-native.sh
#
# Builds libuapmd-jni.so for each Android ABI using CMake + NDK r28.2.
# NDK r28.2 defaults to 16k page alignment for arm64-v8a/x86_64; no extra
# linker flags are needed.
#
# The build requires prefab packages (SDL3, androidaudioplugin, oboe) which
# AGP extracts when you run a Gradle configure step.  Run once if needed:
#   cd kotlin && ./gradlew :uapmd-binding:configureCMakeRelWithDebInfo
#
# Outputs per ABI:
#   kotlin/uapmd-binding/build/jniLibs/<abi>/libuapmd-jni.so
#
# Prerequisites:
#   - NDK r28.2 installed (set ANDROID_NDK_HOME=…/28.2.13676358 to override)
#   - cmake 3.28+ on PATH (or the Android SDK cmake; set CMAKE_CMD to override)
#   - Gradle configure step completed at least once (see above)
#
# Usage:
#   ./build-android-native.sh [arm64-v8a] [x86_64] [armeabi-v7a] [x86]
#   (defaults to arm64-v8a x86_64)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CPP_SRC="$SCRIPT_DIR/kotlin/uapmd-binding/src/androidMain/cpp"
OUT_BASE="$SCRIPT_DIR/kotlin/uapmd-binding/build/jniLibs"
BUILD_BASE="$SCRIPT_DIR/kotlin/uapmd-binding/build/jniLibs-cmake"
CPM_CACHE="$HOME/.cache/CPM/uapmd"

# ── NDK ───────────────────────────────────────────────────────────────────────
NDK="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK" ]]; then
    # Prefer the exact r28.2 version we require
    PREFERRED="${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/28.2.13676358"
    if [[ -d "$PREFERRED" ]]; then
        NDK="$PREFERRED"
    elif [[ -n "${ANDROID_HOME:-}" ]]; then
        # Fall back to the latest installed NDK (must be r28+)
        NDK=$(ls -1d "$ANDROID_HOME/ndk/"*/ 2>/dev/null | sort -V | tail -1)
        NDK="${NDK%/}"
    fi
fi
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
    echo "ERROR: NDK r28.2 not found." >&2
    echo "  Install it via Android Studio or set ANDROID_NDK_HOME to NDK r28.2." >&2
    exit 1
fi
echo "Using NDK: $NDK"

# ── CMake ─────────────────────────────────────────────────────────────────────
CMAKE_CMD="${CMAKE_CMD:-cmake}"
if ! command -v "$CMAKE_CMD" &>/dev/null; then
    SDK_CMAKE="$(ls -1d "${ANDROID_HOME:-$HOME/Library/Android/sdk}/cmake/"*/bin/cmake 2>/dev/null | sort -V | tail -1)"
    if [[ -n "$SDK_CMAKE" && -x "$SDK_CMAKE" ]]; then
        CMAKE_CMD="$SDK_CMAKE"
    else
        echo "ERROR: cmake not found on PATH and Android SDK cmake not available." >&2
        exit 1
    fi
fi
echo "Using cmake: $CMAKE_CMD"

TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"

# ── ABIs ──────────────────────────────────────────────────────────────────────
if [[ $# -eq 0 ]]; then
    ABIS=(arm64-v8a x86_64)
else
    ABIS=("$@")
fi

# ── AAP include dir (required by remidy's CMakeLists when ANDROID) ────────────
# We prefer the include path that AGP already unpacked into the Gradle cache.
# Any version of the androidaudioplugin module works for headers.
find_aap_dir() {
    local include
    include=$(find "$HOME/.gradle/caches" \
        -path "*/androidaudioplugin-*/prefab/modules/androidaudioplugin/include" \
        -type d 2>/dev/null | sort -V | tail -1)
    if [[ -n "$include" ]]; then
        # AAP_DIR must contain an "include" subdirectory
        echo "$(dirname "$include")"
        return 0
    fi
    return 1
}
AAP_DIR="${AAP_DIR:-}"
if [[ -z "$AAP_DIR" ]]; then
    if ! AAP_DIR=$(find_aap_dir); then
        echo "ERROR: androidaudioplugin headers not found in ~/.gradle/caches." >&2
        echo "  Run: cd kotlin && ./gradlew :uapmd-binding:configureCMakeRelWithDebInfo" >&2
        echo "  Or set AAP_DIR to a directory containing include/aap/…" >&2
        exit 1
    fi
fi
echo "Using AAP_DIR: $AAP_DIR"

# ── Build each ABI ─────────────────────────────────────────────────────────────
for ABI in "${ABIS[@]}"; do
    echo ""
    echo "=== Building $ABI ==="

    BUILD_DIR="$BUILD_BASE/$ABI"
    OUT_DIR="$OUT_BASE/$ABI"
    mkdir -p "$BUILD_DIR" "$OUT_DIR"

    # Find the AGP-extracted prefab directory for this ABI (from a prior Gradle
    # configure).  AGP places it under .cxx/<variant>/<hash>/prefab/<abi>/prefab.
    PREFAB_DIR="${PREFAB_DIR_OVERRIDE:-}"
    if [[ -z "$PREFAB_DIR" ]]; then
        PREFAB_DIR=$(find "$SCRIPT_DIR/kotlin/uapmd-binding/.cxx" \
            -path "*/prefab/$ABI/prefab" -type d 2>/dev/null | head -1)
    fi
    if [[ -z "$PREFAB_DIR" ]]; then
        echo "ERROR: No extracted prefab packages found for $ABI." >&2
        echo "  Run: cd kotlin && ./gradlew :uapmd-binding:configureCMakeRelWithDebInfo" >&2
        exit 1
    fi
    echo "  Prefab root: $PREFAB_DIR"

    "$CMAKE_CMD" \
        -S "$CPP_SRC" \
        -B "$BUILD_DIR" \
        -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=android-26 \
        -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE=RelWithDebInfo \
        -DCMAKE_FIND_ROOT_PATH="$PREFAB_DIR" \
        -DAAP_DIR="$AAP_DIR" \
        -DMIDICCI_SKIP_TOOLS=ON \
        -DCPM_SOURCE_CACHE="$CPM_CACHE" \
        -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$OUT_DIR" \
        -DCMAKE_RUNTIME_OUTPUT_DIRECTORY="$OUT_DIR"

    "$CMAKE_CMD" --build "$BUILD_DIR" --target uapmd-jni --parallel

    SO="$OUT_DIR/libuapmd-jni.so"
    if [[ -f "$SO" ]]; then
        echo "  Output: $SO"
    else
        # CMake may place it in a subdirectory depending on the generator
        SO=$(find "$BUILD_DIR" -name "libuapmd-jni.so" | head -1)
        if [[ -n "$SO" ]]; then
            cp "$SO" "$OUT_DIR/libuapmd-jni.so"
            echo "  Output: $OUT_DIR/libuapmd-jni.so"
        else
            echo "WARNING: libuapmd-jni.so not found for $ABI" >&2
        fi
    fi
done

echo ""
echo "Done. Built ABIs: ${ABIS[*]}"
echo "Output: $OUT_BASE"
