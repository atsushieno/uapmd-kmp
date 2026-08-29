package dev.atsushieno.uapmd.jna

import com.fizzed.jne.JNE
import com.sun.jna.*
import com.sun.jna.Structure.FieldOrder
import com.sun.jna.ptr.*

// ─── Callback interfaces (C function pointer types) ──────────────────────────

interface UmpReceiverCallback : Callback {
    fun invoke(context: Pointer?, ump: Pointer?, sizeInBytes: Long, timestamp: Long)
}

interface CreateInstanceCallback : Callback {
    fun invoke(instanceId: Int, error: String?, userData: Pointer?)
}

interface RequestStateCb : Callback {
    fun invoke(state: Pointer?, stateSize: Long, error: String?, userData: Pointer?)
}

interface LoadStateCb : Callback {
    fun invoke(error: String?, userData: Pointer?)
}

interface UiResizeHandler : Callback {
    fun invoke(width: Int, height: Int, userData: Pointer?): Boolean
}

interface GraphDeleteCb : Callback {
    fun invoke(userData: Pointer?)
}

interface EventOutputCb : Callback {
    fun invoke(instanceId: Int, data: Pointer?, dataSizeInBytes: Long, userData: Pointer?)
}

interface AddPluginCb : Callback {
    fun invoke(instanceId: Int, trackIndex: Int, error: String?, userData: Pointer?)
}

interface RenderProgressCb : Callback {
    fun invoke(progress: UapmdOfflineRenderProgress?, userData: Pointer?)
}

interface TimelineChangedCb : Callback {
    fun invoke(userData: Pointer?)
}

interface RenderShouldCancelCb : Callback {
    fun invoke(userData: Pointer?): Boolean
}

interface ScanStartedCb : Callback {
    fun invoke(totalBundles: Int, userData: Pointer?)
}

interface BundleScanCb : Callback {
    fun invoke(bundlePath: String?, userData: Pointer?)
}

interface ScanCompletedCb : Callback {
    fun invoke(userData: Pointer?)
}

interface ScanErrorCb : Callback {
    fun invoke(message: String?, userData: Pointer?)
}

interface ScanShouldCancelCb : Callback {
    fun invoke(userData: Pointer?): Boolean
}

interface InstancingCb : Callback {
    fun invoke(error: String?, userData: Pointer?)
}

interface DocumentPickCb : Callback {
    fun invoke(result: UapmdDocumentPickResult.ByValue, userData: Pointer?)
}

interface DocumentPathCb : Callback {
    fun invoke(result: UapmdDocumentIoResult.ByValue, path: String?, userData: Pointer?)
}

// ─── Event loop callbacks ─────────────────────────────────────────────────────

interface EventLoopInitializeCb : Callback {
    fun invoke(userData: Pointer?)
}

interface EventLoopIsMainThreadCb : Callback {
    fun invoke(userData: Pointer?): Boolean
}

// The C side passes (task_fn: Pointer, task_ctx: Pointer, user_data: Pointer).
// task_fn is a void(*)(void*) C function pointer; call it via JNA Function.
interface EventLoopEnqueueCb : Callback {
    fun invoke(taskFn: Pointer?, taskCtx: Pointer?, userData: Pointer?)
}

// ─── JNA Structure types ─────────────────────────────────────────────────────

@FieldOrder("value", "name")
open class UapmdParameterNamedValue : Structure() {
    @JvmField var value: Double = 0.0
    @JvmField var name: String? = null
}

@FieldOrder(
    "index", "stable_id", "name", "path",
    "default_plain_value", "min_plain_value", "max_plain_value",
    "automatable", "hidden", "discrete",
    "named_values_count", "named_values"
)
open class UapmdParameterMetadata : Structure() {
    @JvmField var index: Int = 0
    @JvmField var stable_id: String? = null
    @JvmField var name: String? = null
    @JvmField var path: String? = null
    @JvmField var default_plain_value: Double = 0.0
    @JvmField var min_plain_value: Double = 0.0
    @JvmField var max_plain_value: Double = 0.0
    @JvmField var automatable: Byte = 0   // C99 bool = 1 byte
    @JvmField var hidden: Byte = 0
    @JvmField var discrete: Byte = 0
    @JvmField var named_values_count: Int = 0
    @JvmField var named_values: Pointer? = null  // const uapmd_parameter_named_value_t*
}

@FieldOrder("bank", "index", "stable_id", "name", "path")
open class UapmdPresetMetadata : Structure() {
    @JvmField var bank: Byte = 0
    @JvmField var index: Int = 0
    @JvmField var stable_id: String? = null
    @JvmField var name: String? = null
    @JvmField var path: String? = null
}

@FieldOrder("plugin_package_name", "plugin_local_name", "instance_id")
open class UapmdAapUiHostDetails : Structure() {
    @JvmField var plugin_package_name: String? = null
    @JvmField var plugin_local_name: String? = null
    @JvmField var instance_id: Int = 0
}

@FieldOrder(
    "has_ui_support",
    "supports_embedded_presentations",
    "supports_floating_presentations",
    "supports_multiple_presentations"
)
open class UapmdUiCapabilities : Structure() {
    @JvmField var has_ui_support: Byte = 0
    @JvmField var supports_embedded_presentations: Byte = 0
    @JvmField var supports_floating_presentations: Byte = 0
    @JvmField var supports_multiple_presentations: Byte = 0
}

@FieldOrder("host_kind", "role", "parent_handle", "web_container_id")
open class UapmdUiPresentationRequest : Structure() {
    @JvmField var host_kind: Int = 0
    @JvmField var role: Int = 1
    @JvmField var parent_handle: Pointer? = null
    @JvmField var web_container_id: String? = null
}

@FieldOrder("samples", "legacy_beats")
open class UapmdTimelinePosition : Structure() {
    @JvmField var samples: Long = 0L
    @JvmField var legacy_beats: Double = 0.0

    class ByVal : UapmdTimelinePosition(), Structure.ByValue
}

@FieldOrder(
    "playhead_position", "is_playing", "loop_enabled",
    "loop_start", "loop_end",
    "tempo", "time_signature_numerator", "time_signature_denominator", "sample_rate"
)
open class UapmdTimelineState : Structure() {
    @JvmField var playhead_position: UapmdTimelinePosition = UapmdTimelinePosition()
    @JvmField var is_playing: Byte = 0
    @JvmField var loop_enabled: Byte = 0
    @JvmField var loop_start: UapmdTimelinePosition = UapmdTimelinePosition()
    @JvmField var loop_end: UapmdTimelinePosition = UapmdTimelinePosition()
    @JvmField var tempo: Double = 0.0
    @JvmField var time_signature_numerator: Int = 0
    @JvmField var time_signature_denominator: Int = 0
    @JvmField var sample_rate: Int = 0
}

@FieldOrder("clip_id", "source_node_id", "success", "error")
open class UapmdClipAddResult : Structure() {
    @JvmField var clip_id: Int = 0
    @JvmField var source_node_id: Int = 0
    @JvmField var success: Byte = 0
    @JvmField var error: String? = null

    class ByVal : UapmdClipAddResult(), Structure.ByValue
}

@FieldOrder("success", "error")
open class UapmdProjectResult : Structure() {
    @JvmField var success: Byte = 0
    @JvmField var error: String? = null

    class ByVal : UapmdProjectResult(), Structure.ByValue
}

@FieldOrder("has_content", "first_sample", "last_sample", "first_seconds", "last_seconds")
open class UapmdContentBounds : Structure() {
    @JvmField var has_content: Byte = 0
    @JvmField var first_sample: Long = 0L
    @JvmField var last_sample: Long = 0L
    @JvmField var first_seconds: Double = 0.0
    @JvmField var last_seconds: Double = 0.0

    class ByVal : UapmdContentBounds(), Structure.ByValue
}

@FieldOrder("start_seconds", "duration_seconds", "velocity", "note", "_pad")
open class UapmdMidiNote : Structure() {
    @JvmField var start_seconds: Double = 0.0
    @JvmField var duration_seconds: Double = 0.0
    @JvmField var velocity: Float = 0f
    @JvmField var note: Byte = 0
    @JvmField var _pad: ByteArray = ByteArray(3)
}

// Maps uapmd_clip_data_t — only the fields needed for UI; pointer fields for markers/warps
// are mapped as Pointer? (they are read-only and owned by ClipManager).
@FieldOrder(
    "clip_id", "reference_id", "position", "duration_samples",
    "source_node_instance_id", "gain", "muted", "name", "filepath",
    "needs_file_save", "clip_type", "tick_resolution", "clip_tempo",
    "nrpn_to_parameter_mapping", "anchor_reference_id", "anchor_origin",
    "anchor_offset", "marker_count", "markers", "audio_warp_count", "audio_warps"
)
open class UapmdClipData : Structure() {
    @JvmField var clip_id: Int = 0
    @JvmField var reference_id: String? = null
    @JvmField var position: UapmdTimelinePosition = UapmdTimelinePosition()
    @JvmField var duration_samples: Long = 0L
    @JvmField var source_node_instance_id: Int = 0
    @JvmField var gain: Double = 1.0
    @JvmField var muted: Byte = 0
    @JvmField var name: String? = null
    @JvmField var filepath: String? = null
    @JvmField var needs_file_save: Byte = 0
    @JvmField var clip_type: Int = 0
    @JvmField var tick_resolution: Int = 0
    @JvmField var clip_tempo: Double = 120.0
    @JvmField var nrpn_to_parameter_mapping: Byte = 0
    @JvmField var anchor_reference_id: String? = null
    @JvmField var anchor_origin: Int = 0
    @JvmField var anchor_offset: UapmdTimelinePosition = UapmdTimelinePosition()
    @JvmField var marker_count: Int = 0
    @JvmField var markers: Pointer? = null
    @JvmField var audio_warp_count: Int = 0
    @JvmField var audio_warps: Pointer? = null
}

