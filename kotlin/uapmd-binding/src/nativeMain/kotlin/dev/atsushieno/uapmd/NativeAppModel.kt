package dev.atsushieno.uapmd

import uapmd.*

class NativeAppModel internal constructor(
    internal val handle: uapmd_app_model_t
) : AppModel {

    override val sequencer: RealtimeSequencer
        // Borrowed: AppModel owns the sequencer, so this wrapper must not destroy it.
        get() = NativeRealtimeSequencer(uapmd_app_sequencer(handle)!!, owned = false)

    override val transport: TransportController
        get() = NativeTransportController(uapmd_app_transport(handle)!!)

    override val sampleRate: Int get() = uapmd_app_sample_rate(handle)
    override val trackCount: UInt get() = uapmd_app_track_count(handle)

    override val isScanning: Boolean get() = uapmd_app_is_scanning(handle)

    override val isAudioEngineEnabled: Boolean get() = uapmd_app_is_audio_engine_enabled(handle)
    override fun setAudioEngineEnabled(enabled: Boolean) = uapmd_app_set_audio_engine_enabled(handle, enabled)
    override fun toggleAudioEngine() = uapmd_app_toggle_audio_engine(handle)

    override var autoBufferSizeEnabled: Boolean
        get() = uapmd_app_auto_buffer_size_enabled(handle)
        set(value) { uapmd_app_set_auto_buffer_size_enabled(handle, value) }

    override fun updateAudioDeviceSettings(sampleRate: Int, bufferSize: UInt) =
        uapmd_app_update_audio_device_settings(handle, sampleRate, bufferSize)

    override fun notifyUiReady() = uapmd_app_notify_ui_ready(handle)
    override fun notifyPersistentStorageReady() = uapmd_app_notify_persistent_storage_ready(handle)
}

class NativeTransportController internal constructor(
    internal val handle: uapmd_transport_controller_t
) : TransportController {
    override val isPlaying: Boolean get() = uapmd_transport_is_playing(handle)
    override val isPaused: Boolean get() = uapmd_transport_is_paused(handle)
    override val isRecording: Boolean get() = uapmd_transport_is_recording(handle)

    override var volume: Float
        get() = uapmd_transport_get_volume(handle)
        set(value) { uapmd_transport_set_volume(handle, value) }

    override fun play() = uapmd_transport_play(handle)
    override fun stop() = uapmd_transport_stop(handle)
    override fun pause() = uapmd_transport_pause(handle)
    override fun resume() = uapmd_transport_resume(handle)
    override fun record() = uapmd_transport_record(handle)
}

actual fun instantiateAppModel() = uapmd_app_instantiate()

actual fun getAppModel(): AppModel =
    NativeAppModel(uapmd_app_instance() ?: error("uapmd_app_instance returned null; call instantiateAppModel() first"))

actual fun cleanupAppModel() = uapmd_app_cleanup()
