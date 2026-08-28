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

    override fun generateScanReport(): String =
        readString(handle) { h, buf, size -> uapmdAppGenerateScanReport(h, buf, size) }

    override fun clearPluginBlocklist() = wasmMod.uapmdAppClearPluginBlocklist(handle)

    // ── Tracks ──────────────────────────────────────────────────────────────

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

    // ── MIDI clip UMP events ────────────────────────────────────────────────
    //
    // uapmd_ump_events_result_t: bool @0, char* @4, uint32 @8, ptr @12 (size 16)
    // uapmd_ump_event_t:         uint64 @0, uint32 @8, ptr @12 (size 16)

    override fun getMidiClipUmpEvents(trackIndex: Int, clipId: Int): UmpEventsResult =
        withWasmStruct(16) { out ->
            wasmMod.uapmdAppGetMidiClipUmpEvents(out, handle, trackIndex, clipId)
            val mod = wasmMod
            val ok = mod.getValue(out, "i8").toInt() != 0
            val errPtr = mod.getValue(out + 4, "i32").toInt()
            val error = if (errPtr != 0) mod.utf8ToString(errPtr) else null
            val count = mod.getValue(out + 8, "i32").toInt()
            val eventsPtr = mod.getValue(out + 12, "i32").toInt()
            if (!ok || eventsPtr == 0 || count == 0) UmpEventsResult(ok, error, emptyList())
            else UmpEventsResult(ok, error, (0 until count).map { i ->
                val base = eventsPtr + i * 16
                val lo = mod.getValue(base, "i32").toInt().toLong() and 0xFFFFFFFFL
                val hi = mod.getValue(base + 4, "i32").toInt().toLong()
                val wordCount = mod.getValue(base + 8, "i32").toInt()
                val wordsPtr = mod.getValue(base + 12, "i32").toInt()
                UmpEvent(hi * 4294967296L + lo, UIntArray(wordCount) { w ->
                    mod.getValue(wordsPtr + w * 4, "i32").toInt().toUInt()
                })
            })
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
        // The wasm bridge marshals C callbacks through its own dispatcher table;
        // this one is not registered there yet, so report the failure rather
        // than silently doing nothing.
        callback(false, "Multi-track SMF import is not wired up on this platform yet.", 0)
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
                    target = readEndpoint(base + 24)
                )
            })
        }

    override fun connectTrackGraph(trackIndex: Int, connection: GraphConnection): OpResult {
        val mod = wasmMod
        val c = mod.malloc(GraphConnectionSize)
        return try {
            wasmWriteI64(mod, c, connection.id.toString())
            mod.setValue(c + 8, connection.busType.nativeValue.toDouble(), "i32")
            writeEndpoint(c + 12, connection.source)
            writeEndpoint(c + 24, connection.target)
            withWasmStruct(8) { out ->
                mod.uapmdAppConnectTrackGraph(out, handle, trackIndex, c)
                readOpResult(out)
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

private fun wasmStrAt(ptr: Int): String {
    val p = wasmMod.getValue(ptr, "i32").toInt()
    return if (p != 0) wasmMod.utf8ToString(p) else ""
}

private const val GraphConnectionSize = 40
// Verified with emcc -fdump-record-layouts-complete:
// uapmd_clip_marker_t     id@0 offset@8 refType@16 refClip@20 refMarker@24 name@28, size 32
// uapmd_audio_warp_point_t offset@0 speed@8 refType@16 refClip@20 refMarker@24,      size 32
// uapmd_clip_audio_events_result_t ok@0 err@4 mCount@8 markers@12 wCount@16 warps@20, size 24
private const val ClipMarkerSize = 32
private const val WarpPointSize = 32

private fun readEndpoint(ptr: Int): GraphEndpoint {
    val mod = wasmMod
    return GraphEndpoint(
        GraphEndpointType.fromNative(mod.getValue(ptr, "i32").toInt()),
        mod.getValue(ptr + 4, "i32").toInt(),
        mod.getValue(ptr + 8, "i32").toInt().toUInt()
    )
}

private fun writeEndpoint(ptr: Int, e: GraphEndpoint) {
    val mod = wasmMod
    mod.setValue(ptr, e.type.nativeValue.toDouble(), "i32")
    mod.setValue(ptr + 4, e.instanceId.toDouble(), "i32")
    mod.setValue(ptr + 8, e.busIndex.toInt().toDouble(), "i32")
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
}

actual fun instantiateAppModel() = wasmMod.uapmdAppInstantiate()

actual fun getAppModel(): AppModel {
    val h = wasmMod.uapmdAppInstance()
    if (h == 0) error("uapmd_app_instance returned null; call instantiateAppModel() first")
    return WasmJsAppModel(h)
}

actual fun cleanupAppModel() = wasmMod.uapmdAppCleanup()
