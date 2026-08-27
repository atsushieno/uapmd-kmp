package dev.atsushieno.uapmd

import com.sun.jna.Pointer
import dev.atsushieno.uapmd.jna.*

class JvmAppModel internal constructor(
    internal val handle: Pointer
) : AppModel {

    override val sequencer: RealtimeSequencer
        // Borrowed: AppModel owns this; callers must not close it (see AppModel.sequencer).
        get() = JvmRealtimeSequencer(lib.uapmd_app_sequencer(handle) ?: error("uapmd_app_sequencer returned null"))

    override val transport: TransportController
        get() = JvmTransportController(
            lib.uapmd_app_transport(handle) ?: error("uapmd_app_transport returned null")
        )

    override val sampleRate: Int get() = lib.uapmd_app_sample_rate(handle)
    override val trackCount: UInt get() = lib.uapmd_app_track_count(handle).toUInt()

    override val isScanning: Boolean get() = lib.uapmd_app_is_scanning(handle)

    override val isAudioEngineEnabled: Boolean get() = lib.uapmd_app_is_audio_engine_enabled(handle)
    override fun setAudioEngineEnabled(enabled: Boolean) = lib.uapmd_app_set_audio_engine_enabled(handle, enabled)
    override fun toggleAudioEngine() = lib.uapmd_app_toggle_audio_engine(handle)

    override var autoBufferSizeEnabled: Boolean
        get() = lib.uapmd_app_auto_buffer_size_enabled(handle)
        set(value) { lib.uapmd_app_set_auto_buffer_size_enabled(handle, value) }

    override fun updateAudioDeviceSettings(sampleRate: Int, bufferSize: UInt) =
        lib.uapmd_app_update_audio_device_settings(handle, sampleRate, bufferSize.toInt())

    override fun notifyUiReady() = lib.uapmd_app_notify_ui_ready(handle)
    override fun notifyPersistentStorageReady() = lib.uapmd_app_notify_persistent_storage_ready(handle)

    // ── Plugin scanning ─────────────────────────────────────────────────────

    override fun performPluginScanning(
        forceRescan: Boolean, mode: ScanMode, remoteTimeoutSeconds: Double, requireFastScanning: Boolean
    ) = lib.uapmd_app_perform_plugin_scanning(
        handle, forceRescan, if (mode == ScanMode.Remote) 1 else 0, remoteTimeoutSeconds, requireFastScanning
    )

    override fun cancelPluginScanning() = lib.uapmd_app_cancel_plugin_scanning(handle)

    override fun generateScanReport(): String =
        readJvmString { buf, size -> lib.uapmd_app_generate_scan_report(handle, buf, size) }

    override fun clearPluginBlocklist() = lib.uapmd_app_clear_plugin_blocklist(handle)

    // ── Tracks ──────────────────────────────────────────────────────────────

    override fun addTrack(callback: (Int, String?) -> Unit) =
        lib.uapmd_app_add_track(handle, null, trackMutationCb(callback))

    override fun removeTrack(trackIndex: Int, callback: (Int, String?) -> Unit) =
        lib.uapmd_app_remove_track(handle, trackIndex, null, trackMutationCb(callback))

    override fun removeAllTracks(callback: (String?) -> Unit) =
        lib.uapmd_app_remove_all_tracks(handle, null, trackClearCb(callback))

    override val timelineTrackCount: UInt get() = lib.uapmd_app_timeline_track_count(handle).toUInt()

    override fun getTimelineTrack(index: UInt): TimelineTrack =
        JvmTimelineTrack(lib.uapmd_app_get_timeline_track(handle, index.toInt()) ?: error("timeline track $index not found"))

    override val masterTimelineTrack: TimelineTrack
        get() = JvmTimelineTrack(lib.uapmd_app_master_timeline_track(handle) ?: error("master timeline track not found"))

    override fun getTimelineState(): TimelineState? {
        val out = UapmdTimelineState()
        if (!lib.uapmd_app_get_timeline_state(handle, out)) return null
        return out.toKotlin()
    }

    // ── History ─────────────────────────────────────────────────────────────

    override val historyState: UndoState
        get() = UapmdUndoState().also { lib.uapmd_app_get_history_state(handle, it) }.toKotlin()

    override fun undo(callback: ((String?) -> Unit)?) =
        lib.uapmd_app_undo(handle, null, callback?.let { historyMutationCb(it) })

    override fun redo(callback: ((String?) -> Unit)?) =
        lib.uapmd_app_redo(handle, null, callback?.let { historyMutationCb(it) })

    // ── Plugin instances ────────────────────────────────────────────────────

    override fun createPluginInstance(
        format: String, pluginId: String, trackIndex: Int,
        config: PluginInstanceConfig, callback: (PluginInstanceResult) -> Unit
    ) {
        val c = UapmdPluginInstanceConfig().apply {
            api_name = config.apiName
            device_name = config.deviceName
            manufacturer = config.manufacturer
            version = config.version
            state_file = config.stateFile
        }
        lateinit var cb: InstanceCreatedCb
        cb = object : InstanceCreatedCb {
            override fun invoke(result: UapmdPluginInstanceResult.ByVal, userData: Pointer?) {
                liveAppCallbacks.remove(cb)
                callback(PluginInstanceResult(result.instance_id, result.plugin_name ?: "", result.error))
            }
        }
        liveAppCallbacks.add(cb)
        lib.uapmd_app_create_plugin_instance(handle, format, pluginId, trackIndex, c, null, cb)
    }

    override fun removePluginInstance(instanceId: Int) = lib.uapmd_app_remove_plugin_instance(handle, instanceId)

    override fun getInstanceGroup(instanceId: Int): UByte =
        lib.uapmd_app_get_instance_group(handle, instanceId).toUByte()

    override fun setInstanceGroup(instanceId: Int, group: UByte): Boolean =
        lib.uapmd_app_set_instance_group(handle, instanceId, group.toByte())

    override fun enableUmpDevice(instanceId: Int, deviceName: String) =
        lib.uapmd_app_enable_ump_device(handle, instanceId, deviceName)

    override fun disableUmpDevice(instanceId: Int) = lib.uapmd_app_disable_ump_device(handle, instanceId)

    override fun requestShowInstanceDetails(instanceId: Int) =
        lib.uapmd_app_request_show_instance_details(handle, instanceId)

    override fun requestShowPluginUi(instanceId: Int) = lib.uapmd_app_request_show_plugin_ui(handle, instanceId)
    override fun hidePluginUi(instanceId: Int) = lib.uapmd_app_hide_plugin_ui(handle, instanceId)
}

