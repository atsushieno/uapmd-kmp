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
