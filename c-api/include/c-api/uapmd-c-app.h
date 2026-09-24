/* uapmd C API — bindings for AppModel (uapmd-app-model) */
#ifndef UAPMD_C_APP_H
#define UAPMD_C_APP_H

#include "uapmd-c-common.h"
#include "uapmd-c-api.h"
#include "uapmd-c-data.h"
#include "uapmd-c-engine.h"
#include "uapmd-c-file.h"
#include "uapmd-c-tooling.h"
#include "uapmd-c-undo.h"
#include "uapmd-c-addin.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ── Opaque handle ───────────────────────────────────────────────────────── */

typedef struct uapmd_app_model*           uapmd_app_model_t;
typedef struct uapmd_transport_controller* uapmd_transport_controller_t;

/* ═══════════════════════════════════════════════════════════════════════════
 *  Lifecycle
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT void uapmd_app_instantiate(void);
UAPMD_C_EXPORT uapmd_app_model_t uapmd_app_instance(void);
UAPMD_C_EXPORT void uapmd_app_cleanup(void);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Accessors
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT uapmd_realtime_sequencer_t uapmd_app_sequencer(uapmd_app_model_t app);
UAPMD_C_EXPORT uapmd_transport_controller_t uapmd_app_transport(uapmd_app_model_t app);
UAPMD_C_EXPORT uapmd_document_provider_t uapmd_app_document_provider(uapmd_app_model_t app);

/*
 * Saves the current project into a document handle obtained from
 * `uapmd_document_provider_pick_save`.
 *
 * This is the flow uapmd-app uses (`MainWindow::handleSaveProject`), and the only
 * one that works where there is no writable path: it stages the project to a temp
 * directory, packs the whole tree into a `.uapmdz` archive, and writes the archive
 * through the provider. Saving to a plain path cannot stand in for it, because a
 * project is a directory — a `.uapmd` document plus its graphs and extensions — not
 * a single file.
 */
UAPMD_C_EXPORT void uapmd_app_save_project_to_document(uapmd_app_model_t app,
                                                          const uapmd_document_handle_t* handle,
                                                          void* user_data,
                                                          uapmd_write_callback_t callback);
UAPMD_C_EXPORT int32_t uapmd_app_sample_rate(uapmd_app_model_t app);
UAPMD_C_EXPORT uint32_t uapmd_app_track_count(uapmd_app_model_t app);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Audio engine control
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT bool uapmd_app_is_scanning(uapmd_app_model_t app);
UAPMD_C_EXPORT bool uapmd_app_is_audio_engine_enabled(uapmd_app_model_t app);
UAPMD_C_EXPORT void uapmd_app_set_audio_engine_enabled(uapmd_app_model_t app, bool enabled);
UAPMD_C_EXPORT void uapmd_app_toggle_audio_engine(uapmd_app_model_t app);
UAPMD_C_EXPORT void uapmd_app_update_audio_device_settings(uapmd_app_model_t app, int32_t sample_rate, uint32_t buffer_size);
UAPMD_C_EXPORT void uapmd_app_set_auto_buffer_size_enabled(uapmd_app_model_t app, bool enabled);
UAPMD_C_EXPORT bool uapmd_app_auto_buffer_size_enabled(uapmd_app_model_t app);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Plugin scanning
 * ═══════════════════════════════════════════════════════════════════════════ */

typedef enum uapmd_plugin_scan_request {
    UAPMD_PLUGIN_SCAN_IN_PROCESS    = 0,
    UAPMD_PLUGIN_SCAN_REMOTE_PROCESS = 1
} uapmd_plugin_scan_request_t;

UAPMD_C_EXPORT void uapmd_app_perform_plugin_scanning(uapmd_app_model_t app,
                                                        bool force_rescan,
                                                        uapmd_plugin_scan_request_t request,
                                                        double remote_timeout_seconds,
                                                        bool require_fast_scanning);
UAPMD_C_EXPORT void uapmd_app_cancel_plugin_scanning(uapmd_app_model_t app);
/* AppModel::stopPluginScanning(): cancels a scan in progress and waits for its
 * worker to finish, running queued main-thread tasks meanwhile. Call on the main
 * thread before tearing down anything the scan's completion callbacks reach;
 * uapmd_app_cleanup() does it as a last resort. */
UAPMD_C_EXPORT void uapmd_app_stop_plugin_scanning(uapmd_app_model_t app);
UAPMD_C_EXPORT size_t uapmd_app_generate_scan_report(uapmd_app_model_t app, char* buf, size_t buf_size);

/*
 * Progress of the slow scan, mirroring `AppModel::SlowScanProgressState`.
 *
 * A slow scan walks every bundle on the machine and can take minutes; without this
 * a caller can only see that `is_scanning` is true, which is indistinguishable from
 * a scan that has hung. uapmd-app's selector shows the counts and the bundle it is
 * on for exactly that reason (`PluginSelector.cpp:163-177`).
 *
 * `current_bundle` points at storage owned by the app model's snapshot and is valid
 * until the next call on the same thread.
 */
typedef struct uapmd_slow_scan_progress {
    bool running;
    uint32_t processed_bundles;
    uint32_t total_bundles;
    const char* current_bundle;
} uapmd_slow_scan_progress_t;

UAPMD_C_EXPORT uapmd_slow_scan_progress_t uapmd_app_slow_scan_progress(uapmd_app_model_t app);

/*
 * Points remote scanning at a standalone scanner executable.
 *
 * Remote scanning relaunches the host's own executable with `--scan-only
 * --ipc-client ...`. That works for a native app whose main() dispatches those
 * arguments, but not for an embedder whose executable is a runtime launcher — a JVM
 * app relaunches `java`, which connects to nothing, and the scan fails with
 * "Remote scanner failed to connect". Setting this to a binary that understands the
 * same arguments (uapmd's own `uapmd-scan`) makes out-of-process scanning work
 * there, which matters because an in-process scan runs every plug-in's entry code
 * inside the app: one bad plug-in and the app dies partway through.
 *
 * Pass NULL or an empty string to restore the default.
 */
UAPMD_C_EXPORT void uapmd_set_remote_scanner_executable(const char* path);

