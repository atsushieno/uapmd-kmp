project(uapmd-c-api LANGUAGES C CXX)

set(UAPMD_C_API_HEADERS
    include/c-api/uapmd-c-api.h
    include/c-api/uapmd-c-common.h
    include/c-api/uapmd-c-data.h
    include/c-api/uapmd-c-file.h
    include/c-api/uapmd-c-engine.h
    include/c-api/uapmd-c-tooling.h
    include/c-api/uapmd-c-gui.h
    include/c-api/uapmd-c-app.h
)

set(UAPMD_C_API_SOURCES
    src/uapmd-c-api.cpp
    src/uapmd-c-data.cpp
    src/uapmd-c-file.cpp
    src/uapmd-c-engine.cpp
    src/uapmd-c-tooling.cpp
    src/uapmd-c-gui.cpp
    src/uapmd-c-app.cpp
)

if(EMSCRIPTEN)
    list(REMOVE_ITEM UAPMD_C_API_SOURCES src/uapmd-c-app.cpp)
endif()

if(ANDROID OR IOS OR EMSCRIPTEN)
    add_library(uapmd-c-api STATIC ${UAPMD_C_API_HEADERS} ${UAPMD_C_API_SOURCES})
else()
    add_library(uapmd-c-api SHARED ${UAPMD_C_API_HEADERS} ${UAPMD_C_API_SOURCES})
endif()

target_include_directories(uapmd-c-api
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)

target_link_libraries(uapmd-c-api
    PUBLIC
        uapmd
        uapmd-data
        uapmd-file
        uapmd-engine
        remidy-tooling
        remidy-gui
)

# App-layer objects (AppModel etc.) — platform-specific linkage
if(ANDROID)
    target_link_libraries(uapmd-c-api PRIVATE uapmd-app)
elseif(NOT IOS AND NOT EMSCRIPTEN)
    target_link_libraries(uapmd-c-api PRIVATE uapmd-app-objects)
endif()

# Private includes from the uapmd source tree (passed via UAPMD_SOURCE_DIR).
# DOWNLOAD_ONLY CPM packages don't propagate their _SOURCE_DIR to parent scope;
# read them from the CPM internal cache entries instead.
target_include_directories(uapmd-c-api
    PRIVATE
        ${UAPMD_SOURCE_DIR}/tools/uapmd-app
        ${UAPMD_SOURCE_DIR}/uapmd-data/include
        ${UAPMD_SOURCE_DIR}/uapmd-engine/include
        ${UAPMD_SOURCE_DIR}/uapmd/include
        ${UAPMD_SOURCE_DIR}/uapmd-file/include
        ${UAPMD_SOURCE_DIR}/remidy/include
        ${UAPMD_SOURCE_DIR}/remidy-tooling/include
        $CACHE{CPM_PACKAGE_choc_SOURCE_DIR}
        $CACHE{CPM_PACKAGE_midicci_SOURCE_DIR}/include
        $CACHE{CPM_PACKAGE_miniaudio_SOURCE_DIR}
        $CACHE{CPM_PACKAGE_concurrentqueue_SOURCE_DIR}
        $CACHE{CPM_PACKAGE_readerwriterqueue_SOURCE_DIR}
)
