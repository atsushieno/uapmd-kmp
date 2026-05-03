cmake_minimum_required(VERSION 3.22.1)
project("uapmd-jni")

if(NOT DEFINED UAPMD_LIB_DIR)
    set(UAPMD_LIB_DIR
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/uapmd/build-android/${ANDROID_ABI}")
endif()

add_library(uapmd-c-api SHARED IMPORTED)
set_target_properties(uapmd-c-api PROPERTIES
    IMPORTED_LOCATION "${UAPMD_LIB_DIR}/libuapmd-c-api.so")

set(UAPMD_INCLUDE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/uapmd/source/uapmd-c-api/include")

add_library(uapmd-jni SHARED uapmd_jni.cpp)

target_include_directories(uapmd-jni PRIVATE ${UAPMD_INCLUDE_DIR})

target_link_libraries(uapmd-jni
    uapmd-c-api
    android
    log)

target_compile_features(uapmd-jni PRIVATE cxx_std_17)