/** The last scanning error, or an empty string. uapmd-app shows this in red. */
UAPMD_C_EXPORT size_t uapmd_app_last_plugin_scan_error(uapmd_app_model_t app, char* buf, size_t buf_size);
UAPMD_C_EXPORT void uapmd_app_clear_plugin_blocklist(uapmd_app_model_t app);

/*
 * AppModel's plug-in blocklist, the one the Plugin Selector shows. A standalone
 * PluginScanTool keeps its own, so reading it there would not necessarily be the
 * same list. `timestamp` from uapmd's BlocklistEntry is not carried across:
 * `uapmd_blocklist_entry_t` (uapmd-c-tooling.h) has no field for it.
 */
/*
 * Master-track tempo map (`AppModel::buildMasterTrackSnapshot`).
 *
 * This is what a beats/ticks view needs: a project whose tempo changes mid-way
 * cannot be rendered from `uapmd_app_get_timeline_state`'s single tempo value.
 * Callers rebuild the snapshot once (`uapmd_app_refresh_master_tempo_map`) and
 * then read the points; the returned lists stay valid until the next refresh on
 * the same model.
 */
typedef struct uapmd_tempo_point {
    double   time_seconds;
    uint64_t tick_position;
    double   bpm;
} uapmd_tempo_point_t;

typedef struct uapmd_time_signature_point {
    double   time_seconds;
    uint64_t tick_position;
    uint8_t  numerator;
    uint8_t  denominator;
} uapmd_time_signature_point_t;

/** Rebuilds the snapshot; returns the master track's content length in seconds. */
UAPMD_C_EXPORT double   uapmd_app_refresh_master_tempo_map(uapmd_app_model_t app);
UAPMD_C_EXPORT uint32_t uapmd_app_master_tempo_point_count(uapmd_app_model_t app);
UAPMD_C_EXPORT bool     uapmd_app_get_master_tempo_point(uapmd_app_model_t app, uint32_t index,
                                                             uapmd_tempo_point_t* out);
UAPMD_C_EXPORT uint32_t uapmd_app_master_time_signature_count(uapmd_app_model_t app);
UAPMD_C_EXPORT bool     uapmd_app_get_master_time_signature(uapmd_app_model_t app, uint32_t index,
                                                                uapmd_time_signature_point_t* out);

UAPMD_C_EXPORT uint32_t uapmd_app_blocklist_count(uapmd_app_model_t app);
UAPMD_C_EXPORT bool     uapmd_app_get_blocklist_entry(uapmd_app_model_t app, uint32_t index,
                                                          uapmd_blocklist_entry_t* out);
UAPMD_C_EXPORT bool     uapmd_app_unblock_plugin_from_blocklist(uapmd_app_model_t app, const char* entry_id);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Plugin instance management
 * ═══════════════════════════════════════════════════════════════════════════ */

typedef struct uapmd_plugin_instance_config {
    const char* api_name;        /* default: "default" */
    const char* device_name;     /* empty = auto-generate */
    const char* manufacturer;    /* default: "UAPMD Project" */
    const char* version;         /* default: "0.1" */
    const char* state_file;      /* path or empty */
} uapmd_plugin_instance_config_t;

typedef struct uapmd_plugin_instance_result {
    int32_t instance_id;
    const char* plugin_name;
    const char* error;
} uapmd_plugin_instance_result_t;

typedef void (*uapmd_instance_created_cb_t)(uapmd_plugin_instance_result_t result, void* user_data);

UAPMD_C_EXPORT void uapmd_app_create_plugin_instance(uapmd_app_model_t app,
                                                       const char* format,
                                                       const char* plugin_id,
                                                       int32_t track_index,
                                                       const uapmd_plugin_instance_config_t* config,
                                                       void* user_data,
                                                       uapmd_instance_created_cb_t callback);

UAPMD_C_EXPORT void uapmd_app_remove_plugin_instance(uapmd_app_model_t app, int32_t instance_id);

/* UMP group */
UAPMD_C_EXPORT uint8_t uapmd_app_get_instance_group(uapmd_app_model_t app, int32_t instance_id);
UAPMD_C_EXPORT bool    uapmd_app_set_instance_group(uapmd_app_model_t app, int32_t instance_id, uint8_t group);

/* UMP device enable/disable */
UAPMD_C_EXPORT void uapmd_app_enable_ump_device(uapmd_app_model_t app, int32_t instance_id, const char* device_name);
UAPMD_C_EXPORT void uapmd_app_disable_ump_device(uapmd_app_model_t app, int32_t instance_id);

/* Virtual MIDI 2.0 devices are the built-in "Virtual MIDI Devices" addin
 * (uapmd_app::registerVirtualMidiDevicesAddin()). A host that wants them calls
 * uapmd_app_register_virtual_midi_devices_addin() and publishes the model with
 * uapmd_addin_manager_register_app_model() (uapmd-c-addin.h) before
 * uapmd_addin_manager_initialize(). While the addin is not active,
 * uapmd_app_enable_ump_device() fails with "Virtual MIDI Devices addin is
 * disabled". */
UAPMD_C_EXPORT void uapmd_app_register_virtual_midi_devices_addin(void);
/* Publishes the model at /uapmd/app/model/v1. */
UAPMD_C_EXPORT void uapmd_addin_manager_register_app_model(uapmd_addin_manager_t mgr, uapmd_app_model_t app);
UAPMD_C_EXPORT bool uapmd_app_virtual_midi_devices_enabled(uapmd_app_model_t app);
/* Off by default. Applies to subsequently registered instances; existing
 * devices are unchanged. */
UAPMD_C_EXPORT bool uapmd_app_auto_create_virtual_midi_devices(uapmd_app_model_t app);
UAPMD_C_EXPORT void uapmd_app_set_auto_create_virtual_midi_devices(uapmd_app_model_t app, bool enabled);
/* AppModel::showVirtualMidiDevices: invoked (on the model thread) when the
 * addin's command asks the host to toggle its window. NULL clears it; clear it
 * before the callback's user_data goes away. */
