package dev.atsushieno.uapmd

class WasmJsAppModel internal constructor(internal val handle: Int) : AppModel {

    override val sequencer: RealtimeSequencer
        // Borrowed: AppModel owns this; callers must not close it (see AppModel.sequencer).
        get() = WasmJsRealtimeSequencer(wasmMod.uapmdAppSequencer(handle))

    override val transport: TransportController
        get() = WasmJsTransportController(wasmMod.uapmdAppTransport(handle))

    override val sampleRate: Int get() = wasmMod.uapmdAppSampleRate(handle)
    override val trackCount: UInt get() = wasmMod.uapmdAppTrackCount(handle).toUInt()

    override val isScanning: Boolean get() = wasmMod.uapmdAppIsScanning(handle)

    override val isAudioEngineEnabled: Boolean get() = wasmMod.uapmdAppIsAudioEngineEnabled(handle)
    override fun setAudioEngineEnabled(enabled: Boolean) = wasmMod.uapmdAppSetAudioEngineEnabled(handle, enabled)
    override fun toggleAudioEngine() = wasmMod.uapmdAppToggleAudioEngine(handle)

    override var autoBufferSizeEnabled: Boolean
        get() = wasmMod.uapmdAppAutoBufferSizeEnabled(handle)
        set(value) { wasmMod.uapmdAppSetAutoBufferSizeEnabled(handle, value) }

    override fun updateAudioDeviceSettings(sampleRate: Int, bufferSize: UInt) =
        wasmMod.uapmdAppUpdateAudioDeviceSettings(handle, sampleRate, bufferSize.toInt())

    override fun notifyUiReady() = wasmMod.uapmdAppNotifyUiReady(handle)
    override fun notifyPersistentStorageReady() = wasmMod.uapmdAppNotifyPersistentStorageReady(handle)

    // ── Plugin scanning ─────────────────────────────────────────────────────

    override fun performPluginScanning(
        forceRescan: Boolean, mode: ScanMode, remoteTimeoutSeconds: Double, requireFastScanning: Boolean
    ) = wasmMod.uapmdAppPerformPluginScanning(
        handle, forceRescan, if (mode == ScanMode.Remote) 1 else 0, remoteTimeoutSeconds, requireFastScanning
    )

    override fun cancelPluginScanning() = wasmMod.uapmdAppCancelPluginScanning(handle)

    // uapmd_slow_scan_progress_t: running@0 processed@4 total@8 currentBundle@12, size 16.
    override val slowScanProgress: SlowScanProgress
        get() = withWasmStruct(16) { out ->
            val mod = wasmMod
            mod.uapmdAppSlowScanProgress(out, handle)
            SlowScanProgress(
                running = mod.getValue(out, "i8").toInt() != 0,
                processedBundles = mod.getValue(out + 4, "i32").toInt().toUInt(),
                totalBundles = mod.getValue(out + 8, "i32").toInt().toUInt(),
                currentBundle = wasmStrAt(out + 12)
            )
        }

    override val lastPluginScanError: String?
        get() = readString(handle) { h, buf, size -> uapmdAppLastPluginScanError(h, buf, size) }
            .ifEmpty { null }

    override fun generateScanReport(): String =
        readString(handle) { h, buf, size -> uapmdAppGenerateScanReport(h, buf, size) }

    override fun clearPluginBlocklist() = wasmMod.uapmdAppClearPluginBlocklist(handle)

    override val blocklist: List<BlocklistEntry>
        get() {
            val mod = wasmMod
            return (0 until mod.uapmdAppBlocklistCount(handle)).mapNotNull { i ->
                val ptr = mod.malloc(16) // sizeof uapmd_blocklist_entry_t: 4 char*
                try {
                    if (!mod.uapmdAppGetBlocklistEntry(handle, i, ptr)) null
                    else {
                        fun getStr(o: Int): String {
                            val p = mod.getValue(ptr + o, "i32").toInt()
                            return if (p != 0) mod.utf8ToString(p) else ""
                        }
                        BlocklistEntry(getStr(0), getStr(4), getStr(8), getStr(12))
                    }
                } finally { mod.free(ptr) }
            }
        }

    override fun unblockPlugin(entryId: String): Boolean =
        withCStringKt(entryId) { p -> wasmMod.uapmdAppUnblockPlugin(handle, p) }

    override fun refreshMasterTempoMap() = wasmMod.uapmdAppRefreshMasterTempoMap(handle)

    // Struct layouts: tempo {double@0, uint64@8, double@16}, signature
    // {double@0, uint64@8, uint8@16, uint8@17}; both 24 bytes, 8-aligned.
    override val masterTempoPoints: List<TempoPoint>
        get() {
            val mod = wasmMod
            return (0 until mod.uapmdAppMasterTempoPointCount(handle)).mapNotNull { i ->
                val ptr = mod.malloc(24)
                try {
                    if (!mod.uapmdAppGetMasterTempoPoint(handle, i, ptr)) null
                    else TempoPoint(
                        mod.getValue(ptr, "double"),
                        wasmReadI64(mod, ptr + 8).toLong(),
                        mod.getValue(ptr + 16, "double")
                    )
                } finally { mod.free(ptr) }
            }
        }

    override val masterTimeSignaturePoints: List<TimeSignaturePoint>
        get() {
            val mod = wasmMod
            return (0 until mod.uapmdAppMasterTimeSignatureCount(handle)).mapNotNull { i ->
                val ptr = mod.malloc(24)
                try {
                    if (!mod.uapmdAppGetMasterTimeSignature(handle, i, ptr)) null
                    else TimeSignaturePoint(
                        mod.getValue(ptr, "double"),
                        wasmReadI64(mod, ptr + 8).toLong(),
                        mod.getValue(ptr + 16, "i8").toInt() and 0xFF,
                        mod.getValue(ptr + 17, "i8").toInt() and 0xFF
                    )
                } finally { mod.free(ptr) }
            }
        }

    // ── Tracks ──────────────────────────────────────────────────────────────

    override fun isTrackMuted(trackIndex: Int) = wasmMod.uapmdAppIsTrackMuted(handle, trackIndex)
    override fun isTrackSolo(trackIndex: Int) = wasmMod.uapmdAppIsTrackSolo(handle, trackIndex)
    override fun setTrackMuted(trackIndex: Int, muted: Boolean) =
        wasmMod.uapmdAppSetTrackMuted(handle, trackIndex, muted)
    override fun setTrackSolo(trackIndex: Int, solo: Boolean) =
        wasmMod.uapmdAppSetTrackSolo(handle, trackIndex, solo)

    override fun addTrack(callback: (Int, String?) -> Unit) =
        wasmMod.uapmdAppAddTrack(handle, 0, appTrackMutationPtr(callback))

    override fun removeTrack(trackIndex: Int, callback: (Int, String?) -> Unit) =
        wasmMod.uapmdAppRemoveTrack(handle, trackIndex, 0, appTrackMutationPtr(callback))

    override fun removeAllTracks(callback: (String?) -> Unit) =
        wasmMod.uapmdAppRemoveAllTracks(handle, 0, appErrorOnlyPtr(callback))

    override val timelineTrackCount: UInt get() = wasmMod.uapmdAppTimelineTrackCount(handle).toUInt()

    override fun getTimelineTrack(index: UInt): TimelineTrack =
        WasmJsTimelineTrack(wasmMod.uapmdAppGetTimelineTrack(handle, index.toInt()))

    override val masterTimelineTrack: TimelineTrack
        get() = WasmJsTimelineTrack(wasmMod.uapmdAppMasterTimelineTrack(handle))

    override fun getTimelineState(): TimelineState? {
        val mod = wasmMod
        val ptr = mod.malloc(80) // sizeof uapmd_timeline_state_t
        return try {
            if (!mod.uapmdAppGetTimelineState(handle, ptr)) null
            else decodeTimelineStateAt(mod, ptr)
        } finally { mod.free(ptr) }
    }

    // ── History ─────────────────────────────────────────────────────────────

