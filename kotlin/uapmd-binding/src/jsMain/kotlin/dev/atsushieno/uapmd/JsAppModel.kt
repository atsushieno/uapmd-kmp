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

    override val slowScanProgress: SlowScanProgress
        get() = withWasmMem(16) { out ->
            jsMod._uapmd_app_slow_scan_progress(out, handle)
            SlowScanProgress(
                running = (jsMod.getValue(out, "i8") as Int) != 0,
                processedBundles = (jsMod.getValue(out + 4, "i32") as Int).toUInt(),
                totalBundles = (jsMod.getValue(out + 8, "i32") as Int).toUInt(),
                currentBundle = jsStrAt(out + 12)
            )
        }

    override val lastPluginScanError: String?
        get() = readJsString(handle) { h, buf, size ->
            jsMod._uapmd_app_last_plugin_scan_error(h, buf, size) as Int
        }.ifEmpty { null }

    override fun cancelPluginScanning() {
        jsMod._uapmd_app_cancel_plugin_scanning(handle)
    }

    override fun stopPluginScanning() {
        jsMod._uapmd_app_stop_plugin_scanning(handle)
    }

    override fun generateScanReport(): String =
        readJsString(handle) { h, buf, size -> jsMod._uapmd_app_generate_scan_report(h, buf, size) as Int }

    override fun clearPluginBlocklist() {
        jsMod._uapmd_app_clear_plugin_blocklist(handle)
    }

    override val blocklist: List<BlocklistEntry>
        get() {
            val n = jsMod._uapmd_app_blocklist_count(handle) as Int
            return (0 until n).mapNotNull { i ->
                val ptr = jsMod._malloc(16) as Int   // 4 char* pointers
                try {
                    if (jsMod._uapmd_app_get_blocklist_entry(handle, i, ptr) != true) null
                    else {
                        fun getStr(o: Int): String {
                            val p = jsMod.getValue(ptr + o, "i32") as Int
                            return if (p != 0) jsMod.UTF8ToString(p) as String else ""
                        }
                        BlocklistEntry(getStr(0), getStr(4), getStr(8), getStr(12))
                    }
                } finally { jsMod._free(ptr) }
            }
        }

    override fun unblockPlugin(entryId: String): Boolean =
        withJsCString(entryId) { p -> jsMod._uapmd_app_unblock_plugin_from_blocklist(handle, p) as Boolean }

    override fun refreshMasterTempoMap(): Double =
        jsMod._uapmd_app_refresh_master_tempo_map(handle) as Double

    override val masterTempoPoints: List<TempoPoint>
        get() {
            val n = jsMod._uapmd_app_master_tempo_point_count(handle) as Int
            return (0 until n).mapNotNull { i ->
                val ptr = jsMod._malloc(24) as Int
                try {
                    if (jsMod._uapmd_app_get_master_tempo_point(handle, i, ptr) != true) null
                    else TempoPoint(
                        jsMod.getValue(ptr, "double") as Double,
                        (jsMod.getValue(ptr + 8, "i64") as? Number)?.toLong() ?: 0L,
                        jsMod.getValue(ptr + 16, "double") as Double
                    )
                } finally { jsMod._free(ptr) }
            }
        }

    override val masterTimeSignaturePoints: List<TimeSignaturePoint>
        get() {
            val n = jsMod._uapmd_app_master_time_signature_count(handle) as Int
            return (0 until n).mapNotNull { i ->
                val ptr = jsMod._malloc(24) as Int
                try {
                    if (jsMod._uapmd_app_get_master_time_signature(handle, i, ptr) != true) null
                    else TimeSignaturePoint(
                        jsMod.getValue(ptr, "double") as Double,
                        (jsMod.getValue(ptr + 8, "i64") as? Number)?.toLong() ?: 0L,
                        (jsMod.getValue(ptr + 16, "i8") as Int) and 0xFF,
                        (jsMod.getValue(ptr + 17, "i8") as Int) and 0xFF
                    )
                } finally { jsMod._free(ptr) }
            }
        }

    // ── Tracks ──────────────────────────────────────────────────────────────

    override fun isTrackMuted(trackIndex: Int) =
        jsMod._uapmd_app_is_track_muted(handle, trackIndex) as Boolean
    override fun isTrackSolo(trackIndex: Int) =
        jsMod._uapmd_app_is_track_solo(handle, trackIndex) as Boolean
    override fun setTrackMuted(trackIndex: Int, muted: Boolean) =
        jsMod._uapmd_app_set_track_muted(handle, trackIndex, muted) as Boolean
    override fun setTrackSolo(trackIndex: Int, solo: Boolean) =
        jsMod._uapmd_app_set_track_solo(handle, trackIndex, solo) as Boolean

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
        get() = JsTimelineTrack(jsMod._uapmd_app_get_master_timeline_track(handle) as Int)

    override fun getTimelineState(): TimelineState? =
        withWasmMem(80) { ptr ->
            if (!(jsMod._uapmd_app_get_timeline_state(handle, ptr) as Boolean)) null
            else jsDecodeTimelineState(ptr)
        }

    // ── History ─────────────────────────────────────────────────────────────

    override val historyState: UndoState
        get() = withWasmMem(Off.STATE_SIZE) { p ->
            jsMod._uapmd_app_history_state(handle, p)
            decodeUndoState(p)
        }

    override fun undo(callback: ((String?) -> Unit)?) {
        jsMod._uapmd_app_undo(handle, 0, callback?.let { makeJsErrorOnly(it) } ?: 0)
    }

    override fun redo(callback: ((String?) -> Unit)?) {
        jsMod._uapmd_app_redo(handle, 0, callback?.let { makeJsErrorOnly(it) } ?: 0)
    }

    // ── Plugin instances ────────────────────────────────────────────────────

    override fun createPluginInstance(
        format: String, pluginId: String, trackIndex: Int,
        config: PluginInstanceConfig, callback: (PluginInstanceResult) -> Unit
    ) {
        // uapmd_plugin_instance_config_t: five char* fields, 20 bytes on wasm32.
        withWasmMem(20) { cfg ->
            val strings = listOf(config.apiName, config.deviceName, config.manufacturer, config.version, config.stateFile)
            val ptrs = strings.map { str ->
                val size = (jsMod.lengthBytesUTF8(str) as Int) + 1
                val p = jsMod._malloc(size) as Int
                jsMod.stringToUTF8(str, p, size)
                p
            }
            ptrs.forEachIndexed { i, p -> jsMod.setValue(cfg + i * 4, p, "i32") }
            try {
                withJsTwoCStrings(format, pluginId) { f, pid ->
                    jsMod._uapmd_app_create_plugin_instance(
                        handle, f, pid, trackIndex, cfg, 0, makeJsInstanceCreated(callback)
                    )
                }
            } finally {
                ptrs.forEach { jsMod._free(it) }
            }
        }
    }

    override fun removePluginInstance(instanceId: Int) {
        jsMod._uapmd_app_remove_plugin_instance(handle, instanceId)
    }

    override fun getInstanceGroup(instanceId: Int): UByte =
        (jsMod._uapmd_app_get_instance_group(handle, instanceId) as Int).toUByte()

    override fun setInstanceGroup(instanceId: Int, group: UByte): Boolean =
        jsMod._uapmd_app_set_instance_group(handle, instanceId, group.toInt()) as Boolean

    override fun enableUmpDevice(instanceId: Int, deviceName: String) {
        withJsCString(deviceName) { d -> jsMod._uapmd_app_enable_ump_device(handle, instanceId, d) }
    }

    override fun disableUmpDevice(instanceId: Int) {
        jsMod._uapmd_app_disable_ump_device(handle, instanceId)
    }

    override val virtualMidiDevicesEnabled: Boolean
        get() = (jsMod._uapmd_app_virtual_midi_devices_enabled(handle) as Int) != 0

    override var autoCreateVirtualMidiDevices: Boolean
        get() = (jsMod._uapmd_app_auto_create_virtual_midi_devices(handle) as Int) != 0
        set(value) { jsMod._uapmd_app_set_auto_create_virtual_midi_devices(handle, value) }

    override var showVirtualMidiDevices: (() -> Unit)?
        get() = jsShowVirtualMidiDevicesHandler
        set(value) {
            jsShowVirtualMidiDevicesHandler = value
            if (value == null) {
                jsMod._uapmd_app_set_show_virtual_midi_devices_callback(handle, 0, 0)
                return
            }
            // One table entry serves every handler: it reads the current one.
            if (jsShowVirtualMidiDevicesFnPtr == 0) {
                val fn: (Int) -> Unit = { _ -> jsShowVirtualMidiDevicesHandler?.invoke() }
                jsShowVirtualMidiDevicesFnPtr = addJsCallback(fn, "vi")
            }
            jsMod._uapmd_app_set_show_virtual_midi_devices_callback(handle, 0, jsShowVirtualMidiDevicesFnPtr)
        }

    override val documentProvider: DocumentProvider
        get() = JsDocumentProvider(jsMod._uapmd_app_document_provider(handle) as Int)

    override fun requestShowInstanceDetails(instanceId: Int) {
        jsMod._uapmd_app_request_show_instance_details(handle, instanceId)
    }

    override fun requestShowPluginUi(instanceId: Int) {
        jsMod._uapmd_app_request_show_plugin_ui(handle, instanceId)
    }

    override fun hidePluginUi(instanceId: Int) {
        jsMod._uapmd_app_hide_plugin_ui(handle, instanceId)
    }

    // ── Project I/O ─────────────────────────────────────────────────────────
    // Struct-returning functions take the result pointer as their FIRST argument.

    private fun projectCall(path: String, call: (out: Int, str: Int) -> Unit): AppProjectResult =
        withWasmMem(8) { out ->                    // sizeof uapmd_app_project_result_t
            withJsCString(path) { p -> call(out, p) }
            decodeJsProjectResult(out)
        }

    override fun loadProject(filePath: String): AppProjectResult =
        projectCall(filePath) { out, p -> jsMod._uapmd_app_load_project(out, handle, p) }

    override fun saveProjectSync(filePath: String): AppProjectResult =
        projectCall(filePath) { out, p -> jsMod._uapmd_app_save_project_sync(out, handle, p) }

    override fun saveProject(filePath: String, callback: (AppProjectResult) -> Unit) {
        withJsCString(filePath) { p ->
            jsMod._uapmd_app_save_project(handle, p, 0, makeJsProjectSave(callback))
        }
    }

    override fun loadProjectFromHandleToken(token: String): AppProjectResult =
        projectCall(token) { out, t -> jsMod._uapmd_app_load_project_from_handle_token(out, handle, t) }

    override fun newProject(): AppProjectResult =
        withWasmMem(8) { out ->                    // sizeof uapmd_app_project_result_t
            jsMod._uapmd_app_new_project(out, handle)
            decodeJsProjectResult(out)
        }

    override val masterTempoMap: TempoMap
        get() = JsTempoMap(jsMod._uapmd_app_master_tempo_map(handle) as Int)

    // ── Timeline clip selection and clipboard ───────────────────────────────

    override fun isTimelineClipSelected(trackIndex: Int, clipId: Int): Boolean =
        jsMod._uapmd_app_is_timeline_clip_selected(handle, trackIndex, clipId) as Boolean

    override val selectedTimelineClips: List<TimelineClipTarget>
        get() {
            val count = jsMod._uapmd_app_selected_timeline_clips(handle, 0, 0) as Int
            if (count <= 0) return emptyList()
            return withWasmMem(count * Off.CLIP_TARGET_STRIDE) { buf ->
                val filled = jsMod._uapmd_app_selected_timeline_clips(handle, buf, count) as Int
                readJsClipTargets(buf, minOf(count, filled))
            }
        }

    override fun selectTimelineClips(clips: List<TimelineClipTarget>, additive: Boolean, toggle: Boolean) {
        if (clips.isEmpty()) {
            jsMod._uapmd_app_select_timeline_clips(handle, 0, 0, additive, toggle)
            return
        }
        withWasmMem(clips.size * Off.CLIP_TARGET_STRIDE) { buf ->
            clips.forEachIndexed { i, t ->
                val entry = buf + i * Off.CLIP_TARGET_STRIDE
                jsSetI32(entry + Off.CLIP_TARGET_TRACK, t.trackIndex)
                jsSetI32(entry + Off.CLIP_TARGET_CLIP, t.clipId)
            }
            jsMod._uapmd_app_select_timeline_clips(handle, buf, clips.size, additive, toggle)
        }
    }

    override fun clearTimelineClipSelection() {
        jsMod._uapmd_app_clear_timeline_clip_selection(handle)
    }

    override fun selectTimelineMidiClip(trackIndex: Int, clipId: Int): Boolean =
        jsMod._uapmd_app_select_timeline_midi_clip(handle, trackIndex, clipId) as Boolean

    override val selectedTimelineMidiClip: TimelineClipTarget?
        get() = withWasmMem(Off.CLIP_TARGET_STRIDE) { out ->
            if (!(jsMod._uapmd_app_selected_timeline_midi_clip(handle, out) as Boolean)) null
            else TimelineClipTarget(
                jsGetI32(out + Off.CLIP_TARGET_TRACK),
                jsGetI32(out + Off.CLIP_TARGET_CLIP)
            )
        }

    override val timelineClipboardCount: Int
        get() = jsMod._uapmd_app_timeline_clipboard_count(handle) as Int

    override fun clearTimelineClipboard() {
        jsMod._uapmd_app_clear_timeline_clipboard(handle)
    }

    override fun copySelectedTimelineClips(): Boolean =
        jsMod._uapmd_app_copy_selected_timeline_clips(handle) as Boolean

    override fun deleteSelectedTimelineClips(cut: Boolean): TimelineClipDeleteResult {
        // One call only: this both deletes and reports. A track can lose several
        // clips but appears once, so the selection size bounds the changed-track
        // list — measured before the call, which clears the selection.
        val capacity = selectedTimelineClips.size
        return withWasmMem(maxOf(capacity, 1) * 4) { tracks ->
            withWasmMem(4) { countPtr ->
                jsSetI32(countPtr, capacity)
                val ok = jsMod._uapmd_app_delete_selected_timeline_clips(
                    handle, cut, if (capacity > 0) tracks else 0, countPtr) as Boolean
                val count = minOf(capacity, jsGetI32(countPtr))
                TimelineClipDeleteResult(
                    ok,
                    (0 until count).map { jsGetI32(tracks + it * 4) },
                    lastTimelineClipError.ifEmpty { null }
                )
            }
        }
    }

    override fun timelinePasteDestinations(trackIndex: Int, originalTracks: Boolean): List<Int> {
        val count = jsMod._uapmd_app_timeline_paste_destinations(handle, trackIndex, originalTracks, 0, 0) as Int
        if (count <= 0) return emptyList()
        return withWasmMem(count * 4) { buf ->
            val filled = jsMod._uapmd_app_timeline_paste_destinations(
                handle, trackIndex, originalTracks, buf, count) as Int
            (0 until minOf(count, filled)).map { jsGetI32(buf + it * 4) }
        }
    }

    override fun pasteTimelineClips(
        trackIndex: Int,
        positionSeconds: Double,
        originalTracks: Boolean
    ): TimelinePasteResult {
        // A paste creates at most one clip per clipboard entry, which bounds it.
        val capacity = timelineClipboardCount
        return withWasmMem(maxOf(capacity, 1) * Off.CLIP_TARGET_STRIDE) { buf ->
            withWasmMem(4) { countPtr ->
                jsSetI32(countPtr, capacity)
                val ok = jsMod._uapmd_app_paste_timeline_clips(
                    handle, trackIndex, positionSeconds, originalTracks,
                    if (capacity > 0) buf else 0, countPtr) as Boolean
                val count = minOf(capacity, jsGetI32(countPtr))
                TimelinePasteResult(ok, readJsClipTargets(buf, count), lastTimelineClipError.ifEmpty { null })
            }
        }
    }

    override val lastTimelineClipError: String
        get() = (jsMod._uapmd_app_last_timeline_clip_error() as Int)
            .let { if (it != 0) jsMod.UTF8ToString(it) as String else "" }

    // ── Piano roll editing session ──────────────────────────────────────────

    override fun pianoRollClipSnapshot(
        trackIndex: Int,
        clipId: Int,
        fallbackDurationSeconds: Double
    ): PianoRollSnapshot? =
        (jsMod._uapmd_app_piano_roll_clip_snapshot(handle, trackIndex, clipId, fallbackDurationSeconds) as Int)
            .takeIf { it != 0 }?.let { JsPianoRollSnapshot(it) }

    override fun openPianoRollSession(trackIndex: Int, clipId: Int): PianoRollSession? =
        (jsMod._uapmd_app_open_piano_roll_session(handle, trackIndex, clipId) as Int)
            .takeIf { it != 0 }?.let { JsPianoRollSession(it) }

    override fun findPianoRollSession(trackIndex: Int, clipId: Int): PianoRollSession? =
        (jsMod._uapmd_app_find_piano_roll_session(handle, trackIndex, clipId) as Int)
            .takeIf { it != 0 }?.let { JsPianoRollSession(it) }

    override fun closePianoRollSession(trackIndex: Int, clipId: Int) {
        jsMod._uapmd_app_close_piano_roll_session(handle, trackIndex, clipId)
    }

    override fun recordPianoRollCommitSource(trackIndex: Int, clipId: Int) {
        jsMod._uapmd_app_record_piano_roll_commit_source(handle, trackIndex, clipId)
    }

    override fun pianoRollSourceMatchesLastEdit(): Boolean =
        jsMod._uapmd_app_piano_roll_source_matches_last_edit(handle) as Boolean

    override fun clearPianoRollCommitSource() {
        jsMod._uapmd_app_clear_piano_roll_commit_source(handle)
    }

    // ── Assorted accessors ──────────────────────────────────────────────────
    //
    // wasm32 layouts, checked with _Static_assert under emcc:
    //   uapmd_midi_port_info_t          char* @0, char* @4                      (8)
    //   uapmd_timeline_content_bounds_t bool @0, double @8, @16, @24           (32)
    //   uapmd_device_entry_t            i32 @0, char* @4 @8 @12, bool @16 @17 @18 (20)
    //   uapmd_plugin_state_result_t     i32 @0, bool @4, char* @8, char* @12   (16)

    override val midiInputPorts: List<MidiPortInfo>
        get() = jsMidiPorts { out, n -> jsMod._uapmd_app_get_midi_input_ports(handle, out, n) }

    override val midiOutputPorts: List<MidiPortInfo>
        get() = jsMidiPorts { out, n -> jsMod._uapmd_app_get_midi_output_ports(handle, out, n) }

    override fun isTrackHidden(trackIndex: Int): Boolean =
        jsMod._uapmd_app_is_track_hidden(handle, trackIndex) as Boolean

    override val timelineContentBounds: TimelineContentBounds
        get() = withWasmMem(JsTimelineContentBoundsSize) { out ->
            jsMod._uapmd_app_timeline_content_bounds(out, handle)
            TimelineContentBounds(
                hasContent = jsGetBool(out),
                startSeconds = jsMod.getValue(out + 8, "double") as Double,
                endSeconds = jsMod.getValue(out + 16, "double") as Double,
                durationSeconds = jsMod.getValue(out + 24, "double") as Double
            )
        }

    override val devices: List<DeviceEntry>
        get() {
            val n = jsMod._uapmd_app_get_devices(handle, 0, 0) as Int
            if (n == 0) return emptyList()
            return withWasmMem(n * JsDeviceEntrySize) { out ->
                val filled = jsMod._uapmd_app_get_devices(handle, out, n) as Int
                (0 until filled).map { jsReadDeviceEntry(out + it * JsDeviceEntrySize) }
            }
        }

    override fun deviceForInstance(instanceId: Int): DeviceEntry? =
        withWasmMem(JsDeviceEntrySize) { out ->
            if (jsMod._uapmd_app_get_device_for_instance(handle, instanceId, out) != true) null
            else jsReadDeviceEntry(out)
        }

    override fun updateDeviceLabel(instanceId: Int, label: String) {
        withJsCString(label) { p -> jsMod._uapmd_app_update_device_label(handle, instanceId, p) }
    }

    override fun loadPluginState(instanceId: Int, filepath: String, callback: (PluginStateResult) -> Unit) {
        withJsCString(filepath) { p ->
            jsMod._uapmd_app_load_plugin_state(handle, instanceId, p, 0, makeJsPluginState(callback))
        }
    }

    override fun savePluginState(instanceId: Int, filepath: String, callback: (PluginStateResult) -> Unit) {
        withJsCString(filepath) { p ->
            jsMod._uapmd_app_save_plugin_state(handle, instanceId, p, 0, makeJsPluginState(callback))
        }
    }

    override fun loadPluginStateSync(instanceId: Int, filepath: String): PluginStateResult =
        withWasmMem(JsPluginStateResultSize) { out ->
            withJsCString(filepath) { p -> jsMod._uapmd_app_load_plugin_state_sync(out, handle, instanceId, p) }
            jsReadPluginStateResult(out)
        }

    override fun savePluginStateSync(instanceId: Int, filepath: String): PluginStateResult =
        withWasmMem(JsPluginStateResultSize) { out ->
            withJsCString(filepath) { p -> jsMod._uapmd_app_save_plugin_state_sync(out, handle, instanceId, p) }
            jsReadPluginStateResult(out)
        }

    override fun markPluginInstanceTrackDirty(instanceId: Int) {
        jsMod._uapmd_app_mark_plugin_instance_track_dirty(handle, instanceId)
    }

    // ── MIDI clip UMP events ────────────────────────────────────────────────
    // uapmd_ump_events_result_t: bool @0, char* @4, uint32 @8, ptr @12 (16 bytes)
    // uapmd_ump_event_t:         uint64 @0, uint32 @8, ptr @12 (16 bytes)

    override fun getMidiClipUmpEvents(trackIndex: Int, clipId: Int): UmpEventsResult =
        // uapmd_ump_events_result_t: ok@0 err@4 count@8 events@12 tickRes@16 tempo@24, size 32
        withWasmMem(32) { out ->
            jsMod._uapmd_app_get_midi_clip_ump_events(out, handle, trackIndex, clipId)
            val ok = (jsMod.getValue(out, "i8") as Int) != 0
            val errPtr = jsMod.getValue(out + 4, "i32") as Int
            val error = if (errPtr != 0) jsMod.UTF8ToString(errPtr) as String else null
            val count = jsMod.getValue(out + 8, "i32") as Int
            val eventsPtr = jsMod.getValue(out + 12, "i32") as Int
            val tickRes = (jsMod.getValue(out + 16, "i32") as Int).toUInt()
            val tempo = jsMod.getValue(out + 24, "double") as Double
            if (!ok || eventsPtr == 0 || count == 0) UmpEventsResult(ok, error, emptyList(), tickRes, tempo)
            else UmpEventsResult(ok, error, (0 until count).map { i ->
                val base = eventsPtr + i * 16
                val lo = (jsMod.getValue(base, "i32") as Int).toLong() and 0xFFFFFFFFL
                val hi = (jsMod.getValue(base + 4, "i32") as Int).toLong()
                val wordCount = jsMod.getValue(base + 8, "i32") as Int
                val wordsPtr = jsMod.getValue(base + 12, "i32") as Int
                UmpEvent(hi * 4294967296L + lo, UIntArray(wordCount) { w ->
                    (jsMod.getValue(wordsPtr + w * 4, "i32") as Int).toUInt()
                })
            }, tickRes, tempo)
        }

    override fun addUmpEventToClip(trackIndex: Int, clipId: Int, tick: Long, words: UIntArray): Boolean =
        withWasmMem(words.size * 4) { buf ->
            words.forEachIndexed { i, w -> jsMod.setValue(buf + i * 4, w.toInt(), "i32") }
            // -sWASM_BIGINT: scalar i64 parameters must arrive as BigInt.
            jsMod._uapmd_app_add_ump_event_to_clip(
                handle, trackIndex, clipId, js("BigInt")(tick.toString()), buf, words.size
            ) as Boolean
        }

    override fun removeUmpEventFromClip(trackIndex: Int, clipId: Int, eventIndex: Int): Boolean =
        jsMod._uapmd_app_remove_ump_event_from_clip(handle, trackIndex, clipId, eventIndex) as Boolean

    override fun removeClipFromTrack(trackIndex: Int, clipId: Int): Boolean =
        jsMod._uapmd_app_remove_clip_from_track(handle, trackIndex, clipId) as Boolean

    override fun importMidiTracksFromFile(filepath: String, callback: (Boolean, String?, Int) -> Unit) {
        // The js bridge marshals C callbacks through its own dispatcher table;
        // this one is not registered there yet, so report the failure rather
        // than silently doing nothing.
        callback(false, "Multi-track SMF import is not wired up on this platform yet.", 0)
    }

    override fun createEmptyMidiClip(
        trackIndex: Int, positionSamples: Long, tickResolution: UInt, bpm: Double
    ): ClipAddResult = withWasmMem(16) { out ->
        jsMod._uapmd_app_create_empty_midi_clip(
            out, handle, trackIndex, js("BigInt")(positionSamples.toString()), tickResolution.toInt(), bpm
        )
        val errPtr = jsMod.getValue(out + 12, "i32") as Int
        ClipAddResult(
            jsMod.getValue(out, "i32") as Int,
            jsMod.getValue(out + 4, "i32") as Int,
            (jsMod.getValue(out + 8, "i8") as Int) != 0,
            if (errPtr != 0) jsMod.UTF8ToString(errPtr) as String else null
        )
    }

    override fun addClipToTrack(
        trackIndex: Int, position: TimelinePosition, reader: AudioFileReader, filepath: String
    ): ClipAddResult = withWasmMem(JsClipAddResultSize) { out ->
        withWasmMem(JsTimelinePositionSize) { pos ->
            jsWritePosition(pos, position)
            withJsCString(filepath) { fp ->
                jsMod._uapmd_app_add_clip_to_track(
                    out, handle, trackIndex, pos, (reader as JsAudioFileReader).handle, fp
                )
            }
        }
        jsDecodeClipAddResult(out)
    }

    override fun addMidiClipToTrack(trackIndex: Int, position: TimelinePosition, filepath: String): ClipAddResult =
        withWasmMem(JsClipAddResultSize) { out ->
            withWasmMem(JsTimelinePositionSize) { pos ->
                jsWritePosition(pos, position)
                withJsCString(filepath) { fp ->
                    jsMod._uapmd_app_add_midi_clip_to_track(out, handle, trackIndex, pos, fp)
                }
            }
            jsDecodeClipAddResult(out)
        }

    override fun addMidiClipFromData(
        trackIndex: Int, position: TimelinePosition,
        umpEvents: List<UInt>, tickTimestamps: List<ULong>,
        tickResolution: UInt, clipTempo: Double,
        tempoChanges: List<MidiTempoChange>, timeSignatureChanges: List<MidiTimeSignatureChange>,
        clipName: String, needsFileSave: Boolean
    ): ClipAddResult {
        val owned = mutableListOf<Int>()
        fun alloc(size: Int): Int = (jsMod._malloc(size) as Int).also { owned += it }
        try {
            val umpBuf = if (umpEvents.isEmpty()) 0 else alloc(umpEvents.size * 4).also { b ->
                umpEvents.forEachIndexed { i, v -> jsSetI32(b + i * 4, v.toInt()) }
            }
            val tickBuf = if (tickTimestamps.isEmpty()) 0 else alloc(tickTimestamps.size * 8).also { b ->
                tickTimestamps.forEachIndexed { i, v -> jsSetI64(b + i * 8, v.toLong()) }
            }
            // uapmd_midi_tempo_change_t: uint64 @0, double @8 (16 bytes)
            val tempoBuf = if (tempoChanges.isEmpty()) 0 else alloc(tempoChanges.size * 16).also { b ->
                tempoChanges.forEachIndexed { i, t ->
                    jsSetI64(b + i * 16, t.tickPosition.toLong())
                    jsMod.setValue(b + i * 16 + 8, t.bpm, "double")
                }
            }
            // uapmd_midi_time_sig_change_t: uint64 @0, four uint8 @8..11 (16 bytes)
            val sigBuf = if (timeSignatureChanges.isEmpty()) 0 else alloc(timeSignatureChanges.size * 16).also { b ->
                timeSignatureChanges.forEachIndexed { i, t ->
                    jsSetI64(b + i * 16, t.tickPosition.toLong())
                    jsSetI8(b + i * 16 + 8, t.numerator.toInt())
                    jsSetI8(b + i * 16 + 9, t.denominator.toInt())
                    jsSetI8(b + i * 16 + 10, t.clocksPerClick.toInt())
                    jsSetI8(b + i * 16 + 11, t.thirtySecondsPerQuarter.toInt())
                }
            }
            return withWasmMem(JsClipAddResultSize) { out ->
                withWasmMem(JsTimelinePositionSize) { pos ->
                    jsWritePosition(pos, position)
                    withJsCString(clipName) { name ->
                        jsMod._uapmd_app_add_midi_clip_from_data(
                            out, handle, trackIndex, pos,
                            umpBuf, umpEvents.size, tickBuf, tickTimestamps.size,
                            tickResolution.toInt(), clipTempo,
                            tempoBuf, tempoChanges.size, sigBuf, timeSignatureChanges.size,
                            name, needsFileSave
                        )
                    }
                }
                jsDecodeClipAddResult(out)
            }
        } finally { owned.forEach { jsMod._free(it) } }
    }

    override fun addDeviceInputToTrack(trackIndex: Int, channelIndices: List<UInt>): Int {
        if (channelIndices.isEmpty())
            return jsMod._uapmd_app_add_device_input_to_track(handle, trackIndex, 0, 0) as Int
        return withWasmMem(channelIndices.size * 4) { buf ->
            channelIndices.forEachIndexed { i, v -> jsSetI32(buf + i * 4, v.toInt()) }
            jsMod._uapmd_app_add_device_input_to_track(handle, trackIndex, buf, channelIndices.size) as Int
        }
    }

    // ── Master track markers ────────────────────────────────────────────────

    override val masterMarkers: List<ClipMarkerData>
        get() {
            val n = jsMod._uapmd_app_master_marker_count(handle) as Int
            if (n == 0) return emptyList()
            return withWasmMem(JsClipMarkerSize) { out ->
                (0 until n).mapNotNull { i ->
                    if (jsMod._uapmd_app_get_master_marker(handle, i, out) != true) null
                    else jsReadClipMarker(out)
                }
            }
        }

    override fun setMasterTrackMarkersWithValidation(markers: List<ClipMarkerData>): OpResult {
        val owned = mutableListOf<Int>()
        fun cstr(v: String): Int {
            val size = jsMod.lengthBytesUTF8(v) as Int + 1
            val p = jsMod._malloc(size) as Int
            jsMod.stringToUTF8(v, p, size)
            owned += p
            return p
        }
        val buf = if (markers.isEmpty()) 0 else (jsMod._malloc(markers.size * JsClipMarkerSize) as Int).also { owned += it }
        return try {
            markers.forEachIndexed { i, m ->
                val b = buf + i * JsClipMarkerSize
                jsSetI32(b, cstr(m.markerId))
                jsMod.setValue(b + 8, m.clipPositionOffset, "double")
                jsSetI32(b + 16, m.referenceType.nativeValue)
                jsSetI32(b + 20, cstr(m.referenceClipId))
                jsSetI32(b + 24, cstr(m.referenceMarkerId))
                jsSetI32(b + 28, cstr(m.name))
            }
            withWasmMem(8) { out ->
                jsMod._uapmd_app_set_master_track_markers_with_validation(out, handle, buf, markers.size)
                jsReadOpResult(out)
            }
        } finally { owned.forEach { jsMod._free(it) } }
    }

    // ── Offline render to file ──────────────────────────────────────────────

    override fun startRenderToFile(settings: RenderToFileSettings): Boolean =
        withWasmMem(JsAppRenderSettingsSize) { p ->
            withJsCString(settings.outputPath) { path ->
                jsSetI32(p, path)
                jsMod.setValue(p + 8, settings.startSeconds, "double")
                jsMod.setValue(p + 16, settings.endSeconds, "double")
                jsSetI8(p + 24, if (settings.hasEndSeconds) 1 else 0)
                jsSetI8(p + 25, if (settings.useContentFallback) 1 else 0)
                jsSetI8(p + 26, if (settings.contentBoundsValid) 1 else 0)
                jsMod.setValue(p + 32, settings.contentStartSeconds, "double")
                jsMod.setValue(p + 40, settings.contentEndSeconds, "double")
                jsMod.setValue(p + 48, settings.tailSeconds, "double")
                jsSetI8(p + 56, if (settings.enableSilenceStop) 1 else 0)
                jsMod.setValue(p + 64, settings.silenceDurationSeconds, "double")
                jsMod.setValue(p + 72, settings.silenceThresholdDb, "double")
                jsMod._uapmd_app_start_render_to_file(handle, p) as Boolean
            }
        }

    override fun cancelRenderToFile() {
        jsMod._uapmd_app_cancel_render_to_file(handle)
    }

    override val renderToFileStatus: RenderToFileStatus
        get() = withWasmMem(JsAppRenderStatusSize) { out ->
            jsMod._uapmd_app_get_render_to_file_status(out, handle)
            RenderToFileStatus(
                running = jsGetBool(out),
                completed = jsGetBool(out + 1),
                success = jsGetBool(out + 2),
                progress = jsMod.getValue(out + 8, "double") as Double,
                renderedSeconds = jsMod.getValue(out + 16, "double") as Double,
                message = jsStrAt(out + 24),
                outputPath = jsStrAt(out + 28)
            )
        }

    override fun clearCompletedRenderStatus() {
        jsMod._uapmd_app_clear_completed_render_status(handle)
    }

    override fun requestShowTrackGraph(trackIndex: Int) {
        jsMod._uapmd_app_request_show_track_graph(handle, trackIndex)
    }

    // ── Track graph ─────────────────────────────────────────────────────────

    override fun ensureTrackUsesEditorGraph(trackIndex: Int): Boolean =
        jsMod._uapmd_app_ensure_track_uses_editor_graph(handle, trackIndex) as Boolean

    override fun revertTrackToSimpleGraph(trackIndex: Int): Boolean =
        jsMod._uapmd_app_revert_track_to_simple_graph(handle, trackIndex) as Boolean

    override fun getTrackGraphConnections(trackIndex: Int): GraphConnectionsResult =
        withWasmMem(16) { out ->
            jsMod._uapmd_app_get_track_graph_connections(out, handle, trackIndex)
            val ok = (jsMod.getValue(out, "i8") as Int) != 0
            val errPtr = jsMod.getValue(out + 4, "i32") as Int
            val error = if (errPtr != 0) jsMod.UTF8ToString(errPtr) as String else null
            val count = jsMod.getValue(out + 8, "i32") as Int
            val ptr = jsMod.getValue(out + 12, "i32") as Int
            if (!ok || ptr == 0 || count == 0) GraphConnectionsResult(ok, error, emptyList())
            else GraphConnectionsResult(ok, error, (0 until count).map { i ->
                val base = ptr + i * JsGraphConnectionSize
                GraphConnection(
                    id = (jsMod.getValue(base, "i64") as? Number)?.toLong() ?: 0L,
                    busType = GraphBusType.fromNative(jsMod.getValue(base + 8, "i32") as Int),
                    source = jsReadEndpoint(base + 12),
                    target = jsReadEndpoint(base + 12 + JsGraphEndpointSize)
                )
            })
        }

    override fun getTrackGraphNodes(trackIndex: Int): GraphNodesResult =
        withWasmMem(40) { out ->
            jsMod._uapmd_app_get_track_graph_nodes(out, handle, trackIndex)
            val ok = (jsMod.getValue(out, "i8") as Int) != 0
            val errPtr = jsMod.getValue(out + 4, "i32") as Int
            val error = if (errPtr != 0) jsMod.UTF8ToString(errPtr) as String else null
            if (!ok) return@withWasmMem GraphNodesResult.failure(error)
            val count = jsMod.getValue(out + 8, "i32") as Int
            val ptr = jsMod.getValue(out + 12, "i32") as Int
            val busCount = jsMod.getValue(out + 16, "i32") as Int
            val busPtr = jsMod.getValue(out + 20, "i32") as Int

            val buses = if (busPtr == 0 || busCount == 0) emptyList() else (0 until busCount).map { i ->
                val base = busPtr + i * JsGraphAudioBusSize
                GraphAudioBus(
                    name = jsStrAt(base),
                    role = AudioBusRole.fromNative(jsMod.getValue(base + 4, "i32") as Int),
                    enabled = (jsMod.getValue(base + 8, "i8") as Int) != 0,
                    channelLayoutName = jsStrAt(base + 12),
                    channelCount = (jsMod.getValue(base + 16, "i32") as Int).toUInt()
                )
            }
            val nodes = if (ptr == 0 || count == 0) emptyList() else (0 until count).map { i ->
                val base = ptr + i * JsGraphNodeSize
                val from = jsMod.getValue(base + 36, "i32") as Int
                val inCount = jsMod.getValue(base + 40, "i32") as Int
                GraphNode(
                    nodeId = jsStrAt(base),
                    nodeType = jsStrAt(base + 4),
                    displayName = jsStrAt(base + 8),
                    instanceId = jsMod.getValue(base + 12, "i32") as Int,
                    bypassed = (jsMod.getValue(base + 16, "i8") as Int) != 0,
                    latencyInSamples = (jsMod.getValue(base + 20, "i32") as Int).toUInt(),
                    tailLengthInSeconds = (jsMod.getValue(base + 24, "double") as Number).toDouble(),
                    hasAudioBuses = (jsMod.getValue(base + 32, "i8") as Int) != 0,
                    hasEventInputs = (jsMod.getValue(base + 33, "i8") as Int) != 0,
                    hasEventOutputs = (jsMod.getValue(base + 34, "i8") as Int) != 0,
                    audioInputBuses = buses.busRange(from, inCount),
                    audioOutputBuses = buses.busRange(
                        from + inCount, jsMod.getValue(base + 44, "i32") as Int
                    ),
                    mainInputBusIndex = jsMod.getValue(base + 48, "i32") as Int,
                    mainOutputBusIndex = jsMod.getValue(base + 52, "i32") as Int
                )
            }
            GraphNodesResult(
                true, error, nodes,
                (jsMod.getValue(out + 24, "i32") as Int).toUInt(),
                (jsMod.getValue(out + 28, "i32") as Int).toUInt(),
                (jsMod.getValue(out + 32, "i32") as Int).toUInt(),
                (jsMod.getValue(out + 36, "i32") as Int).toUInt()
            )
        }

    override fun connectTrackGraph(trackIndex: Int, connection: GraphConnection): OpResult =
        withWasmMem(JsGraphConnectionSize) { c ->
            jsMod.setValue(c, js("BigInt")(connection.id.toString()), "i64")
            jsMod.setValue(c + 8, connection.busType.nativeValue, "i32")
            withJsTwoCStrings(connection.source.nodeId, connection.target.nodeId) { src, tgt ->
                jsWriteEndpoint(c + 12, connection.source, src)
                jsWriteEndpoint(c + 12 + JsGraphEndpointSize, connection.target, tgt)
                withWasmMem(8) { out ->
                    jsMod._uapmd_app_connect_track_graph(out, handle, trackIndex, c)
                    jsReadOpResult(out)
                }
            }
        }

    override fun disconnectTrackGraphConnection(trackIndex: Int, connectionId: Long): OpResult =
        withWasmMem(8) { out ->
            jsMod._uapmd_app_disconnect_track_graph_connection(
                out, handle, trackIndex, js("BigInt")(connectionId.toString())
            )
            jsReadOpResult(out)
        }

    // ── Clip audio events ───────────────────────────────────────────────────

    override fun getClipAudioEvents(trackIndex: Int, clipId: Int): ClipAudioEventsResult =
        withWasmMem(24) { out ->
            jsMod._uapmd_app_get_clip_audio_events(out, handle, trackIndex, clipId)
            val ok = (jsMod.getValue(out, "i8") as Int) != 0
            val errPtr = jsMod.getValue(out + 4, "i32") as Int
            val error = if (errPtr != 0) jsMod.UTF8ToString(errPtr) as String else null
            if (!ok) ClipAudioEventsResult(false, error, emptyList(), emptyList())
            else {
                val mCount = jsMod.getValue(out + 8, "i32") as Int
                val mPtr = jsMod.getValue(out + 12, "i32") as Int
                val wCount = jsMod.getValue(out + 16, "i32") as Int
                val wPtr = jsMod.getValue(out + 20, "i32") as Int
                ClipAudioEventsResult(
                    true, error,
                    if (mPtr == 0) emptyList() else (0 until mCount).map { i ->
                        val b = mPtr + i * JsClipMarkerSize
                        ClipMarkerData(
                            markerId = jsStrAt(b),
                            clipPositionOffset = jsMod.getValue(b + 8, "double") as Double,
                            referenceType = WarpReferenceType.fromNative(jsMod.getValue(b + 16, "i32") as Int),
                            referenceClipId = jsStrAt(b + 20),
                            referenceMarkerId = jsStrAt(b + 24),
                            name = jsStrAt(b + 28)
                        )
                    },
                    if (wPtr == 0) emptyList() else (0 until wCount).map { i ->
                        val b = wPtr + i * JsWarpPointSize
                        AudioWarpPointData(
                            clipPositionOffset = jsMod.getValue(b, "double") as Double,
                            speedRatio = jsMod.getValue(b + 8, "double") as Double,
                            referenceType = WarpReferenceType.fromNative(jsMod.getValue(b + 16, "i32") as Int),
                            referenceClipId = jsStrAt(b + 20),
                            referenceMarkerId = jsStrAt(b + 24)
                        )
                    }
                )
            }
        }

    override fun setClipAudioEvents(
        trackIndex: Int, clipId: Int,
        markers: List<ClipMarkerData>, warps: List<AudioWarpPointData>
    ): OpResult {
        val owned = mutableListOf<Int>()
        fun cstr(v: String): Int {
            val size = (jsMod.lengthBytesUTF8(v) as Int) + 1
            val p = jsMod._malloc(size) as Int
            jsMod.stringToUTF8(v, p, size)
            owned += p
            return p
        }
        val mBuf = if (markers.isEmpty()) 0 else (jsMod._malloc(markers.size * JsClipMarkerSize) as Int).also { owned += it }
        markers.forEachIndexed { i, m ->
            val b = mBuf + i * JsClipMarkerSize
            jsMod.setValue(b, cstr(m.markerId), "i32")
            jsMod.setValue(b + 8, m.clipPositionOffset, "double")
            jsMod.setValue(b + 16, m.referenceType.nativeValue, "i32")
            jsMod.setValue(b + 20, cstr(m.referenceClipId), "i32")
            jsMod.setValue(b + 24, cstr(m.referenceMarkerId), "i32")
            jsMod.setValue(b + 28, cstr(m.name), "i32")
        }
        val wBuf = if (warps.isEmpty()) 0 else (jsMod._malloc(warps.size * JsWarpPointSize) as Int).also { owned += it }
        warps.forEachIndexed { i, w ->
            val b = wBuf + i * JsWarpPointSize
            jsMod.setValue(b, w.clipPositionOffset, "double")
            jsMod.setValue(b + 8, w.speedRatio, "double")
            jsMod.setValue(b + 16, w.referenceType.nativeValue, "i32")
            jsMod.setValue(b + 20, cstr(w.referenceClipId), "i32")
            jsMod.setValue(b + 24, cstr(w.referenceMarkerId), "i32")
        }
        return try {
            withWasmMem(8) { out ->
                jsMod._uapmd_app_set_clip_audio_events(out, handle, trackIndex, clipId, mBuf, markers.size, wBuf, warps.size)
                jsReadOpResult(out)
            }
        } finally { owned.forEach { jsMod._free(it) } }
    }
}

