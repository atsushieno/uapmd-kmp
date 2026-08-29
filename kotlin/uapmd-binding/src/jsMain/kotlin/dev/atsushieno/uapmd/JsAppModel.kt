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
        withJsCString(entryId) { p -> jsMod._uapmd_app_unblock_plugin(handle, p) as Boolean }

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

    // ── MIDI clip UMP events ────────────────────────────────────────────────
    // uapmd_ump_events_result_t: bool @0, char* @4, uint32 @8, ptr @12 (16 bytes)
    // uapmd_ump_event_t:         uint64 @0, uint32 @8, ptr @12 (16 bytes)

    override fun getMidiClipUmpEvents(trackIndex: Int, clipId: Int): UmpEventsResult =
        withWasmMem(16) { out ->
            jsMod._uapmd_app_get_midi_clip_ump_events(out, handle, trackIndex, clipId)
            val ok = (jsMod.getValue(out, "i8") as Int) != 0
            val errPtr = jsMod.getValue(out + 4, "i32") as Int
            val error = if (errPtr != 0) jsMod.UTF8ToString(errPtr) as String else null
            val count = jsMod.getValue(out + 8, "i32") as Int
            val eventsPtr = jsMod.getValue(out + 12, "i32") as Int
            if (!ok || eventsPtr == 0 || count == 0) UmpEventsResult(ok, error, emptyList())
            else UmpEventsResult(ok, error, (0 until count).map { i ->
                val base = eventsPtr + i * 16
                val lo = (jsMod.getValue(base, "i32") as Int).toLong() and 0xFFFFFFFFL
                val hi = (jsMod.getValue(base + 4, "i32") as Int).toLong()
                val wordCount = jsMod.getValue(base + 8, "i32") as Int
                val wordsPtr = jsMod.getValue(base + 12, "i32") as Int
                UmpEvent(hi * 4294967296L + lo, UIntArray(wordCount) { w ->
                    (jsMod.getValue(wordsPtr + w * 4, "i32") as Int).toUInt()
                })
            })
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