    override val historyState: UndoState
        get() = withWasmStruct(WasmOff.STATE_SIZE) { p ->
            wasmMod.uapmdAppGetHistoryState(handle, p)
            decodeUndoState(p)
        }

    override fun undo(callback: ((String?) -> Unit)?) =
        wasmMod.uapmdAppUndo(handle, 0, callback?.let { appErrorOnlyPtr(it) } ?: 0)

    override fun redo(callback: ((String?) -> Unit)?) =
        wasmMod.uapmdAppRedo(handle, 0, callback?.let { appErrorOnlyPtr(it) } ?: 0)

    // ── Plugin instances ────────────────────────────────────────────────────

    override fun createPluginInstance(
        format: String, pluginId: String, trackIndex: Int,
        config: PluginInstanceConfig, callback: (PluginInstanceResult) -> Unit
    ) {
        val mod = wasmMod
        // uapmd_plugin_instance_config_t: five char* fields, 20 bytes on wasm32.
        val cfg = mod.malloc(20)
        val strings = listOf(config.apiName, config.deviceName, config.manufacturer, config.version, config.stateFile)
        val ptrs = strings.map { str ->
            val size = mod.lengthBytesUTF8(str) + 1
            val p = mod.malloc(size)
            mod.stringToUTF8(str, p, size)
            p
        }
        ptrs.forEachIndexed { i, p -> mod.setValue(cfg + i * 4, p.toDouble(), "i32") }
        try {
            withTwoCStringsKt(format, pluginId) { f, pid ->
                mod.uapmdAppCreatePluginInstance(handle, f, pid, trackIndex, cfg, 0, appInstanceCreatedPtr(callback))
            }
        } finally {
            ptrs.forEach { mod.free(it) }
            mod.free(cfg)
        }
    }

    override fun removePluginInstance(instanceId: Int) = wasmMod.uapmdAppRemovePluginInstance(handle, instanceId)

    override fun getInstanceGroup(instanceId: Int): UByte =
        wasmMod.uapmdAppGetInstanceGroup(handle, instanceId).toUByte()

    override fun setInstanceGroup(instanceId: Int, group: UByte): Boolean =
        wasmMod.uapmdAppSetInstanceGroup(handle, instanceId, group.toInt())

    override fun enableUmpDevice(instanceId: Int, deviceName: String) =
        withCStringKt(deviceName) { d -> wasmMod.uapmdAppEnableUmpDevice(handle, instanceId, d) }

    override fun disableUmpDevice(instanceId: Int) = wasmMod.uapmdAppDisableUmpDevice(handle, instanceId)

    override val virtualMidiDevicesEnabled: Boolean
        get() = wasmMod.uapmdAppVirtualMidiDevicesEnabled(handle)

    override var autoCreateVirtualMidiDevices: Boolean
        get() = wasmMod.uapmdAppAutoCreateVirtualMidiDevices(handle)
        set(value) = wasmMod.uapmdAppSetAutoCreateVirtualMidiDevices(handle, value)

    override var showVirtualMidiDevices: (() -> Unit)?
        get() = showVirtualMidiDevicesHandler
        set(value) {
            showVirtualMidiDevicesHandler = value
            if (value == null) {
                wasmMod.uapmdAppSetShowVirtualMidiDevicesCallback(handle, 0, 0)
                return
            }
            // One function-table entry serves every handler: the dispatcher
            // reads the current one, so it is created once and never removed.
            if (showVirtualMidiDevicesFnPtr == 0)
                showVirtualMidiDevicesFnPtr = makeCFunctionPtr(0, "uapmdDispatchShowVirtualMidiDevices", "vi")
            wasmMod.uapmdAppSetShowVirtualMidiDevicesCallback(handle, 0, showVirtualMidiDevicesFnPtr)
        }

    override val documentProvider: DocumentProvider
        get() = WasmJsDocumentProvider(wasmMod.uapmdAppDocumentProvider(handle))

    override fun requestShowInstanceDetails(instanceId: Int) =
        wasmMod.uapmdAppRequestShowInstanceDetails(handle, instanceId)

    override fun requestShowPluginUi(instanceId: Int) = wasmMod.uapmdAppRequestShowPluginUi(handle, instanceId)
    override fun hidePluginUi(instanceId: Int) = wasmMod.uapmdAppHidePluginUi(handle, instanceId)

    // ── Project I/O ─────────────────────────────────────────────────────────

    private fun projectCall(path: String, call: (out: Int, app: Int, str: Int) -> Unit): AppProjectResult =
        withWasmStruct(8) { out ->                 // sizeof uapmd_app_project_result_t
            withCStringKt(path) { p -> call(out, handle, p) }
            decodeProjectResult(out)
        }

    override fun loadProject(filePath: String): AppProjectResult =
        projectCall(filePath) { out, app, p -> wasmMod.uapmdAppLoadProject(out, app, p) }

    override fun saveProjectSync(filePath: String): AppProjectResult =
        projectCall(filePath) { out, app, p -> wasmMod.uapmdAppSaveProjectSync(out, app, p) }

    override fun saveProject(filePath: String, callback: (AppProjectResult) -> Unit) {
        withCStringKt(filePath) { p ->
            wasmMod.uapmdAppSaveProject(handle, p, 0, appProjectSavePtr(callback))
        }
    }

    override fun loadProjectFromHandleToken(token: String): AppProjectResult =
        projectCall(token) { out, app, t -> wasmMod.uapmdAppLoadProjectFromHandleToken(out, app, t) }

    override fun newProject(): AppProjectResult =
        withWasmStruct(8) { out ->                 // sizeof uapmd_app_project_result_t
            wasmMod.uapmdAppNewProject(out, handle)
            decodeProjectResult(out)
        }

    override val masterTempoMap: TempoMap
        get() = WasmJsTempoMap(wasmMod.uapmdAppMasterTempoMap(handle))

    // ── Timeline clip selection and clipboard ───────────────────────────────

    override fun isTimelineClipSelected(trackIndex: Int, clipId: Int): Boolean =
        wasmMod.uapmdAppIsTimelineClipSelected(handle, trackIndex, clipId)

    override val selectedTimelineClips: List<TimelineClipTarget>
        get() {
            val count = wasmMod.uapmdAppSelectedTimelineClips(handle, 0, 0)
            if (count <= 0) return emptyList()
            return withWasmStruct(count * WasmOff.CLIP_TARGET_STRIDE) { buf ->
                val filled = wasmMod.uapmdAppSelectedTimelineClips(handle, buf, count)
                readClipTargets(buf, minOf(count, filled))
            }
        }

    override fun selectTimelineClips(clips: List<TimelineClipTarget>, additive: Boolean, toggle: Boolean) {
        if (clips.isEmpty()) {
            wasmMod.uapmdAppSelectTimelineClips(handle, 0, 0, additive, toggle)
            return
        }
        withWasmStruct(clips.size * WasmOff.CLIP_TARGET_STRIDE) { buf ->
            writeClipTargets(buf, clips)
            wasmMod.uapmdAppSelectTimelineClips(handle, buf, clips.size, additive, toggle)
        }
    }

    override fun clearTimelineClipSelection() = wasmMod.uapmdAppClearTimelineClipSelection(handle)

    override fun selectTimelineMidiClip(trackIndex: Int, clipId: Int): Boolean =
        wasmMod.uapmdAppSelectTimelineMidiClip(handle, trackIndex, clipId)

    override val selectedTimelineMidiClip: TimelineClipTarget?
        get() = withWasmStruct(WasmOff.CLIP_TARGET_STRIDE) { out ->
            if (!wasmMod.uapmdAppSelectedTimelineMidiClip(handle, out)) null
            else TimelineClipTarget(
                wasmGetI32(out + WasmOff.CLIP_TARGET_TRACK),
                wasmGetI32(out + WasmOff.CLIP_TARGET_CLIP)
            )
        }

    override val timelineClipboardCount: Int
        get() = wasmMod.uapmdAppTimelineClipboardCount(handle)

    override fun clearTimelineClipboard() = wasmMod.uapmdAppClearTimelineClipboard(handle)