typedef void (*uapmd_app_show_virtual_midi_devices_cb_t)(void* user_data);
UAPMD_C_EXPORT void uapmd_app_set_show_virtual_midi_devices_callback(uapmd_app_model_t app,
                                                                      void* user_data,
                                                                      uapmd_app_show_virtual_midi_devices_cb_t callback);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Instance details
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT void uapmd_app_request_show_instance_details(uapmd_app_model_t app, int32_t instance_id);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Plugin UI
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT void uapmd_app_request_show_plugin_ui(uapmd_app_model_t app, int32_t instance_id);
UAPMD_C_EXPORT void uapmd_app_show_plugin_ui(uapmd_app_model_t app,
                                                int32_t instance_id,
                                                bool needs_create,
                                                bool is_floating,
                                                void* parent_handle,
                                                void* resize_user_data,
                                                uapmd_ui_resize_handler_t resize_handler);
UAPMD_C_EXPORT void uapmd_app_hide_plugin_ui(uapmd_app_model_t app, int32_t instance_id);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Plugin state save/load
 * ═══════════════════════════════════════════════════════════════════════════ */

typedef struct uapmd_plugin_state_result {
    int32_t instance_id;
    bool success;
    const char* error;
    const char* filepath;
} uapmd_plugin_state_result_t;

typedef void (*uapmd_plugin_state_cb_t)(uapmd_plugin_state_result_t result, void* user_data);

UAPMD_C_EXPORT void uapmd_app_load_plugin_state(uapmd_app_model_t app,
                                                   int32_t instance_id,
                                                   const char* filepath,
                                                   void* user_data,
                                                   uapmd_plugin_state_cb_t callback);
UAPMD_C_EXPORT void uapmd_app_save_plugin_state(uapmd_app_model_t app,
                                                   int32_t instance_id,
                                                   const char* filepath,
                                                   void* user_data,
                                                   uapmd_plugin_state_cb_t callback);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Clip management
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT uapmd_clip_add_result_t uapmd_app_add_clip_to_track(uapmd_app_model_t app,
                                                                      int32_t track_index,
                                                                      uapmd_timeline_position_t position,
                                                                      uapmd_audio_file_reader_t reader,
                                                                      const char* filepath);

/**
 * Imports a possibly multi-track SMF, creating one track per SMF track
 * (`AppModel::importMidiTracksFromFile`). The C shape is flattened to
 * success/error plus the number of tracks created; the caller re-reads the
 * timeline for the detail, which is what a UI does anyway. Per-track warnings
 * from the C++ result are not carried across.
 */
typedef void (*uapmd_midi_tracks_import_cb_t)(bool success, const char* error,
                                              uint32_t imported_track_count, void* user_data);

UAPMD_C_EXPORT void uapmd_app_import_midi_tracks_from_file(uapmd_app_model_t app,
                                                           const char* filepath,
                                                           void* user_data,
                                                           uapmd_midi_tracks_import_cb_t callback);

UAPMD_C_EXPORT uapmd_clip_add_result_t uapmd_app_add_midi_clip_to_track(uapmd_app_model_t app,
                                                                           int32_t track_index,
                                                                           uapmd_timeline_position_t position,
                                                                           const char* filepath);

UAPMD_C_EXPORT uapmd_clip_add_result_t uapmd_app_add_midi_clip_from_data(uapmd_app_model_t app,
                                                                            int32_t track_index,
                                                                            uapmd_timeline_position_t position,
                                                                            const uapmd_ump_t* ump_events,
                                                                            uint32_t ump_event_count,
                                                                            const uint64_t* tick_timestamps,
                                                                            uint32_t tick_count,
                                                                            uint32_t tick_resolution,
                                                                            double clip_tempo,
                                                                            const uapmd_midi_tempo_change_t* tempo_changes,
                                                                            uint32_t tempo_change_count,
                                                                            const uapmd_midi_time_sig_change_t* time_sig_changes,
                                                                            uint32_t time_sig_change_count,
                                                                            const char* clip_name,
                                                                            bool needs_file_save);

UAPMD_C_EXPORT uapmd_clip_add_result_t uapmd_app_create_empty_midi_clip(uapmd_app_model_t app,
                                                                           int32_t track_index,
                                                                           int64_t position_samples,
                                                                           uint32_t tick_resolution,
                                                                           double bpm);

UAPMD_C_EXPORT bool uapmd_app_remove_clip_from_track(uapmd_app_model_t app, int32_t track_index, int32_t clip_id);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Track management
 * ═══════════════════════════════════════════════════════════════════════════ */

/* The mutation callbacks are declared in uapmd-c-undo.h. */

UAPMD_C_EXPORT bool uapmd_app_is_track_muted(uapmd_app_model_t app, int32_t track_index);
UAPMD_C_EXPORT bool uapmd_app_is_track_solo(uapmd_app_model_t app, int32_t track_index);
UAPMD_C_EXPORT bool uapmd_app_set_track_muted(uapmd_app_model_t app, int32_t track_index, bool muted);
UAPMD_C_EXPORT bool uapmd_app_set_track_solo(uapmd_app_model_t app, int32_t track_index, bool solo);

UAPMD_C_EXPORT void uapmd_app_add_track(uapmd_app_model_t app,
                                           void* user_data,
                                           uapmd_track_mutation_cb_t callback);
UAPMD_C_EXPORT void uapmd_app_remove_track(uapmd_app_model_t app,
                                              int32_t track_index,
                                              void* user_data,
                                              uapmd_track_mutation_cb_t callback);
UAPMD_C_EXPORT void uapmd_app_remove_all_tracks(uapmd_app_model_t app,
                                                   void* user_data,
                                                   uapmd_track_clear_cb_t callback);

UAPMD_C_EXPORT int32_t uapmd_app_add_device_input_to_track(uapmd_app_model_t app,
                                                              int32_t track_index,
                                                              const uint32_t* channel_indices,
                                                              uint32_t channel_count);

/* Timeline tracks */
UAPMD_C_EXPORT uint32_t uapmd_app_timeline_track_count(uapmd_app_model_t app);
UAPMD_C_EXPORT uapmd_timeline_track_t uapmd_app_get_timeline_track(uapmd_app_model_t app, uint32_t index);
UAPMD_C_EXPORT uapmd_timeline_track_t uapmd_app_get_master_timeline_track(uapmd_app_model_t app);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Assorted AppModel accessors
 * ═══════════════════════════════════════════════════════════════════════════ */

