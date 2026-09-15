/* uapmd C API — project history: command manager, commands, fragments
 *
 * Introduced by uapmd 0.5.6. Reading the document goes through the timeline
 * facade (uapmd-c-engine.h); changing it as a user action goes through
 * uapmd_commands_* here, so that every edit lands in the undo history.
 */
#ifndef UAPMD_C_UNDO_H
#define UAPMD_C_UNDO_H

#include "uapmd-c-common.h"
#include "uapmd-c-data.h"
#include "uapmd-c-engine.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ── Opaque handles ──────────────────────────────────────────────────────── */

/* The project's history. uapmd 0.5.7 turned the history into an interface
 * (uapmd::ProjectHistory) that the project chooses an implementation of, and
 * withdrew every direct handle on the engine behind it: what used to be
 * reachable as ProjectUndoEngine is now reached through the command manager,
 * which carries the whole contract -- undo/redo, steps, gestures, save points
 * and retention. The uapmd_undo_engine_* family that mirrored the old engine is
 * gone with it; its calls live on here under uapmd_command_manager_*. */
typedef struct uapmd_command_manager*  uapmd_command_manager_t;
typedef struct uapmd_project_commands* uapmd_project_commands_t;
typedef struct uapmd_address_book*     uapmd_address_book_t;
/* Detached document objects. Owned by the caller; release with the matching
 * _destroy function. */
typedef struct uapmd_clip_fragment*    uapmd_clip_fragment_t;
typedef struct uapmd_track_fragment*   uapmd_track_fragment_t;

/* ── Enums ───────────────────────────────────────────────────────────────── */

/* Who is making the change. User and Remote edits enter history; Load,
 * UndoRedo and Internal ones are applied without being recorded. */
typedef enum uapmd_mutation_origin {
    UAPMD_MUTATION_ORIGIN_USER      = 0,
    UAPMD_MUTATION_ORIGIN_UNDO_REDO = 1,
    UAPMD_MUTATION_ORIGIN_LOAD      = 2,
    UAPMD_MUTATION_ORIGIN_REMOTE    = 3,
    UAPMD_MUTATION_ORIGIN_INTERNAL  = 4
} uapmd_mutation_origin_t;

typedef enum uapmd_undo_status {
    UAPMD_UNDO_SUCCEEDED        = 0,
    UAPMD_UNDO_BUSY             = 1,
    UAPMD_UNDO_NOTHING_TO_UNDO  = 2,
    UAPMD_UNDO_NOTHING_TO_REDO  = 3,
    UAPMD_UNDO_FAILED           = 4,
    UAPMD_UNDO_CANCELLED        = 5,
    UAPMD_UNDO_STOPPED          = 6
} uapmd_undo_status_t;

/* Whether an attached object keeps the identifiers it carries (Restore, which
 * is what undoing a delete requires) or is given fresh ones (Mint, which is
 * what paste and duplicate require). */
typedef enum uapmd_object_id_policy {
    UAPMD_OBJECT_ID_RESTORE = 0,
    UAPMD_OBJECT_ID_MINT    = 1
} uapmd_object_id_policy_t;

/* Whether one history step delivers its document events as a single batch.
 *
 * PER_COMMAND lets observers see the step progress, which is what a step that
 * spans asynchronous work needs: a document transaction must not stay open
 * across a remote round trip or a plug-in archive. WHOLE_STEP hides the
 * intermediate states, and is only correct for a step whose commands all run
 * inline. A gesture should almost always stay PER_COMMAND: it lasts as long as
 * the user drags, and batching would freeze every observer for that long. */
typedef enum uapmd_step_event_batching {
    UAPMD_STEP_EVENTS_PER_COMMAND = 0,
    UAPMD_STEP_EVENTS_WHOLE_STEP  = 1
} uapmd_step_event_batching_t;

/* ── Result / state structs ──────────────────────────────────────────────── */

/* `error` is NULL when there is none. It points into per-thread storage that
 * the next history call on the same thread overwrites; copy it to keep it. */
typedef struct uapmd_undo_result {
    uapmd_undo_status_t status;
    const char* error;
} uapmd_undo_result_t;

/* String members follow the same per-thread lifetime rule as above, and are
 * never NULL (an absent description is an empty string). */