@FieldOrder("directions", "id", "name", "sample_rate", "channels")
open class UapmdAudioDeviceInfo : Structure() {
    @JvmField var directions: Int = 0
    @JvmField var id: Int = 0
    @JvmField var name: String? = null
    @JvmField var sample_rate: Int = 0
    @JvmField var channels: Int = 0
}

@FieldOrder(
    "output_path", "start_seconds", "end_seconds",
    "has_end_seconds", "use_content_fallback", "content_bounds_valid",
    "content_start_seconds", "content_end_seconds", "tail_seconds",
    "enable_silence_stop", "silence_duration_seconds", "silence_threshold_db",
    "sample_rate", "buffer_size", "output_channels", "ump_buffer_size"
)
open class UapmdOfflineRenderSettings : Structure() {
    @JvmField var output_path: String? = null
    @JvmField var start_seconds: Double = 0.0
    @JvmField var end_seconds: Double = 0.0
    @JvmField var has_end_seconds: Byte = 0
    @JvmField var use_content_fallback: Byte = 0
    @JvmField var content_bounds_valid: Byte = 0
    @JvmField var content_start_seconds: Double = 0.0
    @JvmField var content_end_seconds: Double = 0.0
    @JvmField var tail_seconds: Double = 0.0
    @JvmField var enable_silence_stop: Byte = 0
    @JvmField var silence_duration_seconds: Double = 0.0
    @JvmField var silence_threshold_db: Double = 0.0
    @JvmField var sample_rate: Int = 0
    @JvmField var buffer_size: Int = 0
    @JvmField var output_channels: Int = 0
    @JvmField var ump_buffer_size: Int = 0
}

@FieldOrder("progress", "rendered_seconds", "total_seconds", "rendered_frames", "total_frames")
open class UapmdOfflineRenderProgress : Structure() {
    @JvmField var progress: Double = 0.0
    @JvmField var rendered_seconds: Double = 0.0
    @JvmField var total_seconds: Double = 0.0
    @JvmField var rendered_frames: Long = 0L
    @JvmField var total_frames: Long = 0L
}

@FieldOrder("success", "canceled", "rendered_seconds", "error_message")
open class UapmdOfflineRenderResult : Structure() {
    @JvmField var success: Byte = 0
    @JvmField var canceled: Byte = 0
    @JvmField var rendered_seconds: Double = 0.0
    @JvmField var error_message: String? = null

    class ByVal : UapmdOfflineRenderResult(), Structure.ByValue
}

@FieldOrder("num_frames", "num_channels", "sample_rate")
open class UapmdAudioFileProperties : Structure() {
    @JvmField var num_frames: Long = 0L  // uint64_t
    @JvmField var num_channels: Int = 0
    @JvmField var sample_rate: Int = 0
}

@FieldOrder("time_seconds", "tick_position", "bpm")
open class UapmdTempoPoint : Structure() {
    @JvmField var time_seconds: Double = 0.0
    @JvmField var tick_position: Long = 0L
    @JvmField var bpm: Double = 0.0
}

@FieldOrder("time_seconds", "tick_position", "numerator", "denominator")
open class UapmdTimeSignaturePoint : Structure() {
    @JvmField var time_seconds: Double = 0.0
    @JvmField var tick_position: Long = 0L
    @JvmField var numerator: Byte = 4
    @JvmField var denominator: Byte = 4
}

@FieldOrder("id", "format", "plugin_id", "reason")
open class UapmdBlocklistEntry : Structure() {
    @JvmField var id: String? = null
    @JvmField var format: String? = null
    @JvmField var plugin_id: String? = null
    @JvmField var reason: String? = null
}

@FieldOrder("id", "display_name", "mime_type")
open class UapmdDocumentHandle(p: Pointer? = null) : Structure(p) {
    @JvmField var id: String? = null
    @JvmField var display_name: String? = null
    @JvmField var mime_type: String? = null
}

@FieldOrder("label", "mime_types", "mime_type_count", "extensions", "extension_count")
open class UapmdDocumentFilter : Structure() {
    @JvmField var label: String? = null
    @JvmField var mime_types: Pointer? = null
    @JvmField var mime_type_count: Int = 0
    @JvmField var extensions: Pointer? = null
    @JvmField var extension_count: Int = 0
}

@FieldOrder("success", "handle_count", "handles", "error")
open class UapmdDocumentPickResult : Structure() {
    @JvmField var success: Byte = 0
    @JvmField var handle_count: Int = 0
    @JvmField var handles: Pointer? = null
    @JvmField var error: String? = null

    class ByValue : UapmdDocumentPickResult(), Structure.ByValue
}

@FieldOrder("success", "error")
open class UapmdDocumentIoResult : Structure() {
    @JvmField var success: Byte = 0
    @JvmField var error: String? = null

    class ByValue : UapmdDocumentIoResult(), Structure.ByValue
}

@FieldOrder(
    "user_data",
    "slow_scan_started", "bundle_scan_started", "bundle_scan_completed",
    "slow_scan_completed", "error_occurred", "should_cancel"
)
open class UapmdScanObserver : Structure() {
    @JvmField var user_data: Pointer? = null
    @JvmField var slow_scan_started: ScanStartedCb? = null
    @JvmField var bundle_scan_started: BundleScanCb? = null
    @JvmField var bundle_scan_completed: BundleScanCb? = null
    @JvmField var slow_scan_completed: ScanCompletedCb? = null
    @JvmField var error_occurred: ScanErrorCb? = null
    @JvmField var should_cancel: ScanShouldCancelCb? = null
}

// ─── Library interface ───────────────────────────────────────────────────────


// ─── Project history structs (uapmd 0.5.6) ───────────────────────────────────

@FieldOrder("status", "error")
open class UapmdUndoResult : Structure() {
    @JvmField var status: Int = 0
    @JvmField var error: String? = null

    class ByVal : UapmdUndoResult(), Structure.ByValue
}

@FieldOrder(
    "busy", "compound_open", "gesture_open", "can_undo", "can_redo", "dirty",
    "compound_description", "undo_description", "redo_description",
    "history_size_in_bytes", "maximum_history_size_in_bytes",
    "current_state_id", "saved_state_id"
)
open class UapmdUndoState : Structure() {
    @JvmField var busy: Byte = 0
    @JvmField var compound_open: Byte = 0
    @JvmField var gesture_open: Byte = 0
    @JvmField var can_undo: Byte = 0
    @JvmField var can_redo: Byte = 0
    @JvmField var dirty: Byte = 0
    @JvmField var compound_description: String? = null
    @JvmField var undo_description: String? = null
    @JvmField var redo_description: String? = null
    @JvmField var history_size_in_bytes: Long = 0L
    @JvmField var maximum_history_size_in_bytes: Long = 0L
    @JvmField var current_state_id: Long = 0L
    @JvmField var saved_state_id: Long = 0L
}

@FieldOrder("track_reference_id", "clip_reference_id")
open class UapmdClipAddress : Structure() {
    @JvmField var track_reference_id: String? = null
    @JvmField var clip_reference_id: String? = null

    class ByVal : UapmdClipAddress(), Structure.ByValue
}

@FieldOrder("track_reference_id", "node_id")
open class UapmdPluginAddress : Structure() {
    @JvmField var track_reference_id: String? = null
    @JvmField var node_id: String? = null

    class ByVal : UapmdPluginAddress(), Structure.ByValue
}

@FieldOrder("id_policy", "insertion_index", "include_plugins", "include_plugin_state", "include_clips")
open class UapmdTrackAttachOptions : Structure() {
    @JvmField var id_policy: Int = 1
    @JvmField var insertion_index: Int = -1
    @JvmField var include_plugins: Byte = 1
    @JvmField var include_plugin_state: Byte = 1
    @JvmField var include_clips: Byte = 1

    class ByVal : UapmdTrackAttachOptions(), Structure.ByValue
}

@FieldOrder("type", "reference_id", "offset")
open class UapmdTimeReference : Structure() {
    @JvmField var type: Int = 0
    @JvmField var reference_id: String? = null
    @JvmField var offset: Double = 0.0

    class ByVal : UapmdTimeReference(), Structure.ByValue
}

@FieldOrder("marker_id", "clip_position_offset", "reference_type", "reference_clip_id", "reference_marker_id", "name")
open class UapmdClipMarker : Structure {
    constructor() : super()
    /** Reads one element of a C array in place. */
    constructor(p: Pointer) : super(p) { read() }

    @JvmField var marker_id: String? = null
    @JvmField var clip_position_offset: Double = 0.0
    @JvmField var reference_type: Int = 0
    @JvmField var reference_clip_id: String? = null
    @JvmField var reference_marker_id: String? = null
    @JvmField var name: String? = null
}

@FieldOrder("clip_position_offset", "speed_ratio", "reference_type", "reference_clip_id", "reference_marker_id")
open class UapmdAudioWarpPoint : Structure {
    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }

    @JvmField var clip_position_offset: Double = 0.0
    @JvmField var speed_ratio: Double = 1.0
    @JvmField var reference_type: Int = 0
    @JvmField var reference_clip_id: String? = null
    @JvmField var reference_marker_id: String? = null
}

@FieldOrder("node_id", "plugin_id", "format", "display_name", "group_index", "state_size", "state")
open class UapmdTrackPluginFragment : Structure() {
    @JvmField var node_id: String? = null
    @JvmField var plugin_id: String? = null
    @JvmField var format: String? = null
    @JvmField var display_name: String? = null
    @JvmField var group_index: Int = -1
    @JvmField var state_size: Int = 0
    @JvmField var state: Pointer? = null
}

