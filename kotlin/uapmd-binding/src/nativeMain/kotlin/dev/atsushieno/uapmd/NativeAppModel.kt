package dev.atsushieno.uapmd

import kotlinx.cinterop.*
import uapmd.*

class NativeAppModel internal constructor(
    internal val handle: uapmd_app_model_t
) : AppModel {

    override val sequencer: RealtimeSequencer
        // Borrowed: AppModel owns this; callers must not close it (see AppModel.sequencer).
        get() = NativeRealtimeSequencer(uapmd_app_sequencer(handle)!!)

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

    // ── Plugin scanning ─────────────────────────────────────────────────────

    override fun performPluginScanning(
        forceRescan: Boolean, mode: ScanMode, remoteTimeoutSeconds: Double, requireFastScanning: Boolean
    ) = uapmd_app_perform_plugin_scanning(
        handle, forceRescan,
        if (mode == ScanMode.Remote) UAPMD_PLUGIN_SCAN_REMOTE_PROCESS else UAPMD_PLUGIN_SCAN_IN_PROCESS,
        remoteTimeoutSeconds, requireFastScanning
    )

    override fun cancelPluginScanning() = uapmd_app_cancel_plugin_scanning(handle)

    override fun generateScanReport(): String =
        readCString { buf, size -> uapmd_app_generate_scan_report(handle, buf, size) }

    override fun clearPluginBlocklist() = uapmd_app_clear_plugin_blocklist(handle)

    // ── Tracks ──────────────────────────────────────────────────────────────

    override fun addTrack(callback: (Int, String?) -> Unit) =
        uapmd_app_add_track(handle, StableRef.create(callback).asCPointer(), appTrackMutationTrampoline)

    override fun removeTrack(trackIndex: Int, callback: (Int, String?) -> Unit) =
        uapmd_app_remove_track(handle, trackIndex, StableRef.create(callback).asCPointer(), appTrackMutationTrampoline)

    override fun removeAllTracks(callback: (String?) -> Unit) =
        uapmd_app_remove_all_tracks(handle, StableRef.create(callback).asCPointer(), appTrackClearTrampoline)

    override val timelineTrackCount: UInt get() = uapmd_app_timeline_track_count(handle)

    override fun getTimelineTrack(index: UInt): TimelineTrack =
        NativeTimelineTrack(uapmd_app_get_timeline_track(handle, index) ?: error("timeline track $index not found"))

    override val masterTimelineTrack: TimelineTrack
        get() = NativeTimelineTrack(uapmd_app_master_timeline_track(handle) ?: error("master timeline track not found"))

    override fun getTimelineState(): TimelineState? = memScoped {
        val out = alloc<uapmd_timeline_state_t>()
        if (!uapmd_app_get_timeline_state(handle, out.ptr)) return null
        out.toKotlin()
    }

    // ── History ─────────────────────────────────────────────────────────────

    override val historyState: UndoState
        get() = memScoped {
            val out = alloc<uapmd_undo_state_t>()
            uapmd_app_get_history_state(handle, out.ptr)
            out.toKotlin()
        }

    override fun undo(callback: ((String?) -> Unit)?) =
        uapmd_app_undo(handle, callback?.let { StableRef.create(it).asCPointer() }, appHistoryTrampoline)

    override fun redo(callback: ((String?) -> Unit)?) =
        uapmd_app_redo(handle, callback?.let { StableRef.create(it).asCPointer() }, appHistoryTrampoline)

    // ── Plugin instances ────────────────────────────────────────────────────

    override fun createPluginInstance(
        format: String, pluginId: String, trackIndex: Int,
        config: PluginInstanceConfig, callback: (PluginInstanceResult) -> Unit
    ) = memScoped {
        val c = alloc<uapmd_plugin_instance_config_t>()
        c.api_name = config.apiName.cstr.ptr
        c.device_name = config.deviceName.cstr.ptr
        c.manufacturer = config.manufacturer.cstr.ptr
        c.version = config.version.cstr.ptr
        c.state_file = config.stateFile.cstr.ptr
        uapmd_app_create_plugin_instance(
            handle, format, pluginId, trackIndex, c.ptr,
            StableRef.create(callback).asCPointer(), appInstanceCreatedTrampoline
        )
    }

    override fun removePluginInstance(instanceId: Int) = uapmd_app_remove_plugin_instance(handle, instanceId)

    override fun getInstanceGroup(instanceId: Int): UByte = uapmd_app_get_instance_group(handle, instanceId)

    override fun setInstanceGroup(instanceId: Int, group: UByte): Boolean =
        uapmd_app_set_instance_group(handle, instanceId, group)

    override fun enableUmpDevice(instanceId: Int, deviceName: String) =
        uapmd_app_enable_ump_device(handle, instanceId, deviceName)

    override fun disableUmpDevice(instanceId: Int) = uapmd_app_disable_ump_device(handle, instanceId)

    override fun requestShowInstanceDetails(instanceId: Int) =
        uapmd_app_request_show_instance_details(handle, instanceId)

    override fun requestShowPluginUi(instanceId: Int) = uapmd_app_request_show_plugin_ui(handle, instanceId)
    override fun hidePluginUi(instanceId: Int) = uapmd_app_hide_plugin_ui(handle, instanceId)

    // ── Project I/O ─────────────────────────────────────────────────────────

    override fun loadProject(filePath: String): AppProjectResult =
        uapmd_app_load_project(handle, filePath).useContents { toKotlin() }

    override fun saveProjectSync(filePath: String): AppProjectResult =
        uapmd_app_save_project_sync(handle, filePath).useContents { toKotlin() }

    override fun saveProject(filePath: String, callback: (AppProjectResult) -> Unit) =
        uapmd_app_save_project(handle, filePath, StableRef.create(callback).asCPointer(), appProjectSaveTrampoline)

    override fun loadProjectFromHandleToken(token: String): AppProjectResult =
        uapmd_app_load_project_from_handle_token(handle, token).useContents { toKotlin() }

    // ── MIDI clip UMP events ────────────────────────────────────────────────

    override fun getMidiClipUmpEvents(trackIndex: Int, clipId: Int): UmpEventsResult =
        uapmd_app_get_midi_clip_ump_events(handle, trackIndex, clipId).useContents {
            if (!success || events == null)
                return@useContents UmpEventsResult(success, error?.toKString(), emptyList())
            val list = (0 until event_count.toInt()).map { i ->
                val e = events!![i]
                val count = e.word_count.toInt()
                val words = UIntArray(count) { w -> e.words!![w] }
                UmpEvent(e.tick.toLong(), words)
            }
            UmpEventsResult(true, error?.toKString(), list)
        }

    override fun addUmpEventToClip(trackIndex: Int, clipId: Int, tick: Long, words: UIntArray): Boolean =
        memScoped {
            val buf = allocArray<UIntVar>(words.size)
            words.forEachIndexed { i, w -> buf[i] = w }
            uapmd_app_add_ump_event_to_clip(handle, trackIndex, clipId, tick.toULong(), buf, words.size.toUInt())
        }

    override fun removeUmpEventFromClip(trackIndex: Int, clipId: Int, eventIndex: Int): Boolean =
        uapmd_app_remove_ump_event_from_clip(handle, trackIndex, clipId, eventIndex)

    override fun removeClipFromTrack(trackIndex: Int, clipId: Int): Boolean =
        uapmd_app_remove_clip_from_track(handle, trackIndex, clipId)
}