// Layouts verified with emcc -fdump-record-layouts-complete; see WasmJsAppModel.kt.
private const val JsGraphConnectionSize = 48
private const val JsGraphEndpointSize = 16
private const val JsGraphNodeSize = 56
private const val JsGraphAudioBusSize = 20
private const val JsClipMarkerSize = 32
private const val JsWarpPointSize = 32

private const val JsClipAddResultSize = 16
private const val JsAppRenderSettingsSize = 80
private const val JsAppRenderStatusSize = 32

private fun jsReadClipMarker(p: Int) = ClipMarkerData(
    markerId = jsStrAt(p),
    clipPositionOffset = jsMod.getValue(p + 8, "double") as Double,
    referenceType = WarpReferenceType.fromNative(jsMod.getValue(p + 16, "i32") as Int),
    referenceClipId = jsStrAt(p + 20),
    referenceMarkerId = jsStrAt(p + 24),
    name = jsStrAt(p + 28)
)

private const val JsMidiPortInfoSize = 8
private const val JsTimelineContentBoundsSize = 32
private const val JsDeviceEntrySize = 20
private const val JsPluginStateResultSize = 16

/**
 * Both port getters answer the count for a null `out`, and neither mutates
 * anything, so the count-then-fill call pair is safe here.
 */
private fun jsMidiPorts(call: (out: Int, count: Int) -> dynamic): List<MidiPortInfo> {
    val n = call(0, 0) as Int
    if (n == 0) return emptyList()
    return withWasmMem(n * JsMidiPortInfoSize) { out ->
        val filled = call(out, n) as Int
        (0 until filled).map { i ->
            val p = out + i * JsMidiPortInfoSize
            MidiPortInfo(jsStrAt(p), jsStrAt(p + 4))
        }
    }
}

