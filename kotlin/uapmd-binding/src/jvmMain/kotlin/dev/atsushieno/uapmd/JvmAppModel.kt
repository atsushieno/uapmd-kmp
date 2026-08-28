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

    // ── Project I/O ─────────────────────────────────────────────────────────

    override fun loadProject(filePath: String): AppProjectResult =
        lib.uapmd_app_load_project(handle, filePath).toKotlin()

    override fun saveProjectSync(filePath: String): AppProjectResult =
        lib.uapmd_app_save_project_sync(handle, filePath).toKotlin()

    override fun saveProject(filePath: String, callback: (AppProjectResult) -> Unit) {
        lateinit var cb: ProjectSaveCb
        cb = object : ProjectSaveCb {
            override fun invoke(result: UapmdAppProjectResult.ByVal, userData: Pointer?) {
                liveAppCallbacks.remove(cb)
                callback(result.toKotlin())
            }
        }
        liveAppCallbacks.add(cb)
        lib.uapmd_app_save_project(handle, filePath, null, cb)
    }

    override fun loadProjectFromHandleToken(token: String): AppProjectResult =
        lib.uapmd_app_load_project_from_handle_token(handle, token).toKotlin()

    // ── MIDI clip UMP events ────────────────────────────────────────────────

    override fun getMidiClipUmpEvents(trackIndex: Int, clipId: Int): UmpEventsResult {
        val r = lib.uapmd_app_get_midi_clip_ump_events(handle, trackIndex, clipId)
        if (r.success == 0.toByte() || r.events == null || r.event_count == 0)
            return UmpEventsResult(r.success != 0.toByte(), r.error, emptyList())
        // Structure.useMemory is protected, so walk the array by offset instead:
        // uapmd_ump_event_t is { uint64 tick; uint32 word_count; const uint32* words }
        // = 8 + 4 + (4 pad) + 8 on LP64.
        val base = r.events!!
        val stride = UapmdUmpEvent().size().toLong()
        val events = (0 until r.event_count).map { i ->
            val e = UapmdUmpEvent(base.share(i * stride))
            val words = e.words?.getIntArray(0, e.word_count) ?: IntArray(0)
            UmpEvent(e.tick, UIntArray(words.size) { words[it].toUInt() })
        }
        return UmpEventsResult(true, r.error, events)
    }

    override fun addUmpEventToClip(trackIndex: Int, clipId: Int, tick: Long, words: UIntArray): Boolean =
        lib.uapmd_app_add_ump_event_to_clip(
            handle, trackIndex, clipId, tick,
            IntArray(words.size) { words[it].toInt() }, words.size
        )

    override fun removeUmpEventFromClip(trackIndex: Int, clipId: Int, eventIndex: Int): Boolean =
        lib.uapmd_app_remove_ump_event_from_clip(handle, trackIndex, clipId, eventIndex)

    override fun removeClipFromTrack(trackIndex: Int, clipId: Int): Boolean =
        lib.uapmd_app_remove_clip_from_track(handle, trackIndex, clipId)

    override fun createEmptyMidiClip(
        trackIndex: Int, positionSamples: Long, tickResolution: UInt, bpm: Double
    ): ClipAddResult {
        val r = lib.uapmd_app_create_empty_midi_clip(handle, trackIndex, positionSamples, tickResolution.toInt(), bpm)
        return ClipAddResult(r.clip_id, r.source_node_id, r.success != 0.toByte(), r.error)
    }

    // ── Track graph ─────────────────────────────────────────────────────────

    override fun ensureTrackUsesEditorGraph(trackIndex: Int): Boolean =
        lib.uapmd_app_ensure_track_uses_editor_graph(handle, trackIndex)

    override fun revertTrackToSimpleGraph(trackIndex: Int): Boolean =
        lib.uapmd_app_revert_track_to_simple_graph(handle, trackIndex)

    override fun getTrackGraphConnections(trackIndex: Int): GraphConnectionsResult {
        val r = lib.uapmd_app_get_track_graph_connections(handle, trackIndex)
        if (r.success == 0.toByte() || r.connections == null || r.count == 0)
            return GraphConnectionsResult(r.success != 0.toByte(), r.error, emptyList())
        val stride = UapmdGraphConnection().size().toLong()
        val list = (0 until r.count).map { i ->
            val c = UapmdGraphConnection(r.connections!!.share(i * stride))
            GraphConnection(
                id = c.id,
                busType = GraphBusType.fromNative(c.bus_type),
                source = c.source.toKotlin(),
                target = c.target.toKotlin()
            )
        }
        return GraphConnectionsResult(true, r.error, list)
    }

    override fun connectTrackGraph(trackIndex: Int, connection: GraphConnection): OpResult {
        val c = UapmdGraphConnection().apply {
            id = connection.id
            bus_type = connection.busType.nativeValue
            source = connection.source.toNative()
            target = connection.target.toNative()
        }
        val r = lib.uapmd_app_connect_track_graph(handle, trackIndex, c)
        return OpResult(r.success != 0.toByte(), r.error)
    }

    override fun disconnectTrackGraphConnection(trackIndex: Int, connectionId: Long): OpResult {
        val r = lib.uapmd_app_disconnect_track_graph_connection(handle, trackIndex, connectionId)
        return OpResult(r.success != 0.toByte(), r.error)
    }

    // ── Clip audio events ───────────────────────────────────────────────────

    override fun getClipAudioEvents(trackIndex: Int, clipId: Int): ClipAudioEventsResult {
        val r = lib.uapmd_app_get_clip_audio_events(handle, trackIndex, clipId)
        if (r.success == 0.toByte())
            return ClipAudioEventsResult(false, r.error, emptyList(), emptyList())

        val markerStride = UapmdClipMarker().size().toLong()
        val markers = if (r.markers == null) emptyList() else (0 until r.marker_count).map { i ->
            UapmdClipMarker(r.markers!!.share(i * markerStride)).toKotlin()
        }
        val warpStride = UapmdAudioWarpPoint().size().toLong()
        val warps = if (r.audio_warps == null) emptyList() else (0 until r.audio_warp_count).map { i ->
            UapmdAudioWarpPoint(r.audio_warps!!.share(i * warpStride)).toKotlinWarp()
        }
        return ClipAudioEventsResult(true, r.error, markers, warps)
    }

    override fun setClipAudioEvents(
        trackIndex: Int, clipId: Int,
        markers: List<ClipMarkerData>, warps: List<AudioWarpPointData>
    ): OpResult {
        val r = lib.uapmd_app_set_clip_audio_events(
            handle, trackIndex, clipId,
            markers.toJvmArray(), markers.size,
            warps.toJvmArray(), warps.size
        )
        return OpResult(r.success != 0.toByte(), r.error)
    }
}

private fun UapmdAudioWarpPoint.toKotlinWarp() = AudioWarpPointData(
    clipPositionOffset = clip_position_offset,
    speedRatio = speed_ratio,
    referenceType = WarpReferenceType.fromNative(reference_type),
    referenceClipId = reference_clip_id ?: "",
    referenceMarkerId = reference_marker_id ?: ""
)

private fun UapmdGraphEndpoint.toKotlin() =
    GraphEndpoint(GraphEndpointType.fromNative(type), instance_id, bus_index.toUInt())

private fun GraphEndpoint.toNative() = UapmdGraphEndpoint().also {
    it.type = type.nativeValue
    it.instance_id = instanceId
    it.bus_index = busIndex.toInt()
}

private fun UapmdAppProjectResult.toKotlin() = AppProjectResult(success != 0.toByte(), error)

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
