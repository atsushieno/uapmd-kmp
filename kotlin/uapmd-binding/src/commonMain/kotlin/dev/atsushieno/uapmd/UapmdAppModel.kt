package dev.atsushieno.uapmd

/**
 * Host side of `uapmd_app::AppModel` (`tools/uapmd-app-model`) — the façade
 * uapmd-app itself renders against, wrapped by `c-api/uapmd-c-app.h`.
 *
 * It is a process-wide singleton: call [instantiateAppModel] once, before any
 * UI exists, then reach it with [getAppModel]. It owns its [RealtimeSequencer],
 * so the instance returned by [sequencer] must not be closed.
 *
 * A host must install an event loop (see `uapmd_set_event_loop`, which the
 * JVM/Android bindings do via `initJvmEventLoop()` / `initAndroidEventLoop()`)
 * *before* instantiating: AppModel marshals plugin deactivation and history
 * completions through `remidy::EventLoop`, and without one they never run.
 */
interface AppModel {
    /** Owned by the model; do not close. */
    val sequencer: RealtimeSequencer
    val transport: TransportController
    val sampleRate: Int
    val trackCount: UInt

    val isScanning: Boolean

    /**
     * Turning this off is not a plain `stopAudio()`: the model stops the
     * transport, mutes the output, lets release/reverb tails drain inaudibly,
     * then deactivates plugins on the main thread and resets processing state,
     * so a restart cannot resume stale voices.
     */
    val isAudioEngineEnabled: Boolean
    fun setAudioEngineEnabled(enabled: Boolean)
    fun toggleAudioEngine()

    var autoBufferSizeEnabled: Boolean
    fun updateAudioDeviceSettings(sampleRate: Int, bufferSize: UInt)

    /** Startup lifecycle; uapmd-app calls both once the UI exists. */
    fun notifyUiReady()
    fun notifyPersistentStorageReady()

    // ── Plugin scanning ─────────────────────────────────────────────────────

    /**
     * Runs asynchronously; watch [isScanning]. [ScanMode.Remote] launches a
     * separate scanner process on desktop and is unavailable on WebAssembly.
     */
    fun performPluginScanning(
        forceRescan: Boolean = false,
        mode: ScanMode = ScanMode.InProcess,
        remoteTimeoutSeconds: Double = 20.0,
        requireFastScanning: Boolean = false
    )
    fun cancelPluginScanning()

    /**
     * Progress of the slow scan. [isScanning] alone cannot tell a long scan from a
     * stuck one, which is why uapmd-app's selector shows these.
     */
    val slowScanProgress: SlowScanProgress

    /** The last scanning error, or null. */
    val lastPluginScanError: String?
    fun generateScanReport(): String
    fun clearPluginBlocklist()

    /**
     * AppModel's own blocklist — the list the Plugin Selector shows. A standalone
     * `ScanTool` keeps a separate one. `BlocklistEntry.timestamp` in uapmd has no
     * counterpart in `uapmd_blocklist_entry_t`, so it is not carried across.
     */
    val blocklist: List<BlocklistEntry>

    /**
     * Master-track tempo map. `getTimelineState().tempo` is a single value and
     * cannot describe a project whose tempo changes; a beats view needs these.
     * Rebuild once with [refreshMasterTempoMap], then read the lists — they stay
     * valid until the next refresh.
     */
    fun refreshMasterTempoMap(): Double
    val masterTempoPoints: List<TempoPoint>
    val masterTimeSignaturePoints: List<TimeSignaturePoint>
    fun unblockPlugin(entryId: String): Boolean


    // ── Tracks ──────────────────────────────────────────────────────────────

    /** Asynchronous since uapmd 0.5.6: track mutations are undo engine operations. */
    fun addTrack(callback: (trackIndex: Int, error: String?) -> Unit)
    fun removeTrack(trackIndex: Int, callback: (trackIndex: Int, error: String?) -> Unit)
    fun removeAllTracks(callback: (error: String?) -> Unit)

    val timelineTrackCount: UInt
    fun getTimelineTrack(index: UInt): TimelineTrack
    val masterTimelineTrack: TimelineTrack

    fun getTimelineState(): TimelineState?

    // ── History ─────────────────────────────────────────────────────────────

    /**
     * Also reports `busy` while an asynchronous plug-in mutation is still
     * capturing state, so shortcuts cannot race the capture.
     */
    val historyState: UndoState
    fun undo(callback: ((error: String?) -> Unit)? = null)
    fun redo(callback: ((error: String?) -> Unit)? = null)

    // ── Plugin instances ────────────────────────────────────────────────────