    override fun copySelectedTimelineClips(): Boolean =
        wasmMod.uapmdAppCopySelectedTimelineClips(handle)

    override fun deleteSelectedTimelineClips(cut: Boolean): TimelineClipDeleteResult {
        // One call only: this both deletes and reports. A track can lose several
        // clips but appears once, so the selection size bounds the changed-track
        // list — measured before the call, which clears the selection.
        val capacity = selectedTimelineClips.size
        return withWasmStruct(maxOf(capacity, 1) * 4) { tracks ->
            withWasmStruct(4) { countPtr ->
                wasmSetI32(countPtr, capacity)
                val ok = wasmMod.uapmdAppDeleteSelectedTimelineClips(
                    handle, cut, if (capacity > 0) tracks else 0, countPtr)
                val count = minOf(capacity, wasmGetI32(countPtr))
                TimelineClipDeleteResult(
                    ok,
                    (0 until count).map { wasmGetI32(tracks + it * 4) },
                    lastTimelineClipError.ifEmpty { null }
                )
            }
        }
    }

    override fun timelinePasteDestinations(trackIndex: Int, originalTracks: Boolean): List<Int> {
        val count = wasmMod.uapmdAppTimelinePasteDestinations(handle, trackIndex, originalTracks, 0, 0)
        if (count <= 0) return emptyList()
        return withWasmStruct(count * 4) { buf ->
            val filled = wasmMod.uapmdAppTimelinePasteDestinations(
                handle, trackIndex, originalTracks, buf, count)
            (0 until minOf(count, filled)).map { wasmGetI32(buf + it * 4) }
        }
    }

    override fun pasteTimelineClips(
        trackIndex: Int,
        positionSeconds: Double,
        originalTracks: Boolean
    ): TimelinePasteResult {
        // A paste creates at most one clip per clipboard entry, which bounds it.
        val capacity = timelineClipboardCount
        return withWasmStruct(maxOf(capacity, 1) * WasmOff.CLIP_TARGET_STRIDE) { buf ->
            withWasmStruct(4) { countPtr ->
                wasmSetI32(countPtr, capacity)
                val ok = wasmMod.uapmdAppPasteTimelineClips(
                    handle, trackIndex, positionSeconds, originalTracks,
                    if (capacity > 0) buf else 0, countPtr)
                val count = minOf(capacity, wasmGetI32(countPtr))
                TimelinePasteResult(ok, readClipTargets(buf, count), lastTimelineClipError.ifEmpty { null })
            }
        }
    }

    override val lastTimelineClipError: String
        get() = wasmMod.uapmdAppLastTimelineClipError()
            .let { if (it != 0) wasmMod.utf8ToString(it) else "" }

    // ── Piano roll editing session ──────────────────────────────────────────

    override fun pianoRollClipSnapshot(
        trackIndex: Int,
        clipId: Int,
        fallbackDurationSeconds: Double
    ): PianoRollSnapshot? =
        wasmMod.uapmdAppPianoRollClipSnapshot(handle, trackIndex, clipId, fallbackDurationSeconds)
            .takeIf { it != 0 }?.let { WasmJsPianoRollSnapshot(it) }

    override fun openPianoRollSession(trackIndex: Int, clipId: Int): PianoRollSession? =
        wasmMod.uapmdAppOpenPianoRollSession(handle, trackIndex, clipId)
            .takeIf { it != 0 }?.let { WasmJsPianoRollSession(it) }

    override fun findPianoRollSession(trackIndex: Int, clipId: Int): PianoRollSession? =
        wasmMod.uapmdAppFindPianoRollSession(handle, trackIndex, clipId)
            .takeIf { it != 0 }?.let { WasmJsPianoRollSession(it) }

    override fun closePianoRollSession(trackIndex: Int, clipId: Int) =
        wasmMod.uapmdAppClosePianoRollSession(handle, trackIndex, clipId)

    override fun recordPianoRollCommitSource(trackIndex: Int, clipId: Int) =
        wasmMod.uapmdAppRecordPianoRollCommitSource(handle, trackIndex, clipId)

    override fun pianoRollSourceMatchesLastEdit(): Boolean =
        wasmMod.uapmdAppPianoRollSourceMatchesLastEdit(handle)

    override fun clearPianoRollCommitSource() = wasmMod.uapmdAppClearPianoRollCommitSource(handle)

    // ── Assorted accessors ──────────────────────────────────────────────────
    //
    // wasm32 layouts, checked with _Static_assert under emcc:
    //   uapmd_midi_port_info_t          char* @0, char* @4                        (8)
    //   uapmd_timeline_content_bounds_t bool @0, double @8, @16, @24             (32)
    //   uapmd_device_entry_t            i32 @0, char* @4 @8 @12, bool @16 @17 @18 (20)
    //   uapmd_plugin_state_result_t     i32 @0, bool @4, char* @8, char* @12     (16)

    override val midiInputPorts: List<MidiPortInfo>
        get() = wasmMidiPorts { out, n -> wasmMod.uapmdAppGetMidiInputPorts(handle, out, n) }

    override val midiOutputPorts: List<MidiPortInfo>
        get() = wasmMidiPorts { out, n -> wasmMod.uapmdAppGetMidiOutputPorts(handle, out, n) }

    override fun isTrackHidden(trackIndex: Int) = wasmMod.uapmdAppIsTrackHidden(handle, trackIndex)

    override val timelineContentBounds: TimelineContentBounds
        get() = withWasmStruct(TimelineContentBoundsSize) { out ->
            wasmMod.uapmdAppTimelineContentBounds(out, handle)
            TimelineContentBounds(
                hasContent = wasmMod.getValue(out, "i8").toInt() != 0,
                startSeconds = wasmMod.getValue(out + 8, "double"),
                endSeconds = wasmMod.getValue(out + 16, "double"),
                durationSeconds = wasmMod.getValue(out + 24, "double")
            )
        }

    override val devices: List<DeviceEntry>
        get() {
            val n = wasmMod.uapmdAppGetDevices(handle, 0, 0)
            if (n == 0) return emptyList()
            return withWasmStruct(n * DeviceEntrySize) { out ->
                val filled = wasmMod.uapmdAppGetDevices(handle, out, n)
                (0 until filled).map { readDeviceEntry(out + it * DeviceEntrySize) }
            }
        }

    override fun deviceForInstance(instanceId: Int): DeviceEntry? =
        withWasmStruct(DeviceEntrySize) { out ->
            if (!wasmMod.uapmdAppGetDeviceForInstance(handle, instanceId, out)) null
            else readDeviceEntry(out)
        }

    override fun updateDeviceLabel(instanceId: Int, label: String) =
        withCStringKt(label) { p -> wasmMod.uapmdAppUpdateDeviceLabel(handle, instanceId, p) }

    override fun loadPluginState(instanceId: Int, filepath: String, callback: (PluginStateResult) -> Unit) =
        withCStringKt(filepath) { p ->
            wasmMod.uapmdAppLoadPluginState(handle, instanceId, p, 0, pluginStatePtr(callback))
        }

    override fun savePluginState(instanceId: Int, filepath: String, callback: (PluginStateResult) -> Unit) =
        withCStringKt(filepath) { p ->
            wasmMod.uapmdAppSavePluginState(handle, instanceId, p, 0, pluginStatePtr(callback))
        }

    override fun loadPluginStateSync(instanceId: Int, filepath: String): PluginStateResult =
        withWasmStruct(PluginStateResultSize) { out ->
            withCStringKt(filepath) { p -> wasmMod.uapmdAppLoadPluginStateSync(out, handle, instanceId, p) }
            readPluginStateResult(out)
        }

    override fun savePluginStateSync(instanceId: Int, filepath: String): PluginStateResult =
        withWasmStruct(PluginStateResultSize) { out ->
            withCStringKt(filepath) { p -> wasmMod.uapmdAppSavePluginStateSync(out, handle, instanceId, p) }
            readPluginStateResult(out)
        }

    override fun markPluginInstanceTrackDirty(instanceId: Int) =
        wasmMod.uapmdAppMarkPluginInstanceTrackDirty(handle, instanceId)

