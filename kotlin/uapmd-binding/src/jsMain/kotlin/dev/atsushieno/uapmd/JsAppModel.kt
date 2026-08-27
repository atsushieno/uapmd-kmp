@file:Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")

package dev.atsushieno.uapmd

class JsAppModel internal constructor(internal val handle: Int) : AppModel {

    override val sequencer: RealtimeSequencer
        // Borrowed: AppModel owns this; callers must not close it (see AppModel.sequencer).
        get() = JsRealtimeSequencer(jsMod._uapmd_app_sequencer(handle) as Int)

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

    // ── Plugin scanning ─────────────────────────────────────────────────────

    override fun performPluginScanning(
        forceRescan: Boolean, mode: ScanMode, remoteTimeoutSeconds: Double, requireFastScanning: Boolean
    ) {
        jsMod._uapmd_app_perform_plugin_scanning(
            handle, forceRescan, if (mode == ScanMode.Remote) 1 else 0, remoteTimeoutSeconds, requireFastScanning
        )
    }

    override fun cancelPluginScanning() {
        jsMod._uapmd_app_cancel_plugin_scanning(handle)
    }

    override fun generateScanReport(): String =
        readJsString(handle) { h, buf, size -> jsMod._uapmd_app_generate_scan_report(h, buf, size) as Int }

    override fun clearPluginBlocklist() {
        jsMod._uapmd_app_clear_plugin_blocklist(handle)
    }

    // ── Tracks ──────────────────────────────────────────────────────────────

    override fun addTrack(callback: (Int, String?) -> Unit) {
        jsMod._uapmd_app_add_track(handle, 0, makeJsTrackMutation(callback))
    }

    override fun removeTrack(trackIndex: Int, callback: (Int, String?) -> Unit) {
        jsMod._uapmd_app_remove_track(handle, trackIndex, 0, makeJsTrackMutation(callback))
    }

    override fun removeAllTracks(callback: (String?) -> Unit) {
        jsMod._uapmd_app_remove_all_tracks(handle, 0, makeJsErrorOnly(callback))
    }

    override val timelineTrackCount: UInt get() = (jsMod._uapmd_app_timeline_track_count(handle) as Int).toUInt()

    override fun getTimelineTrack(index: UInt): TimelineTrack =
        JsTimelineTrack(jsMod._uapmd_app_get_timeline_track(handle, index.toInt()) as Int)

    override val masterTimelineTrack: TimelineTrack
        get() = JsTimelineTrack(jsMod._uapmd_app_master_timeline_track(handle) as Int)

    override fun getTimelineState(): TimelineState? =
        withWasmMem(80) { ptr ->
            if (!(jsMod._uapmd_app_get_timeline_state(handle, ptr) as Boolean)) null
            else jsDecodeTimelineState(ptr)
        }

    // ── History ─────────────────────────────────────────────────────────────

    override val historyState: UndoState
        get() = withWasmMem(Off.STATE_SIZE) { p ->
            jsMod._uapmd_app_get_history_state(handle, p)
            decodeUndoState(p)
        }

    override fun undo(callback: ((String?) -> Unit)?) {
        jsMod._uapmd_app_undo(handle, 0, callback?.let { makeJsErrorOnly(it) } ?: 0)
    }

    override fun redo(callback: ((String?) -> Unit)?) {
        jsMod._uapmd_app_redo(handle, 0, callback?.let { makeJsErrorOnly(it) } ?: 0)
    }
}

/** For C callbacks shaped (const char* error, void* user_data). */
private fun makeJsErrorOnly(callback: (String?) -> Unit): Int {
    var slot = 0
    val fn: (dynamic, dynamic) -> Unit = { errorPtr, _ ->
        try {
            val err = if ((errorPtr as Int) != 0) jsMod.UTF8ToString(errorPtr) as String else null
            callback(err)
        } finally {
            removeJsCallback(slot)
        }
    }
    slot = addJsCallback(fn.asDynamic(), "vii")
    return slot
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