    /**
     * Instantiates [pluginId] and attaches it to [trackIndex]; a negative index
     * creates a new track. The callback runs once, on the thread that finishes
     * instantiation.
     */
    fun createPluginInstance(
        format: String,
        pluginId: String,
        trackIndex: Int,
        config: PluginInstanceConfig = PluginInstanceConfig(),
        callback: (PluginInstanceResult) -> Unit
    )
    fun removePluginInstance(instanceId: Int)

    fun getInstanceGroup(instanceId: Int): UByte
    fun setInstanceGroup(instanceId: Int, group: UByte): Boolean

    /** Registers the instance as a virtual MIDI 2.0 device where the platform supports it. */
    fun enableUmpDevice(instanceId: Int, deviceName: String)
    fun disableUmpDevice(instanceId: Int)

    fun requestShowInstanceDetails(instanceId: Int)
    fun requestShowPluginUi(instanceId: Int)
    fun hidePluginUi(instanceId: Int)

    // ── Project I/O ─────────────────────────────────────────────────────────

    fun loadProject(filePath: String): AppProjectResult
    fun saveProjectSync(filePath: String): AppProjectResult
    fun saveProject(filePath: String, callback: (AppProjectResult) -> Unit)
    /** Android's document-picker path: resolves a content:// handle token. */
    fun loadProjectFromHandleToken(token: String): AppProjectResult

    // ── MIDI clip UMP events ────────────────────────────────────────────────

    fun getMidiClipUmpEvents(trackIndex: Int, clipId: Int): UmpEventsResult
    fun addUmpEventToClip(trackIndex: Int, clipId: Int, tick: Long, words: UIntArray): Boolean
    fun removeUmpEventFromClip(trackIndex: Int, clipId: Int, eventIndex: Int): Boolean

    fun removeClipFromTrack(trackIndex: Int, clipId: Int): Boolean
    /**
     * Imports a possibly multi-track SMF, one new track per SMF track.
     * [callback] receives success, an error when it failed, and how many tracks
     * were created.
     */
    fun importMidiTracksFromFile(filepath: String, callback: (Boolean, String?, Int) -> Unit)

    /** Creates an empty MIDI 2.0 clip; [tickResolution] is ticks per quarter. */
    fun createEmptyMidiClip(
        trackIndex: Int,
        positionSamples: Long,
        tickResolution: UInt = 480u,
        bpm: Double = 120.0
    ): ClipAddResult

    // ── Track graph (DAG) ───────────────────────────────────────────────────

    /** Switches the track from the simple linear chain to the editable graph. */
    fun ensureTrackUsesEditorGraph(trackIndex: Int): Boolean
    fun revertTrackToSimpleGraph(trackIndex: Int): Boolean
    fun getTrackGraphConnections(trackIndex: Int): GraphConnectionsResult
    fun getTrackGraphNodes(trackIndex: Int): GraphNodesResult
    fun connectTrackGraph(trackIndex: Int, connection: GraphConnection): OpResult
    fun disconnectTrackGraphConnection(trackIndex: Int, connectionId: Long): OpResult

    // ── Clip audio events (markers + warps) ─────────────────────────────────

    /** Reading counterpart to `ProjectCommands.setClipMarkers/setClipAudioWarps`. */
    fun getClipAudioEvents(trackIndex: Int, clipId: Int): ClipAudioEventsResult
    fun setClipAudioEvents(
        trackIndex: Int,
        clipId: Int,
        markers: List<ClipMarkerData>,
        warps: List<AudioWarpPointData>
    ): OpResult
}

/** Mirrors `uapmd_clip_audio_events_result_t`. */
data class ClipAudioEventsResult(
    val success: Boolean,
    val error: String?,
    val markers: List<ClipMarkerData>,
    val warps: List<AudioWarpPointData>
)

enum class GraphEndpointType(val nativeValue: Int) {
    GraphInput(0), Plugin(1), GraphOutput(2);
    companion object { fun fromNative(v: Int) = entries.firstOrNull { it.nativeValue == v } ?: Plugin }
}

enum class GraphBusType(val nativeValue: Int) {
    Audio(0), Event(1);
    companion object { fun fromNative(v: Int) = entries.firstOrNull { it.nativeValue == v } ?: Audio }
}

/**
 * One end of a graph connection.
 *
 * [nodeId] is the node's persistent identity and the field to key pins by:
 * [instanceId] is -1 for both graph endpoints and for every built-in node, so it
 * cannot distinguish them. Use [resolvedNodeId] to apply the same fallback
 * uapmd-app applies when the id is empty.
 */