    // ── MIDI clip UMP events ────────────────────────────────────────────────
    //
    // uapmd_ump_events_result_t: bool @0, char* @4, uint32 @8, ptr @12 (size 16)
    // uapmd_ump_event_t:         uint64 @0, uint32 @8, ptr @12 (size 16)

    override fun getMidiClipUmpEvents(trackIndex: Int, clipId: Int): UmpEventsResult =
        // uapmd_ump_events_result_t: ok@0 err@4 count@8 events@12 tickRes@16 tempo@24, size 32
        withWasmStruct(32) { out ->
            wasmMod.uapmdAppGetMidiClipUmpEvents(out, handle, trackIndex, clipId)
            val mod = wasmMod
            val ok = mod.getValue(out, "i8").toInt() != 0
            val errPtr = mod.getValue(out + 4, "i32").toInt()
            val error = if (errPtr != 0) mod.utf8ToString(errPtr) else null
            val count = mod.getValue(out + 8, "i32").toInt()
            val eventsPtr = mod.getValue(out + 12, "i32").toInt()
            val tickRes = mod.getValue(out + 16, "i32").toInt().toUInt()
            val tempo = mod.getValue(out + 24, "double")
            if (!ok || eventsPtr == 0 || count == 0) UmpEventsResult(ok, error, emptyList(), tickRes, tempo)
            else UmpEventsResult(ok, error, (0 until count).map { i ->
                val base = eventsPtr + i * 16
                val lo = mod.getValue(base, "i32").toInt().toLong() and 0xFFFFFFFFL
                val hi = mod.getValue(base + 4, "i32").toInt().toLong()
                val wordCount = mod.getValue(base + 8, "i32").toInt()
                val wordsPtr = mod.getValue(base + 12, "i32").toInt()
                UmpEvent(hi * 4294967296L + lo, UIntArray(wordCount) { w ->
                    mod.getValue(wordsPtr + w * 4, "i32").toInt().toUInt()
                })
            }, tickRes, tempo)
        }

    override fun addUmpEventToClip(trackIndex: Int, clipId: Int, tick: Long, words: UIntArray): Boolean {
        val mod = wasmMod
        val buf = mod.malloc(words.size * 4)
        return try {
            words.forEachIndexed { i, w -> mod.setValue(buf + i * 4, w.toInt().toDouble(), "i32") }
            wasmAppAddUmpEventToClip(mod, handle, trackIndex, clipId, tick.toString(), buf, words.size)
        } finally { mod.free(buf) }
    }

    override fun removeUmpEventFromClip(trackIndex: Int, clipId: Int, eventIndex: Int): Boolean =
        wasmMod.uapmdAppRemoveUmpEventFromClip(handle, trackIndex, clipId, eventIndex)

    override fun removeClipFromTrack(trackIndex: Int, clipId: Int): Boolean =
        wasmMod.uapmdAppRemoveClipFromTrack(handle, trackIndex, clipId)

    // uapmd_clip_add_result_t: int32 @0, int32 @4, bool @8, char* @12 (size 16)
    override fun importMidiTracksFromFile(filepath: String, callback: (Boolean, String?, Int) -> Unit) {
        // void(bool, const char*, uint32_t, void*) -> four i32 args, no return.
        val cbId = nextCallbackId()
        pendingImportMidiTracksCallbacks[cbId] = callback
        val fnPtr = makeCFunctionPtr(cbId, "uapmdDispatchImportMidiTracks", "viiii")
        withCStringKt(filepath) { fp ->
            wasmMod.uapmdAppImportMidiTracksFromFile(handle, fp, 0, fnPtr)
        }
    }

    override fun createEmptyMidiClip(
        trackIndex: Int, positionSamples: Long, tickResolution: UInt, bpm: Double
    ): ClipAddResult = withWasmStruct(16) { out ->
        wasmAppCreateEmptyMidiClip(wasmMod, out, handle, trackIndex, positionSamples.toString(), tickResolution.toInt(), bpm)
        val mod = wasmMod
        val errPtr = mod.getValue(out + 12, "i32").toInt()
        ClipAddResult(
            mod.getValue(out, "i32").toInt(),
            mod.getValue(out + 4, "i32").toInt(),
            mod.getValue(out + 8, "i8").toInt() != 0,
            if (errPtr != 0) mod.utf8ToString(errPtr) else null
        )
    }

    override fun addClipToTrack(
        trackIndex: Int, position: TimelinePosition, reader: AudioFileReader, filepath: String
    ): ClipAddResult = withWasmStruct(ClipAddResultSize) { out ->
        withWasmStruct(TimelinePositionSize) { pos ->
            wasmWritePosition(pos, position)
            withCStringKt(filepath) { fp ->
                wasmMod.uapmdAppAddClipToTrack(
                    out, handle, trackIndex, pos, (reader as WasmJsAudioFileReader).handle, fp
                )
            }
        }
        wasmReadClipAddResult(out)
    }

    override fun addMidiClipToTrack(trackIndex: Int, position: TimelinePosition, filepath: String): ClipAddResult =
        withWasmStruct(ClipAddResultSize) { out ->
            withWasmStruct(TimelinePositionSize) { pos ->
                wasmWritePosition(pos, position)
                withCStringKt(filepath) { fp ->
                    wasmMod.uapmdAppAddMidiClipToTrack(out, handle, trackIndex, pos, fp)
                }
            }
            wasmReadClipAddResult(out)
        }

    override fun addMidiClipFromData(
        trackIndex: Int, position: TimelinePosition,
        umpEvents: List<UInt>, tickTimestamps: List<ULong>,
        tickResolution: UInt, clipTempo: Double,
        tempoChanges: List<MidiTempoChange>, timeSignatureChanges: List<MidiTimeSignatureChange>,
        clipName: String, needsFileSave: Boolean
    ): ClipAddResult {
        val mod = wasmMod
        val owned = mutableListOf<Int>()
        fun alloc(size: Int) = mod.malloc(size).also { owned += it }
        try {
            val umpBuf = if (umpEvents.isEmpty()) 0 else alloc(umpEvents.size * 4).also { b ->
                umpEvents.forEachIndexed { i, v -> wasmSetI32(b + i * 4, v.toInt()) }
            }
            val tickBuf = if (tickTimestamps.isEmpty()) 0 else alloc(tickTimestamps.size * 8).also { b ->
                tickTimestamps.forEachIndexed { i, v -> wasmSetI64(b + i * 8, v.toLong()) }
            }
            // uapmd_midi_tempo_change_t: uint64 @0, double @8 (16 bytes)
            val tempoBuf = if (tempoChanges.isEmpty()) 0 else alloc(tempoChanges.size * MidiTempoChangeSize).also { b ->
                tempoChanges.forEachIndexed { i, t ->
                    wasmSetI64(b + i * MidiTempoChangeSize, t.tickPosition.toLong())
                    mod.setValue(b + i * MidiTempoChangeSize + 8, t.bpm, "double")
                }
            }
            // uapmd_midi_time_sig_change_t: uint64 @0, four uint8 @8..11 (16 bytes)
            val sigBuf = if (timeSignatureChanges.isEmpty()) 0 else alloc(timeSignatureChanges.size * MidiTimeSigChangeSize).also { b ->
                timeSignatureChanges.forEachIndexed { i, t ->
                    val o = b + i * MidiTimeSigChangeSize
                    wasmSetI64(o, t.tickPosition.toLong())
                    mod.setValue(o + 8, t.numerator.toInt().toDouble(), "i8")
                    mod.setValue(o + 9, t.denominator.toInt().toDouble(), "i8")
                    mod.setValue(o + 10, t.clocksPerClick.toInt().toDouble(), "i8")
                    mod.setValue(o + 11, t.thirtySecondsPerQuarter.toInt().toDouble(), "i8")
                }
            }
            return withWasmStruct(ClipAddResultSize) { out ->
                withWasmStruct(TimelinePositionSize) { pos ->
                    wasmWritePosition(pos, position)
                    withCStringKt(clipName) { name ->
                        mod.uapmdAppAddMidiClipFromData(
                            out, handle, trackIndex, pos,
                            umpBuf, umpEvents.size, tickBuf, tickTimestamps.size,
                            tickResolution.toInt(), clipTempo,
                            tempoBuf, tempoChanges.size, sigBuf, timeSignatureChanges.size,
                            name, needsFileSave
                        )
                    }
                }
                wasmReadClipAddResult(out)
            }
        } finally { owned.forEach { mod.free(it) } }
    }