/* MIDI ports the engine can see. Strings point into per-thread storage that the
 * next call of the same getter on this thread overwrites. */
typedef struct uapmd_midi_port_info {
    const char* id;
    const char* display_name;
} uapmd_midi_port_info_t;

/* Pass a null `out` to ask for just the count. */
UAPMD_C_EXPORT uint32_t uapmd_app_get_midi_input_ports(uapmd_app_model_t app, uapmd_midi_port_info_t* out, uint32_t out_count);
UAPMD_C_EXPORT uint32_t uapmd_app_get_midi_output_ports(uapmd_app_model_t app, uapmd_midi_port_info_t* out, uint32_t out_count);

/* A hidden track is skipped by paste and by the track list, but still plays. */
UAPMD_C_EXPORT bool uapmd_app_is_track_hidden(uapmd_app_model_t app, int32_t track_index);

/* Moves the playhead without starting or stopping the transport. Lives on the
 * transport controller, as it does upstream. */
UAPMD_C_EXPORT void uapmd_transport_jump(uapmd_transport_controller_t tc, double position_seconds);

/* The span the timeline's content actually occupies. */
typedef struct uapmd_timeline_content_bounds {
    bool   has_content;
    double start_seconds;
    double end_seconds;
    double duration_seconds;
} uapmd_timeline_content_bounds_t;

UAPMD_C_EXPORT uapmd_timeline_content_bounds_t uapmd_app_timeline_content_bounds(uapmd_app_model_t app);

/* UMP devices the model has instantiated. `label` points into per-thread storage
 * that the next call on this thread overwrites. */
typedef struct uapmd_device_entry {
    int32_t     id;
    const char* label;
    const char* api_name;
    const char* status_message;
    bool        running;
    bool        instantiating;
    bool        has_error;
} uapmd_device_entry_t;

UAPMD_C_EXPORT uint32_t uapmd_app_get_devices(uapmd_app_model_t app, uapmd_device_entry_t* out, uint32_t out_count);
/* The device hosting a plug-in instance; false when it has none. */
UAPMD_C_EXPORT bool uapmd_app_get_device_for_instance(uapmd_app_model_t app, int32_t instance_id, uapmd_device_entry_t* out);
UAPMD_C_EXPORT void uapmd_app_update_device_label(uapmd_app_model_t app, int32_t instance_id, const char* label);

/* Synchronous plug-in state I/O, reusing uapmd_plugin_state_result_t above. The
 * asynchronous uapmd_app_load_plugin_state()/uapmd_app_save_plugin_state() are
 * preferred; a plug-in's state can be slow to produce, and on Android reading it
 * from the main thread can deadlock. These exist for tools and tests that have
 * no loop to post a completion to. */
UAPMD_C_EXPORT uapmd_plugin_state_result_t uapmd_app_load_plugin_state_sync(uapmd_app_model_t app, int32_t instance_id, const char* filepath);
UAPMD_C_EXPORT uapmd_plugin_state_result_t uapmd_app_save_plugin_state_sync(uapmd_app_model_t app, int32_t instance_id, const char* filepath);

/* Marks the track owning this instance dirty, so the next save rewrites it. */
UAPMD_C_EXPORT void uapmd_app_mark_plugin_instance_track_dirty(uapmd_app_model_t app, int32_t instance_id);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Piano roll editing session
 *
 *  Model thread only.
 * ═══════════════════════════════════════════════════════════════════════════ */

typedef struct uapmd_piano_roll_session*  uapmd_piano_roll_session_t;
/* Owned by the caller; release with uapmd_piano_roll_snapshot_destroy(). */
typedef struct uapmd_piano_roll_snapshot* uapmd_piano_roll_snapshot_t;

/* The clipboard actions a session performs on its selection. */
typedef enum uapmd_piano_roll_action {
    UAPMD_PIANO_ROLL_ACTION_NONE       = 0,
    UAPMD_PIANO_ROLL_ACTION_COPY       = 1,
    UAPMD_PIANO_ROLL_ACTION_CUT        = 2,
    UAPMD_PIANO_ROLL_ACTION_PASTE      = 3,
    UAPMD_PIANO_ROLL_ACTION_DELETE     = 4,
    UAPMD_PIANO_ROLL_ACTION_SELECT_ALL = 5
} uapmd_piano_roll_action_t;

/* One note as the session holds it. `deleted` notes stay in the list so that
 * indexes remain stable across an edit; skip them when drawing. */
typedef struct uapmd_piano_roll_note {
    double   start_seconds;
    double   duration_seconds;
    float    velocity;          /* 0.0 - 1.0 */
    uint8_t  note;              /* 0 - 127 */
    uint8_t  channel;
    bool     deleted;
    /* MIDI2 note detail, zero on a note that carries none. */
    uint64_t edit_id;
    uint8_t  ump_group;
    uint16_t release_velocity;
    uint8_t  attribute_type;
    uint16_t attribute_value;
    uint32_t automation_event_count;
} uapmd_piano_roll_note_t;

/* ── Snapshot: a clip parsed into notes ──────────────────────────────────── */

UAPMD_C_EXPORT uapmd_piano_roll_snapshot_t uapmd_app_piano_roll_clip_snapshot(uapmd_app_model_t app,
                                                                                 int32_t track_index,
                                                                                 int32_t clip_id,
                                                                                 double fallback_duration_seconds);
UAPMD_C_EXPORT void     uapmd_piano_roll_snapshot_destroy(uapmd_piano_roll_snapshot_t snapshot);
UAPMD_C_EXPORT bool     uapmd_piano_roll_snapshot_ready(uapmd_piano_roll_snapshot_t snapshot);
/* Never NULL; empty when the snapshot is ready. */
UAPMD_C_EXPORT const char* uapmd_piano_roll_snapshot_error(uapmd_piano_roll_snapshot_t snapshot);
UAPMD_C_EXPORT double   uapmd_piano_roll_snapshot_duration_seconds(uapmd_piano_roll_snapshot_t snapshot);
UAPMD_C_EXPORT uint8_t  uapmd_piano_roll_snapshot_min_note(uapmd_piano_roll_snapshot_t snapshot);
UAPMD_C_EXPORT uint8_t  uapmd_piano_roll_snapshot_max_note(uapmd_piano_roll_snapshot_t snapshot);
UAPMD_C_EXPORT uint32_t uapmd_piano_roll_snapshot_note_count(uapmd_piano_roll_snapshot_t snapshot);
UAPMD_C_EXPORT bool     uapmd_piano_roll_snapshot_get_note(uapmd_piano_roll_snapshot_t snapshot, uint32_t index, uapmd_piano_roll_note_t* out);

