package dev.atsushieno.uapmd

class AndroidAppModel internal constructor(internal val handle: Long) : AppModel {

    override val sequencer: RealtimeSequencer
        // Borrowed: AppModel owns this; callers must not close it (see AppModel.sequencer).
        get() = AndroidRealtimeSequencer(JniBridge.uapmdAppSequencer(handle))

    override val transport: TransportController
        get() = AndroidTransportController(JniBridge.uapmdAppTransport(handle))

    override val sampleRate: Int get() = JniBridge.uapmdAppSampleRate(handle)
    override val trackCount: UInt get() = JniBridge.uapmdAppTrackCount(handle).toUInt()

    override val isScanning: Boolean get() = JniBridge.uapmdAppIsScanning(handle)

    override val isAudioEngineEnabled: Boolean get() = JniBridge.uapmdAppIsAudioEngineEnabled(handle)
    override fun setAudioEngineEnabled(enabled: Boolean) = JniBridge.uapmdAppSetAudioEngineEnabled(handle, enabled)
    override fun toggleAudioEngine() = JniBridge.uapmdAppToggleAudioEngine(handle)

    override var autoBufferSizeEnabled: Boolean
        get() = JniBridge.uapmdAppAutoBufferSizeEnabled(handle)
        set(value) { JniBridge.uapmdAppSetAutoBufferSizeEnabled(handle, value) }

    override fun updateAudioDeviceSettings(sampleRate: Int, bufferSize: UInt) =
        JniBridge.uapmdAppUpdateAudioDeviceSettings(handle, sampleRate, bufferSize.toInt())

    override fun notifyUiReady() = JniBridge.uapmdAppNotifyUiReady(handle)
    override fun notifyPersistentStorageReady() = JniBridge.uapmdAppNotifyPersistentStorageReady(handle)

    // ── Plugin scanning ─────────────────────────────────────────────────────

    override fun performPluginScanning(
        forceRescan: Boolean, mode: ScanMode, remoteTimeoutSeconds: Double, requireFastScanning: Boolean
    ) = JniBridge.uapmdAppPerformPluginScanning(
        handle, forceRescan, if (mode == ScanMode.Remote) 1 else 0, remoteTimeoutSeconds, requireFastScanning
    )

    override fun cancelPluginScanning() = JniBridge.uapmdAppCancelPluginScanning(handle)
    override fun generateScanReport(): String = JniBridge.uapmdAppGenerateScanReport(handle)
    override fun clearPluginBlocklist() = JniBridge.uapmdAppClearPluginBlocklist(handle)

    // ── Tracks ──────────────────────────────────────────────────────────────

    override fun addTrack(callback: (Int, String?) -> Unit) =
        JniBridge.uapmdAppAddTrack(handle, callback)

    override fun removeTrack(trackIndex: Int, callback: (Int, String?) -> Unit) =
        JniBridge.uapmdAppRemoveTrack(handle, trackIndex, callback)

    override fun removeAllTracks(callback: (String?) -> Unit) =
        JniBridge.uapmdAppRemoveAllTracks(handle, callback)

    override val timelineTrackCount: UInt get() = JniBridge.uapmdAppTimelineTrackCount(handle).toUInt()

    override fun getTimelineTrack(index: UInt): TimelineTrack =
        AndroidTimelineTrack(JniBridge.uapmdAppGetTimelineTrack(handle, index.toInt()))

    override val masterTimelineTrack: TimelineTrack
        get() = AndroidTimelineTrack(JniBridge.uapmdAppMasterTimelineTrack(handle))

    override fun getTimelineState(): TimelineState? {
        val d = JniBridge.uapmdAppGetTimelineState(handle) ?: return null
        return TimelineState(
            playheadPosition = TimelinePosition(d[0].toLong(), d[1]),
            isPlaying = d[2] != 0.0,
            loopEnabled = d[3] != 0.0,
            loopStart = TimelinePosition(d[4].toLong(), d[5]),
            loopEnd = TimelinePosition(d[6].toLong(), d[7]),
            tempo = d[8],
            timeSignatureNumerator = d[9].toInt(),
            timeSignatureDenominator = d[10].toInt(),
            sampleRate = d[11].toInt()
        )
    }