    override fun addDeviceInputToTrack(trackIndex: Int, channelIndices: List<UInt>): Int {
        if (channelIndices.isEmpty())
            return wasmMod.uapmdAppAddDeviceInputToTrack(handle, trackIndex, 0, 0)
        return withWasmStruct(channelIndices.size * 4) { buf ->
            channelIndices.forEachIndexed { i, v -> wasmSetI32(buf + i * 4, v.toInt()) }
            wasmMod.uapmdAppAddDeviceInputToTrack(handle, trackIndex, buf, channelIndices.size)
        }
    }

    // ── Master track markers ────────────────────────────────────────────────

    override val masterMarkers: List<ClipMarkerData>
        get() {
            val n = wasmMod.uapmdAppMasterMarkerCount(handle)
            if (n == 0) return emptyList()
            return withWasmStruct(ClipMarkerSize) { out ->
                (0 until n).mapNotNull { i ->
                    if (!wasmMod.uapmdAppGetMasterMarker(handle, i, out)) null else wasmReadClipMarker(out)
                }
            }
        }

    override fun setMasterTrackMarkersWithValidation(markers: List<ClipMarkerData>): OpResult {
        val mod = wasmMod
        val owned = mutableListOf<Int>()
        fun cstr(v: String): Int {
            val size = mod.lengthBytesUTF8(v) + 1
            val p = mod.malloc(size)
            mod.stringToUTF8(v, p, size)
            owned += p
            return p
        }
        val buf = if (markers.isEmpty()) 0 else mod.malloc(markers.size * ClipMarkerSize).also { owned += it }
        return try {
            markers.forEachIndexed { i, m ->
                val b = buf + i * ClipMarkerSize
                mod.setValue(b, cstr(m.markerId).toDouble(), "i32")
                mod.setValue(b + 8, m.clipPositionOffset, "double")
                mod.setValue(b + 16, m.referenceType.nativeValue.toDouble(), "i32")
                mod.setValue(b + 20, cstr(m.referenceClipId).toDouble(), "i32")
                mod.setValue(b + 24, cstr(m.referenceMarkerId).toDouble(), "i32")
                mod.setValue(b + 28, cstr(m.name).toDouble(), "i32")
            }
            withWasmStruct(8) { out ->
                mod.uapmdAppSetMasterTrackMarkersWithValidation(out, handle, buf, markers.size)
                readOpResult(out)
            }
        } finally { owned.forEach { mod.free(it) } }
    }

    // ── Offline render to file ──────────────────────────────────────────────

    override fun startRenderToFile(settings: RenderToFileSettings): Boolean {
        val mod = wasmMod
        return withWasmStruct(AppRenderSettingsSize) { p ->
            withCStringKt(settings.outputPath) { path ->
                mod.setValue(p, path.toDouble(), "i32")
                mod.setValue(p + 8, settings.startSeconds, "double")
                mod.setValue(p + 16, settings.endSeconds, "double")
                mod.setValue(p + 24, (if (settings.hasEndSeconds) 1 else 0).toDouble(), "i8")
                mod.setValue(p + 25, (if (settings.useContentFallback) 1 else 0).toDouble(), "i8")
                mod.setValue(p + 26, (if (settings.contentBoundsValid) 1 else 0).toDouble(), "i8")
                mod.setValue(p + 32, settings.contentStartSeconds, "double")
                mod.setValue(p + 40, settings.contentEndSeconds, "double")
                mod.setValue(p + 48, settings.tailSeconds, "double")
                mod.setValue(p + 56, (if (settings.enableSilenceStop) 1 else 0).toDouble(), "i8")
                mod.setValue(p + 64, settings.silenceDurationSeconds, "double")
                mod.setValue(p + 72, settings.silenceThresholdDb, "double")
                mod.uapmdAppStartRenderToFile(handle, p)
            }
        }
    }

    override fun cancelRenderToFile() = wasmMod.uapmdAppCancelRenderToFile(handle)

    override val renderToFileStatus: RenderToFileStatus
        get() = withWasmStruct(AppRenderStatusSize) { out ->
            val mod = wasmMod
            mod.uapmdAppGetRenderToFileStatus(out, handle)
            RenderToFileStatus(
                running = mod.getValue(out, "i8").toInt() != 0,
                completed = mod.getValue(out + 1, "i8").toInt() != 0,
                success = mod.getValue(out + 2, "i8").toInt() != 0,
                progress = mod.getValue(out + 8, "double"),
                renderedSeconds = mod.getValue(out + 16, "double"),
                message = wasmStrAt(out + 24),
                outputPath = wasmStrAt(out + 28)
            )
        }

    override fun clearCompletedRenderStatus() = wasmMod.uapmdAppClearCompletedRenderStatus(handle)

    override fun requestShowTrackGraph(trackIndex: Int) =
        wasmMod.uapmdAppRequestShowTrackGraph(handle, trackIndex)

    // ── Track graph ─────────────────────────────────────────────────────────
    //
    // uapmd_graph_endpoint_t:          int32 @0, int32 @4, uint32 @8 (12 bytes)
    // uapmd_graph_connection_t:        int64 @0, int32 @8, endpoint @12, @24 (36 -> 40 aligned)
    // uapmd_graph_connections_result_t bool @0, char* @4, uint32 @8, ptr @12 (16)
    // uapmd_op_result_t:               bool @0, char* @4 (8)

    override fun ensureTrackUsesEditorGraph(trackIndex: Int): Boolean =
        wasmMod.uapmdAppEnsureTrackUsesEditorGraph(handle, trackIndex)

    override fun revertTrackToSimpleGraph(trackIndex: Int): Boolean =
        wasmMod.uapmdAppRevertTrackToSimpleGraph(handle, trackIndex)

    override fun getTrackGraphConnections(trackIndex: Int): GraphConnectionsResult =
        withWasmStruct(16) { out ->
            wasmMod.uapmdAppGetTrackGraphConnections(out, handle, trackIndex)
            val mod = wasmMod
            val ok = mod.getValue(out, "i8").toInt() != 0
            val errPtr = mod.getValue(out + 4, "i32").toInt()
            val error = if (errPtr != 0) mod.utf8ToString(errPtr) else null
            val count = mod.getValue(out + 8, "i32").toInt()
            val ptr = mod.getValue(out + 12, "i32").toInt()
            if (!ok || ptr == 0 || count == 0) GraphConnectionsResult(ok, error, emptyList())
            else GraphConnectionsResult(ok, error, (0 until count).map { i ->
                val base = ptr + i * GraphConnectionSize
                GraphConnection(
                    id = wasmReadI64(mod, base).toLong(),
                    busType = GraphBusType.fromNative(mod.getValue(base + 8, "i32").toInt()),
                    source = readEndpoint(base + 12),
                    target = readEndpoint(base + 12 + GraphEndpointSize)
                )
            })
        }

