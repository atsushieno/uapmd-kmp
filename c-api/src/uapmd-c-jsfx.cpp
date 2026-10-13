/* uapmd C API — implementation for the uapmd-format-jsfx bindings */

#include "c-api/uapmd-c-jsfx.h"
#include <uapmd-app-model/uapmd-app-model.hpp>
#include <string>
#include <vector>

#if UAPMD_HAS_JSFX
#include <uapmd-format-jsfx/uapmd-format-jsfx.hpp>

namespace {

uapmd_app::AppModel* AM(uapmd_app_model_t h) { return reinterpret_cast<uapmd_app::AppModel*>(h); }
uapmd_jsfx::JsfxPluginFormat* JF(uapmd_jsfx_plugin_format_t h) {
    return reinterpret_cast<uapmd_jsfx::JsfxPluginFormat*>(h);
}

thread_local std::string tl_content_directory;
thread_local std::string tl_mirror_root;
thread_local std::string tl_registered_name;
thread_local std::string tl_search_path;
thread_local std::string tl_default_search_path;
thread_local std::vector<uapmd_jsfx::JsfxRegisteredFolder> tl_registered_folders;
thread_local uapmd_jsfx::JsfxImportResult tl_import_result;
thread_local std::string tl_import_destination;

void fill(const uapmd_jsfx::JsfxImportResult& result, uapmd_jsfx_import_result_t* out) {
    tl_import_result = result;
    tl_import_destination = tl_import_result.destination.string();
    out->success = tl_import_result.success;
    out->files_written = tl_import_result.filesWritten;
    out->destination = tl_import_destination.c_str();
    out->error = tl_import_result.error.c_str();
}

}

bool uapmd_jsfx_available(void) { return true; }

void uapmd_jsfx_set_editor_font_data(const uint8_t* data, size_t size) {
    uapmd_jsfx::setJsfxEditorFontData(data, data ? size : 0);
}

bool uapmd_jsfx_uses_own_font_backend(void) { return uapmd_jsfx::jsfxUsesOwnFontBackend(); }

const char* uapmd_jsfx_user_content_directory(void) {
    tl_content_directory = uapmd_jsfx::jsfxUserContentDirectory().string();
    return tl_content_directory.c_str();
}

bool uapmd_jsfx_looks_like_archive(const char* file_name) {
    return uapmd_jsfx::looksLikeJsfxArchive(file_name ? file_name : "");
}

void uapmd_jsfx_import_content(const char* file_name, const uint8_t* data, size_t size,
                               const char* subdirectory, uapmd_jsfx_import_result_t* out) {
    if (!out)
        return;
    fill(uapmd_jsfx::importJsfxContent(file_name ? file_name : "", data, data ? size : 0,
                                       subdirectory ? subdirectory : ""),
         out);
}

const char* uapmd_jsfx_folder_mirror_root(void) {
    tl_mirror_root = uapmd_jsfx::jsfxFolderMirrorRoot().string();
    return tl_mirror_root.c_str();
}

uint32_t uapmd_jsfx_registered_folder_count(void) {
    tl_registered_folders = uapmd_jsfx::jsfxRegisteredFolders();
    return static_cast<uint32_t>(tl_registered_folders.size());
}

bool uapmd_jsfx_get_registered_folder(uint32_t index, uapmd_jsfx_registered_folder_t* out) {
    if (!out || index >= tl_registered_folders.size())
        return false;
    auto& folder = tl_registered_folders[index];
    out->token = folder.token.c_str();
    out->name = folder.name.c_str();
    return true;
}

const char* uapmd_jsfx_register_folder(const char* token, const char* name) {
    tl_registered_name = uapmd_jsfx::registerJsfxFolder(token ? token : "", name ? name : "");
    return tl_registered_name.c_str();
}

void uapmd_jsfx_unregister_folder(const char* token) {
    uapmd_jsfx::unregisterJsfxFolder(token ? token : "");
}

bool uapmd_jsfx_clear_folder_mirror(const char* name) {
    return uapmd_jsfx::clearJsfxFolderMirror(name ? name : "");
}

void uapmd_jsfx_write_folder_mirror_file(const char* name, const char* relative_path,
                                         const uint8_t* data, size_t size,
                                         uapmd_jsfx_import_result_t* out) {
    if (!out)
        return;
    fill(uapmd_jsfx::writeJsfxFolderMirrorFile(name ? name : "", relative_path ? relative_path : "",
                                               data, data ? size : 0),
         out);
}

uapmd_jsfx_plugin_format_t uapmd_app_jsfx_plugin_format(uapmd_app_model_t app) {
    return reinterpret_cast<uapmd_jsfx_plugin_format_t>(AM(app)->jsfxPluginFormat());
}

void uapmd_jsfx_format_set_search_paths(uapmd_jsfx_plugin_format_t format, const char* const* paths,
                                        uint32_t count, bool also_use_defaults) {
    std::vector<std::string> list;
    list.reserve(count);
    for (uint32_t i = 0; i < count; i++)
        if (paths && paths[i])
            list.emplace_back(paths[i]);
    JF(format)->searchPaths(std::move(list), also_use_defaults);
}