/* ── Session lifecycle ───────────────────────────────────────────────────── */

/* Owned by the model and shared between callers; do not destroy. */
UAPMD_C_EXPORT uapmd_piano_roll_session_t uapmd_app_open_piano_roll_session(uapmd_app_model_t app, int32_t track_index, int32_t clip_id);
/* NULL when no session is open for that clip. */
UAPMD_C_EXPORT uapmd_piano_roll_session_t uapmd_app_find_piano_roll_session(uapmd_app_model_t app, int32_t track_index, int32_t clip_id);
UAPMD_C_EXPORT void uapmd_app_close_piano_roll_session(uapmd_app_model_t app, int32_t track_index, int32_t clip_id);

/* Whether the session was built from this snapshot's UMP stream. False means
 * the clip changed underneath and the session must be reloaded -- which is what
 * keeps an edit from being applied to a clip it was not made against. */
UAPMD_C_EXPORT bool uapmd_piano_roll_session_matches_source(uapmd_piano_roll_session_t session,
                                                               uapmd_piano_roll_snapshot_t snapshot);
UAPMD_C_EXPORT void uapmd_piano_roll_session_load_notes(uapmd_piano_roll_session_t session,
                                                           uapmd_piano_roll_snapshot_t snapshot);

/* ── Reading ─────────────────────────────────────────────────────────────── */

UAPMD_C_EXPORT uint32_t uapmd_piano_roll_session_note_count(uapmd_piano_roll_session_t session);
UAPMD_C_EXPORT bool     uapmd_piano_roll_session_get_note(uapmd_piano_roll_session_t session, uint32_t index, uapmd_piano_roll_note_t* out);
UAPMD_C_EXPORT bool     uapmd_piano_roll_session_is_note_selected(uapmd_piano_roll_session_t session, uint32_t index);
UAPMD_C_EXPORT uint32_t uapmd_piano_roll_session_selected_note_count(uapmd_piano_roll_session_t session);
/* The note the detail editor is on, or -1. */
UAPMD_C_EXPORT int32_t  uapmd_piano_roll_session_focused_note(uapmd_piano_roll_session_t session);
UAPMD_C_EXPORT void     uapmd_piano_roll_session_set_focused_note(uapmd_piano_roll_session_t session, int32_t index);
UAPMD_C_EXPORT double   uapmd_piano_roll_session_duration_seconds(uapmd_piano_roll_session_t session);
UAPMD_C_EXPORT uint8_t  uapmd_piano_roll_session_min_note(uapmd_piano_roll_session_t session);
UAPMD_C_EXPORT uint8_t  uapmd_piano_roll_session_max_note(uapmd_piano_roll_session_t session);
UAPMD_C_EXPORT uint32_t uapmd_piano_roll_session_clipboard_count(uapmd_piano_roll_session_t session);
UAPMD_C_EXPORT bool     uapmd_piano_roll_session_dirty(uapmd_piano_roll_session_t session);
/* Never NULL; empty when the last edit succeeded. */
UAPMD_C_EXPORT const char* uapmd_piano_roll_session_error(uapmd_piano_roll_session_t session);

/* ── Editing ─────────────────────────────────────────────────────────────── */

/* `index` -1 clears the selection. */
UAPMD_C_EXPORT void uapmd_piano_roll_session_select_note(uapmd_piano_roll_session_t session, int32_t index, bool additive, bool toggle);
UAPMD_C_EXPORT void uapmd_piano_roll_session_perform_action(uapmd_piano_roll_session_t session, uapmd_piano_roll_action_t action, double paste_seconds);
UAPMD_C_EXPORT void uapmd_piano_roll_session_create_note(uapmd_piano_roll_session_t session, double start_seconds, double duration_seconds, uint8_t note, float velocity);
UAPMD_C_EXPORT void uapmd_piano_roll_session_delete_note(uapmd_piano_roll_session_t session, uint32_t index);
UAPMD_C_EXPORT void uapmd_piano_roll_session_resize_note(uapmd_piano_roll_session_t session, uint32_t index, double start_seconds, double duration_seconds, uint8_t note);

/* A move drag works from the notes as they were when it began, so that each
 * update re-applies one delta rather than accumulating rounding. The session
 * holds that snapshot, so a host only says when the drag starts, how far it has
 * moved, and whether it ended or was cancelled. */
UAPMD_C_EXPORT void uapmd_piano_roll_session_begin_drag(uapmd_piano_roll_session_t session);
UAPMD_C_EXPORT void uapmd_piano_roll_session_move_selection(uapmd_piano_roll_session_t session, double time_delta_seconds, int32_t pitch_delta);
/* Puts the dragged notes back as they were. */
UAPMD_C_EXPORT void uapmd_piano_roll_session_cancel_drag(uapmd_piano_roll_session_t session);
/* Marks the edit for write-back when the note actually moved. */
UAPMD_C_EXPORT void uapmd_piano_roll_session_finish_drag(uapmd_piano_roll_session_t session, uint32_t index, double original_start, double original_end, uint8_t original_note);

/* Writes the session back to the clip, through the undo history. */
UAPMD_C_EXPORT bool uapmd_piano_roll_session_commit(uapmd_piano_roll_session_t session, uapmd_app_model_t app);

/* ── Commit-source tracking ──────────────────────────────────────────────────
 *
 * A commit changes the clip, which makes the timeline announce that the clip
 * changed, which would normally make an open piano roll reload from it. Doing
 * that on the echo of the editor's own write throws away whatever the user has
 * done since. These three break that loop: record the source right after a
 * commit, and when a change arrives ask whether it is that same edit coming
 * back before reloading.
 *
 * The match is by content fingerprint, not by a flag, so an edit that really
 * did come from elsewhere still reloads. */
