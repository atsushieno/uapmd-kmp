cmake_minimum_required(VERSION 3.22.1)
project("uapmd-jni")

# ── uapmd-c-api pre-built shared library ────────────────────────────────────
#
# Set UAPMD_LIB_DIR to the directory containing libuapmd-c-api.so for the
# current ABI, e.g. via:
#   cmake -DUAPMD_LIB_DIR=/path/to/build/${ANDROID_ABI} ...
#
# The default path assumes you ran the uapmd Android NDK build from the
# project root with:
#   cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=arm64-v8a ...
#   → external/uapmd/build-android/<abi>/libuapmd-c-api.so
#
if(NOT DEFINED UAPMD_LIB_DIR)
    set(UAPMD_LIB_DIR
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/uapmd/build-android/${ANDROID_ABI}")
endif()

add_library(uapmd-c-api SHARED IMPORTED)
set_target_properties(uapmd-c-api PROPERTIES
    IMPORTED_LOCATION "${UAPMD_LIB_DIR}/libuapmd-c-api.so")

# ── C API headers ─────────────────────────────────────────────────────────────
set(UAPMD_INCLUDE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/uapmd/source/uapmd-c-api/include")

# ── JNI wrapper library ───────────────────────────────────────────────────────
add_library(uapmd-jni SHARED uapmd_jni.cpp)

target_include_directories(uapmd-jni PRIVATE ${UAPMD_INCLUDE_DIR})

target_link_libraries(uapmd-jni
    uapmd-c-api
    android
    log)

target_compile_features(uapmd-jni PRIVATE cxx_std_17)