private fun jsReadDeviceEntry(p: Int) = DeviceEntry(
    id = jsMod.getValue(p, "i32") as Int,
    label = jsStrAt(p + 4),
    apiName = jsStrAt(p + 8),
    statusMessage = jsStrAt(p + 12),
    running = jsGetBool(p + 16),
    instantiating = jsGetBool(p + 17),
    hasError = jsGetBool(p + 18)
)

private fun jsReadPluginStateResult(p: Int) = PluginStateResult(
    instanceId = jsMod.getValue(p, "i32") as Int,
    success = jsGetBool(p + 4),
    error = jsStrAt(p + 8),
    filepath = jsStrAt(p + 12)
)

/** uapmd_plugin_state_result_t arrives by value, i.e. as a pointer. */
private fun makeJsPluginState(callback: (PluginStateResult) -> Unit): Int {
    var slot = 0
    val fn: (dynamic, dynamic) -> Unit = { resultPtr, _ ->
        try { callback(jsReadPluginStateResult(resultPtr as Int)) } finally { removeJsCallback(slot) }
    }
    slot = addJsCallback(fn.asDynamic(), "vii")
    return slot
}

private fun jsStrAt(ptr: Int): String {
    val p = jsMod.getValue(ptr, "i32") as Int
    return if (p != 0) jsMod.UTF8ToString(p) as String else ""
}