@FieldOrder("package_id", "addin_id", "name", "path", "library_path", "built_in", "state", "message")
open class UapmdAddinInfo : Structure() {
    @JvmField var package_id: String? = null
    @JvmField var addin_id: String? = null
    @JvmField var name: String? = null
    @JvmField var path: String? = null
    @JvmField var library_path: String? = null
    @JvmField var built_in: Byte = 0
    @JvmField var state: Int = 0
    @JvmField var message: String? = null
}

// ─── Project history callbacks ───────────────────────────────────────────────

interface UndoCompletionCb : Callback {
    fun invoke(result: UapmdUndoResult.ByVal, userData: Pointer?)
}

interface TrackMutationCb : Callback {
    fun invoke(trackIndex: Int, error: String?, userData: Pointer?)
}

@FieldOrder("api_name", "device_name", "manufacturer", "version", "state_file")
open class UapmdPluginInstanceConfig : Structure() {
    @JvmField var api_name: String? = null
    @JvmField var device_name: String? = null
    @JvmField var manufacturer: String? = null
    @JvmField var version: String? = null
    @JvmField var state_file: String? = null
}

@FieldOrder("instance_id", "plugin_name", "error")
open class UapmdPluginInstanceResult : Structure() {
    @JvmField var instance_id: Int = 0
    @JvmField var plugin_name: String? = null
    @JvmField var error: String? = null

    class ByVal : UapmdPluginInstanceResult(), Structure.ByValue
}

@FieldOrder("success", "error")
open class UapmdAppProjectResult : Structure() {
    @JvmField var success: Byte = 0
    @JvmField var error: String? = null

    class ByVal : UapmdAppProjectResult(), Structure.ByValue
}

@FieldOrder("tick", "word_count", "words")
open class UapmdUmpEvent : Structure {
    constructor() : super()
    /** Reads one element of a C array in place; Structure.useMemory is protected. */
    constructor(p: Pointer) : super(p) { read() }

    @JvmField var tick: Long = 0
    @JvmField var word_count: Int = 0
    @JvmField var words: Pointer? = null
}

@FieldOrder("success", "error", "event_count", "events")
open class UapmdUmpEventsResult : Structure() {
    @JvmField var success: Byte = 0
    @JvmField var error: String? = null
    @JvmField var event_count: Int = 0
    @JvmField var events: Pointer? = null

    class ByVal : UapmdUmpEventsResult(), Structure.ByValue
}

@FieldOrder("type", "node_id", "instance_id", "bus_index")
open class UapmdGraphEndpoint : Structure {
    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }
    @JvmField var type: Int = 0
    @JvmField var node_id: String? = null
    @JvmField var instance_id: Int = 0
    @JvmField var bus_index: Int = 0
}

/* Each struct carries its own @FieldOrder — an annotation applies to the class
 * that immediately follows it, so a struct inserted in between silently steals
 * the previous one's field names and JNA then rejects it at runtime. */
@FieldOrder("name", "role", "enabled", "channel_layout_name", "channel_count")
open class UapmdGraphAudioBus : Structure {
    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }
    @JvmField var name: String? = null
    @JvmField var role: Int = 0
    @JvmField var enabled: Byte = 0
    @JvmField var channel_layout_name: String? = null
    @JvmField var channel_count: Int = 0
}

@FieldOrder(
    "node_id", "node_type", "display_name", "instance_id", "bypassed",
    "latency_in_samples", "tail_length_in_seconds", "has_audio_buses",
    "has_event_inputs", "has_event_outputs", "audio_bus_offset",
    "audio_input_bus_count", "audio_output_bus_count",
    "main_input_bus_index", "main_output_bus_index"
)
open class UapmdGraphNode : Structure {
    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }
    @JvmField var node_id: String? = null
    @JvmField var node_type: String? = null
    @JvmField var display_name: String? = null
    @JvmField var instance_id: Int = 0
    @JvmField var bypassed: Byte = 0
    @JvmField var latency_in_samples: Int = 0
    @JvmField var tail_length_in_seconds: Double = 0.0
    @JvmField var has_audio_buses: Byte = 0
    @JvmField var has_event_inputs: Byte = 0
    @JvmField var has_event_outputs: Byte = 0
    @JvmField var audio_bus_offset: Int = 0
    @JvmField var audio_input_bus_count: Int = 0
    @JvmField var audio_output_bus_count: Int = 0
    @JvmField var main_input_bus_index: Int = 0
    @JvmField var main_output_bus_index: Int = 0
}

@FieldOrder(
    "success", "error", "count", "nodes", "audio_bus_count", "audio_buses",
    "graph_audio_input_bus_count", "graph_audio_output_bus_count",
    "graph_event_input_bus_count", "graph_event_output_bus_count"
)
open class UapmdGraphNodesResult : Structure() {
    @JvmField var success: Byte = 0
    @JvmField var error: String? = null
    @JvmField var count: Int = 0
    @JvmField var nodes: Pointer? = null
    @JvmField var audio_bus_count: Int = 0
    @JvmField var audio_buses: Pointer? = null
    @JvmField var graph_audio_input_bus_count: Int = 0
    @JvmField var graph_audio_output_bus_count: Int = 0
    @JvmField var graph_event_input_bus_count: Int = 0
    @JvmField var graph_event_output_bus_count: Int = 0

    class ByVal : UapmdGraphNodesResult(), Structure.ByValue
}

@FieldOrder("id", "bus_type", "source", "target")
open class UapmdGraphConnection : Structure {
    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }
    @JvmField var id: Long = 0
    @JvmField var bus_type: Int = 0
    @JvmField var source: UapmdGraphEndpoint = UapmdGraphEndpoint()
    @JvmField var target: UapmdGraphEndpoint = UapmdGraphEndpoint()
}

@FieldOrder("success", "error", "count", "connections")
open class UapmdGraphConnectionsResult : Structure() {
    @JvmField var success: Byte = 0
    @JvmField var error: String? = null
    @JvmField var count: Int = 0
    @JvmField var connections: Pointer? = null

    class ByVal : UapmdGraphConnectionsResult(), Structure.ByValue
}

@FieldOrder("success", "error", "marker_count", "markers", "audio_warp_count", "audio_warps")
open class UapmdClipAudioEventsResult : Structure() {
    @JvmField var success: Byte = 0
    @JvmField var error: String? = null
    @JvmField var marker_count: Int = 0
    @JvmField var markers: Pointer? = null
    @JvmField var audio_warp_count: Int = 0
    @JvmField var audio_warps: Pointer? = null

    class ByVal : UapmdClipAudioEventsResult(), Structure.ByValue
}

@FieldOrder("success", "error")
open class UapmdOpResult : Structure() {
    @JvmField var success: Byte = 0
    @JvmField var error: String? = null

    class ByVal : UapmdOpResult(), Structure.ByValue
}

interface ProjectSaveCb : Callback {
    fun invoke(result: UapmdAppProjectResult.ByVal, userData: Pointer?)
}

interface InstanceCreatedCb : Callback {
    fun invoke(result: UapmdPluginInstanceResult.ByVal, userData: Pointer?)
}

interface TrackClearCb : Callback {
    fun invoke(error: String?, userData: Pointer?)
}

interface HistoryMutationCb : Callback {
    fun invoke(error: String?, userData: Pointer?)
}

interface TrackFragmentCb : Callback {
    fun invoke(fragment: Pointer?, error: String?, userData: Pointer?)
}

interface UapmdLibrary : Library {

    companion object {
        const val LIB_NAME = "uapmd-c-api"

        val INSTANCE: UapmdLibrary by lazy {
            JNE.loadLibrary(LIB_NAME)
            @Suppress("UNCHECKED_CAST")
            Native.load(LIB_NAME, UapmdLibrary::class.java) as UapmdLibrary
        }
    }

    // ── Timeline position helpers ────────────────────────────────────────────

    fun uapmd_position_from_samples(samples: Long, sampleRate: Int, tempo: Double): UapmdTimelinePosition.ByVal
    fun uapmd_position_from_beats(beats: Double, sampleRate: Int, tempo: Double): UapmdTimelinePosition.ByVal
    fun uapmd_position_from_seconds(seconds: Double, sampleRate: Int, tempo: Double): UapmdTimelinePosition.ByVal
    fun uapmd_position_to_seconds(pos: UapmdTimelinePosition.ByVal, sampleRate: Int): Double

    // ── AudioFileReader ──────────────────────────────────────────────────────

    interface MidiTracksImportCb : com.sun.jna.Callback {
        fun invoke(success: Boolean, error: String?, importedTrackCount: Int, userData: Pointer?)
    }
    fun uapmd_app_import_midi_tracks_from_file(app: Pointer, filepath: String, userData: Pointer?, cb: MidiTracksImportCb)
    fun uapmd_engine_track_freeze_policy(engine: Pointer, trackIndex: Int): Int
    fun uapmd_engine_track_freeze_state(engine: Pointer, trackIndex: Int): Int
    fun uapmd_engine_is_track_busy(engine: Pointer, trackIndex: Int): Boolean
    fun uapmd_tt_channel_count(tt: Pointer): Int
    fun uapmd_audio_file_reader_create(filepath: String): Pointer?
    fun uapmd_audio_file_reader_create_silent(numFrames: Long, numChannels: Int, sampleRate: Int): Pointer?
    fun uapmd_audio_file_reader_destroy(reader: Pointer?)
    fun uapmd_audio_file_reader_get_properties(reader: Pointer?, out: UapmdAudioFileProperties): Boolean
    fun uapmd_audio_file_reader_read_frames(
        reader: Pointer?,
        startFrame: Long,
        framesToRead: Long,
        dest: Array<Pointer?>,
        numChannels: Int
    )

    // ── DocumentProvider ────────────────────────────────────────────────────