private fun uapmd_app_project_result_t.toKotlin() =
    AppProjectResult(success, error?.toKString())

private val appProjectSaveTrampoline =
    staticCFunction<CValue<uapmd_app_project_result_t>, COpaquePointer?, Unit> { result, userData ->
        if (userData != null) {
            val ref = userData.asStableRef<(AppProjectResult) -> Unit>()
            result.useContents { ref.get()(AppProjectResult(success, error?.toKString())) }
            ref.dispose()
        }
    }

private val appInstanceCreatedTrampoline =
    staticCFunction<CValue<uapmd_plugin_instance_result_t>, COpaquePointer?, Unit> { result, userData ->
        if (userData != null) {
            val ref = userData.asStableRef<(PluginInstanceResult) -> Unit>()
            result.useContents {
                ref.get()(
                    PluginInstanceResult(
                        instanceId = instance_id,
                        pluginName = plugin_name?.toKString() ?: "",
                        error = error?.toKString()
                    )
                )
            }
            ref.dispose()
        }
    }

private val appTrackMutationTrampoline =
    staticCFunction<Int, CPointer<ByteVar>?, COpaquePointer?, Unit> { trackIndex, error, userData ->
        if (userData != null) {
            val ref = userData.asStableRef<(Int, String?) -> Unit>()
            ref.get()(trackIndex, error?.toKString())
            ref.dispose()
        }
    }

private val appTrackClearTrampoline =
    staticCFunction<CPointer<ByteVar>?, COpaquePointer?, Unit> { error, userData ->
        if (userData != null) {
            val ref = userData.asStableRef<(String?) -> Unit>()
            ref.get()(error?.toKString())
            ref.dispose()
        }
    }

private val appHistoryTrampoline =
    staticCFunction<CPointer<ByteVar>?, COpaquePointer?, Unit> { error, userData ->
        if (userData != null) {
            val ref = userData.asStableRef<(String?) -> Unit>()
            ref.get()(error?.toKString())
            ref.dispose()
        }
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
