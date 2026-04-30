package dev.atsushieno.uapmd

// JS binding (browser) — not yet implemented.
// Future plan: Wasm-compiled uapmd-c-api exposed via @JsName / JS interop.

actual fun createPluginHost(): PluginHost = TODO("JS uapmd binding not yet implemented")
actual fun createPluginGraph(eventBufferSizeInBytes: Long): PluginGraph = TODO("JS uapmd binding not yet implemented")
actual fun createSequencerEngine(sampleRate: Int, audioBufferSize: UInt, umpBufferSize: UInt): SequencerEngine = TODO("JS uapmd binding not yet implemented")
actual fun createRealtimeSequencer(bufferSize: UInt, umpBufferSize: UInt, sampleRate: Int, dispatcher: DeviceIODispatcher): RealtimeSequencer = TODO("JS uapmd binding not yet implemented")
actual fun getDefaultDeviceIODispatcher(): DeviceIODispatcher = TODO("JS uapmd binding not yet implemented")
actual fun getAudioDeviceManager(driverName: String): AudioDeviceManager = TODO("JS uapmd binding not yet implemented")
actual fun getMidiIODevice(driverName: String): MidiIODevice = TODO("JS uapmd binding not yet implemented")
actual fun createAudioFileReader(filepath: String): AudioFileReader = TODO("JS uapmd binding not yet implemented")
actual fun createScanTool(): ScanTool = TODO("JS uapmd binding not yet implemented")
actual fun createFormatManager(): FormatManager = TODO("JS uapmd binding not yet implemented")
actual fun createPluginInstancing(scanTool: ScanTool, format: String, pluginId: String): PluginInstancing = TODO("JS uapmd binding not yet implemented")