    override fun getTrackGraphNodes(trackIndex: Int): GraphNodesResult =
        withWasmStruct(40) { out ->
            wasmMod.uapmdAppGetTrackGraphNodes(out, handle, trackIndex)
            val mod = wasmMod
            val ok = mod.getValue(out, "i8").toInt() != 0
            val errPtr = mod.getValue(out + 4, "i32").toInt()
            val error = if (errPtr != 0) mod.utf8ToString(errPtr) else null
            if (!ok) return@withWasmStruct GraphNodesResult.failure(error)
            val count = mod.getValue(out + 8, "i32").toInt()
            val ptr = mod.getValue(out + 12, "i32").toInt()
            val busCount = mod.getValue(out + 16, "i32").toInt()
            val busPtr = mod.getValue(out + 20, "i32").toInt()

            val buses = if (busPtr == 0 || busCount == 0) emptyList() else (0 until busCount).map { i ->
                val base = busPtr + i * GraphAudioBusSize
                GraphAudioBus(
                    name = wasmStrAt(base),
                    role = AudioBusRole.fromNative(mod.getValue(base + 4, "i32").toInt()),
                    enabled = mod.getValue(base + 8, "i8").toInt() != 0,
                    channelLayoutName = wasmStrAt(base + 12),
                    channelCount = mod.getValue(base + 16, "i32").toInt().toUInt()
                )
            }
            val nodes = if (ptr == 0 || count == 0) emptyList() else (0 until count).map { i ->
                val base = ptr + i * GraphNodeSize
                val from = mod.getValue(base + 36, "i32").toInt()
                val inCount = mod.getValue(base + 40, "i32").toInt()
                GraphNode(
                    nodeId = wasmStrAt(base),
                    nodeType = wasmStrAt(base + 4),
                    displayName = wasmStrAt(base + 8),
                    instanceId = mod.getValue(base + 12, "i32").toInt(),
                    bypassed = mod.getValue(base + 16, "i8").toInt() != 0,
                    latencyInSamples = mod.getValue(base + 20, "i32").toInt().toUInt(),
                    tailLengthInSeconds = mod.getValue(base + 24, "double"),
                    hasAudioBuses = mod.getValue(base + 32, "i8").toInt() != 0,
                    hasEventInputs = mod.getValue(base + 33, "i8").toInt() != 0,
                    hasEventOutputs = mod.getValue(base + 34, "i8").toInt() != 0,
                    audioInputBuses = buses.busRange(from, inCount),
                    audioOutputBuses = buses.busRange(
                        from + inCount, mod.getValue(base + 44, "i32").toInt()
                    ),
                    mainInputBusIndex = mod.getValue(base + 48, "i32").toInt(),
                    mainOutputBusIndex = mod.getValue(base + 52, "i32").toInt()
                )
            }
            GraphNodesResult(
                true, error, nodes,
                mod.getValue(out + 24, "i32").toInt().toUInt(),
                mod.getValue(out + 28, "i32").toInt().toUInt(),
                mod.getValue(out + 32, "i32").toInt().toUInt(),
                mod.getValue(out + 36, "i32").toInt().toUInt()
            )
        }

    override fun connectTrackGraph(trackIndex: Int, connection: GraphConnection): OpResult {
        val mod = wasmMod
        val c = mod.malloc(GraphConnectionSize)
        return try {
            wasmWriteI64(mod, c, connection.id.toString())
            mod.setValue(c + 8, connection.busType.nativeValue.toDouble(), "i32")
            withTwoCStringsKt(connection.source.nodeId, connection.target.nodeId) { src, tgt ->
                writeEndpoint(c + 12, connection.source, src)
                writeEndpoint(c + 12 + GraphEndpointSize, connection.target, tgt)
                withWasmStruct(8) { out ->
                    mod.uapmdAppConnectTrackGraph(out, handle, trackIndex, c)
                    readOpResult(out)
                }
            }
        } finally { mod.free(c) }
    }

    override fun disconnectTrackGraphConnection(trackIndex: Int, connectionId: Long): OpResult =
        withWasmStruct(8) { out ->
            wasmAppDisconnectTrackGraph(wasmMod, out, handle, trackIndex, connectionId.toString())
            readOpResult(out)
        }

    // ── Clip audio events ───────────────────────────────────────────────────

    override fun getClipAudioEvents(trackIndex: Int, clipId: Int): ClipAudioEventsResult =
        withWasmStruct(24) { out ->
            wasmMod.uapmdAppGetClipAudioEvents(out, handle, trackIndex, clipId)
            val mod = wasmMod
            val ok = mod.getValue(out, "i8").toInt() != 0
            val errPtr = mod.getValue(out + 4, "i32").toInt()
            val error = if (errPtr != 0) mod.utf8ToString(errPtr) else null
            if (!ok) ClipAudioEventsResult(false, error, emptyList(), emptyList())
            else {
                val mCount = mod.getValue(out + 8, "i32").toInt()
                val mPtr = mod.getValue(out + 12, "i32").toInt()
                val wCount = mod.getValue(out + 16, "i32").toInt()
                val wPtr = mod.getValue(out + 20, "i32").toInt()
                ClipAudioEventsResult(
                    true, error,
                    if (mPtr == 0) emptyList() else (0 until mCount).map { i ->
                        val b = mPtr + i * ClipMarkerSize
                        ClipMarkerData(
                            markerId = wasmStrAt(b),
                            clipPositionOffset = mod.getValue(b + 8, "double"),
                            referenceType = WarpReferenceType.fromNative(mod.getValue(b + 16, "i32").toInt()),
                            referenceClipId = wasmStrAt(b + 20),
                            referenceMarkerId = wasmStrAt(b + 24),
                            name = wasmStrAt(b + 28)
                        )
                    },
                    if (wPtr == 0) emptyList() else (0 until wCount).map { i ->
                        val b = wPtr + i * WarpPointSize
                        AudioWarpPointData(
                            clipPositionOffset = mod.getValue(b, "double"),
                            speedRatio = mod.getValue(b + 8, "double"),
                            referenceType = WarpReferenceType.fromNative(mod.getValue(b + 16, "i32").toInt()),
                            referenceClipId = wasmStrAt(b + 20),
                            referenceMarkerId = wasmStrAt(b + 24)
                        )
                    }
                )
            }
        }

    override fun setClipAudioEvents(
        trackIndex: Int, clipId: Int,
        markers: List<ClipMarkerData>, warps: List<AudioWarpPointData>
    ): OpResult {
        val mod = wasmMod
        val owned = mutableListOf<Int>()
        fun cstr(v: String): Int {
            val size = mod.lengthBytesUTF8(v) + 1
            val p = mod.malloc(size)
            mod.stringToUTF8(v, p, size)
            owned += p
            return p
        }
        val mBuf = if (markers.isEmpty()) 0 else mod.malloc(markers.size * ClipMarkerSize).also { owned += it }
        markers.forEachIndexed { i, m ->
            val b = mBuf + i * ClipMarkerSize
            mod.setValue(b, cstr(m.markerId).toDouble(), "i32")
            mod.setValue(b + 8, m.clipPositionOffset, "double")
            mod.setValue(b + 16, m.referenceType.nativeValue.toDouble(), "i32")
            mod.setValue(b + 20, cstr(m.referenceClipId).toDouble(), "i32")
            mod.setValue(b + 24, cstr(m.referenceMarkerId).toDouble(), "i32")
            mod.setValue(b + 28, cstr(m.name).toDouble(), "i32")
        }
        val wBuf = if (warps.isEmpty()) 0 else mod.malloc(warps.size * WarpPointSize).also { owned += it }
        warps.forEachIndexed { i, w ->
            val b = wBuf + i * WarpPointSize
            mod.setValue(b, w.clipPositionOffset, "double")
            mod.setValue(b + 8, w.speedRatio, "double")
            mod.setValue(b + 16, w.referenceType.nativeValue.toDouble(), "i32")
            mod.setValue(b + 20, cstr(w.referenceClipId).toDouble(), "i32")
            mod.setValue(b + 24, cstr(w.referenceMarkerId).toDouble(), "i32")
        }
        return try {
            withWasmStruct(8) { out ->
                mod.uapmdAppSetClipAudioEvents(out, handle, trackIndex, clipId, mBuf, markers.size, wBuf, warps.size)
                readOpResult(out)
            }
        } finally { owned.forEach { mod.free(it) } }
    }
}

private const val ClipAddResultSize = 16
private const val TimelinePositionSize = 16
private const val MidiTempoChangeSize = 16
private const val MidiTimeSigChangeSize = 16
private const val AppRenderSettingsSize = 80
private const val AppRenderStatusSize = 32

/** uapmd_timeline_position_t: int64 @0, double @8 - passed byval, i.e. by pointer. */
private fun wasmWritePosition(ptr: Int, pos: TimelinePosition) {
    wasmSetI64(ptr, pos.samples)
    wasmMod.setValue(ptr + 8, pos.legacyBeats, "double")
}