    // ── History ─────────────────────────────────────────────────────────────

    override val historyState: UndoState
        get() = JniBridge.uapmdAppGetHistoryState(handle)?.toUndoState()
            ?: error("uapmdAppGetHistoryState returned null")

    override fun undo(callback: ((String?) -> Unit)?) = JniBridge.uapmdAppUndo(handle, callback)
    override fun redo(callback: ((String?) -> Unit)?) = JniBridge.uapmdAppRedo(handle, callback)

    // ── Plugin instances ────────────────────────────────────────────────────

    override fun createPluginInstance(
        format: String, pluginId: String, trackIndex: Int,
        config: PluginInstanceConfig, callback: (PluginInstanceResult) -> Unit
    ) = JniBridge.uapmdAppCreatePluginInstance(
        handle, format, pluginId, trackIndex,
        config.apiName, config.deviceName, config.manufacturer, config.version, config.stateFile
    ) { instanceId: Int, pluginName: String?, error: String? ->
        callback(PluginInstanceResult(instanceId, pluginName ?: "", error))
    }

    override fun removePluginInstance(instanceId: Int) = JniBridge.uapmdAppRemovePluginInstance(handle, instanceId)

    override fun getInstanceGroup(instanceId: Int): UByte =
        JniBridge.uapmdAppGetInstanceGroup(handle, instanceId).toUByte()

    override fun setInstanceGroup(instanceId: Int, group: UByte): Boolean =
        JniBridge.uapmdAppSetInstanceGroup(handle, instanceId, group.toInt())

    override fun enableUmpDevice(instanceId: Int, deviceName: String) =
        JniBridge.uapmdAppEnableUmpDevice(handle, instanceId, deviceName)

    override fun disableUmpDevice(instanceId: Int) = JniBridge.uapmdAppDisableUmpDevice(handle, instanceId)

    override fun requestShowInstanceDetails(instanceId: Int) =
        JniBridge.uapmdAppRequestShowInstanceDetails(handle, instanceId)

    override fun requestShowPluginUi(instanceId: Int) = JniBridge.uapmdAppRequestShowPluginUi(handle, instanceId)
    override fun hidePluginUi(instanceId: Int) = JniBridge.uapmdAppHidePluginUi(handle, instanceId)

    // ── Project I/O ─────────────────────────────────────────────────────────

    override fun loadProject(filePath: String): AppProjectResult =
        JniBridge.uapmdAppLoadProject(handle, filePath).toProjectResult()

    override fun saveProjectSync(filePath: String): AppProjectResult =
        JniBridge.uapmdAppSaveProjectSync(handle, filePath).toProjectResult()

    override fun saveProject(filePath: String, callback: (AppProjectResult) -> Unit) =
        JniBridge.uapmdAppSaveProject(handle, filePath) { success: Boolean, error: String? ->
            callback(AppProjectResult(success, error))
        }

    override fun loadProjectFromHandleToken(token: String): AppProjectResult =
        JniBridge.uapmdAppLoadProjectFromHandleToken(handle, token).toProjectResult()

    // ── MIDI clip UMP events ────────────────────────────────────────────────

    override fun getMidiClipUmpEvents(trackIndex: Int, clipId: Int): UmpEventsResult {
        val packed = JniBridge.uapmdAppGetMidiClipUmpEvents(handle, trackIndex, clipId)
            ?: return UmpEventsResult(false, "native call returned null", emptyList())
        val ok = (packed[0] as LongArray)[0] != 0L
        val error = packed.getOrNull(1) as? String
        val ticks = packed[2] as LongArray
        @Suppress("UNCHECKED_CAST")
        val words = packed[3] as Array<IntArray>
        return UmpEventsResult(ok, error, ticks.indices.map { i ->
            UmpEvent(ticks[i], UIntArray(words[i].size) { w -> words[i][w].toUInt() })
        })
    }

