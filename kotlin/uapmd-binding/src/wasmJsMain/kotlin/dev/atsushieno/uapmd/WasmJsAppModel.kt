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
