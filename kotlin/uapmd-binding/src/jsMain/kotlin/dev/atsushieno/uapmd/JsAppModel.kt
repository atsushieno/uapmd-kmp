@file:Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")

package dev.atsushieno.uapmd

class JsAppModel internal constructor(internal val handle: Int) : AppModel {

    override val sequencer: RealtimeSequencer
        // Borrowed: AppModel owns the sequencer, so this wrapper must not destroy it.
        get() = JsRealtimeSequencer(jsMod._uapmd_app_sequencer(handle) as Int, owned = false)

    override val transport: TransportController
        get() = JsTransportController(jsMod._uapmd_app_transport(handle) as Int)

    override val sampleRate: Int get() = jsMod._uapmd_app_sample_rate(handle) as Int
    override val trackCount: UInt get() = (jsMod._uapmd_app_track_count(handle) as Int).toUInt()

    override val isScanning: Boolean get() = jsMod._uapmd_app_is_scanning(handle) as Boolean

    override val isAudioEngineEnabled: Boolean
        get() = jsMod._uapmd_app_is_audio_engine_enabled(handle) as Boolean

    override fun setAudioEngineEnabled(enabled: Boolean) {
        jsMod._uapmd_app_set_audio_engine_enabled(handle, enabled)
    }

    override fun toggleAudioEngine() {
        jsMod._uapmd_app_toggle_audio_engine(handle)
    }

    override var autoBufferSizeEnabled: Boolean
        get() = jsMod._uapmd_app_auto_buffer_size_enabled(handle) as Boolean
        set(value) { jsMod._uapmd_app_set_auto_buffer_size_enabled(handle, value) }

    override fun updateAudioDeviceSettings(sampleRate: Int, bufferSize: UInt) {
        jsMod._uapmd_app_update_audio_device_settings(handle, sampleRate, bufferSize.toInt())
    }

    override fun notifyUiReady() {
        jsMod._uapmd_app_notify_ui_ready(handle)
    }

    override fun notifyPersistentStorageReady() {
        jsMod._uapmd_app_notify_persistent_storage_ready(handle)
    }
}

class JsTransportController internal constructor(internal val handle: Int) : TransportController {
    override val isPlaying: Boolean get() = jsMod._uapmd_transport_is_playing(handle) as Boolean
    override val isPaused: Boolean get() = jsMod._uapmd_transport_is_paused(handle) as Boolean
    override val isRecording: Boolean get() = jsMod._uapmd_transport_is_recording(handle) as Boolean

    override var volume: Float
        get() = (jsMod._uapmd_transport_get_volume(handle) as Number).toFloat()
        set(value) { jsMod._uapmd_transport_set_volume(handle, value) }

    override fun play() { jsMod._uapmd_transport_play(handle) }
    override fun stop() { jsMod._uapmd_transport_stop(handle) }
    override fun pause() { jsMod._uapmd_transport_pause(handle) }
    override fun resume() { jsMod._uapmd_transport_resume(handle) }
    override fun record() { jsMod._uapmd_transport_record(handle) }
}

actual fun instantiateAppModel() {
    jsMod._uapmd_app_instantiate()
}

actual fun getAppModel(): AppModel {
    val h = jsMod._uapmd_app_instance() as Int
    if (h == 0) error("uapmd_app_instance returned null; call instantiateAppModel() first")
    return JsAppModel(h)
}

actual fun cleanupAppModel() {
    jsMod._uapmd_app_cleanup()
}