private fun wasmReadClipAddResult(ptr: Int): ClipAddResult {
    val mod = wasmMod
    return ClipAddResult(
        mod.getValue(ptr, "i32").toInt(),
        mod.getValue(ptr + 4, "i32").toInt(),
        mod.getValue(ptr + 8, "i8").toInt() != 0,
        mod.getValue(ptr + 12, "i32").toInt().let { if (it != 0) mod.utf8ToString(it) else null }
    )
}

private fun wasmReadClipMarker(b: Int) = ClipMarkerData(
    markerId = wasmStrAt(b),
    clipPositionOffset = wasmMod.getValue(b + 8, "double"),
    referenceType = WarpReferenceType.fromNative(wasmMod.getValue(b + 16, "i32").toInt()),
    referenceClipId = wasmStrAt(b + 20),
    referenceMarkerId = wasmStrAt(b + 24),
    name = wasmStrAt(b + 28)
)

private const val MidiPortInfoSize = 8
private const val TimelineContentBoundsSize = 32
private const val DeviceEntrySize = 20
private const val PluginStateResultSize = 16

/**
 * Both port getters answer the count for a null `out` and mutate nothing, so
 * the count-then-fill call pair is safe here.
 */
private fun wasmMidiPorts(call: (out: Int, count: Int) -> Int): List<MidiPortInfo> {
    val n = call(0, 0)
    if (n == 0) return emptyList()
    return withWasmStruct(n * MidiPortInfoSize) { out ->
        val filled = call(out, n)
        (0 until filled).map { i ->
            val p = out + i * MidiPortInfoSize
            MidiPortInfo(wasmStrAt(p), wasmStrAt(p + 4))
        }
    }
}

private fun readDeviceEntry(p: Int): DeviceEntry {
    val mod = wasmMod
    return DeviceEntry(
        id = mod.getValue(p, "i32").toInt(),
        label = wasmStrAt(p + 4),
        apiName = wasmStrAt(p + 8),
        statusMessage = wasmStrAt(p + 12),
        running = mod.getValue(p + 16, "i8").toInt() != 0,
        instantiating = mod.getValue(p + 17, "i8").toInt() != 0,
        hasError = mod.getValue(p + 18, "i8").toInt() != 0
    )
}

internal fun readPluginStateResult(p: Int) = PluginStateResult(
    instanceId = wasmMod.getValue(p, "i32").toInt(),
    success = wasmMod.getValue(p + 4, "i8").toInt() != 0,
    error = wasmStrAt(p + 8),
    filepath = wasmStrAt(p + 12)
)

private fun pluginStatePtr(callback: (PluginStateResult) -> Unit): Int {
    val cbId = nextCallbackId()
    pendingPluginStates[cbId] = callback
    return makeCFunctionPtr(cbId, "uapmdDispatchPluginState", "vii")
}

private fun wasmStrAt(ptr: Int): String {
    val p = wasmMod.getValue(ptr, "i32").toInt()
    return if (p != 0) wasmMod.utf8ToString(p) else ""
}

private const val GraphConnectionSize = 48
private const val GraphEndpointSize = 16
private const val GraphNodeSize = 56
private const val GraphAudioBusSize = 20
// Verified with emcc -fdump-record-layouts-complete:
// uapmd_graph_endpoint_t  type@0 node_id@4 instance_id@8 bus_index@12,               size 16
// uapmd_graph_connection_t id@0 busType@8 source@12 target@28,                       size 48
// uapmd_graph_audio_bus_t name@0 role@4 enabled@8 layoutName@12 channels@16,      size 20
// uapmd_graph_node_t      nodeId@0 type@4 name@8 instance@12 bypassed@16 latency@20
//                         tail@24 hasBuses@32 hasEvIn@33 hasEvOut@34 busOffset@36
//                         aIn@40 aOut@44 mainIn@48 mainOut@52,                     size 56
// uapmd_graph_nodes_result_t ok@0 err@4 count@8 nodes@12 busCount@16 buses@20
//                         gaIn@24 gaOut@28 geIn@32 geOut@36,                       size 40
// uapmd_clip_marker_t     id@0 offset@8 refType@16 refClip@20 refMarker@24 name@28, size 32
// uapmd_audio_warp_point_t offset@0 speed@8 refType@16 refClip@20 refMarker@24,      size 32
// uapmd_clip_audio_events_result_t ok@0 err@4 mCount@8 markers@12 wCount@16 warps@20, size 24
private const val ClipMarkerSize = 32
private const val WarpPointSize = 32

private fun readEndpoint(ptr: Int): GraphEndpoint {
    val mod = wasmMod
    return GraphEndpoint(
        GraphEndpointType.fromNative(mod.getValue(ptr, "i32").toInt()),
        wasmStrAt(ptr + 4),
        mod.getValue(ptr + 8, "i32").toInt(),
        mod.getValue(ptr + 12, "i32").toInt().toUInt()
    )
}

/** [nodeIdPtr] must stay alive for as long as the struct is passed to native code. */
private fun writeEndpoint(ptr: Int, e: GraphEndpoint, nodeIdPtr: Int) {
    val mod = wasmMod
    mod.setValue(ptr, e.type.nativeValue.toDouble(), "i32")
    mod.setValue(ptr + 4, nodeIdPtr.toDouble(), "i32")
    mod.setValue(ptr + 8, e.instanceId.toDouble(), "i32")
    mod.setValue(ptr + 12, e.busIndex.toInt().toDouble(), "i32")
}

private fun readOpResult(ptr: Int): OpResult {
    val mod = wasmMod
    val ok = mod.getValue(ptr, "i8").toInt() != 0
    val errPtr = mod.getValue(ptr + 4, "i32").toInt()
    return OpResult(ok, if (errPtr != 0) mod.utf8ToString(errPtr) else null)
}

private fun decodeProjectResult(ptr: Int): AppProjectResult {
    val mod = wasmMod
    val ok = mod.getValue(ptr, "i8").toInt() != 0
    val errPtr = mod.getValue(ptr + 4, "i32").toInt()
    return AppProjectResult(ok, if (errPtr != 0) mod.utf8ToString(errPtr) else null)
}

private fun appProjectSavePtr(callback: (AppProjectResult) -> Unit): Int {
    val cbId = nextCallbackId()
    pendingProjectSaves[cbId] = callback
    return makeCFunctionPtr(cbId, "uapmdDispatchProjectSave", "vii")
}

private fun appInstanceCreatedPtr(callback: (PluginInstanceResult) -> Unit): Int {
    val cbId = nextCallbackId()
    pendingInstanceCreations[cbId] = callback
    return makeCFunctionPtr(cbId, "uapmdDispatchInstanceCreated", "vii")
}

private fun appTrackMutationPtr(callback: (Int, String?) -> Unit): Int {
    val cbId = nextCallbackId()
    pendingTrackMutations[cbId] = callback
    return makeCFunctionPtr(cbId, "uapmdDispatchTrackMutation", "viii")
}

private fun appErrorOnlyPtr(callback: (String?) -> Unit): Int {
    val cbId = nextCallbackId()
    pendingErrorOnlyCallbacks[cbId] = callback
    return makeCFunctionPtr(cbId, "uapmdDispatchErrorOnly", "vii")
}

class WasmJsTransportController internal constructor(internal val handle: Int) : TransportController {
    override val isPlaying: Boolean get() = wasmMod.uapmdTransportIsPlaying(handle)
    override val isPaused: Boolean get() = wasmMod.uapmdTransportIsPaused(handle)
    override val isRecording: Boolean get() = wasmMod.uapmdTransportIsRecording(handle)

    override var volume: Float
        get() = wasmMod.uapmdTransportGetVolume(handle)
        set(value) { wasmMod.uapmdTransportSetVolume(handle, value) }

    override fun play() = wasmMod.uapmdTransportPlay(handle)
    override fun stop() = wasmMod.uapmdTransportStop(handle)
    override fun pause() = wasmMod.uapmdTransportPause(handle)
    override fun resume() = wasmMod.uapmdTransportResume(handle)
    override fun record() = wasmMod.uapmdTransportRecord(handle)