UAPMD_C_EXPORT void uapmd_app_record_piano_roll_commit_source(uapmd_app_model_t app, int32_t track_index, int32_t clip_id);
UAPMD_C_EXPORT bool uapmd_app_piano_roll_source_matches_last_edit(uapmd_app_model_t app);
UAPMD_C_EXPORT void uapmd_app_clear_piano_roll_commit_source(uapmd_app_model_t app);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Timeline clip selection and clipboard
 *
 *  Model thread only.
 * ═══════════════════════════════════════════════════════════════════════════ */

typedef struct uapmd_timeline_clip_target {
    int32_t track_index;
    int32_t clip_id;
} uapmd_timeline_clip_target_t;

UAPMD_C_EXPORT bool uapmd_app_is_timeline_clip_selected(uapmd_app_model_t app, int32_t track_index, int32_t clip_id);

/* The number of selected clips; pass a null `out` to ask for just the count. */
UAPMD_C_EXPORT uint32_t uapmd_app_selected_timeline_clips(uapmd_app_model_t app,
                                                             uapmd_timeline_clip_target_t* out,
                                                             uint32_t out_count);

/* Replaces the selection, adds to it (`additive`), or flips each clip's state
 * within it (`toggle`) -- the three outcomes a click, a shift-click and a
 * ctrl-click produce. An empty list with both false clears it. */
UAPMD_C_EXPORT void uapmd_app_select_timeline_clips(uapmd_app_model_t app,
                                                       const uapmd_timeline_clip_target_t* clips,
                                                       uint32_t clip_count,
                                                       bool additive,
                                                       bool toggle);
UAPMD_C_EXPORT void uapmd_app_clear_timeline_clip_selection(uapmd_app_model_t app);

/* The MIDI clip an editor is open on, which is tracked separately from the
 * selection. Returns false when there is none. */
UAPMD_C_EXPORT bool uapmd_app_select_timeline_midi_clip(uapmd_app_model_t app, int32_t track_index, int32_t clip_id);
UAPMD_C_EXPORT bool uapmd_app_selected_timeline_midi_clip(uapmd_app_model_t app, uapmd_timeline_clip_target_t* out);

/* ── Clipboard ───────────────────────────────────────────────────────────── */

UAPMD_C_EXPORT uint32_t uapmd_app_timeline_clipboard_count(uapmd_app_model_t app);
UAPMD_C_EXPORT void     uapmd_app_clear_timeline_clipboard(uapmd_app_model_t app);

/* Copies the selection into the clipboard. On failure the reason is left in
 * per-thread storage readable with uapmd_app_last_timeline_clip_error(). */
UAPMD_C_EXPORT bool uapmd_app_copy_selected_timeline_clips(uapmd_app_model_t app);

/* Deletes the selection; `cut` copies it first. `changed_tracks` receives the
 * indexes of the tracks that lost a clip -- pass a null buffer to ask for just
 * the count, which is returned through `changed_track_count`. */
UAPMD_C_EXPORT bool uapmd_app_delete_selected_timeline_clips(uapmd_app_model_t app,
                                                                bool cut,
                                                                int32_t* changed_tracks,
                                                                uint32_t* changed_track_count);

/* Which tracks a paste would land on, without performing it. `original_tracks`
 * pastes each clip back onto the track it was copied from instead of onto
 * `track_index`. Returns the count; pass a null buffer to ask for just that. */
UAPMD_C_EXPORT uint32_t uapmd_app_timeline_paste_destinations(uapmd_app_model_t app,
                                                                 int32_t track_index,
                                                                 bool original_tracks,
                                                                 int32_t* out,
                                                                 uint32_t out_count);

/* Pastes at `position_seconds`. `pasted` receives the clips created -- pass a
 * null buffer to ask for just the count through `pasted_count`. */
UAPMD_C_EXPORT bool uapmd_app_paste_timeline_clips(uapmd_app_model_t app,
                                                      int32_t track_index,
                                                      double position_seconds,
                                                      bool original_tracks,
                                                      uapmd_timeline_clip_target_t* pasted,
                                                      uint32_t* pasted_count);

/* Never NULL; empty when the last clipboard call succeeded. */
UAPMD_C_EXPORT const char* uapmd_app_last_timeline_clip_error(void);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Timeline state access
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT bool uapmd_app_get_timeline_state(uapmd_app_model_t app, uapmd_timeline_state_t* out);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Track graph editing (DAG graph)
 * ═══════════════════════════════════════════════════════════════════════════ */

/* uapmd_graph_endpoint_type_t, uapmd_graph_bus_type_t, uapmd_graph_endpoint_t
 * and uapmd_graph_connection_t are declared in uapmd-c-undo.h: connection
 * editing lives on ProjectCommands, so the types have to be visible there, and
 * this header includes it. */

typedef struct uapmd_graph_connections_result {
    bool success;
    const char* error;
    uint32_t count;
    const uapmd_graph_connection_t* connections;
} uapmd_graph_connections_result_t;

typedef struct uapmd_op_result {
    bool success;
    const char* error;
} uapmd_op_result_t;

UAPMD_C_EXPORT bool uapmd_app_ensure_track_uses_editor_graph(uapmd_app_model_t app, int32_t track_index);
UAPMD_C_EXPORT void uapmd_app_request_show_track_graph(uapmd_app_model_t app, int32_t track_index);
UAPMD_C_EXPORT bool uapmd_app_revert_track_to_simple_graph(uapmd_app_model_t app, int32_t track_index);

/* remidy::AudioBusRole. */
typedef enum uapmd_audio_bus_role {
    UAPMD_AUDIO_BUS_ROLE_MAIN = 0,
    UAPMD_AUDIO_BUS_ROLE_AUX  = 1
} uapmd_audio_bus_role_t;

/*
 * One audio bus of a graph node, mirroring `remidy::AudioBusConfiguration` and the
 * `AudioBusDefinition` / `AudioChannelLayout` it is built from.
 *
 * `enabled` is reported as the plugin reports it. Callers that draw one pin per bus
 * must decide for themselves whether to skip disabled buses — uapmd-app does skip
 * them, and numbers the buses it draws sequentially over the ones it kept
 * (PluginGraphEditor.cpp:371-400) — but that is a presentation rule, not something
 * this API imposes.
 */
