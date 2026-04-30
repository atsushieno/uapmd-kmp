package dev.atsushieno.uapmd

// Android binding — not yet implemented.
// Future plan: JNI wrapping the NDK-built uapmd-c-api static library.

actual fun createPluginHost(): PluginHost = TODO("Android uapmd binding not yet implemented")
actual fun createPluginGraph(eventBufferSizeInBytes: Long): PluginGraph = TODO("Android uapmd binding not yet implemented")
actual fun createSequencerEngine(sampleRate: Int, audioBufferSize: UInt, umpBufferSize: UInt): SequencerEngine = TODO("Android uapmd binding not yet implemented")
actual fun createRealtimeSequencer(bufferSize: UInt, umpBufferSize: UInt, sampleRate: Int, dispatcher: DeviceIODispatcher): RealtimeSequencer = TODO("Android uapmd binding not yet implemented")
actual fun getDefaultDeviceIODispatcher(): DeviceIODispatcher = TODO("Android uapmd binding not yet implemented")
actual fun getAudioDeviceManager(driverName: String): AudioDeviceManager = TODO("Android uapmd binding not yet implemented")
actual fun getMidiIODevice(driverName: String): MidiIODevice = TODO("Android uapmd binding not yet implemented")
actual fun createAudioFileReader(filepath: String): AudioFileReader = TODO("Android uapmd binding not yet implemented")
actual fun createScanTool(): ScanTool = TODO("Android uapmd binding not yet implemented")
actual fun createFormatManager(): FormatManager = TODO("Android uapmd binding not yet implemented")
actual fun createPluginInstancing(scanTool: ScanTool, format: String, pluginId: String): PluginInstancing = TODO("Android uapmd binding not yet implemented")
