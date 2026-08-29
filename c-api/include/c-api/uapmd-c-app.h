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
UAPMD_C_EXPORT size_t uapmd_app_generate_scan_report(uapmd_app_model_t app, char* buf, size_t buf_size);
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
UAPMD_C_EXPORT bool     uapmd_app_unblock_plugin(uapmd_app_model_t app, const char* entry_id);

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

/* The mutation callbacks live in uapmd-c-undo.h: track mutations became undo
 * engine operations in uapmd 0.5.6, so they complete asynchronously. */

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
UAPMD_C_EXPORT uapmd_timeline_track_t uapmd_app_master_timeline_track(uapmd_app_model_t app);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Timeline state access
 * ═══════════════════════════════════════════════════════════════════════════ */

UAPMD_C_EXPORT bool uapmd_app_get_timeline_state(uapmd_app_model_t app, uapmd_timeline_state_t* out);

/* ═══════════════════════════════════════════════════════════════════════════
 *  Track graph editing (DAG graph)
 * ═══════════════════════════════════════════════════════════════════════════ */

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
UAPMD_C_EXPORT uapmd_op_result_t uapmd_app_set_master_markers(uapmd_app_model_t app,
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
 *  Undo history (uapmd 0.5.6)
 *
 *  The application-level entry points. They wrap the same history that
 *  uapmd_tl_undo_engine() exposes, and additionally reconcile the plug-in
 *  instances the model tracks after the document moves.
 * ═══════════════════════════════════════════════════════════════════════════ */

typedef void (*uapmd_history_mutation_cb_t)(const char* error, void* user_data);

UAPMD_C_EXPORT bool uapmd_app_get_history_state(uapmd_app_model_t app, uapmd_undo_state_t* out);
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

UAPMD_C_EXPORT bool uapmd_app_start_render(uapmd_app_model_t app, const uapmd_app_render_settings_t* settings);
UAPMD_C_EXPORT void uapmd_app_cancel_render(uapmd_app_model_t app);
UAPMD_C_EXPORT uapmd_app_render_status_t uapmd_app_get_render_status(uapmd_app_model_t app);
UAPMD_C_EXPORT void uapmd_app_clear_render_status(uapmd_app_model_t app);

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
