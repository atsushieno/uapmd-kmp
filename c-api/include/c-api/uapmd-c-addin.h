/* uapmd C API — bindings for uapmd-addin-core */
#ifndef UAPMD_C_ADDIN_H
#define UAPMD_C_ADDIN_H

#include "uapmd-c-common.h"
#include "uapmd-c-engine.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ── Opaque handle ───────────────────────────────────────────────────────── */

typedef struct uapmd_addin_manager* uapmd_addin_manager_t;

/* Host-owned registries that addins contribute to. The host creates them,
 * publishes them into an AddinManager before initialize(), and keeps them alive
 * for as long as any addin attached to them stays loaded -- destroying one
 * before uapmd_addin_manager_shutdown() leaves whatever registered in it
 * dangling. An addin removes its own contributions during cleanup(). */
typedef struct uapmd_command_registry*        uapmd_command_registry_t;
typedef struct uapmd_clip_command_registry*   uapmd_clip_command_registry_t;
typedef struct uapmd_clip_editor_registry*    uapmd_clip_editor_registry_t;
typedef struct uapmd_stem_separator_registry* uapmd_stem_separator_registry_t;
typedef struct uapmd_panel_registry*          uapmd_panel_registry_t;

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

/* ═══════════════════════════════════════════════════════════════════════════
 *  Host registries
 *
 *  An addin attaches to exactly one extension point, named by a path, and
 *  fails to load when the host has not published that path. The registries
 *  below are the ones the addins uapmd ships attach to, so a host that wants
 *  them has to publish all of these before initialize():
 *
 *    /uapmd/app/command/v1                    application commands (Virtual MIDI
 *                                             Devices, Augene2 Integration)
 *    /uapmd/app/project-command/v1            project-wide commands: the MIR,
 *                                             Basic Pitch, DrumScript and pitch
 *                                             transcription analyses
 *    /uapmd/app/clip-command/v1               their clip-scoped counterparts
 *    /uapmd/app/timeline/clip-editor/v1       timeline clip editors
 *    /uapmd/audio-import/stem-separator/v1    Demucs and BS-Roformer
 *    /uapmd/app/panel/v1                      addin panels (Augene2 Integration)
 *
 *  uapmd_engine_register_addin_extension_points() publishes the engine's own
 *  two (/uapmd/engine/v1 and /uapmd/audio-graph/provider/v1) separately, and
 *  uapmd_addin_manager_register_app_model() (uapmd-c-app.h) publishes
 *  /uapmd/app/model/v1 for the Virtual MIDI Devices addin.
 *
 *  Every index below addresses into the registry's current contents, which
 *  change when an addin is enabled or disabled. Treat an index as valid only
 *  until the next uapmd_addin_manager_set_enabled() or _initialize() call --
 *  in practice, for the duration of one UI update.
 *
 *  Strings in the *_info_t structs point into per-thread storage that the next
 *  call of the same getter on the same thread overwrites; copy to keep them.
 * ═══════════════════════════════════════════════════════════════════════════ */

/* One command an addin contributed. `order` is the addin's own placement hint;
 * the host decides where and how to present it. */
typedef struct uapmd_addin_command_info {
    const char* id;
    const char* title;
    int32_t     order;
    bool        enabled;  /* false: present it greyed out */
} uapmd_addin_command_info_t;

UAPMD_C_EXPORT uapmd_command_registry_t uapmd_command_registry_create(void);
UAPMD_C_EXPORT void     uapmd_command_registry_destroy(uapmd_command_registry_t reg);
UAPMD_C_EXPORT void     uapmd_addin_manager_register_command_registry(uapmd_addin_manager_t mgr, uapmd_command_registry_t reg);
UAPMD_C_EXPORT uint32_t uapmd_command_registry_count(uapmd_command_registry_t reg);
UAPMD_C_EXPORT bool     uapmd_command_registry_get(uapmd_command_registry_t reg, uint32_t index, uapmd_addin_command_info_t* out);
UAPMD_C_EXPORT bool     uapmd_command_registry_invoke(uapmd_command_registry_t reg, uint32_t index);
/* Invoking by id survives the list changing underneath, which an index does
 * not. False when no command with that id is registered. */
