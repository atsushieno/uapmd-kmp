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
    fun generateScanReport(): String
    fun clearPluginBlocklist()

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
}

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
