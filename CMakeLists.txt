cmake_minimum_required(VERSION 3.22)
project(uapmd-kmp LANGUAGES C CXX)

set(UAPMD_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/external/uapmd/source"
    CACHE PATH "Path to the uapmd source tree")

# Build the uapmd engine libraries from the submodule.
# uapmd-c-api is NOT included here; we use our own copy in c-api/.
add_subdirectory("${UAPMD_SOURCE_DIR}" uapmd-source)

# Build our owned C API wrapper (SHARED on desktop, STATIC on mobile/wasm).
add_subdirectory(c-api)