private fun jsReadEndpoint(ptr: Int) = GraphEndpoint(
    GraphEndpointType.fromNative(jsMod.getValue(ptr, "i32") as Int),
    jsStrAt(ptr + 4),
    jsMod.getValue(ptr + 8, "i32") as Int,
    (jsMod.getValue(ptr + 12, "i32") as Int).toUInt()
)

/** [nodeIdPtr] must outlive the call the struct is passed to. */
private fun jsWriteEndpoint(ptr: Int, e: GraphEndpoint, nodeIdPtr: Int) {
    jsMod.setValue(ptr, e.type.nativeValue, "i32")
    jsMod.setValue(ptr + 4, nodeIdPtr, "i32")
    jsMod.setValue(ptr + 8, e.instanceId, "i32")
    jsMod.setValue(ptr + 12, e.busIndex.toInt(), "i32")
}

private fun jsReadOpResult(ptr: Int): OpResult {
    val ok = (jsMod.getValue(ptr, "i8") as Int) != 0
    val errPtr = jsMod.getValue(ptr + 4, "i32") as Int
    return OpResult(ok, if (errPtr != 0) jsMod.UTF8ToString(errPtr) as String else null)
}

private fun decodeJsProjectResult(ptr: Int): AppProjectResult {
    val ok = (jsMod.getValue(ptr, "i8") as Int) != 0
    val errPtr = jsMod.getValue(ptr + 4, "i32") as Int
    return AppProjectResult(ok, if (errPtr != 0) jsMod.UTF8ToString(errPtr) as String else null)
}

