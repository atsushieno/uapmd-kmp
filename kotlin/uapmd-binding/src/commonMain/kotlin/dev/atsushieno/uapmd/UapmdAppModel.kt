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

    fun isTrackMuted(trackIndex: Int): Boolean
    fun isTrackSolo(trackIndex: Int): Boolean
    fun setTrackMuted(trackIndex: Int, muted: Boolean): Boolean
    fun setTrackSolo(trackIndex: Int, solo: Boolean): Boolean

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

    /**
     * Discards the current project and starts an empty one. Asks nothing and
     * always replaces: whether unsaved changes may be discarded is the caller's
     * decision. Unlike [TimelineFacade.newProject] this also tears down what the
     * outgoing project instantiated and rebuilds the model's view of the result.
     */
    fun newProject(): AppProjectResult

    // ── Timeline clip selection and clipboard ───────────────────────────────
    //
    // Owned by the model rather than by a UI, so that every host selects,
    // copies and pastes clips the same way. The selection is held by stable
    // document identity, not by index, so it survives a clip moving tracks.
    //
    // Model thread only.

    fun isTimelineClipSelected(trackIndex: Int, clipId: Int): Boolean
    val selectedTimelineClips: List<TimelineClipTarget>

    /**
     * Replaces the selection, adds to it ([additive]), or flips each clip's
     * state within it ([toggle]) — what a click, a shift-click and a ctrl-click
     * respectively produce. An empty list with both false clears it.
     */
    fun selectTimelineClips(
        clips: List<TimelineClipTarget>,
        additive: Boolean = false,
        toggle: Boolean = false
    )
    fun clearTimelineClipSelection()

    /** The MIDI clip an editor is open on, tracked separately from the selection. */
    fun selectTimelineMidiClip(trackIndex: Int, clipId: Int): Boolean
    val selectedTimelineMidiClip: TimelineClipTarget?

    val timelineClipboardCount: Int
    fun clearTimelineClipboard()

    /** False leaves the reason in [lastTimelineClipError]. */
    fun copySelectedTimelineClips(): Boolean

    /** [cut] copies the selection before deleting it. */
    fun deleteSelectedTimelineClips(cut: Boolean): TimelineClipDeleteResult

    /**
     * Which tracks a paste would land on, without performing it.
     * [originalTracks] pastes each clip back onto the track it came from
     * instead of onto [trackIndex].
     */
    fun timelinePasteDestinations(trackIndex: Int, originalTracks: Boolean): List<Int>

    fun pasteTimelineClips(
        trackIndex: Int,
        positionSeconds: Double,
        originalTracks: Boolean
    ): TimelinePasteResult

    /** Empty when the last clipboard call succeeded. */
    val lastTimelineClipError: String

    // ── Piano roll editing session ──────────────────────────────────────────
    //
    // uapmd moved the piano roll's editing model out of its GUI and into the
    // app model, so that every host shares one interpretation of what a clip's
    // UMP stream means as notes — including the MIDI2 attributes and per-note
    // automation that a naive note-on/note-off pairing loses.
    //
    // The flow: snapshot the clip, open a session, load the snapshot into it
    // unless the session already matches, edit, commit, close.
    //
    // Model thread only.

    /** The caller owns the snapshot and must close it. */
    fun pianoRollClipSnapshot(
        trackIndex: Int,
        clipId: Int,
        fallbackDurationSeconds: Double = 0.01
    ): PianoRollSnapshot?

    /** Owned by the model and shared between callers; do not close. */
    fun openPianoRollSession(trackIndex: Int, clipId: Int): PianoRollSession?
    fun findPianoRollSession(trackIndex: Int, clipId: Int): PianoRollSession?
    fun closePianoRollSession(trackIndex: Int, clipId: Int)

    /**
     * Commit-source tracking.
     *
     * A commit changes the clip, which makes the timeline announce the change,
     * which would normally make an open piano roll reload from it — throwing
     * away whatever the user has done since. Record the source right after a
     * commit, and when a change arrives ask [pianoRollSourceMatchesLastEdit]
     * before reloading. The match is by content fingerprint, not a flag, so an
     * edit that really did come from elsewhere still reloads.
     */
    fun recordPianoRollCommitSource(trackIndex: Int, clipId: Int)
    fun pianoRollSourceMatchesLastEdit(): Boolean
    fun clearPianoRollCommitSource()

    // ── Assorted accessors ──────────────────────────────────────────────────

    val midiInputPorts: List<MidiPortInfo>
    val midiOutputPorts: List<MidiPortInfo>

    /** A hidden track is skipped by paste and by the track list, but still plays. */
    fun isTrackHidden(trackIndex: Int): Boolean

    /** The span the timeline's content actually occupies. */
    val timelineContentBounds: TimelineContentBounds

    /** UMP devices the model has instantiated. */
    val devices: List<DeviceEntry>
    /** The device hosting a plug-in instance, or null when it has none. */
    fun deviceForInstance(instanceId: Int): DeviceEntry?
    fun updateDeviceLabel(instanceId: Int, label: String)

    /**
     * Plug-in state to and from a file. Prefer these over the `*Sync` variants:
     * a plug-in's state can be slow to produce, and on Android reading it from
     * the main thread can deadlock.
     */
    fun loadPluginState(instanceId: Int, filepath: String, callback: (PluginStateResult) -> Unit)
    fun savePluginState(instanceId: Int, filepath: String, callback: (PluginStateResult) -> Unit)

    /**
     * The blocking variants, for tools and tests that have no loop to post a
     * completion to. See the warning on [loadPluginState].
     */
    fun loadPluginStateSync(instanceId: Int, filepath: String): PluginStateResult
    fun savePluginStateSync(instanceId: Int, filepath: String): PluginStateResult

    /** Marks the track owning this instance dirty, so the next save rewrites it. */
    fun markPluginInstanceTrackDirty(instanceId: Int)

    /**
     * The project's tempo curve, as the engine derived it from the master
     * track. Read this rather than assembling one from the master tempo points,
     * so that display and playback can never be working from different maps.
     */
    val masterTempoMap: TempoMap

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

    /**
     * `AppModel::addClipToTrack`. Ownership of [reader] transfers to the
     * engine, so do not close it afterwards - a successful call consumes it,
     * and a failed one reports `reader not found or already consumed` if the
     * same reader is offered twice.
     */
    fun addClipToTrack(
        trackIndex: Int,
        position: TimelinePosition,
        reader: AudioFileReader,
        filepath: String
    ): ClipAddResult

    /** `AppModel::addMidiClipToTrack`, reading an SMF or SMF2 from disk. */
    fun addMidiClipToTrack(trackIndex: Int, position: TimelinePosition, filepath: String): ClipAddResult

    /**
     * `AppModel::addMidiClipFromData`, for a clip built in memory rather than
     * read from a file - what a generator or a step sequencer produces.
     *
     * [umpEvents] and [tickTimestamps] are parallel: one timestamp per event.
     * Set [needsFileSave] when the clip has no file behind it yet, so that
     * saving the project writes one out.
     */
    fun addMidiClipFromData(
        trackIndex: Int,
        position: TimelinePosition,
        umpEvents: List<UInt>,
        tickTimestamps: List<ULong>,
        tickResolution: UInt = 480u,
        clipTempo: Double = 120.0,
        tempoChanges: List<MidiTempoChange> = emptyList(),
        timeSignatureChanges: List<MidiTimeSignatureChange> = emptyList(),
        clipName: String = "",
        needsFileSave: Boolean = true
    ): ClipAddResult

    /**
     * `AppModel::addDeviceInputToTrack`, returning the new source node id, or a
     * negative value on failure. The undoable counterpart is
     * [ProjectCommands.addDeviceInputToTrack], which takes the node id this
     * returns; prefer that one from a UI.
     */
    fun addDeviceInputToTrack(trackIndex: Int, channelIndices: List<UInt>): Int

    // ── Master track markers ────────────────────────────────────────────────

    /** Reading counterpart to [ProjectCommands.setMasterTrackMarkers]. */
    val masterMarkers: List<ClipMarkerData>

    /**
     * Writes the master markers and reports why a rejected set was rejected,
     * which [ProjectCommands.setMasterTrackMarkers] reduces to a boolean. This
     * path is not undoable.
     */
    fun setMasterTrackMarkersWithValidation(markers: List<ClipMarkerData>): OpResult

    // ── Offline render to file ──────────────────────────────────────────────
    //
    // AppModel runs this as a background job: start it, poll the status, and
    // clear the finished status once the result has been shown. Distinct from
    // SequencerEngine.renderOffline, which renders synchronously on the calling
    // thread.

    /** False when a render is already running or the settings are unusable. */
    fun startRenderToFile(settings: RenderToFileSettings): Boolean
    fun cancelRenderToFile()
    val renderToFileStatus: RenderToFileStatus
    fun clearCompletedRenderStatus()

    /** Asks the host to bring up this track's graph editor. */
    fun requestShowTrackGraph(trackIndex: Int)

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
    val events: List<UmpEvent>,
    val tickResolution: UInt = 0u,
    val clipTempo: Double = 0.0
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

    /** Moves the playhead without starting or stopping the transport. */
    fun jump(positionSeconds: Double)
}