UAPMD_C_EXPORT bool     uapmd_command_registry_invoke_by_id(uapmd_command_registry_t reg, const char* id);
/* Project-wide commands are a CommandRegistry of their own, published at
 * /uapmd/app/project-command/v1; read and invoke it with the functions above. */
UAPMD_C_EXPORT void     uapmd_addin_manager_register_project_command_registry(uapmd_addin_manager_t mgr, uapmd_command_registry_t reg);

/* The clip a clip-scoped command is being offered for. The identifiers are the
 * ones the rest of this API takes, so a command resolves the clip through the
 * engine rather than through anything carried here. */
typedef struct uapmd_clip_command_target {
    int32_t track_index;
    int32_t clip_id;
    bool    midi_clip;
    bool    master_track;
} uapmd_clip_command_target_t;

UAPMD_C_EXPORT uapmd_clip_command_registry_t uapmd_clip_command_registry_create(void);
UAPMD_C_EXPORT void     uapmd_clip_command_registry_destroy(uapmd_clip_command_registry_t reg);
UAPMD_C_EXPORT void     uapmd_addin_manager_register_clip_command_registry(uapmd_addin_manager_t mgr, uapmd_clip_command_registry_t reg);
UAPMD_C_EXPORT uint32_t uapmd_clip_command_registry_count(uapmd_clip_command_registry_t reg);
/* `enabled` in `out` is left true here: a clip command's enablement depends on
 * the target, so ask uapmd_clip_command_registry_enabled() for the real answer. */
UAPMD_C_EXPORT bool     uapmd_clip_command_registry_get(uapmd_clip_command_registry_t reg, uint32_t index, uapmd_addin_command_info_t* out);
/* False hides the command for this clip entirely; true with enabled() false
 * means show it greyed out. */
UAPMD_C_EXPORT bool     uapmd_clip_command_registry_applies_to(uapmd_clip_command_registry_t reg, uint32_t index, uapmd_clip_command_target_t target);
UAPMD_C_EXPORT bool     uapmd_clip_command_registry_enabled(uapmd_clip_command_registry_t reg, uint32_t index, uapmd_clip_command_target_t target);
UAPMD_C_EXPORT bool     uapmd_clip_command_registry_invoke(uapmd_clip_command_registry_t reg, uint32_t index, uapmd_clip_command_target_t target);

/* Timeline clip editors.
 *
 * Only the registry is bound, not uapmd_addin::ClipEditor itself: an editor
 * draws itself by calling update() and render() inside the host's own
 * immediate-mode UI loop, which a Kotlin host does not have and cannot give it.
 * Publishing the extension point still matters, because an addin that asks for
 * it fails to load when it is missing, and enumerating lets a host report what
 * it is declining to show. */
typedef struct uapmd_clip_editor_info {
    const char* id;
    const char* name;
} uapmd_clip_editor_info_t;

UAPMD_C_EXPORT uapmd_clip_editor_registry_t uapmd_clip_editor_registry_create(void);
UAPMD_C_EXPORT void     uapmd_clip_editor_registry_destroy(uapmd_clip_editor_registry_t reg);
UAPMD_C_EXPORT void     uapmd_addin_manager_register_clip_editor_registry(uapmd_addin_manager_t mgr, uapmd_clip_editor_registry_t reg);
UAPMD_C_EXPORT uint32_t uapmd_clip_editor_registry_count(uapmd_clip_editor_registry_t reg);
UAPMD_C_EXPORT bool     uapmd_clip_editor_registry_get(uapmd_clip_editor_registry_t reg, uint32_t index, uapmd_clip_editor_info_t* out);

/* ── Panels ──────────────────────────────────────────────────────────────── */

/* Model-thread services with an optional panel (uapmd_addin::PanelRegistry).
 * update() runs every registered panel's service work -- call it from the model
 * thread on every UI tick, whether or not any panel is shown. Panels draw
 * themselves with render() inside the host's immediate-mode UI loop, which is
 * not bound, for the same reason clip editors are not; an addin that wants to be
 * presented by other toolkits exposes a model of its own (see uapmd-c-augene2.h).
 *
 * Retained panels are project services that outlive their addin's UI: release
 * them with clear_retained_panels() after uapmd_addin_manager_shutdown(), while
 * the sequencer engine is still alive. */
