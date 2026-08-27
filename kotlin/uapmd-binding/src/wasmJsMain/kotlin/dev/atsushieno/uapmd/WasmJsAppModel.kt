package dev.atsushieno.uapmd

class WasmJsAppModel internal constructor(internal val handle: Int) : AppModel {

    override val sequencer: RealtimeSequencer
        // Borrowed: AppModel owns the sequencer, so this wrapper must not destroy it.
        get() = WasmJsRealtimeSequencer(wasmMod.uapmdAppSequencer(handle), owned = false)

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