/** One clip, by the identifiers the rest of this API takes. */
data class TimelineClipTarget(val trackIndex: Int, val clipId: Int)

/** [changedTracks] are the tracks that lost a clip. */
data class TimelineClipDeleteResult(
    val success: Boolean,
    val changedTracks: List<Int>,
    val error: String?
)

data class TimelinePasteResult(
    val success: Boolean,
    val pasted: List<TimelineClipTarget>,
    val error: String?
)

/** A MIDI clip parsed into notes, ready to load into a [PianoRollSession]. */
interface PianoRollSnapshot : AutoCloseable {
    val isReady: Boolean
    /** Empty when [isReady]. */
    val error: String
    val durationSeconds: Double
    val minNote: Int
    val maxNote: Int
    val notes: List<PianoRollNote>
}

/**
 * One note as a session holds it.
 *
 * [deleted] notes stay in the list so indexes remain stable across an edit;
 * skip them when drawing. [editId] is the identity the selection is keyed by —
 * an index is only good until the next edit.
 */
data class PianoRollNote(
    val startSeconds: Double,
    val durationSeconds: Double,
    /** 0.0 - 1.0 */
    val velocity: Float,
    val note: Int,
    val channel: Int,
    val deleted: Boolean,
    val editId: Long,
    val umpGroup: Int,
    val releaseVelocity: Int,
    val attributeType: Int,
    val attributeValue: Int,
    val automationEventCount: Int
)