UAPMD_C_EXPORT uapmd_panel_registry_t uapmd_panel_registry_create(void);
UAPMD_C_EXPORT void uapmd_panel_registry_destroy(uapmd_panel_registry_t reg);
UAPMD_C_EXPORT void uapmd_addin_manager_register_panel_registry(uapmd_addin_manager_t mgr, uapmd_panel_registry_t reg);
UAPMD_C_EXPORT void uapmd_panel_registry_update(uapmd_panel_registry_t reg);
UAPMD_C_EXPORT void uapmd_panel_registry_clear_retained_panels(uapmd_panel_registry_t reg);

/* ── Stem separation ─────────────────────────────────────────────────────── */

/* A separator that needs no model file reports an empty label and zero
 * extensions; one that does needs the file picked by the host and passed to
 * uapmd_import_audio_file(). */
typedef struct uapmd_stem_separator_info {
    const char* id;
    const char* name;
    const char* model_file_label;
    uint32_t    model_file_extension_count;
} uapmd_stem_separator_info_t;

UAPMD_C_EXPORT uapmd_stem_separator_registry_t uapmd_stem_separator_registry_create(void);
UAPMD_C_EXPORT void     uapmd_stem_separator_registry_destroy(uapmd_stem_separator_registry_t reg);
UAPMD_C_EXPORT void     uapmd_addin_manager_register_stem_separator_registry(uapmd_addin_manager_t mgr, uapmd_stem_separator_registry_t reg);
UAPMD_C_EXPORT uint32_t uapmd_stem_separator_registry_count(uapmd_stem_separator_registry_t reg);
UAPMD_C_EXPORT bool     uapmd_stem_separator_registry_get(uapmd_stem_separator_registry_t reg, uint32_t index, uapmd_stem_separator_info_t* out);
/* One accepted model-file extension, e.g. ".onnx". */
UAPMD_C_EXPORT size_t   uapmd_stem_separator_registry_get_model_extension(uapmd_stem_separator_registry_t reg, uint32_t index, uint32_t extension_index, char* buf, size_t buf_size);

/* Progress and cancellation for an import. Return false from the progress
 * callback to cancel; it is called from the worker thread running the import,
 * not from the caller's. */
typedef bool (*uapmd_import_progress_cb_t)(float progress, const char* message, void* user_data);

/* One separated stem, ready to become a clip. */
typedef struct uapmd_audio_stem_import {
    const char* stem_name;
    const char* filepath;
    const char* clip_display_name;
} uapmd_audio_stem_import_t;

/* Pointers inside are valid until the next uapmd_import_audio_file() call on
 * the same thread. */
typedef struct uapmd_audio_import_result {
    bool        success;
    bool        canceled;
    const char* error;      /* NULL when there is none */
    uint32_t    warning_count;
    const char* const* warnings;
    uint32_t    stem_count;
    const uapmd_audio_stem_import_t* stems;
} uapmd_audio_import_result_t;

/* Separates `filepath` into stems and writes them under `output_directory`.
 *
 * Blocking, and slow by nature -- it runs a neural model over the whole file --
 * so call it off the UI thread. It holds a lease on the separator for the whole
 * run, so disabling the contributing addin meanwhile cancels the run rather
 * than unloading the code underneath it.
 *
 * `separator_id` names one of the separators this registry lists; passing an
 * unknown or withdrawn one fails rather than silently picking another.
 * `model_path` is the file the separator asked for through its info, and may be
 * NULL when it asked for none. */
UAPMD_C_EXPORT uapmd_audio_import_result_t uapmd_import_audio_file(
    uapmd_stem_separator_registry_t reg,
    const char* separator_id,
    const char* filepath,
    const char* output_directory,
    const char* model_path,
    void* user_data,
    uapmd_import_progress_cb_t progress);

#ifdef __cplusplus
}
#endif

#endif /* UAPMD_C_ADDIN_H */