    override fun addUmpEventToClip(trackIndex: Int, clipId: Int, tick: Long, words: UIntArray): Boolean =
        JniBridge.uapmdAppAddUmpEventToClip(
            handle, trackIndex, clipId, tick, IntArray(words.size) { words[it].toInt() }
        )

    override fun removeUmpEventFromClip(trackIndex: Int, clipId: Int, eventIndex: Int): Boolean =
        JniBridge.uapmdAppRemoveUmpEventFromClip(handle, trackIndex, clipId, eventIndex)

    override fun removeClipFromTrack(trackIndex: Int, clipId: Int): Boolean =
        JniBridge.uapmdAppRemoveClipFromTrack(handle, trackIndex, clipId)

    override fun createEmptyMidiClip(
        trackIndex: Int, positionSamples: Long, tickResolution: UInt, bpm: Double
    ): ClipAddResult {
        val packed = JniBridge.uapmdAppCreateEmptyMidiClip(handle, trackIndex, positionSamples, tickResolution.toInt(), bpm)
            ?: return ClipAddResult(-1, -1, false, "native call returned null")
        val nums = packed[0] as LongArray
        return ClipAddResult(nums[0].toInt(), nums[1].toInt(), nums[2] != 0L, packed.getOrNull(1) as? String)
    }

    // ── Track graph ─────────────────────────────────────────────────────────

    override fun ensureTrackUsesEditorGraph(trackIndex: Int): Boolean =
        JniBridge.uapmdAppEnsureTrackUsesEditorGraph(handle, trackIndex)

    override fun revertTrackToSimpleGraph(trackIndex: Int): Boolean =
        JniBridge.uapmdAppRevertTrackToSimpleGraph(handle, trackIndex)

    override fun getTrackGraphConnections(trackIndex: Int): GraphConnectionsResult {
        val packed = JniBridge.uapmdAppGetTrackGraphConnections(handle, trackIndex)
            ?: return GraphConnectionsResult(false, "native call returned null", emptyList())
        val ok = (packed[0] as LongArray)[0] != 0L
        val error = packed.getOrNull(1) as? String
        val ids = packed[2] as LongArray
        val flat = packed[3] as IntArray
        return GraphConnectionsResult(ok, error, ids.indices.map { i ->
            val b = i * 7
            GraphConnection(
                id = ids[i],
                busType = GraphBusType.fromNative(flat[b]),
                source = GraphEndpoint(GraphEndpointType.fromNative(flat[b + 1]), flat[b + 2], flat[b + 3].toUInt()),
                target = GraphEndpoint(GraphEndpointType.fromNative(flat[b + 4]), flat[b + 5], flat[b + 6].toUInt())
            )
        })
    }

    override fun connectTrackGraph(trackIndex: Int, connection: GraphConnection): OpResult =
        JniBridge.uapmdAppConnectTrackGraph(
            handle, trackIndex, connection.id, connection.busType.nativeValue,
            connection.source.type.nativeValue, connection.source.instanceId, connection.source.busIndex.toInt(),
            connection.target.type.nativeValue, connection.target.instanceId, connection.target.busIndex.toInt()
        ).toOpResult()

    override fun disconnectTrackGraphConnection(trackIndex: Int, connectionId: Long): OpResult =
        JniBridge.uapmdAppDisconnectTrackGraphConnection(handle, trackIndex, connectionId).toOpResult()

    // ── Clip audio events ───────────────────────────────────────────────────

