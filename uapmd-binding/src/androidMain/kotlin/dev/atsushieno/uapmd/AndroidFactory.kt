package dev.atsushieno.uapmd

actual fun createPluginHost(): PluginHost =
    AndroidPluginHost(JniBridge.uapmdPluginHostCreate())

actual fun createPluginGraph(eventBufferSizeInBytes: Long): PluginGraph =
    AndroidPluginGraph(JniBridge.uapmdGraphCreate(eventBufferSizeInBytes))

actual fun createSequencerEngine(sampleRate: Int, audioBufferSize: UInt, umpBufferSize: UInt): SequencerEngine =
    AndroidSequencerEngine(JniBridge.uapmdEngineCreate(sampleRate, audioBufferSize.toInt(), umpBufferSize.toInt()))

actual fun createRealtimeSequencer(
    bufferSize: UInt,
    umpBufferSize: UInt,
    sampleRate: Int,
    dispatcher: DeviceIODispatcher
): RealtimeSequencer =
    AndroidRealtimeSequencer(
        JniBridge.uapmdRtSequencerCreate(
            bufferSize.toInt(), umpBufferSize.toInt(), sampleRate,
            (dispatcher as AndroidDeviceIODispatcher).handle
        )
    )

actual fun getDefaultDeviceIODispatcher(): DeviceIODispatcher =
    AndroidDeviceIODispatcher(JniBridge.uapmdDefaultDeviceIoDispatcher())

actual fun getAudioDeviceManager(driverName: String): AudioDeviceManager =
    AndroidAudioDeviceManager(JniBridge.uapmdAudioDeviceMgrInstance(driverName.ifEmpty { null }))

actual fun getMidiIODevice(driverName: String): MidiIODevice =
    AndroidMidiIODevice(JniBridge.uapmdMidiDeviceInstance(driverName.ifEmpty { null }))

actual fun createAudioFileReader(filepath: String): AudioFileReader =
    AndroidAudioFileReader(JniBridge.uapmdAudioFileReaderCreate(filepath))

actual fun createScanTool(): ScanTool =
    AndroidScanTool(JniBridge.uapmdScanToolCreate())

actual fun createFormatManager(): FormatManager =
    AndroidFormatManager(JniBridge.uapmdFormatManagerCreate())

actual fun createPluginInstancing(scanTool: ScanTool, format: String, pluginId: String): PluginInstancing =
    AndroidPluginInstancing(
        JniBridge.uapmdInstancingCreate((scanTool as AndroidScanTool).handle, format, pluginId)
    )
