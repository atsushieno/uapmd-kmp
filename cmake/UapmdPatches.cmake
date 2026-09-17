# Applies uapmd-kmp's local patches to the external/uapmd submodule.
#
# uapmd is a submodule, so edits made in its working tree are invisible to this
# repository and are thrown away by the next `git submodule update`. Anything
# uapmd-kmp needs changed in uapmd lives in patches/uapmd/*.patch instead, and
# is applied here at configure time by every entry point that builds uapmd:
# the desktop CMakeLists, the Android one, and the Emscripten one.
#
# Each patch is expected to be upstreamed; this is a holding area, not a fork.
# Once uapmd carries the change, deleting the .patch file is the whole removal.
#
# Applying is idempotent: `git apply --reverse --check` succeeds exactly when a
# patch is already in the tree, which is the normal state after the first
# configure, so a re-configure reports "already applied" rather than failing.

if(DEFINED UAPMD_KMP_PATCHES_APPLIED)
    return()
endif()

set(UAPMD_KMP_PATCHES_DIR "${CMAKE_CURRENT_LIST_DIR}/../patches/uapmd")
get_filename_component(UAPMD_KMP_PATCHES_DIR "${UAPMD_KMP_PATCHES_DIR}" ABSOLUTE)

if(NOT DEFINED UAPMD_KMP_UAPMD_SOURCE_DIR)
    set(UAPMD_KMP_UAPMD_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../external/uapmd")
endif()
get_filename_component(UAPMD_KMP_UAPMD_SOURCE_DIR "${UAPMD_KMP_UAPMD_SOURCE_DIR}" ABSOLUTE)

file(GLOB _uapmd_kmp_patches "${UAPMD_KMP_PATCHES_DIR}/*.patch")
list(SORT _uapmd_kmp_patches)

if(NOT _uapmd_kmp_patches)
    set(UAPMD_KMP_PATCHES_APPLIED TRUE)
    return()
endif()

find_package(Git QUIET)
if(NOT Git_FOUND)
    message(FATAL_ERROR
        "patches/uapmd contains patches but git was not found. git is needed to "
        "apply them to external/uapmd; install it or clear the patch directory.")
endif()

foreach(_patch IN LISTS _uapmd_kmp_patches)
    get_filename_component(_patch_name "${_patch}" NAME)

    # Already in the tree? Reversing it cleanly is the test for that.
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" apply --reverse --check "${_patch}"
        WORKING_DIRECTORY "${UAPMD_KMP_UAPMD_SOURCE_DIR}"
        RESULT_VARIABLE _reverse_ok
        OUTPUT_QUIET ERROR_QUIET)
    if(_reverse_ok EQUAL 0)
        message(STATUS "uapmd patch already applied: ${_patch_name}")
        continue()
    endif()

    execute_process(
        COMMAND "${GIT_EXECUTABLE}" apply --check "${_patch}"
        WORKING_DIRECTORY "${UAPMD_KMP_UAPMD_SOURCE_DIR}"
        RESULT_VARIABLE _check_ok
        ERROR_VARIABLE _check_error)
    if(NOT _check_ok EQUAL 0)
        # Neither applied nor applicable: uapmd moved under the patch. Say so
        # rather than building something that silently lacks the fix.
        message(FATAL_ERROR
            "uapmd patch ${_patch_name} does not apply to external/uapmd and is "
            "not already applied. uapmd has probably changed underneath it: "
            "refresh or drop the patch.\n${_check_error}")
    endif()

    execute_process(
        COMMAND "${GIT_EXECUTABLE}" apply "${_patch}"
        WORKING_DIRECTORY "${UAPMD_KMP_UAPMD_SOURCE_DIR}"
        RESULT_VARIABLE _apply_ok
        ERROR_VARIABLE _apply_error)
    if(NOT _apply_ok EQUAL 0)
        message(FATAL_ERROR "Failed to apply uapmd patch ${_patch_name}:\n${_apply_error}")
    endif()
    message(STATUS "uapmd patch applied: ${_patch_name}")
endforeach()

# Re-run configure when a patch is added, changed or removed.
foreach(_patch IN LISTS _uapmd_kmp_patches)
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${_patch}")
endforeach()

set(UAPMD_KMP_PATCHES_APPLIED TRUE)
