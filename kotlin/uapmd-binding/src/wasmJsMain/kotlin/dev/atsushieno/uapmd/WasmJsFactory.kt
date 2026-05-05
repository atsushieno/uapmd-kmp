package dev.atsushieno.uapmd

actual fun createPluginHost(): PluginHost =
    WasmJsPluginHost(wasmMod.uapmdPluginHostCreate())

actual fun createPluginGraph(eventBufferSizeInBytes: Long): PluginGraph =
    WasmJsPluginGraph(wasmMod.uapmdGraphCreate())

actual fun createSequencerEngine(sampleRate: Int, audioBufferSize: UInt, umpBufferSize: UInt): SequencerEngine =
    WasmJsSequencerEngine(wasmMod.uapmdEngineCreate(sampleRate, audioBufferSize.toInt(), umpBufferSize.toInt()))

actual fun createRealtimeSequencer(
    bufferSize: UInt,
    umpBufferSize: UInt,
    sampleRate: Int,
    dispatcher: DeviceIODispatcher
): RealtimeSequencer =
    WasmJsRealtimeSequencer(
        wasmMod.uapmdRtSequencerCreate(
            bufferSize.toInt(), umpBufferSize.toInt(), sampleRate,
            (dispatcher as WasmJsDeviceIODispatcher).handle
        )
    )

actual fun getDefaultDeviceIODispatcher(): DeviceIODispatcher =
    WasmJsDeviceIODispatcher(wasmMod.uapmdDefaultDeviceIoDispatcher())

actual fun getAudioDeviceManager(driverName: String): AudioDeviceManager =
    WasmJsAudioDeviceManager(
        withCStringKt(driverName.ifEmpty { null }) { ptr -> wasmMod.uapmdAudioDeviceMgrInstance(ptr) }
    )

actual fun getMidiIODevice(driverName: String): MidiIODevice =
    WasmJsMidiIODevice(
        withCStringKt(driverName.ifEmpty { null }) { ptr -> wasmMod.uapmdMidiDeviceInstance(ptr) }
    )

actual fun createAudioFileReader(filepath: String): AudioFileReader =
    withCStringKt(filepath) { ptr -> WasmJsAudioFileReader(wasmMod.uapmdAudioFileReaderCreate(ptr)) }

actual fun createScanTool(): ScanTool =
    WasmJsScanTool(wasmMod.uapmdScanToolCreate())

actual fun createFormatManager(): FormatManager =
    WasmJsFormatManager(wasmMod.uapmdFormatManagerCreate())

actual fun createPluginInstancing(scanTool: ScanTool, format: String, pluginId: String): PluginInstancing =
    withTwoCStringsKt(format, pluginId) { fmtPtr, idPtr ->
        WasmJsPluginInstancing(
            wasmMod.uapmdInstancingCreate(
                (scanTool as WasmJsScanTool).handle, fmtPtr, idPtr
            )
        )
    }