typedef struct uapmd_undo_state {
    bool     busy;
    bool     compound_open;
    bool     gesture_open;
    bool     can_undo;
    bool     can_redo;
    bool     dirty;
    const char* compound_description;
    const char* undo_description;
    const char* redo_description;
    uint64_t history_size_in_bytes;
    uint64_t maximum_history_size_in_bytes;
    uint64_t current_state_id;
    uint64_t saved_state_id;
} uapmd_undo_state_t;

/* Stable document identity of one clip. Both members are NUL-terminated and,
 * for values returned by this API, valid until the next address-book call on
 * the same thread. */
typedef struct uapmd_clip_address {
    const char* track_reference_id;
    const char* clip_reference_id;
} uapmd_clip_address_t;

/* Stable document identity of one plug-in node. A runtime instance id is not
 * usable in history: removing and restoring a plug-in produces a new one. */
typedef struct uapmd_plugin_address {
    const char* track_reference_id;
    const char* node_id;
} uapmd_plugin_address_t;

/* Which parts of a captured track are applied when it is attached. */
typedef struct uapmd_track_attach_options {
    uapmd_object_id_policy_t id_policy;
    int32_t insertion_index;      /* negative appends */
    bool    include_plugins;
    bool    include_plugin_state; /* skipping state also skips the slowest part */
    bool    include_clips;
} uapmd_track_attach_options_t;

/* ── Callbacks ───────────────────────────────────────────────────────────── */

typedef void (*uapmd_undo_completion_cb_t)(uapmd_undo_result_t result, void* user_data);
/* Structural track mutations go through the undo engine, so they complete
 * asynchronously. `error` is NULL on success and only valid for the duration
 * of the callback; `track_index` is -1 when the mutation failed. */
typedef void (*uapmd_track_mutation_cb_t)(int32_t track_index, const char* error, void* user_data);
typedef void (*uapmd_track_clear_cb_t)(const char* error, void* user_data);
/* `fragment` is NULL when capture failed; when non-NULL the callee takes
 * ownership and must release it with uapmd_track_fragment_destroy(). */
typedef void (*uapmd_track_fragment_cb_t)(uapmd_track_fragment_t fragment, const char* error, void* user_data);

/* ═══════════════════════════════════════════════════════════════════════════
 *  ProjectCommandManager — the project's history
 *
 *  Everything that changes the document goes through a command, and every
 *  command is recorded here. Reading the document goes through the timeline
 *  facade instead (uapmd-c-engine.h).
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT bool uapmd_command_manager_get_state(uapmd_command_manager_t cm, uapmd_undo_state_t* out);

UAPMD_C_EXPORT void uapmd_command_manager_undo(uapmd_command_manager_t cm, void* user_data, uapmd_undo_completion_cb_t callback);
UAPMD_C_EXPORT void uapmd_command_manager_redo(uapmd_command_manager_t cm, void* user_data, uapmd_undo_completion_cb_t callback);

/* One named history step spanning several commands. Commands performed while
 * it is open are applied immediately but enter history as one entry only when
 * end_step() succeeds. Nested steps are deliberately rejected. */
UAPMD_C_EXPORT uapmd_undo_result_t uapmd_command_manager_begin_step(uapmd_command_manager_t cm,
                                                                      const char* description,
                                                                      uapmd_mutation_origin_t origin,
                                                                      uapmd_step_event_batching_t batching);
UAPMD_C_EXPORT void uapmd_command_manager_end_step(uapmd_command_manager_t cm, void* user_data, uapmd_undo_completion_cb_t callback);
/* Reverts every child that already ran, in reverse order. */
UAPMD_C_EXPORT void uapmd_command_manager_cancel_step(uapmd_command_manager_t cm, void* user_data, uapmd_undo_completion_cb_t callback);

/* A gesture is a step that coalesces adjacent commands sharing a command id:
 * intermediate values are applied, history keeps only first and last. */
UAPMD_C_EXPORT uapmd_undo_result_t uapmd_command_manager_begin_gesture(uapmd_command_manager_t cm,
                                                                         const char* description,
                                                                         uapmd_mutation_origin_t origin,
                                                                         uapmd_step_event_batching_t batching);
UAPMD_C_EXPORT void uapmd_command_manager_end_gesture(uapmd_command_manager_t cm, void* user_data, uapmd_undo_completion_cb_t callback);
UAPMD_C_EXPORT void uapmd_command_manager_cancel_gesture(uapmd_command_manager_t cm, void* user_data, uapmd_undo_completion_cb_t callback);