    fun uapmd_document_provider_create(): Pointer?
    fun uapmd_document_provider_destroy(provider: Pointer?)
    fun uapmd_document_provider_pick_open(
        provider: Pointer?,
        filters: Array<UapmdDocumentFilter>,
        filterCount: Int,
        allowMultiple: Boolean,
        userData: Pointer?,
        callback: DocumentPickCb?
    )
    fun uapmd_document_provider_resolve_to_path(
        provider: Pointer?,
        handle: UapmdDocumentHandle,
        userData: Pointer?,
        callback: DocumentPathCb?
    )
    fun uapmd_document_provider_tick(provider: Pointer?)
    fun uapmd_prepare_project_load(filePath: String): Pointer?
    fun uapmd_prepared_project_success(prepared: Pointer?): Boolean
    fun uapmd_prepared_project_path(prepared: Pointer?, buf: ByteArray?, bufSize: Long): Long
    fun uapmd_prepared_project_error(prepared: Pointer?, buf: ByteArray?, bufSize: Long): Long
    fun uapmd_prepared_project_destroy(prepared: Pointer?)

    // ── PluginInstance ───────────────────────────────────────────────────────

    fun uapmd_instance_display_name(inst: Pointer?, buf: ByteArray?, bufSize: Long): Long
    fun uapmd_instance_format_name(inst: Pointer?, buf: ByteArray?, bufSize: Long): Long
    fun uapmd_instance_plugin_id(inst: Pointer?, buf: ByteArray?, bufSize: Long): Long
    fun uapmd_instance_get_aap_ui_host_details(inst: Pointer?, out: UapmdAapUiHostDetails): Boolean

    fun uapmd_instance_get_bypassed(inst: Pointer?): Boolean
    fun uapmd_instance_set_bypassed(inst: Pointer?, value: Boolean)

    fun uapmd_instance_start_processing(inst: Pointer?): Int
    fun uapmd_instance_stop_processing(inst: Pointer?): Int

    fun uapmd_instance_latency_in_samples(inst: Pointer?): Int
    fun uapmd_instance_tail_length_in_seconds(inst: Pointer?): Double
    fun uapmd_instance_requires_replacing_process(inst: Pointer?): Boolean

    fun uapmd_instance_parameter_count(inst: Pointer?): Int
    fun uapmd_instance_get_parameter_metadata(inst: Pointer?, listIndex: Int, out: UapmdParameterMetadata): Boolean
    fun uapmd_instance_get_parameter_value(inst: Pointer?, index: Int): Double
    fun uapmd_instance_set_parameter_value(inst: Pointer?, index: Int, value: Double)
    fun uapmd_instance_get_parameter_value_string(inst: Pointer?, index: Int, value: Double, buf: ByteArray?, bufSize: Long): Long

    fun uapmd_instance_set_per_note_controller_value(inst: Pointer?, note: Byte, index: Byte, value: Double)
    fun uapmd_instance_get_per_note_controller_value_string(inst: Pointer?, note: Byte, index: Byte, value: Double, buf: ByteArray?, bufSize: Long): Long

    fun uapmd_instance_preset_count(inst: Pointer?): Int
    fun uapmd_instance_get_preset_metadata(inst: Pointer?, listIndex: Int, out: UapmdPresetMetadata): Boolean
    fun uapmd_instance_load_preset(inst: Pointer?, presetIndex: Int)

    fun uapmd_instance_save_state_sync(inst: Pointer?, buf: Pointer?, bufSize: Long): Long
    fun uapmd_instance_load_state_sync(inst: Pointer?, data: ByteArray, dataSize: Long)

    fun uapmd_instance_request_state(
        inst: Pointer?,
        ctx: Int,
        includeUiState: Boolean,
        userData: Pointer?,
        callback: RequestStateCb?
    )
    fun uapmd_instance_load_state(
        inst: Pointer?,
        state: ByteArray,
        stateSize: Long,
        ctx: Int,
        includeUiState: Boolean,
        userData: Pointer?,
        callback: LoadStateCb?
    )

    fun uapmd_instance_has_ui_support(inst: Pointer?): Boolean
    fun uapmd_instance_get_ui_capabilities(inst: Pointer?, out: UapmdUiCapabilities)
    fun uapmd_instance_create_ui_presentation(
        inst: Pointer?,
        request: UapmdUiPresentationRequest,
        resizeUserData: Pointer?,
        resizeHandler: UiResizeHandler?
    ): Pointer?
    fun uapmd_instance_create_ui(
        inst: Pointer?,
        isFloating: Boolean,
        parentHandle: Pointer?,
        resizeUserData: Pointer?,
        resizeHandler: UiResizeHandler?
    ): Boolean
    fun uapmd_instance_destroy_ui(inst: Pointer?)
    fun uapmd_instance_show_ui(inst: Pointer?): Boolean
    fun uapmd_instance_hide_ui(inst: Pointer?)
    fun uapmd_instance_is_ui_visible(inst: Pointer?): Boolean
    fun uapmd_instance_set_ui_size(inst: Pointer?, width: Int, height: Int): Boolean
    fun uapmd_instance_get_ui_size(inst: Pointer?, width: IntByReference, height: IntByReference): Boolean
    fun uapmd_instance_can_ui_resize(inst: Pointer?): Boolean
    fun uapmd_ui_presentation_destroy(presentation: Pointer?)
    fun uapmd_ui_presentation_show(presentation: Pointer?): Boolean
    fun uapmd_ui_presentation_hide(presentation: Pointer?)
    fun uapmd_ui_presentation_is_visible(presentation: Pointer?): Boolean
    fun uapmd_ui_presentation_set_size(presentation: Pointer?, width: Int, height: Int): Boolean
    fun uapmd_ui_presentation_get_size(presentation: Pointer?, width: IntByReference, height: IntByReference): Boolean
    fun uapmd_ui_presentation_can_resize(presentation: Pointer?): Boolean

    // ── PluginHost ───────────────────────────────────────────────────────────

    fun uapmd_plugin_host_create(): Pointer?
    fun uapmd_plugin_host_destroy(host: Pointer?)

    fun uapmd_plugin_host_catalog_entry_count(host: Pointer?): Int
    fun uapmd_plugin_host_get_catalog_entry(
        host: Pointer?, index: Int,
        formatBuf: ByteArray, formatBufSize: Long,
        pluginIdBuf: ByteArray, pluginIdBufSize: Long,
        displayNameBuf: ByteArray, displayNameBufSize: Long,
        vendorBuf: ByteArray, vendorBufSize: Long
    ): Boolean
    fun uapmd_plugin_host_save_catalog(host: Pointer?, path: String)
    fun uapmd_plugin_host_perform_scanning(host: Pointer?, rescan: Boolean)
    fun uapmd_plugin_host_reload_catalog_from_cache(host: Pointer?)

    fun uapmd_plugin_host_create_instance(
        host: Pointer?,
        sampleRate: Int,
        bufferSize: Int,
        mainInputChannels: Int,
        mainOutputChannels: Int,
        offlineMode: Boolean,
        format: String,
        pluginId: String,
        userData: Pointer?,
        callback: CreateInstanceCallback?
    )
    fun uapmd_plugin_host_delete_instance(host: Pointer?, instanceId: Int)
    fun uapmd_plugin_host_get_instance(host: Pointer?, instanceId: Int): Pointer?

    fun uapmd_plugin_host_instance_id_count(host: Pointer?): Int
    fun uapmd_plugin_host_get_instance_ids(host: Pointer?, out: IntArray, outCount: Int): Boolean

    // ── PluginNode ───────────────────────────────────────────────────────────

    fun uapmd_node_instance_id(node: Pointer?): Int
    fun uapmd_node_instance(node: Pointer?): Pointer?
    fun uapmd_node_schedule_events(node: Pointer?, timestamp: Long, events: ByteArray, size: Long): Boolean
    fun uapmd_node_send_all_notes_off(node: Pointer?)

    // ── PluginGraph ──────────────────────────────────────────────────────────

    fun uapmd_graph_create(eventBufferSizeInBytes: Long): Pointer?
    fun uapmd_graph_destroy(graph: Pointer?)

    fun uapmd_graph_append_node(
        graph: Pointer?,
        instanceId: Int,
        instance: Pointer?,
        deleteUserData: Pointer?,
        onDelete: GraphDeleteCb?
    ): Int
    fun uapmd_graph_remove_node(graph: Pointer?, instanceId: Int): Boolean

    fun uapmd_graph_plugin_count(graph: Pointer?): Int
    fun uapmd_graph_get_plugin_node(graph: Pointer?, instanceId: Int): Pointer?

    fun uapmd_graph_set_event_output_callback(graph: Pointer?, userData: Pointer?, callback: EventOutputCb?)

    fun uapmd_graph_output_bus_count(graph: Pointer?): Int
    fun uapmd_graph_output_latency_in_samples(graph: Pointer?, busIndex: Int): Int
    fun uapmd_graph_output_tail_length_in_seconds(graph: Pointer?, busIndex: Int): Double
    fun uapmd_graph_render_lead_in_samples(graph: Pointer?): Int
    fun uapmd_graph_main_output_latency_in_samples(graph: Pointer?): Int
    fun uapmd_graph_main_output_tail_length_in_seconds(graph: Pointer?): Double

    // ── MidiIO ───────────────────────────────────────────────────────────────

    fun uapmd_midi_io_add_input_handler(io: Pointer?, receiver: UmpReceiverCallback?, userData: Pointer?)
    fun uapmd_midi_io_remove_input_handler(io: Pointer?, receiver: UmpReceiverCallback?)
    fun uapmd_midi_io_send(io: Pointer?, messages: IntArray, length: Long, timestamp: Long)

    // ── FunctionBlock ────────────────────────────────────────────────────────

    fun uapmd_fb_midi_io(fb: Pointer?): Pointer?
    fun uapmd_fb_get_group(fb: Pointer?): Byte
    fun uapmd_fb_set_group(fb: Pointer?, groupId: Byte)
    fun uapmd_fb_detach_output_mapper(fb: Pointer?)
    fun uapmd_fb_initialize(fb: Pointer?)