    override fun getClipAudioEvents(trackIndex: Int, clipId: Int): ClipAudioEventsResult {
        val packed = JniBridge.uapmdAppGetClipAudioEvents(handle, trackIndex, clipId)
            ?: return ClipAudioEventsResult(false, "native call returned null", emptyList(), emptyList())
        val ok = (packed[0] as LongArray)[0] != 0L
        val error = packed.getOrNull(1) as? String
        @Suppress("UNCHECKED_CAST") val mStr = packed[2] as Array<String>
        val mNum = packed[3] as DoubleArray
        val mRef = packed[4] as IntArray
        val wNum = packed[5] as DoubleArray
        val wRef = packed[6] as IntArray
        @Suppress("UNCHECKED_CAST") val wStr = packed[7] as Array<String>
        return ClipAudioEventsResult(
            ok, error,
            mNum.indices.map { i ->
                ClipMarkerData(
                    markerId = mStr[i * 4],
                    clipPositionOffset = mNum[i],
                    referenceType = WarpReferenceType.fromNative(mRef[i]),
                    referenceClipId = mStr[i * 4 + 1],
                    referenceMarkerId = mStr[i * 4 + 2],
                    name = mStr[i * 4 + 3]
                )
            },
            wRef.indices.map { i ->
                AudioWarpPointData(
                    clipPositionOffset = wNum[i * 2],
                    speedRatio = wNum[i * 2 + 1],
                    referenceType = WarpReferenceType.fromNative(wRef[i]),
                    referenceClipId = wStr[i * 2],
                    referenceMarkerId = wStr[i * 2 + 1]
                )
            }
        )
    }

    override fun setClipAudioEvents(
        trackIndex: Int, clipId: Int,
        markers: List<ClipMarkerData>, warps: List<AudioWarpPointData>
    ): OpResult = JniBridge.uapmdAppSetClipAudioEvents(
        handle, trackIndex, clipId,
        Array(markers.size * 4) { i ->
            val m = markers[i / 4]
            when (i % 4) {
                0 -> m.markerId; 1 -> m.referenceClipId; 2 -> m.referenceMarkerId; else -> m.name
            }
        },
        DoubleArray(markers.size) { markers[it].clipPositionOffset },
        IntArray(markers.size) { markers[it].referenceType.nativeValue },
        DoubleArray(warps.size * 2) { i -> if (i % 2 == 0) warps[i / 2].clipPositionOffset else warps[i / 2].speedRatio },
        IntArray(warps.size) { warps[it].referenceType.nativeValue },
        Array(warps.size * 2) { i ->
            val w = warps[i / 2]
            if (i % 2 == 0) w.referenceClipId else w.referenceMarkerId
        }
    ).toOpResult()
}

private fun Array<Any>?.toOpResult(): OpResult {
    if (this == null) return OpResult(false, "native call returned null")
    return OpResult((this[0] as LongArray)[0] != 0L, this.getOrNull(1) as? String)
}

private fun Array<Any>?.toProjectResult(): AppProjectResult {
    if (this == null) return AppProjectResult(false, "native call returned null")
    val nums = this[0] as LongArray
    return AppProjectResult(nums[0] != 0L, this.getOrNull(1) as? String)
}

class AndroidTransportController internal constructor(internal val handle: Long) : TransportController {
    override val isPlaying: Boolean get() = JniBridge.uapmdTransportIsPlaying(handle)
    override val isPaused: Boolean get() = JniBridge.uapmdTransportIsPaused(handle)
    override val isRecording: Boolean get() = JniBridge.uapmdTransportIsRecording(handle)

    override var volume: Float
        get() = JniBridge.uapmdTransportGetVolume(handle)
        set(value) { JniBridge.uapmdTransportSetVolume(handle, value) }

    override fun play() = JniBridge.uapmdTransportPlay(handle)
    override fun stop() = JniBridge.uapmdTransportStop(handle)
    override fun pause() = JniBridge.uapmdTransportPause(handle)
    override fun resume() = JniBridge.uapmdTransportResume(handle)
    override fun record() = JniBridge.uapmdTransportRecord(handle)
}

actual fun instantiateAppModel() = JniBridge.uapmdAppInstantiate()

actual fun getAppModel(): AppModel {
    val h = JniBridge.uapmdAppInstance()
    if (h == 0L) error("uapmd_app_instance returned null; call instantiateAppModel() first")
    return AndroidAppModel(h)
}

actual fun cleanupAppModel() = JniBridge.uapmdAppCleanup()