private fun makeJsProjectSave(callback: (AppProjectResult) -> Unit): Int {
    var slot = 0
    val fn: (dynamic, dynamic) -> Unit = { resultPtr, _ ->
        try { callback(decodeJsProjectResult(resultPtr as Int)) } finally { removeJsCallback(slot) }
    }
    slot = addJsCallback(fn.asDynamic(), "vii")
    return slot
}

/**
 * uapmd_plugin_instance_result_t arrives by value, i.e. as a pointer.
 * wasm32 layout: int32 id @0, char* name @4, char* err @8.
 */
private fun makeJsInstanceCreated(callback: (PluginInstanceResult) -> Unit): Int {
    var slot = 0
    val fn: (dynamic, dynamic) -> Unit = { resultPtr, _ ->
        try {
            val p = resultPtr as Int
            val id = jsMod.getValue(p, "i32") as Int
            val namePtr = jsMod.getValue(p + 4, "i32") as Int
            val errPtr = jsMod.getValue(p + 8, "i32") as Int
            callback(
                PluginInstanceResult(
                    instanceId = id,
                    pluginName = if (namePtr != 0) jsMod.UTF8ToString(namePtr) as String else "",
                    error = if (errPtr != 0) jsMod.UTF8ToString(errPtr) as String else null
                )
            )
        } finally {
            removeJsCallback(slot)
        }
    }
    slot = addJsCallback(fn.asDynamic(), "vii")
    return slot
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

    override fun jump(positionSeconds: Double) { jsMod._uapmd_transport_jump(handle, positionSeconds) }
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

actual fun registerVirtualMidiDevicesAddin() {
    jsMod._uapmd_app_register_virtual_midi_devices_addin()
}

private var jsShowVirtualMidiDevicesHandler: (() -> Unit)? = null
private var jsShowVirtualMidiDevicesFnPtr = 0

class JsDocumentProvider internal constructor(internal val handle: Int) : DocumentProvider {
    override fun tick() { jsMod._uapmd_document_provider_tick(handle) }
}

private fun readJsClipTargets(base: Int, count: Int): List<TimelineClipTarget> =
    (0 until count).map {
        val entry = base + it * Off.CLIP_TARGET_STRIDE
        TimelineClipTarget(
            jsGetI32(entry + Off.CLIP_TARGET_TRACK),
            jsGetI32(entry + Off.CLIP_TARGET_CLIP)
        )
    }

private fun readJsPianoRollNote(p: Int) = PianoRollNote(
    startSeconds = jsGetF64(p + Off.PR_NOTE_START),
    durationSeconds = jsGetF64(p + Off.PR_NOTE_DURATION),
    velocity = (jsMod.getValue(p + Off.PR_NOTE_VELOCITY, "float") as Number).toFloat(),
    note = jsGetI8(p + Off.PR_NOTE_NOTE) and 0xFF,
    channel = jsGetI8(p + Off.PR_NOTE_CHANNEL) and 0xFF,
    deleted = jsGetBool(p + Off.PR_NOTE_DELETED),
    editId = jsGetI64(p + Off.PR_NOTE_EDIT_ID),
    umpGroup = jsGetI8(p + Off.PR_NOTE_UMP_GROUP) and 0xFF,
    releaseVelocity = (jsMod.getValue(p + Off.PR_NOTE_RELEASE_VELOCITY, "i16") as Int) and 0xFFFF,
    attributeType = jsGetI8(p + Off.PR_NOTE_ATTRIBUTE_TYPE) and 0xFF,
    attributeValue = (jsMod.getValue(p + Off.PR_NOTE_ATTRIBUTE_VALUE, "i16") as Int) and 0xFFFF,
    automationEventCount = jsGetI32(p + Off.PR_NOTE_AUTOMATION_COUNT)
)

class JsPianoRollSnapshot internal constructor(internal val handle: Int) : PianoRollSnapshot {
    override val isReady: Boolean get() = jsMod._uapmd_piano_roll_snapshot_ready(handle) as Boolean
    override val error: String
        get() = (jsMod._uapmd_piano_roll_snapshot_error(handle) as Int)
            .let { if (it != 0) jsMod.UTF8ToString(it) as String else "" }
    override val durationSeconds: Double
        get() = jsMod._uapmd_piano_roll_snapshot_duration_seconds(handle) as Double
    override val minNote: Int get() = jsMod._uapmd_piano_roll_snapshot_min_note(handle) as Int
    override val maxNote: Int get() = jsMod._uapmd_piano_roll_snapshot_max_note(handle) as Int

    override val notes: List<PianoRollNote>
        get() = withWasmMem(Off.PR_NOTE_SIZE) { out ->
            (0 until (jsMod._uapmd_piano_roll_snapshot_note_count(handle) as Int)).mapNotNull { i ->
                if (!(jsMod._uapmd_piano_roll_snapshot_get_note(handle, i, out) as Boolean)) null
                else readJsPianoRollNote(out)
            }
        }

    override fun close() { jsMod._uapmd_piano_roll_snapshot_destroy(handle) }
}

class JsPianoRollSession internal constructor(private val handle: Int) : PianoRollSession {
    override val notes: List<PianoRollNote>
        get() = withWasmMem(Off.PR_NOTE_SIZE) { out ->
            (0 until (jsMod._uapmd_piano_roll_session_note_count(handle) as Int)).mapNotNull { i ->
                if (!(jsMod._uapmd_piano_roll_session_get_note(handle, i, out) as Boolean)) null
                else readJsPianoRollNote(out)
            }
        }

    override fun isNoteSelected(index: Int) =
        jsMod._uapmd_piano_roll_session_is_note_selected(handle, index) as Boolean
    override val selectedNoteCount: Int
        get() = jsMod._uapmd_piano_roll_session_selected_note_count(handle) as Int

    override var focusedNote: Int
        get() = jsMod._uapmd_piano_roll_session_focused_note(handle) as Int
        set(value) { jsMod._uapmd_piano_roll_session_set_focused_note(handle, value) }

    override val durationSeconds: Double
        get() = jsMod._uapmd_piano_roll_session_duration_seconds(handle) as Double
    override val minNote: Int get() = jsMod._uapmd_piano_roll_session_min_note(handle) as Int
    override val maxNote: Int get() = jsMod._uapmd_piano_roll_session_max_note(handle) as Int
    override val clipboardCount: Int get() = jsMod._uapmd_piano_roll_session_clipboard_count(handle) as Int
    override val isDirty: Boolean get() = jsMod._uapmd_piano_roll_session_dirty(handle) as Boolean
    override val error: String
        get() = (jsMod._uapmd_piano_roll_session_error(handle) as Int)
            .let { if (it != 0) jsMod.UTF8ToString(it) as String else "" }

    override fun matchesSource(snapshot: PianoRollSnapshot) =
        jsMod._uapmd_piano_roll_session_matches_source(
            handle, (snapshot as JsPianoRollSnapshot).handle) as Boolean

    override fun loadNotes(snapshot: PianoRollSnapshot?) {
        jsMod._uapmd_piano_roll_session_load_notes(handle, (snapshot as JsPianoRollSnapshot?)?.handle ?: 0)
    }

    override fun selectNote(index: Int, additive: Boolean, toggle: Boolean) {
        jsMod._uapmd_piano_roll_session_select_note(handle, index, additive, toggle)
    }

    override fun performAction(action: PianoRollAction, pasteSeconds: Double) {
        jsMod._uapmd_piano_roll_session_perform_action(handle, action.nativeValue, pasteSeconds)
    }

    override fun createNote(startSeconds: Double, durationSeconds: Double, note: Int, velocity: Float) {
        jsMod._uapmd_piano_roll_session_create_note(handle, startSeconds, durationSeconds, note, velocity)
    }

    override fun deleteNote(index: Int) { jsMod._uapmd_piano_roll_session_delete_note(handle, index) }

    override fun resizeNote(index: Int, startSeconds: Double, durationSeconds: Double, note: Int) {
        jsMod._uapmd_piano_roll_session_resize_note(handle, index, startSeconds, durationSeconds, note)
    }

    override fun beginDrag() { jsMod._uapmd_piano_roll_session_begin_drag(handle) }
    override fun moveSelection(timeDeltaSeconds: Double, pitchDelta: Int) {
        jsMod._uapmd_piano_roll_session_move_selection(handle, timeDeltaSeconds, pitchDelta)
    }
    override fun cancelDrag() { jsMod._uapmd_piano_roll_session_cancel_drag(handle) }
    override fun finishDrag(index: Int, originalStart: Double, originalEnd: Double, originalNote: Int) {
        jsMod._uapmd_piano_roll_session_finish_drag(handle, index, originalStart, originalEnd, originalNote)
    }

    override fun commit(app: AppModel) =
        jsMod._uapmd_piano_roll_session_commit(handle, (app as JsAppModel).handle) as Boolean
}