    // ── FunctionBlockManager ─────────────────────────────────────────────────

    fun uapmd_fbm_count(mgr: Pointer?): Long
    fun uapmd_fbm_create_device(mgr: Pointer?): Long
    fun uapmd_fbm_get_device_by_index(mgr: Pointer?, index: Int): Pointer?
    fun uapmd_fbm_get_device_for_instance(mgr: Pointer?, instanceId: Int): Pointer?
    fun uapmd_fbm_delete_empty_devices(mgr: Pointer?)
    fun uapmd_fbm_detach_all_output_mappers(mgr: Pointer?)
    fun uapmd_fbm_clear_all_devices(mgr: Pointer?)

    // ── UmpInputMapper ───────────────────────────────────────────────────────

    fun uapmd_ump_in_set_parameter_value(m: Pointer?, index: Int, value: Double)
    fun uapmd_ump_in_get_parameter_value(m: Pointer?, index: Int): Double
    fun uapmd_ump_in_set_per_note_controller_value(m: Pointer?, note: Byte, index: Byte, value: Double)
    fun uapmd_ump_in_load_preset(m: Pointer?, index: Int)

    // ── UmpOutputMapper ──────────────────────────────────────────────────────

    fun uapmd_ump_out_send_parameter_value(m: Pointer?, index: Int, value: Double)
    fun uapmd_ump_out_send_per_note_controller_value(m: Pointer?, note: Byte, index: Byte, value: Double)
    fun uapmd_ump_out_send_preset_index_change(m: Pointer?, index: Int)

    // ── SequencerEngine ──────────────────────────────────────────────────────

    fun uapmd_engine_create(sampleRate: Int, audioBufferSize: Int, umpBufferSize: Int): Pointer?
    fun uapmd_engine_destroy(engine: Pointer?)

    fun uapmd_engine_enqueue_ump(engine: Pointer?, instanceId: Int, ump: IntArray, sizeInBytes: Long, timestamp: Long)

    fun uapmd_engine_plugin_host(engine: Pointer?): Pointer?
    fun uapmd_engine_get_plugin_instance(engine: Pointer?, instanceId: Int): Pointer?
    fun uapmd_engine_function_block_manager(engine: Pointer?): Pointer?

    fun uapmd_engine_track_count(engine: Pointer?): Int
    fun uapmd_engine_get_track(engine: Pointer?, index: Int): Pointer?
    fun uapmd_engine_master_track(engine: Pointer?): Pointer?
    fun uapmd_engine_add_empty_track(engine: Pointer?): Int

    fun uapmd_engine_add_plugin_to_track(
        engine: Pointer?,
        trackIndex: Int,
        format: String,
        pluginId: String,
        userData: Pointer?,
        callback: AddPluginCb?
    )

    fun uapmd_engine_remove_plugin_instance(engine: Pointer?, instanceId: Int): Boolean
    fun uapmd_engine_remove_track(engine: Pointer?, trackIndex: Int): Boolean
    fun uapmd_engine_cleanup_empty_tracks(engine: Pointer?)

    fun uapmd_engine_find_track_for_instance(engine: Pointer?, instanceId: Int): Int
    fun uapmd_engine_get_instance_group(engine: Pointer?, instanceId: Int): Byte
    fun uapmd_engine_set_instance_group(engine: Pointer?, instanceId: Int, group: Byte): Boolean

    fun uapmd_engine_track_latency(engine: Pointer?, trackIndex: Int): Int
    fun uapmd_engine_master_track_latency(engine: Pointer?): Int
    fun uapmd_engine_track_render_lead(engine: Pointer?, trackIndex: Int): Int
    fun uapmd_engine_master_track_render_lead(engine: Pointer?): Int

    fun uapmd_engine_set_default_channels(engine: Pointer?, inputChannels: Int, outputChannels: Int)
    fun uapmd_engine_set_sample_rate(engine: Pointer?, sampleRate: Int)
    fun uapmd_engine_get_offline_rendering(engine: Pointer?): Boolean
    fun uapmd_engine_set_offline_rendering(engine: Pointer?, enabled: Boolean)
    fun uapmd_engine_set_active(engine: Pointer?, active: Boolean)
    fun uapmd_engine_set_external_pump(engine: Pointer?, enabled: Boolean)

    fun uapmd_engine_is_playback_active(engine: Pointer?): Boolean
    fun uapmd_engine_get_playback_position(engine: Pointer?): Long
    fun uapmd_engine_set_playback_position(engine: Pointer?, samples: Long)
    fun uapmd_engine_render_playback_position(engine: Pointer?): Long
    fun uapmd_engine_start_playback(engine: Pointer?)
    fun uapmd_engine_stop_playback(engine: Pointer?)
    fun uapmd_engine_pause_playback(engine: Pointer?)
    fun uapmd_engine_resume_playback(engine: Pointer?)

    fun uapmd_engine_send_note_on(engine: Pointer?, instanceId: Int, note: Int)
    fun uapmd_engine_send_note_off(engine: Pointer?, instanceId: Int, note: Int)
    fun uapmd_engine_send_pitch_bend(engine: Pointer?, instanceId: Int, value: Float)
    fun uapmd_engine_send_channel_pressure(engine: Pointer?, instanceId: Int, pressure: Float)
    fun uapmd_engine_set_parameter_value(engine: Pointer?, instanceId: Int, index: Int, value: Double)

    fun uapmd_engine_get_input_spectrum(engine: Pointer?, outSpectrum: FloatArray, numBars: Int)
    fun uapmd_engine_get_output_spectrum(engine: Pointer?, outSpectrum: FloatArray, numBars: Int)

    fun uapmd_engine_timeline(engine: Pointer?): Pointer?

    // ── SequencerTrack ───────────────────────────────────────────────────────

    fun uapmd_track_graph(track: Pointer?): Pointer?
    fun uapmd_track_latency_in_samples(track: Pointer?): Int
    fun uapmd_track_render_lead_in_samples(track: Pointer?): Int
    fun uapmd_track_tail_length_in_seconds(track: Pointer?): Double
    fun uapmd_engine_midi_recorder(engine: Pointer?): Pointer?
    fun uapmd_midi_recorder_start(rec: Pointer?, trackReferenceId: String?, clipId: Int, startSample: Long): Boolean
    fun uapmd_midi_recorder_stop(rec: Pointer?)
    fun uapmd_midi_recorder_cancel(rec: Pointer?)
    fun uapmd_midi_recorder_is_recording(rec: Pointer?): Boolean
    fun uapmd_track_get_gain(track: Pointer?): Double
    fun uapmd_track_get_muted(track: Pointer?): Boolean
    fun uapmd_track_get_solo(track: Pointer?): Boolean
    fun uapmd_track_get_bypassed(track: Pointer?): Boolean
    fun uapmd_track_get_frozen(track: Pointer?): Boolean
    fun uapmd_track_set_bypassed(track: Pointer?, value: Boolean)
    fun uapmd_track_set_frozen(track: Pointer?, value: Boolean)
    fun uapmd_track_ordered_instance_id_count(track: Pointer?): Int
    fun uapmd_track_get_ordered_instance_ids(track: Pointer?, out: IntArray, outCount: Int): Boolean
    fun uapmd_track_set_instance_group(track: Pointer?, instanceId: Int, group: Byte)
    fun uapmd_track_get_instance_group(track: Pointer?, instanceId: Int): Byte
    fun uapmd_track_find_available_group(track: Pointer?): Byte
    fun uapmd_track_remove_instance(track: Pointer?, instanceId: Int)

    // ── TimelineFacade ───────────────────────────────────────────────────────

    fun uapmd_tl_get_state(tl: Pointer?, out: UapmdTimelineState): Boolean
    fun uapmd_tl_set_tempo(tl: Pointer?, tempo: Double)
    fun uapmd_tl_set_time_signature(tl: Pointer?, numerator: Int, denominator: Int)
    fun uapmd_tl_set_loop(tl: Pointer?, enabled: Boolean, start: UapmdTimelinePosition.ByVal, end: UapmdTimelinePosition.ByVal)

    fun uapmd_tl_track_count(tl: Pointer?): Int
    fun uapmd_tl_get_track(tl: Pointer?, index: Int): Pointer?
    fun uapmd_tl_master_timeline_track(tl: Pointer?): Pointer?

    fun uapmd_tl_add_audio_clip(
        tl: Pointer?,
        trackIndex: Int,
        position: UapmdTimelinePosition.ByVal,
        reader: Pointer?,
        filepath: String?
    ): UapmdClipAddResult.ByVal

    fun uapmd_tl_add_midi_clip_from_file(
        tl: Pointer?,
        trackIndex: Int,
        position: UapmdTimelinePosition.ByVal,
        filepath: String?,
        nrpnToParameterMapping: Boolean
    ): UapmdClipAddResult.ByVal

    fun uapmd_tl_remove_clip(tl: Pointer?, trackIndex: Int, clipId: Int): Boolean

    fun uapmd_tl_load_project(tl: Pointer?, filePath: String?): UapmdProjectResult.ByVal
    fun uapmd_tl_calculate_content_bounds(tl: Pointer?): UapmdContentBounds.ByVal

    fun uapmd_tl_get_clip_midi_notes(tl: Pointer?, trackIndex: Int, clipId: Int,
                                      outNotes: UapmdMidiNote?, maxNotes: Int,
                                      outMinNote: com.sun.jna.ptr.IntByReference?, outMaxNote: com.sun.jna.ptr.IntByReference?): Int
    fun uapmd_tl_set_timeline_changed_callback(tl: Pointer?, cb: TimelineChangedCb?, userData: Pointer?)

    // ── TimelineTrack / ClipManager ──────────────────────────────────────────