uint32_t uapmd_jsfx_format_search_path_count(uapmd_jsfx_plugin_format_t format) {
    return static_cast<uint32_t>(JF(format)->searchPaths().size());
}

const char* uapmd_jsfx_format_get_search_path(uapmd_jsfx_plugin_format_t format, uint32_t index) {
    auto paths = JF(format)->searchPaths();
    tl_search_path = index < paths.size() ? paths[index] : std::string{};
    return tl_search_path.c_str();
}

bool uapmd_jsfx_format_use_default_search_paths(uapmd_jsfx_plugin_format_t format) {
    return JF(format)->useDefaultSearchPaths();
}

uint32_t uapmd_jsfx_format_default_search_path_count(uapmd_jsfx_plugin_format_t format) {
    return static_cast<uint32_t>(JF(format)->defaultSearchPaths().size());
}

const char* uapmd_jsfx_format_get_default_search_path(uapmd_jsfx_plugin_format_t format, uint32_t index) {
    auto paths = JF(format)->defaultSearchPaths();
    tl_default_search_path = index < paths.size() ? paths[index].string() : std::string{};
    return tl_default_search_path.c_str();
}

uapmd_jsfx_duplicate_listing_t uapmd_jsfx_format_duplicate_listing(uapmd_jsfx_plugin_format_t format) {
    return static_cast<uapmd_jsfx_duplicate_listing_t>(JF(format)->duplicateListing());
}

void uapmd_jsfx_format_set_duplicate_listing(uapmd_jsfx_plugin_format_t format,
                                             uapmd_jsfx_duplicate_listing_t value) {
    JF(format)->duplicateListing(static_cast<uapmd_jsfx::JsfxDuplicateListing>(value));
}

void uapmd_app_sync_jsfx_registered_folders(uapmd_app_model_t app, void* user_data,
                                            uapmd_jsfx_folder_sync_cb_t callback) {
    AM(app)->syncJsfxRegisteredFolders([callback, user_data](uint32_t written, std::string error) {
        if (callback)
            callback(written, error.empty() ? nullptr : error.c_str(), user_data);
    });
}

#else

namespace {
const char* empty() { return ""; }
void fill_unavailable(uapmd_jsfx_import_result_t* out) {
    if (!out)
        return;
    out->success = false;
    out->files_written = 0;
    out->destination = "";
    out->error = "JSFX is unavailable in this build.";
}
}

bool uapmd_jsfx_available(void) { return false; }
void uapmd_jsfx_set_editor_font_data(const uint8_t*, size_t) {}
bool uapmd_jsfx_uses_own_font_backend(void) { return false; }
const char* uapmd_jsfx_user_content_directory(void) { return empty(); }
bool uapmd_jsfx_looks_like_archive(const char*) { return false; }
void uapmd_jsfx_import_content(const char*, const uint8_t*, size_t, const char*,
                               uapmd_jsfx_import_result_t* out) { fill_unavailable(out); }
const char* uapmd_jsfx_folder_mirror_root(void) { return empty(); }
uint32_t uapmd_jsfx_registered_folder_count(void) { return 0; }
bool uapmd_jsfx_get_registered_folder(uint32_t, uapmd_jsfx_registered_folder_t*) { return false; }
const char* uapmd_jsfx_register_folder(const char*, const char*) { return empty(); }
void uapmd_jsfx_unregister_folder(const char*) {}
bool uapmd_jsfx_clear_folder_mirror(const char*) { return false; }
void uapmd_jsfx_write_folder_mirror_file(const char*, const char*, const uint8_t*, size_t,
                                         uapmd_jsfx_import_result_t* out) { fill_unavailable(out); }
uapmd_jsfx_plugin_format_t uapmd_app_jsfx_plugin_format(uapmd_app_model_t) { return nullptr; }
void uapmd_jsfx_format_set_search_paths(uapmd_jsfx_plugin_format_t, const char* const*, uint32_t, bool) {}
uint32_t uapmd_jsfx_format_search_path_count(uapmd_jsfx_plugin_format_t) { return 0; }
const char* uapmd_jsfx_format_get_search_path(uapmd_jsfx_plugin_format_t, uint32_t) { return empty(); }
bool uapmd_jsfx_format_use_default_search_paths(uapmd_jsfx_plugin_format_t) { return false; }
uint32_t uapmd_jsfx_format_default_search_path_count(uapmd_jsfx_plugin_format_t) { return 0; }
const char* uapmd_jsfx_format_get_default_search_path(uapmd_jsfx_plugin_format_t, uint32_t) { return empty(); }
uapmd_jsfx_duplicate_listing_t uapmd_jsfx_format_duplicate_listing(uapmd_jsfx_plugin_format_t) {
    return UAPMD_JSFX_DUPLICATE_LISTING_SHOW_ALL;
}
void uapmd_jsfx_format_set_duplicate_listing(uapmd_jsfx_plugin_format_t, uapmd_jsfx_duplicate_listing_t) {}
void uapmd_app_sync_jsfx_registered_folders(uapmd_app_model_t, void* user_data,
                                            uapmd_jsfx_folder_sync_cb_t callback) {
    if (callback)
        callback(0, nullptr, user_data);
}

#endif