typedef struct uapmd_graph_audio_bus {
    const char* name;                 /* AudioBusDefinition::name() */
    uapmd_audio_bus_role_t role;      /* AudioBusConfiguration::role() */
    bool enabled;                     /* AudioBusConfiguration::enabled() */
    const char* channel_layout_name;  /* AudioChannelLayout::name() */
    uint32_t channel_count;           /* AudioChannelLayout::channels() */
} uapmd_graph_audio_bus_t;

/*
 * One node of a track's graph: `uapmd_graph::AudioGraphNode` as `AudioGraph::nodes()`
 * reports it, plus the `remidy::PluginAudioBuses` facade the node exposes.
 *
 * A node that hosts no plugin instance (a built-in node such as the track's gain)
 * reports `instance_id` -1 and `has_audio_buses` false; it has no bus list of its
 * own, and a caller that needs bus counts for it should fall back to the graph's
 * own layout, reported on uapmd_graph_nodes_result_t.
 *
 * The node's buses live in the result's `audio_buses` array, inputs first and then
 * outputs, starting at `audio_bus_offset`.
 */
typedef struct uapmd_graph_node {
    const char* node_id;              /* AudioGraphNode::nodeId() */
    const char* node_type;            /* AudioGraphNode::nodeType() */
    const char* display_name;         /* AudioGraphNode::displayName() */
    int32_t instance_id;              /* AudioPluginNode::instanceId(), -1 when not a plugin node */
    bool bypassed;                    /* AudioGraphNode::bypassed() */
    uint32_t latency_in_samples;      /* AudioGraphNode::latencyInSamples() */
    double tail_length_in_seconds;    /* AudioGraphNode::tailLengthInSeconds() */
    bool has_audio_buses;             /* AudioGraphNode::audioBuses() != nullptr */
    bool has_event_inputs;            /* PluginAudioBuses::hasEventInputs() */
    bool has_event_outputs;           /* PluginAudioBuses::hasEventOutputs() */
    uint32_t audio_bus_offset;        /* index into uapmd_graph_nodes_result_t::audio_buses */
    uint32_t audio_input_bus_count;   /* PluginAudioBuses::audioInputBuses().size() */
    uint32_t audio_output_bus_count;  /* PluginAudioBuses::audioOutputBuses().size() */
    int32_t main_input_bus_index;     /* PluginAudioBuses::mainInputBusIndex(), <0 when none */
    int32_t main_output_bus_index;    /* PluginAudioBuses::mainOutputBusIndex(), <0 when none */
} uapmd_graph_node_t;

typedef struct uapmd_graph_nodes_result {
    bool success;
    const char* error;
    uint32_t count;
    const uapmd_graph_node_t* nodes;
    uint32_t audio_bus_count;
    const uapmd_graph_audio_bus_t* audio_buses;
    /* uapmd_graph::AudioGraphBusesLayout, from the graph's AudioBusesLayoutExtension
       (all four default to 1 when the graph carries no such extension). */
    uint32_t graph_audio_input_bus_count;
    uint32_t graph_audio_output_bus_count;
    uint32_t graph_event_input_bus_count;
    uint32_t graph_event_output_bus_count;
} uapmd_graph_nodes_result_t;

UAPMD_C_EXPORT uapmd_graph_connections_result_t uapmd_app_get_track_graph_connections(uapmd_app_model_t app, int32_t track_index);
UAPMD_C_EXPORT uapmd_graph_nodes_result_t uapmd_app_get_track_graph_nodes(uapmd_app_model_t app, int32_t track_index);
UAPMD_C_EXPORT uapmd_op_result_t uapmd_app_connect_track_graph(uapmd_app_model_t app,
                                                                  int32_t track_index,
                                                                  const uapmd_graph_connection_t* connection);
UAPMD_C_EXPORT uapmd_op_result_t uapmd_app_disconnect_track_graph_connection(uapmd_app_model_t app,
                                                                               int32_t track_index,
                                                                               int64_t connection_id);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Clip audio events (markers + warps)
 * ═══════════════════════════════════════════════════════════════════════════ */

typedef struct uapmd_clip_audio_events_result {
    bool success;
    const char* error;
    uint32_t marker_count;
    const uapmd_clip_marker_t* markers;
    uint32_t audio_warp_count;
    const uapmd_audio_warp_point_t* audio_warps;
} uapmd_clip_audio_events_result_t;

UAPMD_C_EXPORT uapmd_clip_audio_events_result_t uapmd_app_get_clip_audio_events(uapmd_app_model_t app,
                                                                                   int32_t track_index,
                                                                                   int32_t clip_id);
UAPMD_C_EXPORT uapmd_op_result_t uapmd_app_set_clip_audio_events(uapmd_app_model_t app,
                                                                     int32_t track_index,
                                                                     int32_t clip_id,
                                                                     const uapmd_clip_marker_t* markers,
                                                                     uint32_t marker_count,
                                                                     const uapmd_audio_warp_point_t* warps,
                                                                     uint32_t warp_count);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Master track markers
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT uint32_t uapmd_app_master_marker_count(uapmd_app_model_t app);
UAPMD_C_EXPORT bool     uapmd_app_get_master_marker(uapmd_app_model_t app, uint32_t index, uapmd_clip_marker_t* out);
UAPMD_C_EXPORT uapmd_op_result_t uapmd_app_set_master_track_markers_with_validation(uapmd_app_model_t app,
                                                                 const uapmd_clip_marker_t* markers,
                                                                 uint32_t count);

/* ═══════════════════════════════════════════════════════════════════════════
 *  MIDI clip UMP event editing
 * ═══════════════════════════════════════════════════════════════════════════ */

typedef struct uapmd_ump_event {
    uint64_t tick;
    uint32_t word_count;
    const uint32_t* words;
} uapmd_ump_event_t;