    fun uapmd_tt_clip_manager(tt: Pointer?): Pointer?
    fun uapmd_cm_clip_count(cm: Pointer?): Long   // size_t
    fun uapmd_cm_get_all_clips(cm: Pointer?, out: UapmdClipData, outCount: Int): Int

    // ── AudioIODeviceManager ─────────────────────────────────────────────────

    fun uapmd_audio_device_mgr_instance(driverName: String?): Pointer?
    fun uapmd_audio_device_mgr_device_count(mgr: Pointer?): Int
    fun uapmd_audio_device_mgr_get_device_info(mgr: Pointer?, index: Int, out: UapmdAudioDeviceInfo): Boolean
    fun uapmd_audio_device_mgr_open(
        mgr: Pointer?,
        inputDeviceIndex: Int,
        outputDeviceIndex: Int,
        sampleRate: Int,
        bufferSize: Int
    ): Pointer?

    // ── AudioIODevice ────────────────────────────────────────────────────────

    fun uapmd_audio_device_sample_rate(dev: Pointer?): Double
    fun uapmd_audio_device_channels(dev: Pointer?): Int
    fun uapmd_audio_device_input_channels(dev: Pointer?): Int
    fun uapmd_audio_device_output_channels(dev: Pointer?): Int
    fun uapmd_audio_device_start(dev: Pointer?): Int
    fun uapmd_audio_device_stop(dev: Pointer?): Int
    fun uapmd_audio_device_is_playing(dev: Pointer?): Boolean

    // ── MidiIODevice ─────────────────────────────────────────────────────────

    fun uapmd_midi_device_instance(driverName: String?): Pointer?

    // ── DeviceIODispatcher ───────────────────────────────────────────────────

    fun uapmd_default_device_io_dispatcher(): Pointer?
    fun uapmd_dispatcher_start(disp: Pointer?): Int
    fun uapmd_dispatcher_stop(disp: Pointer?): Int
    fun uapmd_dispatcher_is_playing(disp: Pointer?): Boolean
    fun uapmd_dispatcher_clear_output_buffers(disp: Pointer?)

    // ── RealtimeSequencer ────────────────────────────────────────────────────

    fun uapmd_rt_sequencer_create(
        bufferSize: Int,
        umpBufferSize: Int,
        sampleRate: Int,
        dispatcher: Pointer?
    ): Pointer?
    fun uapmd_rt_sequencer_destroy(seq: Pointer?)
    fun uapmd_rt_sequencer_engine(seq: Pointer?): Pointer?
    fun uapmd_rt_sequencer_start_audio(seq: Pointer?): Int
    fun uapmd_rt_sequencer_stop_audio(seq: Pointer?): Int
    fun uapmd_rt_sequencer_is_audio_playing(seq: Pointer?): Int
    fun uapmd_rt_sequencer_clear_output_buffers(seq: Pointer?)
    fun uapmd_rt_sequencer_sample_rate(seq: Pointer?): Int
    fun uapmd_rt_sequencer_set_sample_rate(seq: Pointer?, newSampleRate: Int): Boolean
    fun uapmd_rt_sequencer_reconfigure_audio_device(
        seq: Pointer?,
        inputDeviceIndex: Int,
        outputDeviceIndex: Int,
        sampleRate: Int,
        bufferSize: Int
    ): Boolean

    // ── Offline Renderer ─────────────────────────────────────────────────────

    fun uapmd_render_offline(
        engine: Pointer?,
        settings: UapmdOfflineRenderSettings,
        userData: Pointer?,
        progressCb: RenderProgressCb?,
        cancelCb: RenderShouldCancelCb?
    ): UapmdOfflineRenderResult.ByVal

    // ── ScanTool ─────────────────────────────────────────────────────────────

    fun uapmd_scan_tool_create(): Pointer?
    fun uapmd_scan_tool_destroy(tool: Pointer?)

    fun uapmd_scan_tool_catalog_entry_count(tool: Pointer?): Int
    fun uapmd_scan_tool_format_count(tool: Pointer?): Int
    fun uapmd_scan_tool_get_format_name(tool: Pointer?, index: Int, buf: ByteArray?, bufSize: Long): Long

    fun uapmd_scan_tool_get_cache_file(tool: Pointer?, buf: ByteArray?, bufSize: Long): Long
    fun uapmd_scan_tool_set_cache_file(tool: Pointer?, path: String)
    fun uapmd_scan_tool_save_cache(tool: Pointer?)
    fun uapmd_scan_tool_save_cache_to(tool: Pointer?, path: String)

    fun uapmd_scan_tool_perform_scanning(tool: Pointer?, requireFastScanning: Boolean, observer: UapmdScanObserver?)

    fun uapmd_scan_tool_blocklist_count(tool: Pointer?): Int
    fun uapmd_scan_tool_get_blocklist_entry(tool: Pointer?, index: Int, out: UapmdBlocklistEntry): Boolean
    fun uapmd_scan_tool_flush_blocklist(tool: Pointer?)
    fun uapmd_scan_tool_unblock_bundle(tool: Pointer?, entryId: String): Boolean
    fun uapmd_scan_tool_clear_blocklist(tool: Pointer?)
    fun uapmd_scan_tool_add_to_blocklist(tool: Pointer?, formatName: String, pluginId: String, reason: String)

    fun uapmd_scan_tool_last_scan_error(tool: Pointer?, buf: ByteArray?, bufSize: Long): Long

    // ── PluginInstancing ─────────────────────────────────────────────────────

    fun uapmd_instancing_create(tool: Pointer?, format: String, pluginId: String): Pointer?
    fun uapmd_instancing_destroy(inst: Pointer?)
    fun uapmd_instancing_make_alive(inst: Pointer?, userData: Pointer?, callback: InstancingCb?)
    fun uapmd_instancing_state(inst: Pointer?): Int

    // ── FormatManager ────────────────────────────────────────────────────────

    fun uapmd_format_manager_create(): Pointer?
    fun uapmd_format_manager_destroy(mgr: Pointer?)
    fun uapmd_format_manager_format_count(mgr: Pointer?): Int
    fun uapmd_format_manager_get_format_name(mgr: Pointer?, index: Int, buf: ByteArray?, bufSize: Long): Long

    // ── Custom EventLoop ─────────────────────────────────────────────────────

    fun uapmd_set_event_loop(
        userData: Pointer?,
        onInitialize: EventLoopInitializeCb?,
        isMainThread: EventLoopIsMainThreadCb,
        enqueueTask: EventLoopEnqueueCb
    )

    // ══ Project history: ProjectUndoEngine (uapmd 0.5.6) ═════════════════════

    fun uapmd_undo_engine_get_state(eng: Pointer?, out: UapmdUndoState): Boolean
    fun uapmd_undo_engine_undo(eng: Pointer?, userData: Pointer?, callback: UndoCompletionCb?)
    fun uapmd_undo_engine_redo(eng: Pointer?, userData: Pointer?, callback: UndoCompletionCb?)
    fun uapmd_undo_engine_begin_compound(eng: Pointer?, description: String?, origin: Int): UapmdUndoResult.ByVal
    fun uapmd_undo_engine_end_compound(eng: Pointer?, userData: Pointer?, callback: UndoCompletionCb?)
    fun uapmd_undo_engine_cancel_compound(eng: Pointer?, userData: Pointer?, callback: UndoCompletionCb?)
    fun uapmd_undo_engine_begin_gesture(eng: Pointer?, description: String?, origin: Int): UapmdUndoResult.ByVal
    fun uapmd_undo_engine_end_gesture(eng: Pointer?, userData: Pointer?, callback: UndoCompletionCb?)
    fun uapmd_undo_engine_cancel_gesture(eng: Pointer?, userData: Pointer?, callback: UndoCompletionCb?)
    fun uapmd_undo_engine_clear(eng: Pointer?, markCurrentStateSaved: Boolean): Boolean
    fun uapmd_undo_engine_mark_saved(eng: Pointer?): Boolean
    fun uapmd_undo_engine_mark_state_saved(eng: Pointer?, stateId: Long): Boolean
    fun uapmd_undo_engine_set_maximum_history_size(eng: Pointer?, bytes: Long): Boolean
    fun uapmd_undo_engine_shutdown(eng: Pointer?)

    // ══ Project history: ProjectCommandManager ══════════════════════════════

    fun uapmd_command_manager_get_state(cm: Pointer?, out: UapmdUndoState): Boolean
    fun uapmd_command_manager_history(cm: Pointer?): Pointer?
    fun uapmd_command_manager_undo(cm: Pointer?, userData: Pointer?, callback: UndoCompletionCb?)
    fun uapmd_command_manager_redo(cm: Pointer?, userData: Pointer?, callback: UndoCompletionCb?)
    fun uapmd_command_manager_begin_step(cm: Pointer?, description: String?, origin: Int): UapmdUndoResult.ByVal
    fun uapmd_command_manager_end_step(cm: Pointer?, userData: Pointer?, callback: UndoCompletionCb?)
    fun uapmd_command_manager_cancel_step(cm: Pointer?, userData: Pointer?, callback: UndoCompletionCb?)
    fun uapmd_command_manager_begin_gesture(cm: Pointer?, description: String?, origin: Int): UapmdUndoResult.ByVal
    fun uapmd_command_manager_end_gesture(cm: Pointer?, userData: Pointer?, callback: UndoCompletionCb?)
    fun uapmd_command_manager_cancel_gesture(cm: Pointer?, userData: Pointer?, callback: UndoCompletionCb?)
    fun uapmd_command_manager_shutdown(cm: Pointer?)

    // ══ Project history: ProjectCommands ════════════════════════════════════