/** The clipboard actions a session performs on its selection. */
enum class PianoRollAction(val nativeValue: Int) {
    None(0), Copy(1), Cut(2), Paste(3), Delete(4), SelectAll(5)
}

/**
 * A live editing session over one MIDI clip. Owned by the model — obtain one
 * with [AppModel.openPianoRollSession] and release it with
 * [AppModel.closePianoRollSession].
 */
interface PianoRollSession {
    val notes: List<PianoRollNote>
    fun isNoteSelected(index: Int): Boolean
    val selectedNoteCount: Int
    /** The note the detail editor is on, or -1. */
    var focusedNote: Int
    val durationSeconds: Double
    val minNote: Int
    val maxNote: Int
    val clipboardCount: Int
    val isDirty: Boolean
    /** Empty when the last edit succeeded. */
    val error: String

    /**
     * Whether this session was built from that snapshot's UMP stream. False
     * means the clip changed underneath and the session must be reloaded —
     * which is what stops an edit being applied to a clip it was not made
     * against.
     */
    fun matchesSource(snapshot: PianoRollSnapshot): Boolean
    fun loadNotes(snapshot: PianoRollSnapshot?)

    /** [index] -1 clears the selection. */
    fun selectNote(index: Int, additive: Boolean = false, toggle: Boolean = false)
    fun performAction(action: PianoRollAction, pasteSeconds: Double = 0.0)
    fun createNote(startSeconds: Double, durationSeconds: Double, note: Int, velocity: Float)
    fun deleteNote(index: Int)
    fun resizeNote(index: Int, startSeconds: Double, durationSeconds: Double, note: Int)

    /**
     * A move drag works from the notes as they were when it began, so each
     * update re-applies one delta rather than accumulating rounding. The
     * session holds that snapshot: say when the drag starts, how far it has
     * moved, and whether it ended or was cancelled.
     */
    fun beginDrag()
    fun moveSelection(timeDeltaSeconds: Double, pitchDelta: Int)
    fun cancelDrag()
    fun finishDrag(index: Int, originalStart: Double, originalEnd: Double, originalNote: Int)

    /** Writes the session back to the clip, through the undo history. */
    fun commit(app: AppModel): Boolean
}

data class MidiPortInfo(val id: String, val displayName: String)

/** [filepath] is the path actually used for the save or load. */
data class PluginStateResult(
    val instanceId: Int,
    val success: Boolean,
    val error: String,
    val filepath: String
)

data class TimelineContentBounds(
    val hasContent: Boolean,
    val startSeconds: Double,
    val endSeconds: Double,
    val durationSeconds: Double
)

/** One UMP device the model has instantiated. */
data class DeviceEntry(
    val id: Int,
    val label: String,
    val apiName: String,
    val statusMessage: String,
    val running: Boolean,
    val instantiating: Boolean,
    val hasError: Boolean
)

/** A tempo change inside a MIDI clip, positioned in the clip's own ticks. */
data class MidiTempoChange(val tickPosition: ULong, val bpm: Double)

/** A meter change inside a MIDI clip, positioned in the clip's own ticks. */
data class MidiTimeSignatureChange(
    val tickPosition: ULong,
    val numerator: UByte,
    val denominator: UByte,
    val clocksPerClick: UByte = 24u,
    val thirtySecondsPerQuarter: UByte = 8u
)

/**
 * What to render and when to stop.
 *
 * The span is [startSeconds] to [endSeconds]; leave [hasEndSeconds] false and
 * set [useContentFallback] to render to the end of the content instead, in
 * which case the content bounds must be supplied (they come from
 * [AppModel.timelineContentBounds]). [tailSeconds] keeps rendering past the end
 * so that reverbs and delays are not cut off, and the silence stop ends the
 * render early once the output stays below [silenceThresholdDb] for
 * [silenceDurationSeconds].
 */
data class RenderToFileSettings(
    val outputPath: String,
    val startSeconds: Double = 0.0,
    val endSeconds: Double = 0.0,
    val hasEndSeconds: Boolean = false,
    val useContentFallback: Boolean = true,
    val contentBoundsValid: Boolean = false,
    val contentStartSeconds: Double = 0.0,
    val contentEndSeconds: Double = 0.0,
    val tailSeconds: Double = 0.0,
    val enableSilenceStop: Boolean = false,
    val silenceDurationSeconds: Double = 2.0,
    val silenceThresholdDb: Double = -60.0
)

data class RenderToFileStatus(
    val running: Boolean,
    val completed: Boolean,
    val success: Boolean,
    val progress: Double,
    val renderedSeconds: Double,
    val message: String,
    val outputPath: String
)
