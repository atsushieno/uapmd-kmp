/* uapmd C API — bindings for uapmd-format-jsfx (the JSFX plugin format)
 *
 * JSFX is a plugin format the application registers rather than one
 * uapmd-plugin-hosting knows about; AppModel registers it before its first scan.
 * What is bound here is what a host needs to manage where effects come from:
 * the format's search paths and listing policy, the user content directory that
 * imports are copied into, and the folders registered on platforms where a folder
 * is a grant rather than a location (see uapmd_document_provider_pick_folder()).
 *
 * Every function is available in every build. When the build does not include
 * JSFX, uapmd_jsfx_available() is false, uapmd_app_jsfx_plugin_format() returns
 * NULL, and the rest answer with the empty value.
 *
 * Strings returned as `const char*` point into per-thread storage that the next
 * call of the same function on the same thread overwrites; copy to keep them.
 */
#ifndef UAPMD_C_JSFX_H
#define UAPMD_C_JSFX_H

#include "uapmd-c-common.h"
#include "uapmd-c-app.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct uapmd_jsfx_plugin_format* uapmd_jsfx_plugin_format_t;

/* uapmd_jsfx::JsfxDuplicateListing */
typedef enum uapmd_jsfx_duplicate_listing {
    UAPMD_JSFX_DUPLICATE_LISTING_PREFER_LATEST_VERSION = 0,
    UAPMD_JSFX_DUPLICATE_LISTING_HIDE_EXACT_DUPLICATES = 1,
    UAPMD_JSFX_DUPLICATE_LISTING_SHOW_ALL              = 2
} uapmd_jsfx_duplicate_listing_t;

/* uapmd_jsfx::JsfxImportResult. Strings are per-thread, as above. */
typedef struct uapmd_jsfx_import_result {
    bool        success;
    uint32_t    files_written;
    const char* destination;
    const char* error;   /* empty when success */
} uapmd_jsfx_import_result_t;

/* uapmd_jsfx::JsfxRegisteredFolder. Strings are per-thread, as above. */
typedef struct uapmd_jsfx_registered_folder {
    const char* token;   /* opaque; means something only to the document provider */
    const char* name;    /* the user-facing name, and the mirror's directory name */
} uapmd_jsfx_registered_folder_t;

UAPMD_C_EXPORT bool uapmd_jsfx_available(void);

/* ── Free functions of uapmd-format-jsfx ─────────────────────────────────── */

/* setJsfxEditorFontData(); the data is copied. */
UAPMD_C_EXPORT void uapmd_jsfx_set_editor_font_data(const uint8_t* data, size_t size);
UAPMD_C_EXPORT bool uapmd_jsfx_uses_own_font_backend(void);

/* jsfxUserContentDirectory(); empty where there is nowhere writable. */
UAPMD_C_EXPORT const char* uapmd_jsfx_user_content_directory(void);
UAPMD_C_EXPORT bool uapmd_jsfx_looks_like_archive(const char* file_name);
/* importJsfxContent(). `subdirectory` may be NULL or empty. */
UAPMD_C_EXPORT void uapmd_jsfx_import_content(const char* file_name,
                                                const uint8_t* data,
                                                size_t size,
                                                const char* subdirectory,
                                                uapmd_jsfx_import_result_t* out);

UAPMD_C_EXPORT const char* uapmd_jsfx_folder_mirror_root(void);
/* jsfxRegisteredFolders(). The count call reads the registered folders and keeps
 * them for the calling thread; get reads from what that thread kept. */
UAPMD_C_EXPORT uint32_t uapmd_jsfx_registered_folder_count(void);
UAPMD_C_EXPORT bool     uapmd_jsfx_get_registered_folder(uint32_t index,
                                                           uapmd_jsfx_registered_folder_t* out);
/* registerJsfxFolder(): the name actually used. */
UAPMD_C_EXPORT const char* uapmd_jsfx_register_folder(const char* token, const char* name);
UAPMD_C_EXPORT void        uapmd_jsfx_unregister_folder(const char* token);
UAPMD_C_EXPORT bool        uapmd_jsfx_clear_folder_mirror(const char* name);
UAPMD_C_EXPORT void        uapmd_jsfx_write_folder_mirror_file(const char* name,
                                                                 const char* relative_path,
                                                                 const uint8_t* data,
                                                                 size_t size,
                                                                 uapmd_jsfx_import_result_t* out);

/* ── JsfxPluginFormat ────────────────────────────────────────────────────── */

/* AppModel::jsfxPluginFormat(); NULL when there is none. Owned by the model. */
UAPMD_C_EXPORT uapmd_jsfx_plugin_format_t uapmd_app_jsfx_plugin_format(uapmd_app_model_t app);

/* searchPaths(paths, alsoUseDefaults). A rescan is needed afterwards. */
UAPMD_C_EXPORT void        uapmd_jsfx_format_set_search_paths(uapmd_jsfx_plugin_format_t format,
                                                                const char* const* paths,
                                                                uint32_t count,
                                                                bool also_use_defaults);
UAPMD_C_EXPORT uint32_t    uapmd_jsfx_format_search_path_count(uapmd_jsfx_plugin_format_t format);
UAPMD_C_EXPORT const char* uapmd_jsfx_format_get_search_path(uapmd_jsfx_plugin_format_t format, uint32_t index);
UAPMD_C_EXPORT bool        uapmd_jsfx_format_use_default_search_paths(uapmd_jsfx_plugin_format_t format);
UAPMD_C_EXPORT uint32_t    uapmd_jsfx_format_default_search_path_count(uapmd_jsfx_plugin_format_t format);
UAPMD_C_EXPORT const char* uapmd_jsfx_format_get_default_search_path(uapmd_jsfx_plugin_format_t format, uint32_t index);
UAPMD_C_EXPORT uapmd_jsfx_duplicate_listing_t uapmd_jsfx_format_duplicate_listing(uapmd_jsfx_plugin_format_t format);
UAPMD_C_EXPORT void        uapmd_jsfx_format_set_duplicate_listing(uapmd_jsfx_plugin_format_t format,
                                                                     uapmd_jsfx_duplicate_listing_t value);

/* ── AppModel ────────────────────────────────────────────────────────────── */

typedef void (*uapmd_jsfx_folder_sync_cb_t)(uint32_t files_written, const char* error, void* user_data);

/* AppModel::syncJsfxRegisteredFolders(). The callback runs on the main thread;
 * `error` is NULL when every folder was read. Without JSFX it completes at once. */
UAPMD_C_EXPORT void uapmd_app_sync_jsfx_registered_folders(uapmd_app_model_t app,
                                                             void* user_data,
                                                             uapmd_jsfx_folder_sync_cb_t callback);

#ifdef __cplusplus
}
#endif

#endif /* UAPMD_C_JSFX_H */