    fun uapmd_commands_history(cmd: Pointer?): Pointer?
    fun uapmd_commands_set_clip_enabled(cmd: Pointer?, trackIndex: Int, clipId: Int, enabled: Boolean, origin: Int): Boolean
    fun uapmd_commands_set_clip_anchor(cmd: Pointer?, trackIndex: Int, clipId: Int, anchor: UapmdTimeReference.ByVal, origin: Int): Boolean
    fun uapmd_commands_set_clip_gain(cmd: Pointer?, trackIndex: Int, clipId: Int, gain: Double, origin: Int): Boolean
    fun uapmd_commands_set_clip_muted(cmd: Pointer?, trackIndex: Int, clipId: Int, muted: Boolean, origin: Int): Boolean
    fun uapmd_commands_resize_clip(cmd: Pointer?, trackIndex: Int, clipId: Int, newDurationSamples: Long, origin: Int): Boolean
    fun uapmd_commands_set_clip_name(cmd: Pointer?, trackIndex: Int, clipId: Int, name: String?, origin: Int): Boolean
    fun uapmd_commands_set_clip_filepath(cmd: Pointer?, trackIndex: Int, clipId: Int, filepath: String?, origin: Int): Boolean
    fun uapmd_commands_set_clip_needs_file_save(cmd: Pointer?, trackIndex: Int, clipId: Int, needsSave: Boolean, origin: Int): Boolean
    fun uapmd_commands_set_clip_markers(cmd: Pointer?, trackIndex: Int, clipId: Int, markers: UapmdClipMarker?, markerCount: Int, origin: Int): Boolean
    fun uapmd_commands_set_clip_audio_warps(cmd: Pointer?, trackIndex: Int, clipId: Int, warps: UapmdAudioWarpPoint?, warpCount: Int, origin: Int): Boolean
    fun uapmd_commands_set_track_gain(cmd: Pointer?, trackIndex: Int, gain: Double, origin: Int): Boolean
    fun uapmd_commands_set_track_muted(cmd: Pointer?, trackIndex: Int, muted: Boolean, origin: Int): Boolean
    fun uapmd_commands_set_track_solo(cmd: Pointer?, trackIndex: Int, solo: Boolean, origin: Int): Boolean
    fun uapmd_commands_set_track_bypassed(cmd: Pointer?, trackIndex: Int, bypassed: Boolean, origin: Int): Boolean
    fun uapmd_commands_set_track_freeze_policy_enabled(cmd: Pointer?, trackIndex: Int, enabled: Boolean, origin: Int): Boolean
    fun uapmd_commands_set_plugin_bypassed(cmd: Pointer?, instanceId: Int, bypassed: Boolean, origin: Int): Boolean
    fun uapmd_commands_set_plugin_parameter_value(cmd: Pointer?, instanceId: Int, parameterIndex: Int, value: Double, origin: Int): Boolean
    fun uapmd_commands_set_plugin_per_note_controller_value(
        cmd: Pointer?, instanceId: Int, contextType: Int,
        note: Int, channel: Int, group: Int, extra: Int,
        parameterIndex: Int, value: Double, origin: Int
    ): Boolean
    fun uapmd_commands_set_plugin_group(cmd: Pointer?, instanceId: Int, group: Byte, origin: Int): Boolean
    fun uapmd_commands_set_master_track_markers(cmd: Pointer?, markers: UapmdClipMarker?, markerCount: Int, origin: Int): Boolean

    // ══ Project history: ProjectAddressBook ═════════════════════════════════

    fun uapmd_addresses_timeline_track(ab: Pointer?, trackReferenceId: String?): Pointer?
    fun uapmd_addresses_sequencer_track(ab: Pointer?, trackReferenceId: String?): Pointer?
    fun uapmd_addresses_track_index(ab: Pointer?, trackReferenceId: String?): Int
    fun uapmd_addresses_clip_id(ab: Pointer?, address: UapmdClipAddress.ByVal): Int
    fun uapmd_addresses_plugin_instance_id(ab: Pointer?, address: UapmdPluginAddress.ByVal): Int
    fun uapmd_addresses_track_reference_id(ab: Pointer?, trackIndex: Int): String?
    fun uapmd_addresses_clip_address(ab: Pointer?, trackIndex: Int, clipId: Int, out: UapmdClipAddress): Boolean
    fun uapmd_addresses_plugin_address(ab: Pointer?, instanceId: Int, out: UapmdPluginAddress): Boolean

    // ══ Fragments ═══════════════════════════════════════════════════════════

    fun uapmd_clip_fragment_destroy(fragment: Pointer?)
    fun uapmd_clip_fragment_is_midi(fragment: Pointer?): Boolean
    fun uapmd_clip_fragment_get_clip(fragment: Pointer?, out: UapmdClipData): Boolean
    fun uapmd_clip_fragment_ump_event_count(fragment: Pointer?): Int
    fun uapmd_clip_fragment_get_ump_events(fragment: Pointer?, out: IntArray?, outCount: Int): Int
    fun uapmd_clip_fragment_get_ump_tick_timestamps(fragment: Pointer?, out: LongArray?, outCount: Int): Int
    fun uapmd_clip_fragment_extension_state_count(fragment: Pointer?): Int
    fun uapmd_clip_fragment_extension_state_key(fragment: Pointer?, index: Int, buf: ByteArray?, bufSize: Long): Long
    fun uapmd_clip_fragment_extension_state_data(fragment: Pointer?, index: Int, buf: ByteArray?, bufSize: Long): Long

    fun uapmd_track_fragment_destroy(fragment: Pointer?)
    fun uapmd_track_fragment_reference_id(fragment: Pointer?, buf: ByteArray?, bufSize: Long): Long
    fun uapmd_track_fragment_volume(fragment: Pointer?): Double
    fun uapmd_track_fragment_muted(fragment: Pointer?): Boolean
    fun uapmd_track_fragment_solo(fragment: Pointer?): Boolean
    fun uapmd_track_fragment_graph_type(fragment: Pointer?, buf: ByteArray?, bufSize: Long): Long
    fun uapmd_track_fragment_graph_bytes(fragment: Pointer?, buf: ByteArray?, bufSize: Long): Long
    fun uapmd_track_fragment_clip_count(fragment: Pointer?): Int
    fun uapmd_track_fragment_get_clip(fragment: Pointer?, index: Int): Pointer?
    fun uapmd_track_fragment_plugin_count(fragment: Pointer?): Int
    fun uapmd_track_fragment_get_plugin(fragment: Pointer?, index: Int, out: UapmdTrackPluginFragment): Boolean

    // ══ TimelineFacade history accessors and undoable mutations ═════════════

    fun uapmd_tl_undo_engine(tl: Pointer?): Pointer?
    fun uapmd_tl_commands(tl: Pointer?): Pointer?
    fun uapmd_tl_addresses(tl: Pointer?): Pointer?
    fun uapmd_tl_begin_document_transaction(tl: Pointer?)
    fun uapmd_tl_end_document_transaction(tl: Pointer?)
    fun uapmd_tl_remove_clip_with_origin(tl: Pointer?, trackIndex: Int, clipId: Int, origin: Int): Boolean
    fun uapmd_tl_clear_clips_from_track(tl: Pointer?, trackIndex: Int, origin: Int): Boolean
    fun uapmd_tl_clip_enabled(tl: Pointer?, trackIndex: Int, clipId: Int): Boolean
    fun uapmd_tl_replace_midi_clip_content(
        tl: Pointer?, trackIndex: Int, clipId: Int,
        umpEvents: IntArray?, umpEventCount: Int,
        tickTimestamps: LongArray?, tickCount: Int, origin: Int
    ): Boolean
    fun uapmd_tl_replace_audio_clip_content(
        tl: Pointer?, trackIndex: Int, clipId: Int, filepath: String?,
        markers: UapmdClipMarker?, markerCount: Int,
        warps: UapmdAudioWarpPoint?, warpCount: Int,
        masterMarkers: UapmdClipMarker?, masterMarkerCount: Int, origin: Int
    ): Boolean
    fun uapmd_tl_capture_clip_fragment(tl: Pointer?, trackIndex: Int, clipId: Int): Pointer?
    fun uapmd_tl_attach_clip_fragment(tl: Pointer?, trackIndex: Int, fragment: Pointer?, idPolicy: Int): UapmdClipAddResult.ByVal
    fun uapmd_tl_capture_track_fragment(tl: Pointer?, trackIndex: Int, userData: Pointer?, callback: TrackFragmentCb?)
    fun uapmd_tl_attach_track_fragment(tl: Pointer?, fragment: Pointer?, options: UapmdTrackAttachOptions.ByVal, userData: Pointer?, callback: TrackMutationCb?)
    fun uapmd_tl_add_empty_track(tl: Pointer?, origin: Int, userData: Pointer?, callback: TrackMutationCb?)
    fun uapmd_tl_remove_track(tl: Pointer?, trackIndex: Int, origin: Int, userData: Pointer?, callback: TrackMutationCb?)
    fun uapmd_tl_record_track_addition(tl: Pointer?, trackIndex: Int, origin: Int, userData: Pointer?, callback: TrackMutationCb?)
    fun uapmd_tl_set_plugin_state(tl: Pointer?, instanceId: Int, state: ByteArray?, stateSize: Long, origin: Int, userData: Pointer?, callback: UndoCompletionCb?)
    fun uapmd_tl_load_plugin_preset(tl: Pointer?, instanceId: Int, presetIndex: Int, origin: Int, userData: Pointer?, callback: UndoCompletionCb?)
    fun uapmd_tl_record_plugin_instance_addition(tl: Pointer?, instanceId: Int, origin: Int, userData: Pointer?, callback: UndoCompletionCb?)
    fun uapmd_tl_remove_plugin_instance(tl: Pointer?, instanceId: Int, origin: Int, userData: Pointer?, callback: UndoCompletionCb?)
    fun uapmd_tl_has_pending_plugin_mutations(tl: Pointer?): Boolean

    // ══ Engine dirty state and master markers ═══════════════════════════════

