/* uapmd C API — bindings for uapmd-augene2 (the Augene2 MML integration addin)
 *
 * The addin keeps a project service alive even while its UI is disabled, so a
 * project that carries Augene2 resources loads and saves the same either way.
 * A host registers that service once, right after publishing its panel
 * registry, and drives it through uapmd_panel_registry_update().
 *
 * The integration model below is what the addin's panel presents. Every call
 * runs on the model thread; requests complete in later panel-registry updates.
 *
 * Every function is available in every build. When the build does not include
 * uapmd-augene2, uapmd_augene2_available() is false and
 * uapmd_augene2_integration() returns NULL.
 */
#ifndef UAPMD_C_AUGENE2_H
#define UAPMD_C_AUGENE2_H

#include "uapmd-c-common.h"
#include "uapmd-c-engine.h"
#include "uapmd-c-addin.h"

#ifdef __cplusplus
extern "C" {
#endif

/* A reference to the integration that stops keeping it alive: once the
 * application releases the project service, every call is a no-op returning
 * the empty value. Release the reference itself with
 * uapmd_augene2_integration_release(). */
typedef struct uapmd_augene2_integration* uapmd_augene2_integration_t;

/* Strings point into per-thread storage that the next call of the same getter
 * on the same thread overwrites; copy to keep them. */
typedef struct uapmd_augene2_source {
    const char* path;           /* project-relative resource path */
    const char* external_path;  /* linked file; empty for a bundled copy */
    bool        compile;        /* compiled on its own, not only #included */
} uapmd_augene2_source_t;

typedef struct uapmd_augene2_track_mapping {
    const char* key;
    /* A timeline track index, UAPMD_MASTER_TRACK_INDEX, or -1 when the track
     * is no longer available. */
    int32_t     track_index;
} uapmd_augene2_track_mapping_t;

UAPMD_C_EXPORT bool uapmd_augene2_available(void);

/* uapmd_augene2::registerProjectService(). */
UAPMD_C_EXPORT void uapmd_augene2_register_project_service(uapmd_timeline_facade_t timeline,
                                                             uapmd_panel_registry_t panels);

/* uapmd_augene2::integration(); NULL when there is none. */
UAPMD_C_EXPORT uapmd_augene2_integration_t uapmd_augene2_integration(void);
UAPMD_C_EXPORT void uapmd_augene2_integration_release(uapmd_augene2_integration_t integration);

UAPMD_C_EXPORT bool uapmd_augene2_integration_is_open(uapmd_augene2_integration_t integration);
UAPMD_C_EXPORT void uapmd_augene2_integration_set_open(uapmd_augene2_integration_t integration, bool open);
UAPMD_C_EXPORT bool uapmd_augene2_integration_busy(uapmd_augene2_integration_t integration);
UAPMD_C_EXPORT bool uapmd_augene2_integration_compiling(uapmd_augene2_integration_t integration);

UAPMD_C_EXPORT uint32_t uapmd_augene2_integration_source_count(uapmd_augene2_integration_t integration);
UAPMD_C_EXPORT bool     uapmd_augene2_integration_get_source(uapmd_augene2_integration_t integration,
                                                              uint32_t index,
                                                              uapmd_augene2_source_t* out);
UAPMD_C_EXPORT uint32_t uapmd_augene2_integration_track_mapping_count(uapmd_augene2_integration_t integration);
UAPMD_C_EXPORT bool     uapmd_augene2_integration_get_track_mapping(uapmd_augene2_integration_t integration,
                                                                     uint32_t index,
                                                                     uapmd_augene2_track_mapping_t* out);

/* Size-query convention: pass a NULL buffer to learn the required length. */
UAPMD_C_EXPORT size_t   uapmd_augene2_integration_status(uapmd_augene2_integration_t integration,
                                                          char* buf, size_t buf_size);
UAPMD_C_EXPORT uint32_t uapmd_augene2_integration_diagnostic_count(uapmd_augene2_integration_t integration);
UAPMD_C_EXPORT size_t   uapmd_augene2_integration_get_diagnostic(uapmd_augene2_integration_t integration,
                                                                  uint32_t index, char* buf, size_t buf_size);
UAPMD_C_EXPORT size_t   uapmd_augene2_integration_resource_folder(uapmd_augene2_integration_t integration,
                                                                   char* buf, size_t buf_size);
UAPMD_C_EXPORT void     uapmd_augene2_integration_set_resource_folder(uapmd_augene2_integration_t integration,
                                                                       const char* folder);

/* Picks MML files through the application's document provider
 * (uapmd_app_document_provider(), which the host must keep ticking). */
UAPMD_C_EXPORT void uapmd_augene2_integration_import_sources(uapmd_augene2_integration_t integration, bool compile);
UAPMD_C_EXPORT void uapmd_augene2_integration_relink_source(uapmd_augene2_integration_t integration, const char* path);
UAPMD_C_EXPORT void uapmd_augene2_integration_remove_source(uapmd_augene2_integration_t integration, const char* path);
UAPMD_C_EXPORT void uapmd_augene2_integration_compile(uapmd_augene2_integration_t integration);

#ifdef __cplusplus
}
#endif

#endif /* UAPMD_C_AUGENE2_H */
