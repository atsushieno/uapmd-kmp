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

    override val slowScanProgress: SlowScanProgress
        get() = lib.uapmd_app_slow_scan_progress(handle).let {
            SlowScanProgress(
                running = it.running != 0.toByte(),
                processedBundles = it.processed_bundles.toUInt(),
                totalBundles = it.total_bundles.toUInt(),
                currentBundle = it.current_bundle.orEmpty()
            )
        }

    override val lastPluginScanError: String?
        get() = readJvmString { b, n -> lib.uapmd_app_last_plugin_scan_error(handle, b, n) }
            .ifEmpty { null }

    override fun generateScanReport(): String =
        readJvmString { buf, size -> lib.uapmd_app_generate_scan_report(handle, buf, size) }

    override fun clearPluginBlocklist() = lib.uapmd_app_clear_plugin_blocklist(handle)

    override val blocklist: List<BlocklistEntry>
        get() = (0 until lib.uapmd_app_blocklist_count(handle)).mapNotNull { i ->
            val out = UapmdBlocklistEntry()
            if (!lib.uapmd_app_get_blocklist_entry(handle, i, out)) null
            else BlocklistEntry(out.id ?: "", out.format ?: "", out.plugin_id ?: "", out.reason ?: "")
        }

    override fun unblockPlugin(entryId: String) = lib.uapmd_app_unblock_plugin_from_blocklist(handle, entryId)

    override fun refreshMasterTempoMap() = lib.uapmd_app_refresh_master_tempo_map(handle)

    override val masterTempoPoints: List<TempoPoint>
        get() = (0 until lib.uapmd_app_master_tempo_point_count(handle)).mapNotNull { i ->
            val out = UapmdTempoPoint()
            if (!lib.uapmd_app_get_master_tempo_point(handle, i, out)) null
            else TempoPoint(out.time_seconds, out.tick_position, out.bpm)
        }

    override val masterTimeSignaturePoints: List<TimeSignaturePoint>
        get() = (0 until lib.uapmd_app_master_time_signature_count(handle)).mapNotNull { i ->
            val out = UapmdTimeSignaturePoint()
            if (!lib.uapmd_app_get_master_time_signature(handle, i, out)) null
            else TimeSignaturePoint(
                out.time_seconds, out.tick_position,
                out.numerator.toInt() and 0xFF, out.denominator.toInt() and 0xFF
            )
        }

    // ── Tracks ──────────────────────────────────────────────────────────────

    override fun isTrackMuted(trackIndex: Int) = lib.uapmd_app_is_track_muted(handle, trackIndex)
    override fun isTrackSolo(trackIndex: Int) = lib.uapmd_app_is_track_solo(handle, trackIndex)
    override fun setTrackMuted(trackIndex: Int, muted: Boolean) =
        lib.uapmd_app_set_track_muted(handle, trackIndex, muted)
    override fun setTrackSolo(trackIndex: Int, solo: Boolean) =
        lib.uapmd_app_set_track_solo(handle, trackIndex, solo)

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
        get() = JvmTimelineTrack(lib.uapmd_app_get_master_timeline_track(handle) ?: error("master timeline track not found"))

    override fun getTimelineState(): TimelineState? {
        val out = UapmdTimelineState()
        if (!lib.uapmd_app_get_timeline_state(handle, out)) return null
        return out.toKotlin()
    }

    // ── History ─────────────────────────────────────────────────────────────

    override val historyState: UndoState
        get() = UapmdUndoState().also { lib.uapmd_app_history_state(handle, it) }.toKotlin()

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

    override fun newProject(): AppProjectResult = lib.uapmd_app_new_project(handle).toKotlin()

    // ── Timeline clip selection and clipboard ───────────────────────────────

    override fun isTimelineClipSelected(trackIndex: Int, clipId: Int): Boolean =
        lib.uapmd_app_is_timeline_clip_selected(handle, trackIndex, clipId)

    override val selectedTimelineClips: List<TimelineClipTarget>
        get() {
            val count = lib.uapmd_app_selected_timeline_clips(handle, null, 0)
            if (count <= 0) return emptyList()
            @Suppress("UNCHECKED_CAST")
            val arr = UapmdTimelineClipTarget().toArray(count) as Array<UapmdTimelineClipTarget>
            val filled = lib.uapmd_app_selected_timeline_clips(handle, arr[0], count)
            return arr.take(minOf(count, filled)).map {
                it.read()
                TimelineClipTarget(it.track_index, it.clip_id)
            }
        }

    override fun selectTimelineClips(clips: List<TimelineClipTarget>, additive: Boolean, toggle: Boolean) {
        if (clips.isEmpty()) {
            lib.uapmd_app_select_timeline_clips(handle, null, 0, additive, toggle)
            return
        }
        @Suppress("UNCHECKED_CAST")
        val arr = UapmdTimelineClipTarget().toArray(clips.size) as Array<UapmdTimelineClipTarget>
        clips.forEachIndexed { i, t ->
            arr[i].track_index = t.trackIndex
            arr[i].clip_id = t.clipId
            arr[i].write()
        }
        lib.uapmd_app_select_timeline_clips(handle, arr[0], clips.size, additive, toggle)
    }

    override fun clearTimelineClipSelection() = lib.uapmd_app_clear_timeline_clip_selection(handle)

    override fun selectTimelineMidiClip(trackIndex: Int, clipId: Int): Boolean =
        lib.uapmd_app_select_timeline_midi_clip(handle, trackIndex, clipId)

    override val selectedTimelineMidiClip: TimelineClipTarget?
        get() {
            val out = UapmdTimelineClipTarget()
            if (!lib.uapmd_app_selected_timeline_midi_clip(handle, out)) return null
            return TimelineClipTarget(out.track_index, out.clip_id)
        }

    override val timelineClipboardCount: Int get() = lib.uapmd_app_timeline_clipboard_count(handle)

    override fun clearTimelineClipboard() = lib.uapmd_app_clear_timeline_clipboard(handle)

    override fun copySelectedTimelineClips(): Boolean = lib.uapmd_app_copy_selected_timeline_clips(handle)

    override fun deleteSelectedTimelineClips(cut: Boolean): TimelineClipDeleteResult {
        // One call only: this both deletes and reports. A track can lose several
        // clips but appears once, so the selection size bounds the changed-track
        // list — measured before the call, which clears the selection.
        val capacity = selectedTimelineClips.size
        val countOut = intArrayOf(capacity)
        val tracks = if (capacity > 0) IntArray(capacity) else null
        val ok = lib.uapmd_app_delete_selected_timeline_clips(handle, cut, tracks, countOut)
        val changed = tracks?.take(minOf(capacity, countOut[0])).orEmpty()
        return TimelineClipDeleteResult(ok, changed, lastTimelineClipError.ifEmpty { null })
    }

    override fun timelinePasteDestinations(trackIndex: Int, originalTracks: Boolean): List<Int> {
        val count = lib.uapmd_app_timeline_paste_destinations(handle, trackIndex, originalTracks, null, 0)
        if (count <= 0) return emptyList()
        val out = IntArray(count)
        val filled = lib.uapmd_app_timeline_paste_destinations(handle, trackIndex, originalTracks, out, count)
        return out.take(minOf(count, filled))
    }

    override fun pasteTimelineClips(
        trackIndex: Int,
        positionSeconds: Double,
        originalTracks: Boolean
    ): TimelinePasteResult {
        // The clipboard bounds the result, so one call with a buffer that size
        // is enough — a paste creates at most one clip per clipboard entry.
        val capacity = timelineClipboardCount
        val countOut = intArrayOf(capacity)
        @Suppress("UNCHECKED_CAST")
        val arr = if (capacity > 0)
            UapmdTimelineClipTarget().toArray(capacity) as Array<UapmdTimelineClipTarget>
        else null
        val ok = lib.uapmd_app_paste_timeline_clips(
            handle, trackIndex, positionSeconds, originalTracks, arr?.get(0), countOut
        )
        val pasted = (0 until minOf(capacity, countOut[0])).map {
            arr!![it].read()
            TimelineClipTarget(arr[it].track_index, arr[it].clip_id)
        }
        return TimelinePasteResult(ok, pasted, lastTimelineClipError.ifEmpty { null })
    }

    override val lastTimelineClipError: String
        get() = lib.uapmd_app_last_timeline_clip_error() ?: ""

    // ── Piano roll editing session ──────────────────────────────────────────

    override fun pianoRollClipSnapshot(
        trackIndex: Int,
        clipId: Int,
        fallbackDurationSeconds: Double
    ): PianoRollSnapshot? =
        lib.uapmd_app_piano_roll_clip_snapshot(handle, trackIndex, clipId, fallbackDurationSeconds)
            ?.let { JvmPianoRollSnapshot(it) }

    override fun openPianoRollSession(trackIndex: Int, clipId: Int): PianoRollSession? =
        lib.uapmd_app_open_piano_roll_session(handle, trackIndex, clipId)?.let { JvmPianoRollSession(it) }

    override fun findPianoRollSession(trackIndex: Int, clipId: Int): PianoRollSession? =
        lib.uapmd_app_find_piano_roll_session(handle, trackIndex, clipId)?.let { JvmPianoRollSession(it) }

    override fun closePianoRollSession(trackIndex: Int, clipId: Int) =
        lib.uapmd_app_close_piano_roll_session(handle, trackIndex, clipId)

    override fun recordPianoRollCommitSource(trackIndex: Int, clipId: Int) =
        lib.uapmd_app_record_piano_roll_commit_source(handle, trackIndex, clipId)

    override fun pianoRollSourceMatchesLastEdit(): Boolean =
        lib.uapmd_app_piano_roll_source_matches_last_edit(handle)

    override fun clearPianoRollCommitSource() = lib.uapmd_app_clear_piano_roll_commit_source(handle)

    // ── Assorted accessors ──────────────────────────────────────────────────

    override val midiInputPorts: List<MidiPortInfo>
        get() = readMidiPorts { out, n -> lib.uapmd_app_get_midi_input_ports(handle, out, n) }

    override val midiOutputPorts: List<MidiPortInfo>
        get() = readMidiPorts { out, n -> lib.uapmd_app_get_midi_output_ports(handle, out, n) }

    override fun isTrackHidden(trackIndex: Int) = lib.uapmd_app_is_track_hidden(handle, trackIndex)

    override val timelineContentBounds: TimelineContentBounds
        get() = lib.uapmd_app_timeline_content_bounds(handle).let {
            TimelineContentBounds(
                it.has_content != 0.toByte(), it.start_seconds, it.end_seconds, it.duration_seconds
            )
        }

    override val devices: List<DeviceEntry>
        get() {
            val count = lib.uapmd_app_get_devices(handle, null, 0)
            if (count <= 0) return emptyList()
            @Suppress("UNCHECKED_CAST")
            val arr = UapmdDeviceEntry().toArray(count) as Array<UapmdDeviceEntry>
            val filled = lib.uapmd_app_get_devices(handle, arr[0], count)
            return arr.take(minOf(count, filled)).map { it.read(); it.toKotlin() }
        }

    override fun deviceForInstance(instanceId: Int): DeviceEntry? {
        val out = UapmdDeviceEntry()
        if (!lib.uapmd_app_get_device_for_instance(handle, instanceId, out)) return null
        return out.toKotlin()
    }

    override fun updateDeviceLabel(instanceId: Int, label: String) =
        lib.uapmd_app_update_device_label(handle, instanceId, label)

    override fun loadPluginState(instanceId: Int, filepath: String, callback: (PluginStateResult) -> Unit) =
        lib.uapmd_app_load_plugin_state(handle, instanceId, filepath, null, pluginStateCallback(callback))

    override fun savePluginState(instanceId: Int, filepath: String, callback: (PluginStateResult) -> Unit) =
        lib.uapmd_app_save_plugin_state(handle, instanceId, filepath, null, pluginStateCallback(callback))

    override fun loadPluginStateSync(instanceId: Int, filepath: String) =
        lib.uapmd_app_load_plugin_state_sync(handle, instanceId, filepath).toKotlin()

    override fun savePluginStateSync(instanceId: Int, filepath: String) =
        lib.uapmd_app_save_plugin_state_sync(handle, instanceId, filepath).toKotlin()

    override fun markPluginInstanceTrackDirty(instanceId: Int) =
        lib.uapmd_app_mark_plugin_instance_track_dirty(handle, instanceId)

    private fun readMidiPorts(call: (UapmdMidiPortInfo?, Int) -> Int): List<MidiPortInfo> {
        val count = call(null, 0)
        if (count <= 0) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val arr = UapmdMidiPortInfo().toArray(count) as Array<UapmdMidiPortInfo>
        val filled = call(arr[0], count)
        return arr.take(minOf(count, filled)).map {
            it.read(); MidiPortInfo(it.id ?: "", it.display_name ?: "")
        }
    }

    override val masterTempoMap: TempoMap
        get() = JvmTempoMap(lib.uapmd_app_master_tempo_map(handle) ?: error("no master tempo map"))

    // ── MIDI clip UMP events ────────────────────────────────────────────────

    override fun getMidiClipUmpEvents(trackIndex: Int, clipId: Int): UmpEventsResult {
        val r = lib.uapmd_app_get_midi_clip_ump_events(handle, trackIndex, clipId)
        if (r.success == 0.toByte() || r.events == null || r.event_count == 0)
            return UmpEventsResult(
                r.success != 0.toByte(), r.error, emptyList(),
                r.tick_resolution.toUInt(), r.clip_tempo
            )
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
        return UmpEventsResult(true, r.error, events, r.tick_resolution.toUInt(), r.clip_tempo)
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

    private val importCallbacks = mutableListOf<UapmdLibrary.MidiTracksImportCb>()

    override fun importMidiTracksFromFile(filepath: String, callback: (Boolean, String?, Int) -> Unit) {
        // JNA callbacks must stay reachable until the native side is done with them.
        val cb = object : UapmdLibrary.MidiTracksImportCb {
            override fun invoke(success: Boolean, error: String?, importedTrackCount: Int, userData: com.sun.jna.Pointer?) {
                importCallbacks.remove(this)
                callback(success, error, importedTrackCount)
            }
        }
        importCallbacks.add(cb)
        lib.uapmd_app_import_midi_tracks_from_file(handle, filepath, null, cb)
    }

    override fun createEmptyMidiClip(
        trackIndex: Int, positionSamples: Long, tickResolution: UInt, bpm: Double
    ): ClipAddResult {
        val r = lib.uapmd_app_create_empty_midi_clip(handle, trackIndex, positionSamples, tickResolution.toInt(), bpm)
        return ClipAddResult(r.clip_id, r.source_node_id, r.success != 0.toByte(), r.error)
    }

    override fun addClipToTrack(
        trackIndex: Int, position: TimelinePosition, reader: AudioFileReader, filepath: String
    ): ClipAddResult {
        val r = lib.uapmd_app_add_clip_to_track(
            handle, trackIndex, position.toJvmByVal(), (reader as JvmAudioFileReader).handle, filepath
        )
        return ClipAddResult(r.clip_id, r.source_node_id, r.success != 0.toByte(), r.error)
    }

    override fun addMidiClipToTrack(trackIndex: Int, position: TimelinePosition, filepath: String): ClipAddResult {
        val r = lib.uapmd_app_add_midi_clip_to_track(handle, trackIndex, position.toJvmByVal(), filepath)
        return ClipAddResult(r.clip_id, r.source_node_id, r.success != 0.toByte(), r.error)
    }

    override fun addMidiClipFromData(
        trackIndex: Int, position: TimelinePosition,
        umpEvents: List<UInt>, tickTimestamps: List<ULong>,
        tickResolution: UInt, clipTempo: Double,
        tempoChanges: List<MidiTempoChange>, timeSignatureChanges: List<MidiTimeSignatureChange>,
        clipName: String, needsFileSave: Boolean
    ): ClipAddResult {
        val r = lib.uapmd_app_add_midi_clip_from_data(
            handle, trackIndex, position.toJvmByVal(),
            umpEvents.takeIf { it.isNotEmpty() }?.map { it.toInt() }?.toIntArray(), umpEvents.size,
            tickTimestamps.takeIf { it.isNotEmpty() }?.map { it.toLong() }?.toLongArray(), tickTimestamps.size,
            tickResolution.toInt(), clipTempo,
            tempoChanges.toJvmArray(), tempoChanges.size,
            timeSignatureChanges.toJvmArray(), timeSignatureChanges.size,
            clipName, needsFileSave
        )
        return ClipAddResult(r.clip_id, r.source_node_id, r.success != 0.toByte(), r.error)
    }

    override fun addDeviceInputToTrack(trackIndex: Int, channelIndices: List<UInt>): Int =
        lib.uapmd_app_add_device_input_to_track(
            handle, trackIndex,
            channelIndices.takeIf { it.isNotEmpty() }?.map { it.toInt() }?.toIntArray(), channelIndices.size
        )

    // ── Master track markers ────────────────────────────────────────────────

    override val masterMarkers: List<ClipMarkerData>
        get() {
            val n = lib.uapmd_app_master_marker_count(handle)
            return (0 until n).mapNotNull { i ->
                val out = UapmdClipMarker()
                if (lib.uapmd_app_get_master_marker(handle, i, out)) out.toKotlin() else null
            }
        }

    override fun setMasterTrackMarkersWithValidation(markers: List<ClipMarkerData>): OpResult {
        val r = lib.uapmd_app_set_master_track_markers_with_validation(handle, markers.toJvmArray(), markers.size)
        return OpResult(r.success != 0.toByte(), r.error)
    }

    // ── Offline render to file ──────────────────────────────────────────────

    override fun startRenderToFile(settings: RenderToFileSettings): Boolean =
        lib.uapmd_app_start_render_to_file(handle, settings.toJvmSettings())

    override fun cancelRenderToFile() = lib.uapmd_app_cancel_render_to_file(handle)

    override val renderToFileStatus: RenderToFileStatus
        get() = lib.uapmd_app_get_render_to_file_status(handle).let {
            RenderToFileStatus(
                running = it.running != 0.toByte(),
                completed = it.completed != 0.toByte(),
                success = it.success != 0.toByte(),
                progress = it.progress,
                renderedSeconds = it.rendered_seconds,
                message = it.message ?: "",
                outputPath = it.output_path ?: ""
            )
        }

    override fun clearCompletedRenderStatus() = lib.uapmd_app_clear_completed_render_status(handle)

    override fun requestShowTrackGraph(trackIndex: Int) =
        lib.uapmd_app_request_show_track_graph(handle, trackIndex)

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

    override fun getTrackGraphNodes(trackIndex: Int): GraphNodesResult {
        val r = lib.uapmd_app_get_track_graph_nodes(handle, trackIndex)
        if (r.success == 0.toByte())
            return GraphNodesResult.failure(r.error)
        val busStride = UapmdGraphAudioBus().size().toLong()
        val buses = if (r.audio_buses == null || r.audio_bus_count == 0) emptyList()
        else (0 until r.audio_bus_count).map { i ->
            val b = UapmdGraphAudioBus(r.audio_buses!!.share(i * busStride))
            GraphAudioBus(
                name = b.name.orEmpty(),
                role = AudioBusRole.fromNative(b.role),
                enabled = b.enabled != 0.toByte(),
                channelLayoutName = b.channel_layout_name.orEmpty(),
                channelCount = b.channel_count.toUInt()
            )
        }
        val stride = UapmdGraphNode().size().toLong()
        val nodes = if (r.nodes == null || r.count == 0) emptyList() else (0 until r.count).map { i ->
            val n = UapmdGraphNode(r.nodes!!.share(i * stride))
            val from = n.audio_bus_offset
            GraphNode(
                nodeId = n.node_id.orEmpty(),
                nodeType = n.node_type.orEmpty(),
                displayName = n.display_name.orEmpty(),
                instanceId = n.instance_id,
                bypassed = n.bypassed != 0.toByte(),
                latencyInSamples = n.latency_in_samples.toUInt(),
                tailLengthInSeconds = n.tail_length_in_seconds,
                hasAudioBuses = n.has_audio_buses != 0.toByte(),
                hasEventInputs = n.has_event_inputs != 0.toByte(),
                hasEventOutputs = n.has_event_outputs != 0.toByte(),
                audioInputBuses = buses.busRange(from, n.audio_input_bus_count),
                audioOutputBuses = buses.busRange(from + n.audio_input_bus_count, n.audio_output_bus_count),
                mainInputBusIndex = n.main_input_bus_index,
                mainOutputBusIndex = n.main_output_bus_index
            )
        }
        return GraphNodesResult(
            true, r.error, nodes,
            r.graph_audio_input_bus_count.toUInt(),
            r.graph_audio_output_bus_count.toUInt(),
            r.graph_event_input_bus_count.toUInt(),
            r.graph_event_output_bus_count.toUInt()
        )
    }

    override fun connectTrackGraph(trackIndex: Int, connection: GraphConnection): OpResult {
        val r = lib.uapmd_app_connect_track_graph(handle, trackIndex, connection.toJvmStruct())
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
    GraphEndpoint(GraphEndpointType.fromNative(type), node_id.orEmpty(), instance_id, bus_index.toUInt())

internal fun GraphConnection.toJvmStruct() = UapmdGraphConnection().apply {
    id = this@toJvmStruct.id
    bus_type = busType.nativeValue
    source = this@toJvmStruct.source.toNative()
    target = this@toJvmStruct.target.toNative()
}

private fun GraphEndpoint.toNative() = UapmdGraphEndpoint().also {
    it.type = type.nativeValue
    it.node_id = nodeId.ifEmpty { null }
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

    override fun jump(positionSeconds: Double) = lib.uapmd_transport_jump(handle, positionSeconds)
}

actual fun instantiateAppModel() = lib.uapmd_app_instantiate()

actual fun getAppModel(): AppModel =
    JvmAppModel(lib.uapmd_app_instance() ?: error("uapmd_app_instance returned null; call instantiateAppModel() first"))

actual fun cleanupAppModel() = lib.uapmd_app_cleanup()

internal fun UapmdPianoRollNote.toKotlin() = PianoRollNote(
    startSeconds = start_seconds,
    durationSeconds = duration_seconds,
    velocity = velocity,
    note = note.toInt() and 0xFF,
    channel = channel.toInt() and 0xFF,
    deleted = deleted != 0.toByte(),
    editId = edit_id,
    umpGroup = ump_group.toInt() and 0xFF,
    releaseVelocity = release_velocity.toInt() and 0xFFFF,
    attributeType = attribute_type.toInt() and 0xFF,
    attributeValue = attribute_value.toInt() and 0xFFFF,
    automationEventCount = automation_event_count
)

class JvmPianoRollSnapshot internal constructor(internal val handle: Pointer) : PianoRollSnapshot {
    override val isReady: Boolean get() = lib.uapmd_piano_roll_snapshot_ready(handle)
    override val error: String get() = lib.uapmd_piano_roll_snapshot_error(handle) ?: ""
    override val durationSeconds: Double get() = lib.uapmd_piano_roll_snapshot_duration_seconds(handle)
    override val minNote: Int get() = lib.uapmd_piano_roll_snapshot_min_note(handle).toInt() and 0xFF
    override val maxNote: Int get() = lib.uapmd_piano_roll_snapshot_max_note(handle).toInt() and 0xFF

    override val notes: List<PianoRollNote>
        get() {
            val out = UapmdPianoRollNote()
            return (0 until lib.uapmd_piano_roll_snapshot_note_count(handle)).mapNotNull { i ->
                if (!lib.uapmd_piano_roll_snapshot_get_note(handle, i, out)) null else out.toKotlin()
            }
        }

    override fun close() = lib.uapmd_piano_roll_snapshot_destroy(handle)
}

class JvmPianoRollSession internal constructor(private val handle: Pointer) : PianoRollSession {
    override val notes: List<PianoRollNote>
        get() {
            val out = UapmdPianoRollNote()
            return (0 until lib.uapmd_piano_roll_session_note_count(handle)).mapNotNull { i ->
                if (!lib.uapmd_piano_roll_session_get_note(handle, i, out)) null else out.toKotlin()
            }
        }

    override fun isNoteSelected(index: Int) = lib.uapmd_piano_roll_session_is_note_selected(handle, index)
    override val selectedNoteCount: Int get() = lib.uapmd_piano_roll_session_selected_note_count(handle)

    override var focusedNote: Int
        get() = lib.uapmd_piano_roll_session_focused_note(handle)
        set(value) { lib.uapmd_piano_roll_session_set_focused_note(handle, value) }

    override val durationSeconds: Double get() = lib.uapmd_piano_roll_session_duration_seconds(handle)
    override val minNote: Int get() = lib.uapmd_piano_roll_session_min_note(handle).toInt() and 0xFF
    override val maxNote: Int get() = lib.uapmd_piano_roll_session_max_note(handle).toInt() and 0xFF
    override val clipboardCount: Int get() = lib.uapmd_piano_roll_session_clipboard_count(handle)
    override val isDirty: Boolean get() = lib.uapmd_piano_roll_session_dirty(handle)
    override val error: String get() = lib.uapmd_piano_roll_session_error(handle) ?: ""

    override fun matchesSource(snapshot: PianoRollSnapshot) =
        lib.uapmd_piano_roll_session_matches_source(handle, (snapshot as JvmPianoRollSnapshot).handle)

    override fun loadNotes(snapshot: PianoRollSnapshot?) =
        lib.uapmd_piano_roll_session_load_notes(handle, (snapshot as JvmPianoRollSnapshot?)?.handle)

    override fun selectNote(index: Int, additive: Boolean, toggle: Boolean) =
        lib.uapmd_piano_roll_session_select_note(handle, index, additive, toggle)

    override fun performAction(action: PianoRollAction, pasteSeconds: Double) =
        lib.uapmd_piano_roll_session_perform_action(handle, action.nativeValue, pasteSeconds)

    override fun createNote(startSeconds: Double, durationSeconds: Double, note: Int, velocity: Float) =
        lib.uapmd_piano_roll_session_create_note(handle, startSeconds, durationSeconds, note.toByte(), velocity)

    override fun deleteNote(index: Int) = lib.uapmd_piano_roll_session_delete_note(handle, index)

    override fun resizeNote(index: Int, startSeconds: Double, durationSeconds: Double, note: Int) =
        lib.uapmd_piano_roll_session_resize_note(handle, index, startSeconds, durationSeconds, note.toByte())

    override fun beginDrag() = lib.uapmd_piano_roll_session_begin_drag(handle)
    override fun moveSelection(timeDeltaSeconds: Double, pitchDelta: Int) =
        lib.uapmd_piano_roll_session_move_selection(handle, timeDeltaSeconds, pitchDelta)
    override fun cancelDrag() = lib.uapmd_piano_roll_session_cancel_drag(handle)
    override fun finishDrag(index: Int, originalStart: Double, originalEnd: Double, originalNote: Int) =
        lib.uapmd_piano_roll_session_finish_drag(handle, index, originalStart, originalEnd, originalNote.toByte())

    override fun commit(app: AppModel) =
        lib.uapmd_piano_roll_session_commit(handle, (app as JvmAppModel).handle)
}

private fun UapmdDeviceEntry.toKotlin() = DeviceEntry(
    id = id,
    label = label ?: "",
    apiName = api_name ?: "",
    statusMessage = status_message ?: "",
    running = running != 0.toByte(),
    instantiating = instantiating != 0.toByte(),
    hasError = has_error != 0.toByte()
)

private fun UapmdPluginStateResult.toKotlin() = PluginStateResult(
    instanceId = instance_id,
    success = success != 0.toByte(),
    error = error ?: "",
    filepath = filepath ?: ""
)

/**
 * JNA keeps a trampoline alive only while Java references the callback, so each
 * pending state call parks its own until the native side has fired it once.
 */
private val pendingPluginStateCallbacks = java.util.Collections.synchronizedSet(mutableSetOf<Any>())

private fun pluginStateCallback(callback: (PluginStateResult) -> Unit): PluginStateCb {
    lateinit var cb: PluginStateCb
    cb = object : PluginStateCb {
        override fun invoke(result: UapmdPluginStateResult.ByVal, userData: Pointer?) {
            try { callback(result.toKotlin()) } finally { pendingPluginStateCallbacks.remove(cb) }
        }
    }
    pendingPluginStateCallbacks.add(cb)
    return cb
}

private fun RenderToFileSettings.toJvmSettings() = UapmdAppRenderSettings().also {
    it.output_path = outputPath
    it.start_seconds = startSeconds
    it.end_seconds = endSeconds
    it.has_end_seconds = if (hasEndSeconds) 1 else 0
    it.use_content_fallback = if (useContentFallback) 1 else 0
    it.content_bounds_valid = if (contentBoundsValid) 1 else 0
    it.content_start_seconds = contentStartSeconds
    it.content_end_seconds = contentEndSeconds
    it.tail_seconds = tailSeconds
    it.enable_silence_stop = if (enableSilenceStop) 1 else 0
    it.silence_duration_seconds = silenceDurationSeconds
    it.silence_threshold_db = silenceThresholdDb
    it.write()
}

@JvmName("tempoChangesToJvmArray")
private fun List<MidiTempoChange>.toJvmArray(): UapmdMidiTempoChange? {
    if (isEmpty()) return null
    @Suppress("UNCHECKED_CAST")
    val arr = UapmdMidiTempoChange().toArray(size) as Array<UapmdMidiTempoChange>
    forEachIndexed { i, t ->
        arr[i].tick_position = t.tickPosition.toLong()
        arr[i].bpm = t.bpm
        arr[i].write()
    }
    return arr[0]
}

@JvmName("timeSigChangesToJvmArray")
private fun List<MidiTimeSignatureChange>.toJvmArray(): UapmdMidiTimeSigChange? {
    if (isEmpty()) return null
    @Suppress("UNCHECKED_CAST")
    val arr = UapmdMidiTimeSigChange().toArray(size) as Array<UapmdMidiTimeSigChange>
    forEachIndexed { i, t ->
        arr[i].tick_position = t.tickPosition.toLong()
        arr[i].numerator = t.numerator.toByte()
        arr[i].denominator = t.denominator.toByte()
        arr[i].clocks_per_click = t.clocksPerClick.toByte()
        arr[i].thirty_seconds_per_quarter = t.thirtySecondsPerQuarter.toByte()
        arr[i].write()
    }
    return arr[0]
}