    fun uapmd_engine_is_project_dirty(engine: Pointer?): Boolean
    fun uapmd_engine_is_track_dirty(engine: Pointer?, trackIndex: Int): Boolean
    fun uapmd_engine_mark_track_dirty(engine: Pointer?, trackIndex: Int, dirty: Boolean)
    fun uapmd_engine_clear_track_dirty_state(engine: Pointer?)
    fun uapmd_engine_master_marker_count(engine: Pointer?): Int
    fun uapmd_engine_get_master_marker(engine: Pointer?, index: Int, out: UapmdClipMarker): Boolean
    fun uapmd_engine_set_master_markers(engine: Pointer?, markers: UapmdClipMarker?, count: Int)

    // ══ AddinManager ════════════════════════════════════════════════════════

    fun uapmd_addin_manager_create(): Pointer?
    fun uapmd_addin_manager_destroy(mgr: Pointer?)
    fun uapmd_addin_manager_register_extension_point(mgr: Pointer?, path: String?, extensionPoint: Pointer?)
    fun uapmd_engine_register_addin_extension_points(engine: Pointer?, mgr: Pointer?)
    fun uapmd_addin_manager_initialize(mgr: Pointer?)
    fun uapmd_addin_manager_set_enabled(mgr: Pointer?, packageId: String?, addinId: String?, enabled: Boolean): Boolean
    fun uapmd_addin_manager_shutdown(mgr: Pointer?)
    fun uapmd_addin_manager_directory_count(mgr: Pointer?): Int
    fun uapmd_addin_manager_get_directory(mgr: Pointer?, index: Int, buf: ByteArray?, bufSize: Long): Long
    fun uapmd_addin_manager_addin_count(mgr: Pointer?): Int
    fun uapmd_addin_manager_get_addin(mgr: Pointer?, index: Int, out: UapmdAddinInfo): Boolean
    fun uapmd_addin_manager_last_error(mgr: Pointer?, buf: ByteArray?, bufSize: Long): Long
    fun uapmd_addin_supports_dynamic_loading(): Boolean
    fun uapmd_addin_state_name(state: Int): String?

    // ══ AppModel / TransportController ══════════════════════════════════════

    fun uapmd_app_instantiate()
    fun uapmd_app_instance(): Pointer?
    fun uapmd_app_cleanup()

    fun uapmd_app_sequencer(app: Pointer?): Pointer?
    fun uapmd_app_transport(app: Pointer?): Pointer?
    fun uapmd_app_sample_rate(app: Pointer?): Int
    fun uapmd_app_track_count(app: Pointer?): Int

    fun uapmd_app_is_scanning(app: Pointer?): Boolean
    fun uapmd_app_is_audio_engine_enabled(app: Pointer?): Boolean
    fun uapmd_app_set_audio_engine_enabled(app: Pointer?, enabled: Boolean)
    fun uapmd_app_toggle_audio_engine(app: Pointer?)
    fun uapmd_app_update_audio_device_settings(app: Pointer?, sampleRate: Int, bufferSize: Int)
    fun uapmd_app_set_auto_buffer_size_enabled(app: Pointer?, enabled: Boolean)
    fun uapmd_app_auto_buffer_size_enabled(app: Pointer?): Boolean

    fun uapmd_app_notify_ui_ready(app: Pointer?)
    fun uapmd_app_notify_persistent_storage_ready(app: Pointer?)

    fun uapmd_transport_is_playing(tc: Pointer?): Boolean
    fun uapmd_transport_is_paused(tc: Pointer?): Boolean
    fun uapmd_transport_is_recording(tc: Pointer?): Boolean
    fun uapmd_transport_get_volume(tc: Pointer?): Float
    fun uapmd_transport_set_volume(tc: Pointer?, volume: Float)
    fun uapmd_transport_play(tc: Pointer?)
    fun uapmd_transport_stop(tc: Pointer?)
    fun uapmd_transport_pause(tc: Pointer?)
    fun uapmd_transport_resume(tc: Pointer?)
    fun uapmd_transport_record(tc: Pointer?)

    fun uapmd_app_perform_plugin_scanning(app: Pointer?, forceRescan: Boolean, request: Int, remoteTimeoutSeconds: Double, requireFastScanning: Boolean)
    fun uapmd_app_cancel_plugin_scanning(app: Pointer?)
    fun uapmd_app_generate_scan_report(app: Pointer?, buf: ByteArray?, bufSize: Long): Long
    fun uapmd_app_clear_plugin_blocklist(app: Pointer?)
    fun uapmd_app_blocklist_count(app: Pointer?): Int
    fun uapmd_app_refresh_master_tempo_map(app: Pointer?): Double
    fun uapmd_app_master_tempo_point_count(app: Pointer?): Int
    fun uapmd_app_get_master_tempo_point(app: Pointer?, index: Int, out: UapmdTempoPoint): Boolean
    fun uapmd_app_master_time_signature_count(app: Pointer?): Int
    fun uapmd_app_get_master_time_signature(app: Pointer?, index: Int, out: UapmdTimeSignaturePoint): Boolean
    fun uapmd_app_get_blocklist_entry(app: Pointer?, index: Int, out: UapmdBlocklistEntry): Boolean
    fun uapmd_app_unblock_plugin(app: Pointer?, entryId: String): Boolean

    fun uapmd_app_add_track(app: Pointer?, userData: Pointer?, callback: TrackMutationCb?)
    fun uapmd_app_remove_track(app: Pointer?, trackIndex: Int, userData: Pointer?, callback: TrackMutationCb?)
    fun uapmd_app_remove_all_tracks(app: Pointer?, userData: Pointer?, callback: TrackClearCb?)

    fun uapmd_app_timeline_track_count(app: Pointer?): Int
    fun uapmd_app_get_timeline_track(app: Pointer?, index: Int): Pointer?
    fun uapmd_app_master_timeline_track(app: Pointer?): Pointer?
    fun uapmd_app_get_timeline_state(app: Pointer?, out: UapmdTimelineState): Boolean

    fun uapmd_app_get_history_state(app: Pointer?, out: UapmdUndoState): Boolean
    fun uapmd_app_undo(app: Pointer?, userData: Pointer?, callback: HistoryMutationCb?)
    fun uapmd_app_redo(app: Pointer?, userData: Pointer?, callback: HistoryMutationCb?)

    fun uapmd_app_create_plugin_instance(app: Pointer?, format: String?, pluginId: String?, trackIndex: Int, config: UapmdPluginInstanceConfig?, userData: Pointer?, callback: InstanceCreatedCb?)
    fun uapmd_app_remove_plugin_instance(app: Pointer?, instanceId: Int)
    fun uapmd_app_get_instance_group(app: Pointer?, instanceId: Int): Byte
    fun uapmd_app_set_instance_group(app: Pointer?, instanceId: Int, group: Byte): Boolean
    fun uapmd_app_enable_ump_device(app: Pointer?, instanceId: Int, deviceName: String?)
    fun uapmd_app_disable_ump_device(app: Pointer?, instanceId: Int)
    fun uapmd_app_request_show_instance_details(app: Pointer?, instanceId: Int)
    fun uapmd_app_request_show_plugin_ui(app: Pointer?, instanceId: Int)
    fun uapmd_app_hide_plugin_ui(app: Pointer?, instanceId: Int)

    fun uapmd_app_load_project(app: Pointer?, filePath: String?): UapmdAppProjectResult.ByVal
    fun uapmd_app_save_project_sync(app: Pointer?, filePath: String?): UapmdAppProjectResult.ByVal
    fun uapmd_app_save_project(app: Pointer?, filePath: String?, userData: Pointer?, callback: ProjectSaveCb?)
    fun uapmd_app_load_project_from_handle_token(app: Pointer?, token: String?): UapmdAppProjectResult.ByVal

    fun uapmd_app_get_midi_clip_ump_events(app: Pointer?, trackIndex: Int, clipId: Int): UapmdUmpEventsResult.ByVal
    fun uapmd_app_add_ump_event_to_clip(app: Pointer?, trackIndex: Int, clipId: Int, tick: Long, words: IntArray?, wordCount: Int): Boolean
    fun uapmd_app_remove_ump_event_from_clip(app: Pointer?, trackIndex: Int, clipId: Int, eventIndex: Int): Boolean
    fun uapmd_app_remove_clip_from_track(app: Pointer?, trackIndex: Int, clipId: Int): Boolean
    fun uapmd_app_create_empty_midi_clip(app: Pointer?, trackIndex: Int, positionSamples: Long, tickResolution: Int, bpm: Double): UapmdClipAddResult.ByVal

    fun uapmd_app_ensure_track_uses_editor_graph(app: Pointer?, trackIndex: Int): Boolean
    fun uapmd_app_revert_track_to_simple_graph(app: Pointer?, trackIndex: Int): Boolean
    fun uapmd_app_get_track_graph_connections(app: Pointer?, trackIndex: Int): UapmdGraphConnectionsResult.ByVal
    fun uapmd_app_get_track_graph_nodes(app: Pointer?, trackIndex: Int): UapmdGraphNodesResult.ByVal
    fun uapmd_app_connect_track_graph(app: Pointer?, trackIndex: Int, connection: UapmdGraphConnection?): UapmdOpResult.ByVal
    fun uapmd_app_disconnect_track_graph_connection(app: Pointer?, trackIndex: Int, connectionId: Long): UapmdOpResult.ByVal

    fun uapmd_app_get_clip_audio_events(app: Pointer?, trackIndex: Int, clipId: Int): UapmdClipAudioEventsResult.ByVal
    fun uapmd_app_set_clip_audio_events(app: Pointer?, trackIndex: Int, clipId: Int, markers: UapmdClipMarker?, markerCount: Int, warps: UapmdAudioWarpPoint?, warpCount: Int): UapmdOpResult.ByVal
}
