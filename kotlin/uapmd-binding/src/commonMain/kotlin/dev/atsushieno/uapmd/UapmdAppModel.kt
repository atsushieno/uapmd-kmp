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
}

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
