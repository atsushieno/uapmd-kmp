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

    override val slowScanProgress: SlowScanProgress
        get() = uapmd_app_slow_scan_progress(handle).useContents {
            SlowScanProgress(
                running = running,
                processedBundles = processed_bundles,
                totalBundles = total_bundles,
                currentBundle = current_bundle?.toKString().orEmpty()
            )
        }

    override val lastPluginScanError: String?
        get() = readCString { buf, size -> uapmd_app_last_plugin_scan_error(handle, buf, size) }
            .ifEmpty { null }

    override fun generateScanReport(): String =
        readCString { buf, size -> uapmd_app_generate_scan_report(handle, buf, size) }

    override fun clearPluginBlocklist() = uapmd_app_clear_plugin_blocklist(handle)

    override val blocklist: List<BlocklistEntry>
        get() = memScoped {
            val n = uapmd_app_blocklist_count(handle).toInt()
            (0 until n).mapNotNull { i ->
                val out = alloc<uapmd_blocklist_entry_t>()
                if (!uapmd_app_get_blocklist_entry(handle, i.toUInt(), out.ptr)) null
                else BlocklistEntry(
                    out.id?.toKString() ?: "",
                    out.format?.toKString() ?: "",
                    out.plugin_id?.toKString() ?: "",
                    out.reason?.toKString() ?: ""
                )
            }
        }

    override fun unblockPlugin(entryId: String) = uapmd_app_unblock_plugin_from_blocklist(handle, entryId)

    override fun refreshMasterTempoMap() = uapmd_app_refresh_master_tempo_map(handle)

    override val masterTempoPoints: List<TempoPoint>
        get() = memScoped {
            val n = uapmd_app_master_tempo_point_count(handle).toInt()
            (0 until n).mapNotNull { i ->
                val out = alloc<uapmd_tempo_point_t>()
                if (!uapmd_app_get_master_tempo_point(handle, i.toUInt(), out.ptr)) null
                else TempoPoint(out.time_seconds, out.tick_position.toLong(), out.bpm)
            }
        }

    override val masterTimeSignaturePoints: List<TimeSignaturePoint>
        get() = memScoped {
            val n = uapmd_app_master_time_signature_count(handle).toInt()
            (0 until n).mapNotNull { i ->
                val out = alloc<uapmd_time_signature_point_t>()
                if (!uapmd_app_get_master_time_signature(handle, i.toUInt(), out.ptr)) null
                else TimeSignaturePoint(
                    out.time_seconds, out.tick_position.toLong(),
                    out.numerator.toInt(), out.denominator.toInt()
                )
            }
        }

    // ── Tracks ──────────────────────────────────────────────────────────────

    override fun isTrackMuted(trackIndex: Int) = uapmd_app_is_track_muted(handle, trackIndex)
    override fun isTrackSolo(trackIndex: Int) = uapmd_app_is_track_solo(handle, trackIndex)
    override fun setTrackMuted(trackIndex: Int, muted: Boolean) =
        uapmd_app_set_track_muted(handle, trackIndex, muted)
    override fun setTrackSolo(trackIndex: Int, solo: Boolean) =
        uapmd_app_set_track_solo(handle, trackIndex, solo)

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
        get() = NativeTimelineTrack(uapmd_app_get_master_timeline_track(handle) ?: error("master timeline track not found"))

    override fun getTimelineState(): TimelineState? = memScoped {
        val out = alloc<uapmd_timeline_state_t>()
        if (!uapmd_app_get_timeline_state(handle, out.ptr)) return null
        out.toKotlin()
    }

    // ── History ─────────────────────────────────────────────────────────────

    override val historyState: UndoState
        get() = memScoped {
            val out = alloc<uapmd_undo_state_t>()
            uapmd_app_history_state(handle, out.ptr)
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

    override fun newProject(): AppProjectResult =
        uapmd_app_new_project(handle).useContents { toKotlin() }

    // ── Timeline clip selection and clipboard ───────────────────────────────

    override fun isTimelineClipSelected(trackIndex: Int, clipId: Int): Boolean =
        uapmd_app_is_timeline_clip_selected(handle, trackIndex, clipId)

    override val selectedTimelineClips: List<TimelineClipTarget>
        get() = memScoped {
            val count = uapmd_app_selected_timeline_clips(handle, null, 0u).toInt()
            if (count <= 0) return@memScoped emptyList()
            val buf = allocArray<uapmd_timeline_clip_target_t>(count)
            val filled = uapmd_app_selected_timeline_clips(handle, buf, count.toUInt()).toInt()
            (0 until minOf(count, filled)).map {
                TimelineClipTarget(buf[it].track_index, buf[it].clip_id)
            }
        }

    override fun selectTimelineClips(clips: List<TimelineClipTarget>, additive: Boolean, toggle: Boolean) = memScoped {
        if (clips.isEmpty()) {
            uapmd_app_select_timeline_clips(handle, null, 0u, additive, toggle)
            return@memScoped
        }
        val buf = allocArray<uapmd_timeline_clip_target_t>(clips.size)
        clips.forEachIndexed { i, t ->
            buf[i].track_index = t.trackIndex
            buf[i].clip_id = t.clipId
        }
        uapmd_app_select_timeline_clips(handle, buf, clips.size.toUInt(), additive, toggle)
    }

    override fun clearTimelineClipSelection() = uapmd_app_clear_timeline_clip_selection(handle)

    override fun selectTimelineMidiClip(trackIndex: Int, clipId: Int): Boolean =
        uapmd_app_select_timeline_midi_clip(handle, trackIndex, clipId)

    override val selectedTimelineMidiClip: TimelineClipTarget?
        get() = memScoped {
            val out = alloc<uapmd_timeline_clip_target_t>()
            if (!uapmd_app_selected_timeline_midi_clip(handle, out.ptr)) null
            else TimelineClipTarget(out.track_index, out.clip_id)
        }

    override val timelineClipboardCount: Int
        get() = uapmd_app_timeline_clipboard_count(handle).toInt()

    override fun clearTimelineClipboard() = uapmd_app_clear_timeline_clipboard(handle)

    override fun copySelectedTimelineClips(): Boolean = uapmd_app_copy_selected_timeline_clips(handle)

    override fun deleteSelectedTimelineClips(cut: Boolean): TimelineClipDeleteResult = memScoped {
        // One call only: this both deletes and reports. A track can lose several
        // clips but appears once, so the selection size bounds the changed-track
        // list — measured before the call, which clears the selection.
        val capacity = selectedTimelineClips.size
        val countOut = alloc<UIntVar>()
        countOut.value = capacity.toUInt()
        val buf = if (capacity > 0) allocArray<IntVar>(capacity) else null
        val ok = uapmd_app_delete_selected_timeline_clips(handle, cut, buf, countOut.ptr)
        val changed = if (buf == null) emptyList()
        else (0 until minOf(capacity, countOut.value.toInt())).map { buf[it] }
        TimelineClipDeleteResult(ok, changed, lastTimelineClipError.ifEmpty { null })
    }

    override fun timelinePasteDestinations(trackIndex: Int, originalTracks: Boolean): List<Int> = memScoped {
        val count = uapmd_app_timeline_paste_destinations(handle, trackIndex, originalTracks, null, 0u).toInt()
        if (count <= 0) return@memScoped emptyList()
        val buf = allocArray<IntVar>(count)
        val filled = uapmd_app_timeline_paste_destinations(
            handle, trackIndex, originalTracks, buf, count.toUInt()).toInt()
        (0 until minOf(count, filled)).map { buf[it] }
    }

    override fun pasteTimelineClips(
        trackIndex: Int,
        positionSeconds: Double,
        originalTracks: Boolean
    ): TimelinePasteResult = memScoped {
        // A paste creates at most one clip per clipboard entry, so that bounds
        // the result and one call is enough.
        val capacity = timelineClipboardCount
        val countOut = alloc<UIntVar>()
        countOut.value = capacity.toUInt()
        val buf = if (capacity > 0) allocArray<uapmd_timeline_clip_target_t>(capacity) else null
        val ok = uapmd_app_paste_timeline_clips(
            handle, trackIndex, positionSeconds, originalTracks, buf, countOut.ptr)
        val pasted = if (buf == null) emptyList()
        else (0 until minOf(capacity, countOut.value.toInt())).map {
            TimelineClipTarget(buf[it].track_index, buf[it].clip_id)
        }
        TimelinePasteResult(ok, pasted, lastTimelineClipError.ifEmpty { null })
    }

    override val lastTimelineClipError: String
        get() = uapmd_app_last_timeline_clip_error()?.toKString() ?: ""

    // ── Piano roll editing session ──────────────────────────────────────────

    override fun pianoRollClipSnapshot(
        trackIndex: Int,
        clipId: Int,
        fallbackDurationSeconds: Double
    ): PianoRollSnapshot? =
        uapmd_app_piano_roll_clip_snapshot(handle, trackIndex, clipId, fallbackDurationSeconds)
            ?.let { NativePianoRollSnapshot(it) }

    override fun openPianoRollSession(trackIndex: Int, clipId: Int): PianoRollSession? =
        uapmd_app_open_piano_roll_session(handle, trackIndex, clipId)?.let { NativePianoRollSession(it) }

    override fun findPianoRollSession(trackIndex: Int, clipId: Int): PianoRollSession? =
        uapmd_app_find_piano_roll_session(handle, trackIndex, clipId)?.let { NativePianoRollSession(it) }

    override fun closePianoRollSession(trackIndex: Int, clipId: Int) =
        uapmd_app_close_piano_roll_session(handle, trackIndex, clipId)

    override fun recordPianoRollCommitSource(trackIndex: Int, clipId: Int) =
        uapmd_app_record_piano_roll_commit_source(handle, trackIndex, clipId)

    override fun pianoRollSourceMatchesLastEdit(): Boolean =
        uapmd_app_piano_roll_source_matches_last_edit(handle)

    override fun clearPianoRollCommitSource() = uapmd_app_clear_piano_roll_commit_source(handle)

    // ── Assorted accessors ──────────────────────────────────────────────────

    override val midiInputPorts: List<MidiPortInfo>
        get() = readMidiPorts { out, n -> uapmd_app_get_midi_input_ports(handle, out, n) }

    override val midiOutputPorts: List<MidiPortInfo>
        get() = readMidiPorts { out, n -> uapmd_app_get_midi_output_ports(handle, out, n) }

    override fun isTrackHidden(trackIndex: Int) = uapmd_app_is_track_hidden(handle, trackIndex)

    override val timelineContentBounds: TimelineContentBounds
        get() = uapmd_app_timeline_content_bounds(handle).useContents {
            TimelineContentBounds(has_content, start_seconds, end_seconds, duration_seconds)
        }

    override val devices: List<DeviceEntry>
        get() = memScoped {
            val count = uapmd_app_get_devices(handle, null, 0u).toInt()
            if (count <= 0) return@memScoped emptyList()
            val buf = allocArray<uapmd_device_entry_t>(count)
            val filled = uapmd_app_get_devices(handle, buf, count.toUInt()).toInt()
            (0 until minOf(count, filled)).map { buf[it].toKotlin() }
        }

    override fun deviceForInstance(instanceId: Int): DeviceEntry? = memScoped {
        val out = alloc<uapmd_device_entry_t>()
        if (!uapmd_app_get_device_for_instance(handle, instanceId, out.ptr)) null else out.toKotlin()
    }

    override fun updateDeviceLabel(instanceId: Int, label: String) =
        uapmd_app_update_device_label(handle, instanceId, label)

    override fun loadPluginState(instanceId: Int, filepath: String, callback: (PluginStateResult) -> Unit) =
        uapmd_app_load_plugin_state(handle, instanceId, filepath,
            StableRef.create(callback).asCPointer(), pluginStateTrampoline)

    override fun savePluginState(instanceId: Int, filepath: String, callback: (PluginStateResult) -> Unit) =
        uapmd_app_save_plugin_state(handle, instanceId, filepath,
            StableRef.create(callback).asCPointer(), pluginStateTrampoline)

    override fun loadPluginStateSync(instanceId: Int, filepath: String): PluginStateResult =
        uapmd_app_load_plugin_state_sync(handle, instanceId, filepath).useContents { toKotlin() }

    override fun savePluginStateSync(instanceId: Int, filepath: String): PluginStateResult =
        uapmd_app_save_plugin_state_sync(handle, instanceId, filepath).useContents { toKotlin() }

    override fun markPluginInstanceTrackDirty(instanceId: Int) =
        uapmd_app_mark_plugin_instance_track_dirty(handle, instanceId)

    private fun readMidiPorts(
        call: (CPointer<uapmd_midi_port_info_t>?, UInt) -> UInt
    ): List<MidiPortInfo> = memScoped {
        val count = call(null, 0u).toInt()
        if (count <= 0) return@memScoped emptyList()
        val buf = allocArray<uapmd_midi_port_info_t>(count)
        val filled = call(buf, count.toUInt()).toInt()
        (0 until minOf(count, filled)).map {
            MidiPortInfo(buf[it].id?.toKString() ?: "", buf[it].display_name?.toKString() ?: "")
        }
    }

    override val masterTempoMap: TempoMap
        get() = NativeTempoMap(uapmd_app_master_tempo_map(handle)!!)

    // ── MIDI clip UMP events ────────────────────────────────────────────────

    override fun getMidiClipUmpEvents(trackIndex: Int, clipId: Int): UmpEventsResult =
        uapmd_app_get_midi_clip_ump_events(handle, trackIndex, clipId).useContents {
            if (!success || events == null)
                return@useContents UmpEventsResult(
                    success, error?.toKString(), emptyList(), tick_resolution, clip_tempo
                )
            val list = (0 until event_count.toInt()).map { i ->
                val e = events!![i]
                val count = e.word_count.toInt()
                val words = UIntArray(count) { w -> e.words!![w] }
                UmpEvent(e.tick.toLong(), words)
            }
            UmpEventsResult(true, error?.toKString(), list, tick_resolution, clip_tempo)
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

    override fun importMidiTracksFromFile(filepath: String, callback: (Boolean, String?, Int) -> Unit) =
        uapmd_app_import_midi_tracks_from_file(
            handle, filepath, StableRef.create(callback).asCPointer(), appMidiTracksImportTrampoline
        )

    override fun createEmptyMidiClip(
        trackIndex: Int, positionSamples: Long, tickResolution: UInt, bpm: Double
    ): ClipAddResult =
        uapmd_app_create_empty_midi_clip(handle, trackIndex, positionSamples, tickResolution, bpm)
            .useContents { ClipAddResult(clip_id, source_node_id, success, error?.toKString()) }

    override fun addClipToTrack(
        trackIndex: Int, position: TimelinePosition, reader: AudioFileReader, filepath: String
    ): ClipAddResult = memScoped {
        uapmd_app_add_clip_to_track(
            handle, trackIndex, position.toNativeCValue(),
            (reader as NativeAudioFileReader).handle, filepath
        ).useContents { ClipAddResult(clip_id, source_node_id, success, error?.toKString()) }
    }

    override fun addMidiClipToTrack(trackIndex: Int, position: TimelinePosition, filepath: String): ClipAddResult =
        memScoped {
            uapmd_app_add_midi_clip_to_track(handle, trackIndex, position.toNativeCValue(), filepath)
                .useContents { ClipAddResult(clip_id, source_node_id, success, error?.toKString()) }
        }

    override fun addMidiClipFromData(
        trackIndex: Int, position: TimelinePosition,
        umpEvents: List<UInt>, tickTimestamps: List<ULong>,
        tickResolution: UInt, clipTempo: Double,
        tempoChanges: List<MidiTempoChange>, timeSignatureChanges: List<MidiTimeSignatureChange>,
        clipName: String, needsFileSave: Boolean
    ): ClipAddResult = memScoped {
        val ump = if (umpEvents.isEmpty()) null else allocArray<UIntVar>(umpEvents.size).also { b ->
            umpEvents.forEachIndexed { i, v -> b[i] = v }
        }
        val ticks = if (tickTimestamps.isEmpty()) null else allocArray<ULongVar>(tickTimestamps.size).also { b ->
            tickTimestamps.forEachIndexed { i, v -> b[i] = v }
        }
        val tempos = if (tempoChanges.isEmpty()) null else allocArray<uapmd_midi_tempo_change_t>(tempoChanges.size).also { b ->
            tempoChanges.forEachIndexed { i, t -> b[i].tick_position = t.tickPosition; b[i].bpm = t.bpm }
        }
        val sigs = if (timeSignatureChanges.isEmpty()) null else allocArray<uapmd_midi_time_sig_change_t>(timeSignatureChanges.size).also { b ->
            timeSignatureChanges.forEachIndexed { i, t ->
                b[i].tick_position = t.tickPosition
                b[i].numerator = t.numerator
                b[i].denominator = t.denominator
                b[i].clocks_per_click = t.clocksPerClick
                b[i].thirty_seconds_per_quarter = t.thirtySecondsPerQuarter
            }
        }
        uapmd_app_add_midi_clip_from_data(
            handle, trackIndex, position.toNativeCValue(),
            ump, umpEvents.size.toUInt(), ticks, tickTimestamps.size.toUInt(),
            tickResolution, clipTempo,
            tempos, tempoChanges.size.toUInt(), sigs, timeSignatureChanges.size.toUInt(),
            clipName, needsFileSave
        ).useContents { ClipAddResult(clip_id, source_node_id, success, error?.toKString()) }
    }

    override fun addDeviceInputToTrack(trackIndex: Int, channelIndices: List<UInt>): Int = memScoped {
        val buf = if (channelIndices.isEmpty()) null else allocArray<UIntVar>(channelIndices.size).also { b ->
            channelIndices.forEachIndexed { i, v -> b[i] = v }
        }
        uapmd_app_add_device_input_to_track(handle, trackIndex, buf, channelIndices.size.toUInt())
    }

    // ── Master track markers ────────────────────────────────────────────────

    override val masterMarkers: List<ClipMarkerData>
        get() = memScoped {
            val count = uapmd_app_master_marker_count(handle).toInt()
            if (count == 0) return emptyList()
            val out = alloc<uapmd_clip_marker_t>()
            (0 until count).mapNotNull { i ->
                if (!uapmd_app_get_master_marker(handle, i.toUInt(), out.ptr)) null else out.toKotlin()
            }
        }

    override fun setMasterTrackMarkersWithValidation(markers: List<ClipMarkerData>): OpResult = memScoped {
        uapmd_app_set_master_track_markers_with_validation(
            handle, markersToNative(markers), markers.size.toUInt()
        ).useContents { OpResult(success, error?.toKString()) }
    }

    // ── Offline render to file ──────────────────────────────────────────────

    override fun startRenderToFile(settings: RenderToFileSettings): Boolean = memScoped {
        val s = alloc<uapmd_app_render_settings_t>()
        s.output_path = settings.outputPath.cstr.ptr
        s.start_seconds = settings.startSeconds
        s.end_seconds = settings.endSeconds
        s.has_end_seconds = settings.hasEndSeconds
        s.use_content_fallback = settings.useContentFallback
        s.content_bounds_valid = settings.contentBoundsValid
        s.content_start_seconds = settings.contentStartSeconds
        s.content_end_seconds = settings.contentEndSeconds
        s.tail_seconds = settings.tailSeconds
        s.enable_silence_stop = settings.enableSilenceStop
        s.silence_duration_seconds = settings.silenceDurationSeconds
        s.silence_threshold_db = settings.silenceThresholdDb
        uapmd_app_start_render_to_file(handle, s.ptr)
    }

    override fun cancelRenderToFile() = uapmd_app_cancel_render_to_file(handle)

    override val renderToFileStatus: RenderToFileStatus
        get() = uapmd_app_get_render_to_file_status(handle).useContents {
            RenderToFileStatus(
                running, completed, success, progress, rendered_seconds,
                message?.toKString() ?: "", output_path?.toKString() ?: ""
            )
        }

    override fun clearCompletedRenderStatus() = uapmd_app_clear_completed_render_status(handle)

    override fun requestShowTrackGraph(trackIndex: Int) =
        uapmd_app_request_show_track_graph(handle, trackIndex)

    // ── Track graph ─────────────────────────────────────────────────────────

    override fun ensureTrackUsesEditorGraph(trackIndex: Int): Boolean =
        uapmd_app_ensure_track_uses_editor_graph(handle, trackIndex)

    override fun revertTrackToSimpleGraph(trackIndex: Int): Boolean =
        uapmd_app_revert_track_to_simple_graph(handle, trackIndex)

    override fun getTrackGraphConnections(trackIndex: Int): GraphConnectionsResult =
        uapmd_app_get_track_graph_connections(handle, trackIndex).useContents {
            if (!success || connections == null)
                return@useContents GraphConnectionsResult(success, error?.toKString(), emptyList())
            val list = (0 until count.toInt()).map { i ->
                val c = connections!![i]
                GraphConnection(
                    id = c.id,
                    busType = GraphBusType.fromNative(c.bus_type.toInt()),
                    source = c.source.readValue().useContents { toKotlinEndpoint() },
                    target = c.target.readValue().useContents { toKotlinEndpoint() }
                )
            }
            GraphConnectionsResult(true, error?.toKString(), list)
        }

    override fun getTrackGraphNodes(trackIndex: Int): GraphNodesResult =
        uapmd_app_get_track_graph_nodes(handle, trackIndex).useContents {
            if (!success)
                return@useContents GraphNodesResult.failure(error?.toKString())
            val buses = if (audio_buses == null) emptyList() else (0 until audio_bus_count.toInt()).map { i ->
                val b = audio_buses!![i]
                GraphAudioBus(
                    name = b.name?.toKString().orEmpty(),
                    role = AudioBusRole.fromNative(b.role.toInt()),
                    enabled = b.enabled,
                    channelLayoutName = b.channel_layout_name?.toKString().orEmpty(),
                    channelCount = b.channel_count
                )
            }
            val list = if (nodes == null) emptyList() else (0 until count.toInt()).map { i ->
                val n = nodes!![i]
                val from = n.audio_bus_offset.toInt()
                val inCount = n.audio_input_bus_count.toInt()
                GraphNode(
                    nodeId = n.node_id?.toKString().orEmpty(),
                    nodeType = n.node_type?.toKString().orEmpty(),
                    displayName = n.display_name?.toKString().orEmpty(),
                    instanceId = n.instance_id,
                    bypassed = n.bypassed,
                    latencyInSamples = n.latency_in_samples,
                    tailLengthInSeconds = n.tail_length_in_seconds,
                    hasAudioBuses = n.has_audio_buses,
                    hasEventInputs = n.has_event_inputs,
                    hasEventOutputs = n.has_event_outputs,
                    audioInputBuses = buses.busRange(from, inCount),
                    audioOutputBuses = buses.busRange(from + inCount, n.audio_output_bus_count.toInt()),
                    mainInputBusIndex = n.main_input_bus_index,
                    mainOutputBusIndex = n.main_output_bus_index
                )
            }
            GraphNodesResult(
                true, error?.toKString(), list,
                graph_audio_input_bus_count,
                graph_audio_output_bus_count,
                graph_event_input_bus_count,
                graph_event_output_bus_count
            )
        }

    override fun connectTrackGraph(trackIndex: Int, connection: GraphConnection): OpResult = memScoped {
        val c = alloc<uapmd_graph_connection_t>()
        c.id = connection.id
        c.bus_type = connection.busType.nativeValue.toUInt()
        c.source.type = connection.source.type.nativeValue.toUInt()
        // The node ids live in this memScoped block, which outlives the call.
        c.source.node_id = connection.source.nodeId.cstr.ptr
        c.source.instance_id = connection.source.instanceId
        c.source.bus_index = connection.source.busIndex
        c.target.type = connection.target.type.nativeValue.toUInt()
        c.target.node_id = connection.target.nodeId.cstr.ptr
        c.target.instance_id = connection.target.instanceId
        c.target.bus_index = connection.target.busIndex
        uapmd_app_connect_track_graph(handle, trackIndex, c.ptr).useContents {
            OpResult(success, error?.toKString())
        }
    }

    override fun disconnectTrackGraphConnection(trackIndex: Int, connectionId: Long): OpResult =
        uapmd_app_disconnect_track_graph_connection(handle, trackIndex, connectionId).useContents {
            OpResult(success, error?.toKString())
        }

    // ── Clip audio events ───────────────────────────────────────────────────

    override fun getClipAudioEvents(trackIndex: Int, clipId: Int): ClipAudioEventsResult =
        uapmd_app_get_clip_audio_events(handle, trackIndex, clipId).useContents {
            if (!success) return@useContents ClipAudioEventsResult(false, error?.toKString(), emptyList(), emptyList())
            val ms = if (markers == null) emptyList() else (0 until marker_count.toInt()).map { i ->
                val m = markers!![i]
                ClipMarkerData(
                    markerId = m.marker_id?.toKString() ?: "",
                    clipPositionOffset = m.clip_position_offset,
                    referenceType = WarpReferenceType.fromNative(m.reference_type.toInt()),
                    referenceClipId = m.reference_clip_id?.toKString() ?: "",
                    referenceMarkerId = m.reference_marker_id?.toKString() ?: "",
                    name = m.name?.toKString() ?: ""
                )
            }
            val ws = if (audio_warps == null) emptyList() else (0 until audio_warp_count.toInt()).map { i ->
                val w = audio_warps!![i]
                AudioWarpPointData(
                    clipPositionOffset = w.clip_position_offset,
                    speedRatio = w.speed_ratio,
                    referenceType = WarpReferenceType.fromNative(w.reference_type.toInt()),
                    referenceClipId = w.reference_clip_id?.toKString() ?: "",
                    referenceMarkerId = w.reference_marker_id?.toKString() ?: ""
                )
            }
            ClipAudioEventsResult(true, error?.toKString(), ms, ws)
        }

    override fun setClipAudioEvents(
        trackIndex: Int, clipId: Int,
        markers: List<ClipMarkerData>, warps: List<AudioWarpPointData>
    ): OpResult = memScoped {
        val m = allocArray<uapmd_clip_marker_t>(markers.size.coerceAtLeast(1))
        markers.forEachIndexed { i, d ->
            m[i].marker_id = d.markerId.cstr.ptr
            m[i].clip_position_offset = d.clipPositionOffset
            m[i].reference_type = d.referenceType.nativeValue.toUInt()
            m[i].reference_clip_id = d.referenceClipId.cstr.ptr
            m[i].reference_marker_id = d.referenceMarkerId.cstr.ptr
            m[i].name = d.name.cstr.ptr
        }
        val w = allocArray<uapmd_audio_warp_point_t>(warps.size.coerceAtLeast(1))
        warps.forEachIndexed { i, d ->
            w[i].clip_position_offset = d.clipPositionOffset
            w[i].speed_ratio = d.speedRatio
            w[i].reference_type = d.referenceType.nativeValue.toUInt()
            w[i].reference_clip_id = d.referenceClipId.cstr.ptr
            w[i].reference_marker_id = d.referenceMarkerId.cstr.ptr
        }
        uapmd_app_set_clip_audio_events(
            handle, trackIndex, clipId,
            if (markers.isEmpty()) null else m, markers.size.toUInt(),
            if (warps.isEmpty()) null else w, warps.size.toUInt()
        ).useContents { OpResult(success, error?.toKString()) }
    }
}

private fun uapmd_graph_endpoint_t.toKotlinEndpoint() =
    GraphEndpoint(
        GraphEndpointType.fromNative(type.toInt()),
        node_id?.toKString().orEmpty(),
        instance_id,
        bus_index
    )

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

private val appMidiTracksImportTrampoline =
    staticCFunction<Boolean, CPointer<ByteVar>?, UInt, COpaquePointer?, Unit> { success, error, count, userData ->
        if (userData != null) {
            val ref = userData.asStableRef<(Boolean, String?, Int) -> Unit>()
            ref.get()(success, error?.toKString(), count.toInt())
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

    override fun jump(positionSeconds: Double) = uapmd_transport_jump(handle, positionSeconds)
}

actual fun instantiateAppModel() = uapmd_app_instantiate()

actual fun getAppModel(): AppModel =
    NativeAppModel(uapmd_app_instance() ?: error("uapmd_app_instance returned null; call instantiateAppModel() first"))

actual fun cleanupAppModel() = uapmd_app_cleanup()

internal fun uapmd_piano_roll_note_t.toKotlin() = PianoRollNote(
    startSeconds = start_seconds,
    durationSeconds = duration_seconds,
    velocity = velocity,
    note = note.toInt(),
    channel = channel.toInt(),
    deleted = deleted,
    editId = edit_id.toLong(),
    umpGroup = ump_group.toInt(),
    releaseVelocity = release_velocity.toInt(),
    attributeType = attribute_type.toInt(),
    attributeValue = attribute_value.toInt(),
    automationEventCount = automation_event_count.toInt()
)

class NativePianoRollSnapshot internal constructor(
    internal val handle: uapmd_piano_roll_snapshot_t
) : PianoRollSnapshot {
    override val isReady: Boolean get() = uapmd_piano_roll_snapshot_ready(handle)
    override val error: String get() = uapmd_piano_roll_snapshot_error(handle)?.toKString() ?: ""
    override val durationSeconds: Double get() = uapmd_piano_roll_snapshot_duration_seconds(handle)
    override val minNote: Int get() = uapmd_piano_roll_snapshot_min_note(handle).toInt()
    override val maxNote: Int get() = uapmd_piano_roll_snapshot_max_note(handle).toInt()

    override val notes: List<PianoRollNote>
        get() = memScoped {
            val out = alloc<uapmd_piano_roll_note_t>()
            (0u until uapmd_piano_roll_snapshot_note_count(handle)).mapNotNull { i ->
                if (!uapmd_piano_roll_snapshot_get_note(handle, i, out.ptr)) null else out.toKotlin()
            }
        }

    override fun close() = uapmd_piano_roll_snapshot_destroy(handle)
}

class NativePianoRollSession internal constructor(
    private val handle: uapmd_piano_roll_session_t
) : PianoRollSession {
    override val notes: List<PianoRollNote>
        get() = memScoped {
            val out = alloc<uapmd_piano_roll_note_t>()
            (0u until uapmd_piano_roll_session_note_count(handle)).mapNotNull { i ->
                if (!uapmd_piano_roll_session_get_note(handle, i, out.ptr)) null else out.toKotlin()
            }
        }

    override fun isNoteSelected(index: Int) =
        uapmd_piano_roll_session_is_note_selected(handle, index.toUInt())
    override val selectedNoteCount: Int
        get() = uapmd_piano_roll_session_selected_note_count(handle).toInt()

    override var focusedNote: Int
        get() = uapmd_piano_roll_session_focused_note(handle)
        set(value) { uapmd_piano_roll_session_set_focused_note(handle, value) }

    override val durationSeconds: Double get() = uapmd_piano_roll_session_duration_seconds(handle)
    override val minNote: Int get() = uapmd_piano_roll_session_min_note(handle).toInt()
    override val maxNote: Int get() = uapmd_piano_roll_session_max_note(handle).toInt()
    override val clipboardCount: Int get() = uapmd_piano_roll_session_clipboard_count(handle).toInt()
    override val isDirty: Boolean get() = uapmd_piano_roll_session_dirty(handle)
    override val error: String get() = uapmd_piano_roll_session_error(handle)?.toKString() ?: ""

    override fun matchesSource(snapshot: PianoRollSnapshot) =
        uapmd_piano_roll_session_matches_source(handle, (snapshot as NativePianoRollSnapshot).handle)

    override fun loadNotes(snapshot: PianoRollSnapshot?) =
        uapmd_piano_roll_session_load_notes(handle, (snapshot as NativePianoRollSnapshot?)?.handle)

    override fun selectNote(index: Int, additive: Boolean, toggle: Boolean) =
        uapmd_piano_roll_session_select_note(handle, index, additive, toggle)

    override fun performAction(action: PianoRollAction, pasteSeconds: Double) =
        uapmd_piano_roll_session_perform_action(handle, action.nativeValue.toUInt(), pasteSeconds)

    override fun createNote(startSeconds: Double, durationSeconds: Double, note: Int, velocity: Float) =
        uapmd_piano_roll_session_create_note(handle, startSeconds, durationSeconds, note.toUByte(), velocity)

    override fun deleteNote(index: Int) = uapmd_piano_roll_session_delete_note(handle, index.toUInt())

    override fun resizeNote(index: Int, startSeconds: Double, durationSeconds: Double, note: Int) =
        uapmd_piano_roll_session_resize_note(handle, index.toUInt(), startSeconds, durationSeconds, note.toUByte())

    override fun beginDrag() = uapmd_piano_roll_session_begin_drag(handle)
    override fun moveSelection(timeDeltaSeconds: Double, pitchDelta: Int) =
        uapmd_piano_roll_session_move_selection(handle, timeDeltaSeconds, pitchDelta)
    override fun cancelDrag() = uapmd_piano_roll_session_cancel_drag(handle)
    override fun finishDrag(index: Int, originalStart: Double, originalEnd: Double, originalNote: Int) =
        uapmd_piano_roll_session_finish_drag(handle, index.toUInt(), originalStart, originalEnd, originalNote.toUByte())

    override fun commit(app: AppModel) =
        uapmd_piano_roll_session_commit(handle, (app as NativeAppModel).handle)
}

private fun uapmd_device_entry_t.toKotlin() = DeviceEntry(
    id = id,
    label = label?.toKString() ?: "",
    apiName = api_name?.toKString() ?: "",
    statusMessage = status_message?.toKString() ?: "",
    running = running,
    instantiating = instantiating,
    hasError = has_error
)

private fun uapmd_plugin_state_result_t.toKotlin() = PluginStateResult(
    instanceId = instance_id,
    success = success,
    error = error?.toKString() ?: "",
    filepath = filepath?.toKString() ?: ""
)

/** The C callback takes the result struct by value, i.e. as a pointer. */
private val pluginStateTrampoline = staticCFunction {
    result: CValue<uapmd_plugin_state_result_t>, userData: COpaquePointer? ->
    val ref = userData!!.asStableRef<(PluginStateResult) -> Unit>()
    try { ref.get()(result.useContents { toKotlin() }) } finally { ref.dispose() }
}