/* Save points and retention. What "dirty" means belongs to the history, not to
 * whoever writes the file: a numeric cursor cannot tell an undo followed by a
 * fresh edit from the state that was saved.
 *
 * Saving captures the current state id when serialization starts and marks that
 * node saved once the write succeeds, so an edit made while the write is in
 * flight still leaves the project dirty. */
UAPMD_C_EXPORT bool uapmd_command_manager_mark_saved(uapmd_command_manager_t cm);
UAPMD_C_EXPORT bool uapmd_command_manager_mark_state_saved(uapmd_command_manager_t cm, uint64_t state_id);
/* Releases every retained revert and makes the current document a new history
 * root. Fails while a command is pending or a step is open. */
UAPMD_C_EXPORT bool uapmd_command_manager_clear(uapmd_command_manager_t cm, bool mark_current_state_saved);
UAPMD_C_EXPORT bool uapmd_command_manager_set_maximum_history_size(uapmd_command_manager_t cm, uint64_t bytes);

/* Refuses new work and completes pending notification as Cancelled. */
UAPMD_C_EXPORT void uapmd_command_manager_shutdown(uapmd_command_manager_t cm);

/* ═══════════════════════════════════════════════════════════════════════════
 *  ProjectCommands — the undoable edits a project supports
 *
 *  Each returns false when the target does not exist or the change was
 *  rejected; setting the value already in place succeeds without creating a
 *  history step.
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT uapmd_command_manager_t uapmd_commands_history(uapmd_project_commands_t cmd);

/* Clip properties */
UAPMD_C_EXPORT bool uapmd_commands_set_clip_enabled(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, bool enabled, uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_set_clip_anchor(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, uapmd_time_reference_t anchor, uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_set_clip_gain(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, double gain, uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_set_clip_muted(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, bool muted, uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_resize_clip(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, int64_t new_duration_samples, uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_set_clip_name(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, const char* name, uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_set_clip_filepath(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, const char* filepath, uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_set_clip_needs_file_save(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, bool needs_save, uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_set_clip_markers(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, const uapmd_clip_marker_t* markers, uint32_t marker_count, uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_set_clip_audio_warps(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, const uapmd_audio_warp_point_t* warps, uint32_t warp_count, uapmd_mutation_origin_t origin);

/* Track properties. These address their track by stable document identity
 * during replay, so inserting or removing another track does not redirect an
 * undo to the wrong one. */
UAPMD_C_EXPORT bool uapmd_commands_set_track_gain(uapmd_project_commands_t cmd, int32_t track_index, double gain, uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_set_track_muted(uapmd_project_commands_t cmd, int32_t track_index, bool muted, uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_set_track_solo(uapmd_project_commands_t cmd, int32_t track_index, bool solo, uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_set_track_bypassed(uapmd_project_commands_t cmd, int32_t track_index, bool bypassed, uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_set_track_freeze_policy_enabled(uapmd_project_commands_t cmd, int32_t track_index, bool enabled, uapmd_mutation_origin_t origin);

/* Plug-in properties. A plug-in is addressed by document identity, not by the
 * runtime instance id, which changes when it is removed and restored. */
UAPMD_C_EXPORT bool uapmd_commands_set_plugin_bypassed(uapmd_project_commands_t cmd, int32_t instance_id, bool bypassed, uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_set_plugin_parameter_value(uapmd_project_commands_t cmd, int32_t instance_id, int32_t parameter_index, double value, uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_set_plugin_per_note_controller_value(uapmd_project_commands_t cmd,
                                                                           int32_t instance_id,
                                                                           uint32_t context_type,  /* remidy::PerNoteControllerContextTypes bit flags */
                                                                           uint32_t note,
                                                                           uint32_t channel,
                                                                           uint32_t group,
                                                                           uint32_t extra,
                                                                           int32_t parameter_index,
                                                                           double value,
                                                                           uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_set_plugin_group(uapmd_project_commands_t cmd, int32_t instance_id, uint8_t group, uapmd_mutation_origin_t origin);

/* ── Track graph edges ───────────────────────────────────────────────────── */

typedef enum uapmd_graph_endpoint_type {
    UAPMD_GRAPH_ENDPOINT_GRAPH_INPUT  = 0,
    UAPMD_GRAPH_ENDPOINT_PLUGIN       = 1,
    UAPMD_GRAPH_ENDPOINT_GRAPH_OUTPUT = 2
} uapmd_graph_endpoint_type_t;

typedef enum uapmd_graph_bus_type {
    UAPMD_GRAPH_BUS_AUDIO = 0,
    UAPMD_GRAPH_BUS_EVENT = 1
} uapmd_graph_bus_type_t;

/*
 * `node_id` is the node's persistent identity as stored in the project, and is the
 * field a graph editor must key its pins by: `instance_id` is -1 for BOTH graph
 * endpoints and for every built-in node, so it cannot tell them apart. It may be
 * null or empty, in which case the identity is derived the way uapmd-app's
 * `endpointNodeId()` derives it (PluginGraphEditor.cpp:105): "graph:input",
 * "graph:output", or "plugin:<instance_id>".
 */
typedef struct uapmd_graph_endpoint {
    uapmd_graph_endpoint_type_t type;
    const char* node_id;
    int32_t instance_id;
    uint32_t bus_index;
} uapmd_graph_endpoint_t;

typedef struct uapmd_graph_connection {
    int64_t id;
    uapmd_graph_bus_type_t bus_type;
    uapmd_graph_endpoint_t source;
    uapmd_graph_endpoint_t target;
} uapmd_graph_connection_t;

/* Device inputs. Adding one, rerouting it and removing it all write the same
 * per-input value, so undoing an add is a removal. Moved here from the timeline
 * facade in uapmd 0.5.7, which is why they are undoable at all. */
UAPMD_C_EXPORT bool uapmd_commands_add_device_input_to_track(uapmd_project_commands_t cmd,
                                                                int32_t track_index,
                                                                int32_t source_node_id,
                                                                const uint32_t* channel_indices,
                                                                uint32_t channel_count,
                                                                uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_set_device_input_channels(uapmd_project_commands_t cmd,
                                                                int32_t track_index,
                                                                int32_t source_node_id,
                                                                const uint32_t* channel_indices,
                                                                uint32_t channel_count,
                                                                uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_remove_device_input_from_track(uapmd_project_commands_t cmd,
                                                                     int32_t track_index,
                                                                     int32_t source_node_id,
                                                                     uapmd_mutation_origin_t origin);

/* Track graph edges. Presence is the value here too, so undoing a connect is a
 * disconnect. On failure the reason the graph gave -- a cycle, a missing
 * endpoint, a direction mismatch -- is left in per-thread storage readable with
 * uapmd_commands_last_graph_error() until the next graph call on this thread. */
UAPMD_C_EXPORT bool uapmd_commands_connect_track_graph(uapmd_project_commands_t cmd,
                                                          int32_t track_index,
                                                          const uapmd_graph_connection_t* connection,
                                                          uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_commands_disconnect_track_graph_connection(uapmd_project_commands_t cmd,
                                                                         int32_t track_index,
                                                                         int64_t connection_id,
                                                                         uapmd_mutation_origin_t origin);
/* Never NULL; empty when the last graph call succeeded. */
UAPMD_C_EXPORT const char* uapmd_commands_last_graph_error(void);

/* Replaces a track's graph with a fresh one of the requested type. The event
 * buffer size is the caller's: the project loader and the application model
 * each supply their own. */
UAPMD_C_EXPORT bool uapmd_commands_replace_track_graph_type(uapmd_project_commands_t cmd,
                                                               int32_t track_index,
                                                               const char* graph_type_id,
                                                               size_t event_buffer_size_in_bytes,
                                                               uapmd_mutation_origin_t origin);

/* Project-wide properties. The caller is responsible for validating marker
 * identity and reference cycles before submitting. */
UAPMD_C_EXPORT bool uapmd_commands_set_master_track_markers(uapmd_project_commands_t cmd, const uapmd_clip_marker_t* markers, uint32_t marker_count, uapmd_mutation_origin_t origin);

/* The complete persisted latency/monitoring configuration, as one history step.
 * To change a single field, read the current settings with
 * uapmd_engine_get_latency_compensation_settings(), alter that field, and
 * submit the whole snapshot back. `implementation_properties` is a flat array
 * of alternating key and value pointers, so `property_count` counts pairs. */
typedef struct uapmd_latency_compensation_settings {
    const char* implementation_id;
    int32_t     playback_compensation_mode;  /* uapmd_playback_compensation_mode_t */
    int32_t     input_monitoring_policy;     /* uapmd_input_monitoring_policy_t */
    const int32_t* monitored_track_indexes;
    uint32_t       monitored_track_count;
    const int32_t* record_armed_track_indexes;
    uint32_t       record_armed_track_count;
    const char* const* implementation_properties; /* key, value, key, value, ... */
    uint32_t           property_count;            /* number of pairs */
} uapmd_latency_compensation_settings_t;

typedef enum uapmd_playback_compensation_mode {
    UAPMD_PLAYBACK_COMPENSATION_COMPENSATED = 0,
    UAPMD_PLAYBACK_COMPENSATION_LOW_LATENCY = 1
} uapmd_playback_compensation_mode_t;

typedef enum uapmd_input_monitoring_policy {
    /* Prefer monitored live input over fully compensated playback on that path
     * when the track is both record-armed and monitor-enabled. */
    UAPMD_INPUT_MONITORING_TAPE_STYLE = 0,
    /* Low-latency monitoring only for tracks with live input that are
     * explicitly monitor-enabled; other playback stays compensated. */
    UAPMD_INPUT_MONITORING_AUTO       = 1,
    UAPMD_INPUT_MONITORING_OFF        = 2
} uapmd_input_monitoring_policy_t;

UAPMD_C_EXPORT bool uapmd_commands_set_latency_compensation_settings(uapmd_project_commands_t cmd,
                                                                        const uapmd_latency_compensation_settings_t* settings,
                                                                        uapmd_mutation_origin_t origin);

/* ═══════════════════════════════════════════════════════════════════════════
 *  ProjectAddressBook — persistent identity <-> runtime index
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT uapmd_timeline_track_t  uapmd_addresses_timeline_track(uapmd_address_book_t ab, const char* track_reference_id);
UAPMD_C_EXPORT uapmd_sequencer_track_t uapmd_addresses_sequencer_track(uapmd_address_book_t ab, const char* track_reference_id);
/* Returns UAPMD_MASTER_TRACK_INDEX for the master track, -1 when unknown. */
UAPMD_C_EXPORT int32_t uapmd_addresses_track_index(uapmd_address_book_t ab, const char* track_reference_id);
UAPMD_C_EXPORT int32_t uapmd_addresses_clip_id(uapmd_address_book_t ab, uapmd_clip_address_t address);
UAPMD_C_EXPORT int32_t uapmd_addresses_plugin_instance_id(uapmd_address_book_t ab, uapmd_plugin_address_t address);

/* Capture. Each yields NULL / false when the live object does not exist. */
UAPMD_C_EXPORT const char* uapmd_addresses_track_reference_id(uapmd_address_book_t ab, int32_t track_index);
UAPMD_C_EXPORT bool uapmd_addresses_clip_address(uapmd_address_book_t ab, int32_t track_index, int32_t clip_id, uapmd_clip_address_t* out);
UAPMD_C_EXPORT bool uapmd_addresses_plugin_address(uapmd_address_book_t ab, int32_t instance_id, uapmd_plugin_address_t* out);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Fragments — detached clips and tracks (undo payload and clipboard)
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT void uapmd_clip_fragment_destroy(uapmd_clip_fragment_t fragment);
UAPMD_C_EXPORT bool uapmd_clip_fragment_is_midi(uapmd_clip_fragment_t fragment);
/* The clip metadata exactly as the document holds it. Pointer members inside
 * `out` stay valid until the fragment is destroyed. */
UAPMD_C_EXPORT bool uapmd_clip_fragment_get_clip(uapmd_clip_fragment_t fragment, uapmd_clip_data_t* out);
UAPMD_C_EXPORT uint32_t uapmd_clip_fragment_ump_event_count(uapmd_clip_fragment_t fragment);
UAPMD_C_EXPORT uint32_t uapmd_clip_fragment_get_ump_events(uapmd_clip_fragment_t fragment, uapmd_ump_t* out, uint32_t out_count);
UAPMD_C_EXPORT uint32_t uapmd_clip_fragment_get_ump_tick_timestamps(uapmd_clip_fragment_t fragment, uint64_t* out, uint32_t out_count);
UAPMD_C_EXPORT uint32_t uapmd_clip_fragment_extension_state_count(uapmd_clip_fragment_t fragment);
UAPMD_C_EXPORT size_t   uapmd_clip_fragment_extension_state_key(uapmd_clip_fragment_t fragment, uint32_t index, char* buf, size_t buf_size);
UAPMD_C_EXPORT size_t   uapmd_clip_fragment_extension_state_data(uapmd_clip_fragment_t fragment, uint32_t index, uint8_t* buf, size_t buf_size);

UAPMD_C_EXPORT void uapmd_track_fragment_destroy(uapmd_track_fragment_t fragment);
UAPMD_C_EXPORT size_t uapmd_track_fragment_reference_id(uapmd_track_fragment_t fragment, char* buf, size_t buf_size);
UAPMD_C_EXPORT double uapmd_track_fragment_volume(uapmd_track_fragment_t fragment);
UAPMD_C_EXPORT bool   uapmd_track_fragment_muted(uapmd_track_fragment_t fragment);
UAPMD_C_EXPORT bool   uapmd_track_fragment_solo(uapmd_track_fragment_t fragment);
UAPMD_C_EXPORT size_t uapmd_track_fragment_graph_type(uapmd_track_fragment_t fragment, char* buf, size_t buf_size);
UAPMD_C_EXPORT size_t uapmd_track_fragment_graph_bytes(uapmd_track_fragment_t fragment, uint8_t* buf, size_t buf_size);
UAPMD_C_EXPORT uint32_t uapmd_track_fragment_clip_count(uapmd_track_fragment_t fragment);
/* Borrowed view of a contained clip fragment; it lives as long as the track
 * fragment does, so do NOT pass it to uapmd_clip_fragment_destroy(). */
UAPMD_C_EXPORT uapmd_clip_fragment_t uapmd_track_fragment_get_clip(uapmd_track_fragment_t fragment, uint32_t index);

typedef struct uapmd_track_plugin_fragment {
    const char* node_id;
    const char* plugin_id;
    const char* format;
    const char* display_name;
    int32_t     group_index;
    uint32_t    state_size;
    const uint8_t* state;
} uapmd_track_plugin_fragment_t;

UAPMD_C_EXPORT uint32_t uapmd_track_fragment_plugin_count(uapmd_track_fragment_t fragment);
UAPMD_C_EXPORT bool     uapmd_track_fragment_get_plugin(uapmd_track_fragment_t fragment, uint32_t index, uapmd_track_plugin_fragment_t* out);

/* ═══════════════════════════════════════════════════════════════════════════
 *  TimelineFacade — history accessors and undoable structural mutations
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT uapmd_project_commands_t uapmd_tl_commands(uapmd_timeline_facade_t tl);
/* Shorthand for the command manager behind uapmd_tl_commands(). */
UAPMD_C_EXPORT uapmd_command_manager_t  uapmd_tl_history(uapmd_timeline_facade_t tl);
UAPMD_C_EXPORT uapmd_address_book_t     uapmd_tl_addresses(uapmd_timeline_facade_t tl);

/* Groups the document events produced by everything between the two calls into
 * a single batch. Use whenever one user-visible action performs several
 * mutations, or observers can see it half-applied. Calls nest. */
UAPMD_C_EXPORT void uapmd_tl_begin_document_transaction(uapmd_timeline_facade_t tl);
UAPMD_C_EXPORT void uapmd_tl_end_document_transaction(uapmd_timeline_facade_t tl);

UAPMD_C_EXPORT bool uapmd_tl_remove_clip_with_origin(uapmd_timeline_facade_t tl, int32_t track_index, int32_t clip_id, uapmd_mutation_origin_t origin);
/* Removes every clip on the track. True when at least one was removed. */
UAPMD_C_EXPORT bool uapmd_tl_clear_clips_from_track(uapmd_timeline_facade_t tl, int32_t track_index, uapmd_mutation_origin_t origin);
UAPMD_C_EXPORT bool uapmd_tl_clip_enabled(uapmd_timeline_facade_t tl, int32_t track_index, int32_t clip_id);

UAPMD_C_EXPORT bool uapmd_tl_replace_midi_clip_content(uapmd_timeline_facade_t tl,
                                                          int32_t track_index,
                                                          int32_t clip_id,
                                                          const uapmd_ump_t* ump_events,
                                                          uint32_t ump_event_count,
                                                          const uint64_t* tick_timestamps,
                                                          uint32_t tick_count,
                                                          uapmd_mutation_origin_t origin);

/* A non-empty `filepath` switches the clip to that file and adopts its length;
 * otherwise the clip keeps its length. `master_markers` is supplied because
 * markers and warps may reference the master track. */
UAPMD_C_EXPORT bool uapmd_tl_replace_audio_clip_content(uapmd_timeline_facade_t tl,
                                                           int32_t track_index,
                                                           int32_t clip_id,
                                                           const char* filepath,
                                                           const uapmd_clip_marker_t* markers,
                                                           uint32_t marker_count,
                                                           const uapmd_audio_warp_point_t* warps,
                                                           uint32_t warp_count,
                                                           const uapmd_clip_marker_t* master_markers,
                                                           uint32_t master_marker_count,
                                                           uapmd_mutation_origin_t origin);

/* Capture is non-destructive; deleting is a separate remove call. Must NOT be
 * called inside a document transaction. Returns NULL when the clip is unknown;
 * otherwise the caller owns the fragment. */
UAPMD_C_EXPORT uapmd_clip_fragment_t uapmd_tl_capture_clip_fragment(uapmd_timeline_facade_t tl, int32_t track_index, int32_t clip_id);
UAPMD_C_EXPORT uapmd_clip_add_result_t uapmd_tl_attach_clip_fragment(uapmd_timeline_facade_t tl,
                                                                        int32_t track_index,
                                                                        uapmd_clip_fragment_t fragment,
                                                                        uapmd_object_id_policy_t id_policy);

/* Paste: attaches with fresh identifiers AND records the result as one
 * undoable edit, which plain attach does not do. Must NOT be called inside a
 * document transaction. */
UAPMD_C_EXPORT uapmd_clip_add_result_t uapmd_tl_paste_clip_fragment(uapmd_timeline_facade_t tl,
                                                                       int32_t track_index,
                                                                       uapmd_clip_fragment_t fragment);

/* Both halves are asynchronous because a track owns live plug-in instances.
 * The callback runs exactly once, on the thread completing the last plug-in
 * operation. Capture must NOT be called inside a document transaction. */
UAPMD_C_EXPORT void uapmd_tl_capture_track_fragment(uapmd_timeline_facade_t tl,
                                                       int32_t track_index,
                                                       void* user_data,
                                                       uapmd_track_fragment_cb_t callback);
UAPMD_C_EXPORT void uapmd_tl_attach_track_fragment(uapmd_timeline_facade_t tl,
                                                      uapmd_track_fragment_t fragment,
                                                      uapmd_track_attach_options_t options,
                                                      void* user_data,
                                                      uapmd_track_mutation_cb_t callback);

/* Structural track mutations, recorded in history. */
UAPMD_C_EXPORT void uapmd_tl_add_empty_track(uapmd_timeline_facade_t tl, uapmd_mutation_origin_t origin, void* user_data, uapmd_track_mutation_cb_t callback);
UAPMD_C_EXPORT void uapmd_tl_remove_track(uapmd_timeline_facade_t tl, int32_t track_index, uapmd_mutation_origin_t origin, void* user_data, uapmd_track_mutation_cb_t callback);
/* Records an already-published, fully constructed track as one addition. */
UAPMD_C_EXPORT void uapmd_tl_record_track_addition(uapmd_timeline_facade_t tl, int32_t track_index, uapmd_mutation_origin_t origin, void* user_data, uapmd_track_mutation_cb_t callback);

/* Plug-in lifecycle and state, recorded as one asynchronous history step. */
UAPMD_C_EXPORT void uapmd_tl_set_plugin_state(uapmd_timeline_facade_t tl, int32_t instance_id, const uint8_t* state, size_t state_size, uapmd_mutation_origin_t origin, void* user_data, uapmd_undo_completion_cb_t callback);
UAPMD_C_EXPORT void uapmd_tl_load_plugin_preset(uapmd_timeline_facade_t tl, int32_t instance_id, int32_t preset_index, uapmd_mutation_origin_t origin, void* user_data, uapmd_undo_completion_cb_t callback);
UAPMD_C_EXPORT void uapmd_tl_record_plugin_instance_addition(uapmd_timeline_facade_t tl, int32_t instance_id, uapmd_mutation_origin_t origin, void* user_data, uapmd_undo_completion_cb_t callback);
UAPMD_C_EXPORT void uapmd_tl_remove_plugin_instance(uapmd_timeline_facade_t tl, int32_t instance_id, uapmd_mutation_origin_t origin, void* user_data, uapmd_undo_completion_cb_t callback);
UAPMD_C_EXPORT bool uapmd_tl_has_pending_plugin_mutations(uapmd_timeline_facade_t tl);

#ifdef __cplusplus
}
#endif

#endif /* UAPMD_C_UNDO_H */
