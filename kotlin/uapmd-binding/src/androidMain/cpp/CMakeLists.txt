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

# Every UAPMD_ENABLE_* feature option, on -- before add_subdirectory(), since
# uapmd declares the same options and option() is a no-op once the cache entry
# exists. The Gradle build passes -DUAPMD_ENABLE_ARA=OFF, which still wins.
get_filename_component(UAPMD_KMP_CMAKE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../cmake" REALPATH)
include("${UAPMD_KMP_CMAKE_DIR}/UapmdFeatureOptions.cmake")
include("${UAPMD_KMP_CMAKE_DIR}/UapmdPatches.cmake")

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
add_library(uapmd-jni SHARED uapmd_jni.cpp uapmd_jni_history.cpp uapmd_jni_app.cpp)

target_include_directories(uapmd-jni PRIVATE
    "${CAPI_DIR}/include")

target_link_libraries(uapmd-jni
    uapmd-c-api
    android
    log)

target_compile_features(uapmd-jni PRIVATE cxx_std_17)
