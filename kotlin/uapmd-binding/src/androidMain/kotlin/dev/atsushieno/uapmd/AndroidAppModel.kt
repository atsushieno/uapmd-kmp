package dev.atsushieno.uapmd

class AndroidAppModel internal constructor(internal val handle: Long) : AppModel {

    override val sequencer: RealtimeSequencer
        // Borrowed: AppModel owns the sequencer, so this wrapper must not destroy it.
        get() = AndroidRealtimeSequencer(JniBridge.uapmdAppSequencer(handle), owned = false)

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
