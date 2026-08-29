package dev.atsushieno.uapmd

interface SequencerEngine {
    fun enqueueUmp(instanceId: Int, ump: UIntArray, timestamp: Long)
    val pluginHost: PluginHost
    fun getPluginInstance(instanceId: Int): PluginInstance?
    val functionBlockManager: FunctionBlockManager

    val trackCount: UInt
    fun getTrack(index: UInt): SequencerTrack
    val masterTrack: SequencerTrack

    /** `FrozenTrackManager::freezePolicyForTrack` — what the user asked for. */
    fun trackFreezePolicy(trackIndex: Int): FreezePolicy
    /** `FrozenTrackManager::runtimeStateForTrack` — what the engine is doing. */
    fun trackFreezeState(trackIndex: Int): FreezeRuntimeState
    /**
     * Progress of the track's freeze render, or null when it is not rendering.
     * `FrozenTrackManager::renderProgressForTrack` returns an optional for the
     * same reason: only one track renders at a time, and only while Rendering.
     */
    fun trackFreezeRenderProgress(trackIndex: Int): OfflineRenderProgress?
    /** True while a freeze render is in flight; the legend disables the track's controls. */
    fun isTrackBusy(trackIndex: Int): Boolean
    fun addEmptyTrack(): Int
    fun addPluginToTrack(
        trackIndex: Int,
        format: String,
        pluginId: String,
        callback: (instanceId: Int, trackIndex: Int, error: String?) -> Unit
    )
    fun removePluginInstance(instanceId: Int): Boolean
    fun removeTrack(trackIndex: Int): Boolean
    fun cleanupEmptyTracks()
    fun findTrackForInstance(instanceId: Int): Int
    fun getInstanceGroup(instanceId: Int): UByte
    fun setInstanceGroup(instanceId: Int, group: UByte): Boolean

    fun getTrackLatency(trackIndex: Int): UInt
    val masterTrackLatency: UInt
    fun getTrackRenderLead(trackIndex: Int): UInt
    val masterTrackRenderLead: UInt

    fun setDefaultChannels(inputChannels: UInt, outputChannels: UInt)
    var sampleRate: Int
    var offlineRendering: Boolean
    fun setActive(active: Boolean)
    fun setExternalPump(enabled: Boolean)

    val isPlaybackActive: Boolean
    var playbackPosition: Long
    val renderPlaybackPosition: Long
    fun startPlayback()
    fun stopPlayback()
    fun pausePlayback()
    fun resumePlayback()

    fun sendNoteOn(instanceId: Int, note: Int)
    fun sendNoteOff(instanceId: Int, note: Int)
    fun sendPitchBend(instanceId: Int, value: Float)
    fun sendChannelPressure(instanceId: Int, pressure: Float)
    fun setParameterValue(instanceId: Int, index: Int, value: Double)

    fun getInputSpectrum(numBars: Int): FloatArray
    fun getOutputSpectrum(numBars: Int): FloatArray

    val timeline: TimelineFacade

    /**
     * Live MIDI capture into one clip, or null when the extension is absent.
     * Backed by `uapmd::MidiRecorder`.
     */
    val midiRecorder: MidiRecorder?

    // ─── Project / track dirty state (uapmd 0.5.6) ──────────────────────────

    /**
     * True when the project document differs from its saved history node, or an
     * asynchronous project mutation is still being committed.
     */
    val isProjectDirty: Boolean
    /**
     * Track render/cache dirtiness: runtime state owned by the engine, separate
     * from project-document history dirtiness.
     */
    fun isTrackDirty(trackIndex: Int): Boolean
    fun markTrackDirty(trackIndex: Int, dirty: Boolean = true)
    fun clearTrackDirtyState()

    /**
     * Project-wide markers, owned by the engine alongside the master track.
     * The setter applies markers directly; use
     * [ProjectCommands.setMasterTrackMarkers] instead when the change should be
     * undoable.
     */
    var masterTrackMarkers: List<ClipMarkerData>

    /**
     * Publishes every extension point this engine offers into [manager]. Call
     * before [AddinManager.initialize].
     */
    fun registerAddinExtensionPoints(manager: AddinManager)

    fun renderOffline(
        settings: OfflineRenderSettings,
        progressCallback: ((OfflineRenderProgress) -> Unit)? = null,
        shouldCancel: (() -> Boolean)? = null
    ): OfflineRenderResult
}

/** `uapmd::MidiRecorder` — captures live MIDI input into a selected clip. */
interface MidiRecorder {
    val isRecording: Boolean
    /** [trackReferenceId] is the document identity, not a runtime index. */
    fun start(trackReferenceId: String, clipId: Int, startSample: Long = 0L): Boolean
    fun stop()
    fun cancel()
}

interface SequencerTrack {
    val graph: PluginGraph
    val latencyInSamples: UInt
    val renderLeadInSamples: UInt
    val tailLengthInSeconds: Double
    var bypassed: Boolean
    var frozen: Boolean

    /**
     * Mixer state. Read-only here on purpose: the undoable setters live on
     * [ProjectCommands], so a UI reads from the track and writes through commands.
     */
    val gain: Double
    val muted: Boolean
    val solo: Boolean
    fun getOrderedInstanceIds(): List<Int>
    fun setInstanceGroup(instanceId: Int, group: UByte)
    fun getInstanceGroup(instanceId: Int): UByte
    fun findAvailableGroup(): UByte
    fun removeInstance(instanceId: Int)
}

interface AudioDeviceManager {
    val deviceCount: UInt
    fun getDeviceInfo(index: UInt): AudioDeviceInfo?
    fun open(inputDeviceIndex: Int, outputDeviceIndex: Int, sampleRate: UInt, bufferSize: UInt): AudioIODevice
}

interface AudioIODevice {
    val sampleRate: Double
    val channels: UInt
    val inputChannels: UInt
    val outputChannels: UInt
    fun start(): Int
    fun stop(): Int
    val isPlaying: Boolean
}

/** Marker for a platform MIDI I/O device handle. */
interface MidiIODevice

interface DeviceIODispatcher {
    fun start(): Int
    fun stop(): Int
    val isPlaying: Boolean
    fun clearOutputBuffers()
}

interface RealtimeSequencer : AutoCloseable {
    val engine: SequencerEngine
    fun startAudio(): Int
    fun stopAudio(): Int
    fun isAudioPlaying(): Int
    fun clearOutputBuffers()
    var sampleRate: Int
    fun reconfigureAudioDevice(
        inputDeviceIndex: Int,
        outputDeviceIndex: Int,
        sampleRate: UInt,
        bufferSize: UInt
    ): Boolean
}

enum class FreezePolicy(val nativeValue: Int) {
    Off(0), On(1);

    companion object {
        fun fromNative(v: Int): FreezePolicy = entries.firstOrNull { it.nativeValue == v } ?: Off
    }
}

enum class FreezeRuntimeState(val nativeValue: Int) {
    Live(0), Rendering(1), Frozen(2), Error(3);

    companion object {
        fun fromNative(v: Int): FreezeRuntimeState =
            entries.firstOrNull { it.nativeValue == v } ?: Live
    }
}
