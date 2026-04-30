package dev.atsushieno.uapmd

// JVM binding (desktop JVM via Compose Desktop) — not yet implemented.
// Future plan: JNA wrapping the pre-built uapmd-c-api shared library.

actual fun createPluginHost(): PluginHost = TODO("JVM uapmd binding not yet implemented")
actual fun createPluginGraph(eventBufferSizeInBytes: Long): PluginGraph = TODO("JVM uapmd binding not yet implemented")
actual fun createSequencerEngine(sampleRate: Int, audioBufferSize: UInt, umpBufferSize: UInt): SequencerEngine = TODO("JVM uapmd binding not yet implemented")
actual fun createRealtimeSequencer(bufferSize: UInt, umpBufferSize: UInt, sampleRate: Int, dispatcher: DeviceIODispatcher): RealtimeSequencer = TODO("JVM uapmd binding not yet implemented")
actual fun getDefaultDeviceIODispatcher(): DeviceIODispatcher = TODO("JVM uapmd binding not yet implemented")
actual fun getAudioDeviceManager(driverName: String): AudioDeviceManager = TODO("JVM uapmd binding not yet implemented")
actual fun getMidiIODevice(driverName: String): MidiIODevice = TODO("JVM uapmd binding not yet implemented")
actual fun createAudioFileReader(filepath: String): AudioFileReader = TODO("JVM uapmd binding not yet implemented")
actual fun createScanTool(): ScanTool = TODO("JVM uapmd binding not yet implemented")
actual fun createFormatManager(): FormatManager = TODO("JVM uapmd binding not yet implemented")
actual fun createPluginInstancing(scanTool: ScanTool, format: String, pluginId: String): PluginInstancing = TODO("JVM uapmd binding not yet implemented")
