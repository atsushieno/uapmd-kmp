/* uapmd C API — bindings for remidy-tooling (PluginScanTool, PluginInstancing, etc.) */
#ifndef UAPMD_C_TOOLING_H
#define UAPMD_C_TOOLING_H

#include "uapmd-c-common.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ── Opaque handles ─────────────────────────────────────────────────────── */

typedef struct uapmd_scan_tool*          uapmd_scan_tool_t;
typedef struct uapmd_plugin_instancing*  uapmd_plugin_instancing_t;
typedef struct uapmd_format_manager*     uapmd_format_manager_t;

/* ── Enums ──────────────────────────────────────────────────────────────── */

typedef enum uapmd_scan_mode {
    UAPMD_SCAN_MODE_IN_PROCESS = 0,
    UAPMD_SCAN_MODE_REMOTE     = 1
} uapmd_scan_mode_t;

typedef enum uapmd_instancing_state {
    UAPMD_INSTANCING_CREATED     = 0,
    UAPMD_INSTANCING_PREPARING   = 1,
    UAPMD_INSTANCING_READY       = 2,
    UAPMD_INSTANCING_ERROR       = 3,
    UAPMD_INSTANCING_TERMINATING = 4,
    UAPMD_INSTANCING_TERMINATED  = 5
} uapmd_instancing_state_t;

/* ── Data structs ───────────────────────────────────────────────────────── */

typedef struct uapmd_blocklist_entry {
    const char* id;
    const char* format;
    const char* plugin_id;
    const char* reason;
} uapmd_blocklist_entry_t;

/* ── Scan observer callbacks ────────────────────────────────────────────── */

typedef void (*uapmd_scan_started_cb_t)(uint32_t total_bundles, void* user_data);
typedef void (*uapmd_bundle_scan_cb_t)(const char* bundle_path, void* user_data);
typedef void (*uapmd_scan_completed_cb_t)(void* user_data);
typedef void (*uapmd_scan_error_cb_t)(const char* message, void* user_data);
typedef bool (*uapmd_scan_should_cancel_cb_t)(void* user_data);

typedef struct uapmd_scan_observer {
    void* user_data;
    uapmd_scan_started_cb_t      slow_scan_started;
    uapmd_bundle_scan_cb_t       bundle_scan_started;
    uapmd_bundle_scan_cb_t       bundle_scan_completed;
    uapmd_scan_completed_cb_t    slow_scan_completed;
    uapmd_scan_error_cb_t        error_occurred;
    uapmd_scan_should_cancel_cb_t should_cancel;
} uapmd_scan_observer_t;

/* Instancing callback */
typedef void (*uapmd_instancing_cb_t)(const char* error, void* user_data);

/* ═══════════════════════════════════════════════════════════════════════════
 *  PluginScanTool
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT uapmd_scan_tool_t uapmd_scan_tool_create(void);
UAPMD_C_EXPORT void              uapmd_scan_tool_destroy(uapmd_scan_tool_t tool);

/* Catalog access (returns the underlying catalog from the tool) */
UAPMD_C_EXPORT uint32_t uapmd_scan_tool_catalog_entry_count(uapmd_scan_tool_t tool);

/* Format list */
UAPMD_C_EXPORT uint32_t uapmd_scan_tool_format_count(uapmd_scan_tool_t tool);
UAPMD_C_EXPORT size_t   uapmd_scan_tool_get_format_name(uapmd_scan_tool_t tool, uint32_t index,
                                                          char* buf, size_t buf_size);

/* Cache file */
/* Where this installation keeps the plugin list cache, the blocklist, the search
 * path settings and anything a plugin format writes.
 *
 * Desktop and the web work this out themselves. Android and iOS cannot -- the
 * location belongs to the application sandbox -- so the platform layer sets it
 * before anything that writes is constructed. Setting it after a scan tool exists
 * does not move that tool's files. An empty path means nothing persists, which is
 * a supported state. */
UAPMD_C_EXPORT void   uapmd_application_data_directory_set(const char* path);
UAPMD_C_EXPORT size_t uapmd_application_data_directory_get(char* buf, size_t buf_size);

UAPMD_C_EXPORT size_t uapmd_scan_tool_get_cache_file(uapmd_scan_tool_t tool, char* buf, size_t buf_size);

/* The user's plugin search paths, kept beside the cache file. Empty on platforms
 * with no writable location.
 *
 * Load applies the stored paths to every format that has any, and must run after
 * the formats are registered and before the first scan, because a format reads its
 * search paths when it enumerates. Save records what the formats currently have;
 * changing them afterwards needs a rescan, since dropping a location has to drop
 * its plugins from the catalog. */
UAPMD_C_EXPORT size_t uapmd_scan_tool_get_search_path_settings_file(uapmd_scan_tool_t tool,
                                                                    char* buf, size_t buf_size);
UAPMD_C_EXPORT void   uapmd_scan_tool_load_search_path_settings(uapmd_scan_tool_t tool);
UAPMD_C_EXPORT void   uapmd_scan_tool_save_search_path_settings(uapmd_scan_tool_t tool);