data class GraphEndpoint(
    val type: GraphEndpointType,
    val nodeId: String,
    val instanceId: Int,
    val busIndex: UInt
) {
    /** Mirrors uapmd-app's `endpointNodeId()` (PluginGraphEditor.cpp:105). */
    val resolvedNodeId: String
        get() = when {
            nodeId.isNotEmpty() -> nodeId
            type == GraphEndpointType.GraphInput -> "graph:input"
            type == GraphEndpointType.GraphOutput -> "graph:output"
            instanceId >= 0 -> "plugin:$instanceId"
            else -> ""
        }
}

/** Mirrors `remidy::AudioBusRole`. */
enum class AudioBusRole(val nativeValue: Int) {
    Main(0), Aux(1);
    companion object { fun fromNative(v: Int) = entries.firstOrNull { it.nativeValue == v } ?: Main }
}

/** One audio bus of a graph node — `remidy::AudioBusConfiguration`. */
data class GraphAudioBus(
    val name: String,
    val role: AudioBusRole,
    val enabled: Boolean,
    val channelLayoutName: String,
    val channelCount: UInt
)

/**
 * One node of a track graph: `uapmd_graph::AudioGraphNode` plus the
 * `remidy::PluginAudioBuses` facade it exposes.
 *
 * Every bus is here, enabled or not. Whether a disabled bus gets a pin is the
 * editor's decision — uapmd-app skips them — not this type's.
 *
 * A node hosting no plugin instance (a built-in node such as the track's gain)
 * reports [instanceId] -1 and [hasAudioBuses] false, and has no buses of its own;
 * the graph's own layout on [GraphNodesResult] is the fallback for those.
 */
data class GraphNode(
    val nodeId: String,
    val nodeType: String,
    val displayName: String,
    val instanceId: Int,
    val bypassed: Boolean,
    val latencyInSamples: UInt,
    val tailLengthInSeconds: Double,
    val hasAudioBuses: Boolean,
    val hasEventInputs: Boolean,
    val hasEventOutputs: Boolean,
    val audioInputBuses: List<GraphAudioBus>,
    val audioOutputBuses: List<GraphAudioBus>,
    val mainInputBusIndex: Int,
    val mainOutputBusIndex: Int
)

/**
 * The C API hands the buses back as one flat array shared by every node, each node
 * naming its offset into it; this cuts a node's slice out, tolerating a malformed
 * range rather than throwing across the FFI boundary.
 */
internal fun List<GraphAudioBus>.busRange(from: Int, count: Int): List<GraphAudioBus> =
    if (from < 0 || count <= 0 || from + count > size) emptyList()
    else subList(from, from + count).toList()

data class GraphNodesResult(
    val success: Boolean,
    val error: String?,
    val nodes: List<GraphNode>,
    val graphAudioInputBusCount: UInt,
    val graphAudioOutputBusCount: UInt,
    val graphEventInputBusCount: UInt,
    val graphEventOutputBusCount: UInt
) {
    companion object {
        fun failure(error: String?) = GraphNodesResult(false, error, emptyList(), 0u, 0u, 0u, 0u)
    }
}

data class GraphConnection(
    val id: Long,
    val busType: GraphBusType,
    val source: GraphEndpoint,
    val target: GraphEndpoint
)

data class GraphConnectionsResult(
    val success: Boolean,
    val error: String?,
    val connections: List<GraphConnection>
)

/** Mirrors `uapmd_op_result_t`. */
data class OpResult(val success: Boolean, val error: String?)

/** One UMP event in a MIDI clip: a tick plus the 1-4 words of the message. */
data class UmpEvent(val tick: Long, val words: UIntArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is UmpEvent && tick == other.tick && words.contentEquals(other.words))

    override fun hashCode(): Int = 31 * tick.hashCode() + words.contentHashCode()
}

/** Mirrors `uapmd_ump_events_result_t`. */
data class UmpEventsResult(
    val success: Boolean,
    val error: String?,
    val events: List<UmpEvent>
)

/** Mirrors `uapmd_app_project_result_t`. */
data class AppProjectResult(val success: Boolean, val error: String?)

/** Mirrors `uapmd_plugin_instance_config_t`; empty strings take the C defaults. */
data class PluginInstanceConfig(
    val apiName: String = "default",
    val deviceName: String = "",
    val manufacturer: String = "UAPMD Project",
    val version: String = "0.1",
    val stateFile: String = ""
)

/** Mirrors `uapmd_plugin_instance_result_t`. */
data class PluginInstanceResult(
    val instanceId: Int,
    val pluginName: String,
    val error: String?
)

/** `uapmd_app::TransportController`. Owned by the [AppModel]. */
interface TransportController {
    val isPlaying: Boolean
    val isPaused: Boolean
    val isRecording: Boolean
    var volume: Float

    fun play()
    fun stop()
    fun pause()
    fun resume()
    fun record()
}