// Callbacks are held by a strong reference until they fire, so JNA cannot collect
// the trampoline while native code still owns the pointer.
private fun trackMutationCb(callback: (Int, String?) -> Unit): TrackMutationCb {
    lateinit var cb: TrackMutationCb
    cb = object : TrackMutationCb {
        override fun invoke(trackIndex: Int, error: String?, userData: Pointer?) {
            liveAppCallbacks.remove(cb)
            callback(trackIndex, error)
        }
    }
    liveAppCallbacks.add(cb)
    return cb
}

private fun trackClearCb(callback: (String?) -> Unit): TrackClearCb {
    lateinit var cb: TrackClearCb
    cb = object : TrackClearCb {
        override fun invoke(error: String?, userData: Pointer?) {
            liveAppCallbacks.remove(cb)
            callback(error)
        }
    }
    liveAppCallbacks.add(cb)
    return cb
}

private fun historyMutationCb(callback: (String?) -> Unit): HistoryMutationCb {
    lateinit var cb: HistoryMutationCb
    cb = object : HistoryMutationCb {
        override fun invoke(error: String?, userData: Pointer?) {
            liveAppCallbacks.remove(cb)
            callback(error)
        }
    }
    liveAppCallbacks.add(cb)
    return cb
}

private val liveAppCallbacks = java.util.Collections.synchronizedSet(mutableSetOf<Any>())

class JvmTransportController internal constructor(
    internal val handle: Pointer
) : TransportController {
    override val isPlaying: Boolean get() = lib.uapmd_transport_is_playing(handle)
    override val isPaused: Boolean get() = lib.uapmd_transport_is_paused(handle)
    override val isRecording: Boolean get() = lib.uapmd_transport_is_recording(handle)

    override var volume: Float
        get() = lib.uapmd_transport_get_volume(handle)
        set(value) { lib.uapmd_transport_set_volume(handle, value) }

    override fun play() = lib.uapmd_transport_play(handle)
    override fun stop() = lib.uapmd_transport_stop(handle)
    override fun pause() = lib.uapmd_transport_pause(handle)
    override fun resume() = lib.uapmd_transport_resume(handle)
    override fun record() = lib.uapmd_transport_record(handle)
}

actual fun instantiateAppModel() = lib.uapmd_app_instantiate()

actual fun getAppModel(): AppModel =
    JvmAppModel(lib.uapmd_app_instance() ?: error("uapmd_app_instance returned null; call instantiateAppModel() first"))

actual fun cleanupAppModel() = lib.uapmd_app_cleanup()
