cmake_minimum_required(VERSION 3.28)
project("uapmd-jni")

# uapmd source tree (android/app/src/main/cpp is a symlink to source/)
get_filename_component(UAPMD_SRC_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../external/uapmd/android/app/src/main/cpp"
    REALPATH)

# Our owned c-api directory (sibling of kotlin/ and external/)
get_filename_component(CAPI_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../c-api"
    REALPATH)

include("${UAPMD_SRC_DIR}/cmake/CPM.cmake")

find_package(SDL3 REQUIRED CONFIG)

set(UAPMD_SOURCE_DIR "${UAPMD_SRC_DIR}")
add_subdirectory("${UAPMD_SRC_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/uapmd" EXCLUDE_FROM_ALL)

# ContainerWindow_Android.cpp calls SDL_GetAndroidJNIEnv() which lives in libSDL3.so;
# the upstream remidy-gui CMakeLists.txt does not add this link for Android.
if(ANDROID)
    target_link_libraries(remidy-gui PUBLIC SDL3::SDL3)
endif()

add_subdirectory("${CAPI_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/uapmd-c-api")

# JNI bridge
add_library(uapmd-jni SHARED uapmd_jni.cpp)

target_include_directories(uapmd-jni PRIVATE
    "${CAPI_DIR}/include")

target_link_libraries(uapmd-jni
    uapmd-c-api
    android
    log)

target_compile_features(uapmd-jni PRIVATE cxx_std_17)