typedef struct uapmd_ump_events_result {
    bool success;
    const char* error;
    uint32_t event_count;
    const uapmd_ump_event_t* events;
    /* The clip's own tick grid and tempo, which AppModel reports alongside the
     * events. Appended after `events` so the offsets above stay put. Both are 0
     * when the call failed. */
    uint32_t tick_resolution;
    double   clip_tempo;
} uapmd_ump_events_result_t;

UAPMD_C_EXPORT uapmd_ump_events_result_t uapmd_app_get_midi_clip_ump_events(uapmd_app_model_t app,
                                                                               int32_t track_index,
                                                                               int32_t clip_id);
UAPMD_C_EXPORT bool uapmd_app_add_ump_event_to_clip(uapmd_app_model_t app,
                                                       int32_t track_index,
                                                       int32_t clip_id,
                                                       uint64_t tick,
                                                       const uint32_t* words,
                                                       uint32_t word_count);
UAPMD_C_EXPORT bool uapmd_app_remove_ump_event_from_clip(uapmd_app_model_t app,
                                                            int32_t track_index,
                                                            int32_t clip_id,
                                                            int32_t event_index);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Undo history
 *
 *  The application-level entry points. They wrap the same history that
 *  uapmd_tl_undo_engine() exposes, and additionally reconcile the plug-in
 *  instances the model tracks after the document moves.
 * ═══════════════════════════════════════════════════════════════════════════ */

typedef void (*uapmd_history_mutation_cb_t)(const char* error, void* user_data);

UAPMD_C_EXPORT bool uapmd_app_history_state(uapmd_app_model_t app, uapmd_undo_state_t* out);
UAPMD_C_EXPORT void uapmd_app_undo(uapmd_app_model_t app, void* user_data, uapmd_history_mutation_cb_t callback);
UAPMD_C_EXPORT void uapmd_app_redo(uapmd_app_model_t app, void* user_data, uapmd_history_mutation_cb_t callback);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Project save/load
 * ═══════════════════════════════════════════════════════════════════════════ */

typedef struct uapmd_app_project_result {
    bool success;
    const char* error;
} uapmd_app_project_result_t;

typedef void (*uapmd_project_save_cb_t)(uapmd_app_project_result_t result, void* user_data);

UAPMD_C_EXPORT void uapmd_app_save_project(uapmd_app_model_t app, const char* file_path, void* user_data, uapmd_project_save_cb_t callback);
UAPMD_C_EXPORT uapmd_app_project_result_t uapmd_app_save_project_sync(uapmd_app_model_t app, const char* file_path);
UAPMD_C_EXPORT uapmd_app_project_result_t uapmd_app_load_project(uapmd_app_model_t app, const char* file_path);
UAPMD_C_EXPORT uapmd_app_project_result_t uapmd_app_load_project_from_handle_token(uapmd_app_model_t app, const char* token);
/* Discards the current project and starts an empty one. Asks nothing and
 * always replaces: whether unsaved changes may be discarded is the caller's
 * decision. Unlike uapmd_tl_new_project() this also tears down what the
 * outgoing project instantiated and rebuilds the model's view of the result. */
UAPMD_C_EXPORT uapmd_app_project_result_t uapmd_app_new_project(uapmd_app_model_t app);

/* The project's tempo curve, as the engine derived it from the master track.
 * Read this rather than assembling one from uapmd_app_get_master_tempo_point(),
 * so that display and playback can never be working from different maps. See
 * uapmd_tempo_map_* in uapmd-c-engine.h. */
UAPMD_C_EXPORT uapmd_tempo_map_t uapmd_app_master_tempo_map(uapmd_app_model_t app);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Offline rendering
 * ═══════════════════════════════════════════════════════════════════════════ */

typedef struct uapmd_app_render_settings {
    const char* output_path;
    double start_seconds;
    double end_seconds;             /* 0 if not set */
    bool has_end_seconds;
    bool use_content_fallback;
    bool content_bounds_valid;
    double content_start_seconds;
    double content_end_seconds;
    double tail_seconds;
    bool enable_silence_stop;
    double silence_duration_seconds;
    double silence_threshold_db;
} uapmd_app_render_settings_t;

typedef struct uapmd_app_render_status {
    bool running;
    bool completed;
    bool success;
    double progress;
    double rendered_seconds;
    const char* message;
    const char* output_path;
} uapmd_app_render_status_t;

UAPMD_C_EXPORT bool uapmd_app_start_render_to_file(uapmd_app_model_t app, const uapmd_app_render_settings_t* settings);
UAPMD_C_EXPORT void uapmd_app_cancel_render_to_file(uapmd_app_model_t app);
UAPMD_C_EXPORT uapmd_app_render_status_t uapmd_app_get_render_to_file_status(uapmd_app_model_t app);
UAPMD_C_EXPORT void uapmd_app_clear_completed_render_status(uapmd_app_model_t app);

/* ═══════════════════════════════════════════════════════════════════════════
 *  TransportController
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT bool  uapmd_transport_is_playing(uapmd_transport_controller_t tc);
UAPMD_C_EXPORT bool  uapmd_transport_is_paused(uapmd_transport_controller_t tc);
UAPMD_C_EXPORT bool  uapmd_transport_is_recording(uapmd_transport_controller_t tc);
UAPMD_C_EXPORT float uapmd_transport_get_volume(uapmd_transport_controller_t tc);
UAPMD_C_EXPORT void  uapmd_transport_set_volume(uapmd_transport_controller_t tc, float volume);

UAPMD_C_EXPORT void uapmd_transport_play(uapmd_transport_controller_t tc);
UAPMD_C_EXPORT void uapmd_transport_stop(uapmd_transport_controller_t tc);
UAPMD_C_EXPORT void uapmd_transport_pause(uapmd_transport_controller_t tc);
UAPMD_C_EXPORT void uapmd_transport_resume(uapmd_transport_controller_t tc);
UAPMD_C_EXPORT void uapmd_transport_record(uapmd_transport_controller_t tc);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Startup lifecycle
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT void uapmd_app_notify_ui_ready(uapmd_app_model_t app);
UAPMD_C_EXPORT void uapmd_app_notify_persistent_storage_ready(uapmd_app_model_t app);

#ifdef __cplusplus
}
#endif

#endif /* UAPMD_C_APP_H */
