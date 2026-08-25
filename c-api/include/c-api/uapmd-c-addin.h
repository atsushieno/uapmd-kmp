/* uapmd C API — bindings for uapmd-addin-core
 *
 * Introduced by uapmd 0.5.6. An addin is a package that attaches itself to
 * named extension points a host publishes; ARA support is one such addin.
 * Hosts own an AddinManager, publish their extension points into it, and then
 * initialize() it to load whatever is installed.
 */
#ifndef UAPMD_C_ADDIN_H
#define UAPMD_C_ADDIN_H

#include "uapmd-c-common.h"
#include "uapmd-c-engine.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ── Opaque handle ───────────────────────────────────────────────────────── */

typedef struct uapmd_addin_manager* uapmd_addin_manager_t;

/* ── Types ───────────────────────────────────────────────────────────────── */

typedef enum uapmd_addin_state {
    UAPMD_ADDIN_INACTIVE     = 0,
    UAPMD_ADDIN_INITIALIZING = 1,
    UAPMD_ADDIN_ACTIVE       = 2,
    UAPMD_ADDIN_CLEANING_UP  = 3,
    UAPMD_ADDIN_FAILED       = 4
} uapmd_addin_state_t;

/* String members point into the manager's own storage and stay valid until the
 * next call that mutates its addin list (initialize/set_enabled/shutdown) or
 * the manager is destroyed. The exception is `library_path`, which is
 * materialised into per-thread storage that the next
 * uapmd_addin_manager_get_addin() call on the same thread overwrites. */
typedef struct uapmd_addin_info {
    const char* package_id;
    const char* addin_id;
    const char* name;
    const char* path;          /* extension point path this addin attaches to */
    const char* library_path;  /* empty for built-in addins */
    bool        built_in;
    uapmd_addin_state_t state;
    const char* message;       /* failure detail, or empty */
} uapmd_addin_info_t;

/* ═══════════════════════════════════════════════════════════════════════════
 *  AddinManager
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT uapmd_addin_manager_t uapmd_addin_manager_create(void);
UAPMD_C_EXPORT void uapmd_addin_manager_destroy(uapmd_addin_manager_t mgr);

/* Publish an extension point under `path`. The pointer stays owned by the
 * caller and must outlive every addin attached to it. Register all extension
 * points before calling initialize(). */
UAPMD_C_EXPORT void uapmd_addin_manager_register_extension_point(uapmd_addin_manager_t mgr,
                                                                    const char* path,
                                                                    void* extension_point);

/* Publishes every extension point the sequencer engine offers into `mgr`.
 * Call before uapmd_addin_manager_initialize(). */
UAPMD_C_EXPORT void uapmd_engine_register_addin_extension_points(uapmd_sequencer_engine_t engine,
                                                                    uapmd_addin_manager_t mgr);

UAPMD_C_EXPORT void uapmd_addin_manager_initialize(uapmd_addin_manager_t mgr);
UAPMD_C_EXPORT bool uapmd_addin_manager_set_enabled(uapmd_addin_manager_t mgr,
                                                      const char* package_id,
                                                      const char* addin_id,
                                                      bool enabled);
UAPMD_C_EXPORT void uapmd_addin_manager_shutdown(uapmd_addin_manager_t mgr);

/* Directories scanned for installed addin packages. */
UAPMD_C_EXPORT uint32_t uapmd_addin_manager_directory_count(uapmd_addin_manager_t mgr);
UAPMD_C_EXPORT size_t   uapmd_addin_manager_get_directory(uapmd_addin_manager_t mgr, uint32_t index, char* buf, size_t buf_size);

UAPMD_C_EXPORT uint32_t uapmd_addin_manager_addin_count(uapmd_addin_manager_t mgr);
UAPMD_C_EXPORT bool     uapmd_addin_manager_get_addin(uapmd_addin_manager_t mgr, uint32_t index, uapmd_addin_info_t* out);

UAPMD_C_EXPORT size_t uapmd_addin_manager_last_error(uapmd_addin_manager_t mgr, char* buf, size_t buf_size);

/* False on platforms without dynamic loading (Wasm, iOS), where only built-in
 * addins are available. */
UAPMD_C_EXPORT bool uapmd_addin_supports_dynamic_loading(void);
UAPMD_C_EXPORT const char* uapmd_addin_state_name(uapmd_addin_state_t state);

#ifdef __cplusplus
}
#endif

#endif /* UAPMD_C_ADDIN_H */