    override fun jump(positionSeconds: Double) = wasmMod.uapmdTransportJump(handle, positionSeconds)
}

actual fun instantiateAppModel() = wasmMod.uapmdAppInstantiate()

actual fun getAppModel(): AppModel {
    val h = wasmMod.uapmdAppInstance()
    if (h == 0) error("uapmd_app_instance returned null; call instantiateAppModel() first")
    return WasmJsAppModel(h)
}

actual fun cleanupAppModel() = wasmMod.uapmdAppCleanup()

actual fun registerVirtualMidiDevicesAddin() = wasmMod.uapmdAppRegisterVirtualMidiDevicesAddin()

private var showVirtualMidiDevicesFnPtr = 0

class WasmJsDocumentProvider internal constructor(internal val handle: Int) : DocumentProvider {
    override fun tick() = wasmMod.uapmdDocumentProviderTick(handle)
}

private fun readClipTargets(base: Int, count: Int): List<TimelineClipTarget> =
    (0 until count).map {
        val entry = base + it * WasmOff.CLIP_TARGET_STRIDE
        TimelineClipTarget(
            wasmGetI32(entry + WasmOff.CLIP_TARGET_TRACK),
            wasmGetI32(entry + WasmOff.CLIP_TARGET_CLIP)
        )
    }

private fun writeClipTargets(base: Int, clips: List<TimelineClipTarget>) {
    clips.forEachIndexed { i, t ->
        val entry = base + i * WasmOff.CLIP_TARGET_STRIDE
        wasmSetI32(entry + WasmOff.CLIP_TARGET_TRACK, t.trackIndex)
        wasmSetI32(entry + WasmOff.CLIP_TARGET_CLIP, t.clipId)
    }
}

private fun readPianoRollNote(p: Int) = PianoRollNote(
    startSeconds = wasmGetF64(p + WasmOff.PR_NOTE_START),
    durationSeconds = wasmGetF64(p + WasmOff.PR_NOTE_DURATION),
    velocity = wasmGetF32(p + WasmOff.PR_NOTE_VELOCITY),
    note = wasmGetU8(p + WasmOff.PR_NOTE_NOTE),
    channel = wasmGetU8(p + WasmOff.PR_NOTE_CHANNEL),
    deleted = wasmGetBool(p + WasmOff.PR_NOTE_DELETED),
    editId = wasmGetI64(p + WasmOff.PR_NOTE_EDIT_ID),
    umpGroup = wasmGetU8(p + WasmOff.PR_NOTE_UMP_GROUP),
    releaseVelocity = wasmGetU16(p + WasmOff.PR_NOTE_RELEASE_VELOCITY),
    attributeType = wasmGetU8(p + WasmOff.PR_NOTE_ATTRIBUTE_TYPE),
    attributeValue = wasmGetU16(p + WasmOff.PR_NOTE_ATTRIBUTE_VALUE),
    automationEventCount = wasmGetI32(p + WasmOff.PR_NOTE_AUTOMATION_COUNT)
)

class WasmJsPianoRollSnapshot internal constructor(internal val handle: Int) : PianoRollSnapshot {
    override val isReady: Boolean get() = wasmMod.uapmdPianoRollSnapshotReady(handle)
    override val error: String
        get() = wasmMod.uapmdPianoRollSnapshotError(handle)
            .let { if (it != 0) wasmMod.utf8ToString(it) else "" }
    override val durationSeconds: Double get() = wasmMod.uapmdPianoRollSnapshotDurationSeconds(handle)
    override val minNote: Int get() = wasmMod.uapmdPianoRollSnapshotMinNote(handle)
    override val maxNote: Int get() = wasmMod.uapmdPianoRollSnapshotMaxNote(handle)

    override val notes: List<PianoRollNote>
        get() = withWasmStruct(WasmOff.PR_NOTE_SIZE) { out ->
            (0 until wasmMod.uapmdPianoRollSnapshotNoteCount(handle)).mapNotNull { i ->
                if (!wasmMod.uapmdPianoRollSnapshotGetNote(handle, i, out)) null else readPianoRollNote(out)
            }
        }

    override fun close() = wasmMod.uapmdPianoRollSnapshotDestroy(handle)
}

class WasmJsPianoRollSession internal constructor(private val handle: Int) : PianoRollSession {
    override val notes: List<PianoRollNote>
        get() = withWasmStruct(WasmOff.PR_NOTE_SIZE) { out ->
            (0 until wasmMod.uapmdPianoRollSessionNoteCount(handle)).mapNotNull { i ->
                if (!wasmMod.uapmdPianoRollSessionGetNote(handle, i, out)) null else readPianoRollNote(out)
            }
        }

    override fun isNoteSelected(index: Int) = wasmMod.uapmdPianoRollSessionIsNoteSelected(handle, index)
    override val selectedNoteCount: Int get() = wasmMod.uapmdPianoRollSessionSelectedNoteCount(handle)

    override var focusedNote: Int
        get() = wasmMod.uapmdPianoRollSessionFocusedNote(handle)
        set(value) { wasmMod.uapmdPianoRollSessionSetFocusedNote(handle, value) }

    override val durationSeconds: Double get() = wasmMod.uapmdPianoRollSessionDurationSeconds(handle)
    override val minNote: Int get() = wasmMod.uapmdPianoRollSessionMinNote(handle)
    override val maxNote: Int get() = wasmMod.uapmdPianoRollSessionMaxNote(handle)
    override val clipboardCount: Int get() = wasmMod.uapmdPianoRollSessionClipboardCount(handle)
    override val isDirty: Boolean get() = wasmMod.uapmdPianoRollSessionDirty(handle)
    override val error: String
        get() = wasmMod.uapmdPianoRollSessionError(handle)
            .let { if (it != 0) wasmMod.utf8ToString(it) else "" }

    override fun matchesSource(snapshot: PianoRollSnapshot) =
        wasmMod.uapmdPianoRollSessionMatchesSource(handle, (snapshot as WasmJsPianoRollSnapshot).handle)

    override fun loadNotes(snapshot: PianoRollSnapshot?) =
        wasmMod.uapmdPianoRollSessionLoadNotes(handle, (snapshot as WasmJsPianoRollSnapshot?)?.handle ?: 0)

    override fun selectNote(index: Int, additive: Boolean, toggle: Boolean) =
        wasmMod.uapmdPianoRollSessionSelectNote(handle, index, additive, toggle)

    override fun performAction(action: PianoRollAction, pasteSeconds: Double) =
        wasmMod.uapmdPianoRollSessionPerformAction(handle, action.nativeValue, pasteSeconds)

    override fun createNote(startSeconds: Double, durationSeconds: Double, note: Int, velocity: Float) =
        wasmMod.uapmdPianoRollSessionCreateNote(handle, startSeconds, durationSeconds, note, velocity)

    override fun deleteNote(index: Int) = wasmMod.uapmdPianoRollSessionDeleteNote(handle, index)

    override fun resizeNote(index: Int, startSeconds: Double, durationSeconds: Double, note: Int) =
        wasmMod.uapmdPianoRollSessionResizeNote(handle, index, startSeconds, durationSeconds, note)

    override fun beginDrag() = wasmMod.uapmdPianoRollSessionBeginDrag(handle)
    override fun moveSelection(timeDeltaSeconds: Double, pitchDelta: Int) =
        wasmMod.uapmdPianoRollSessionMoveSelection(handle, timeDeltaSeconds, pitchDelta)
    override fun cancelDrag() = wasmMod.uapmdPianoRollSessionCancelDrag(handle)
    override fun finishDrag(index: Int, originalStart: Double, originalEnd: Double, originalNote: Int) =
        wasmMod.uapmdPianoRollSessionFinishDrag(handle, index, originalStart, originalEnd, originalNote)

    override fun commit(app: AppModel) =
        wasmMod.uapmdPianoRollSessionCommit(handle, (app as WasmJsAppModel).handle)
}