/* Per-format search paths, indexed as uapmd_scan_tool_get_format_name indexes.
 *
 * Only a format that looks its plugins up in search paths has any: AU is
 * enumerated by the operating system, so it reports false here and the rest of
 * these do nothing. The default locations are a separate, uneditable list; the
 * override paths are the user's own, and `use_default` decides whether the
 * defaults are consulted as well.
 *
 * Changing any of this needs a rescan before it shows in the catalog, because
 * dropping a location has to drop its plugins. */
UAPMD_C_EXPORT bool uapmd_scan_tool_format_uses_search_paths(uapmd_scan_tool_t tool, uint32_t format_index);

UAPMD_C_EXPORT uint32_t uapmd_scan_tool_format_default_search_path_count(uapmd_scan_tool_t tool, uint32_t format_index);
UAPMD_C_EXPORT size_t   uapmd_scan_tool_format_get_default_search_path(uapmd_scan_tool_t tool, uint32_t format_index,
                                                                      uint32_t path_index, char* buf, size_t buf_size);

UAPMD_C_EXPORT uint32_t uapmd_scan_tool_format_search_path_count(uapmd_scan_tool_t tool, uint32_t format_index);
UAPMD_C_EXPORT size_t   uapmd_scan_tool_format_get_search_path(uapmd_scan_tool_t tool, uint32_t format_index,
                                                              uint32_t path_index, char* buf, size_t buf_size);
UAPMD_C_EXPORT void     uapmd_scan_tool_format_add_search_path(uapmd_scan_tool_t tool, uint32_t format_index,
                                                              const char* path);
/* Replaces the whole set; `count` of 0 clears it. */
UAPMD_C_EXPORT void     uapmd_scan_tool_format_set_search_paths(uapmd_scan_tool_t tool, uint32_t format_index,
                                                               const char* const* paths, uint32_t count);

UAPMD_C_EXPORT bool uapmd_scan_tool_format_get_use_default_search_paths(uapmd_scan_tool_t tool, uint32_t format_index);
UAPMD_C_EXPORT void uapmd_scan_tool_format_set_use_default_search_paths(uapmd_scan_tool_t tool, uint32_t format_index,
                                                                       bool value);
UAPMD_C_EXPORT void   uapmd_scan_tool_set_cache_file(uapmd_scan_tool_t tool, const char* path);
UAPMD_C_EXPORT void   uapmd_scan_tool_save_cache(uapmd_scan_tool_t tool);
UAPMD_C_EXPORT void   uapmd_scan_tool_save_cache_to(uapmd_scan_tool_t tool, const char* path);

/* Scanning */
UAPMD_C_EXPORT void uapmd_scan_tool_perform_scanning(uapmd_scan_tool_t tool,
                                                       bool require_fast_scanning,
                                                       const uapmd_scan_observer_t* observer);

/* Blocklist */
UAPMD_C_EXPORT uint32_t uapmd_scan_tool_blocklist_count(uapmd_scan_tool_t tool);
UAPMD_C_EXPORT bool     uapmd_scan_tool_get_blocklist_entry(uapmd_scan_tool_t tool, uint32_t index,
                                                              uapmd_blocklist_entry_t* out);
UAPMD_C_EXPORT void     uapmd_scan_tool_flush_blocklist(uapmd_scan_tool_t tool);
UAPMD_C_EXPORT bool     uapmd_scan_tool_unblock_bundle(uapmd_scan_tool_t tool, const char* entry_id);
UAPMD_C_EXPORT void     uapmd_scan_tool_clear_blocklist(uapmd_scan_tool_t tool);
UAPMD_C_EXPORT void     uapmd_scan_tool_add_to_blocklist(uapmd_scan_tool_t tool,
                                                           const char* format_name,
                                                           const char* plugin_id,
                                                           const char* reason);

/* Query */
UAPMD_C_EXPORT size_t uapmd_scan_tool_last_scan_error(uapmd_scan_tool_t tool, char* buf, size_t buf_size);

/* ═══════════════════════════════════════════════════════════════════════════
 *  PluginInstancing
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT uapmd_plugin_instancing_t uapmd_instancing_create(uapmd_scan_tool_t tool,
                                                                    const char* format,
                                                                    const char* plugin_id);
UAPMD_C_EXPORT void uapmd_instancing_destroy(uapmd_plugin_instancing_t inst);

UAPMD_C_EXPORT void uapmd_instancing_make_alive(uapmd_plugin_instancing_t inst,
                                                  void* user_data,
                                                  uapmd_instancing_cb_t callback);
UAPMD_C_EXPORT uapmd_instancing_state_t uapmd_instancing_state(uapmd_plugin_instancing_t inst);

/* ═══════════════════════════════════════════════════════════════════════════
 *  PluginFormatManager
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT uapmd_format_manager_t uapmd_format_manager_create(void);
UAPMD_C_EXPORT void                   uapmd_format_manager_destroy(uapmd_format_manager_t mgr);
UAPMD_C_EXPORT uint32_t               uapmd_format_manager_format_count(uapmd_format_manager_t mgr);
UAPMD_C_EXPORT size_t                 uapmd_format_manager_get_format_name(uapmd_format_manager_t mgr,
                                                                             uint32_t index,
                                                                             char* buf, size_t buf_size);

#ifdef __cplusplus
}
#endif

#endif /* UAPMD_C_TOOLING_H */
