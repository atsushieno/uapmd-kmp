package dev.atsushieno.uapmd

// Wasm/JS binding (browser) — not yet implemented.
// Future plan: uapmd-c-api compiled to Wasm via Emscripten, bound via K/Wasm interop.

actual fun createPluginHost(): PluginHost = TODO("WasmJs uapmd binding not yet implemented")
actual fun createPluginGraph(eventBufferSizeInBytes: Long): PluginGraph = TODO("WasmJs uapmd binding not yet implemented")
actual fun createSequencerEngine(sampleRate: Int, audioBufferSize: UInt, umpBufferSize: UInt): SequencerEngine = TODO("WasmJs uapmd binding not yet implemented")
actual fun createRealtimeSequencer(bufferSize: UInt, umpBufferSize: UInt, sampleRate: Int, dispatcher: DeviceIODispatcher): RealtimeSequencer = TODO("WasmJs uapmd binding not yet implemented")
actual fun getDefaultDeviceIODispatcher(): DeviceIODispatcher = TODO("WasmJs uapmd binding not yet implemented")
actual fun getAudioDeviceManager(driverName: String): AudioDeviceManager = TODO("WasmJs uapmd binding not yet implemented")
actual fun getMidiIODevice(driverName: String): MidiIODevice = TODO("WasmJs uapmd binding not yet implemented")
actual fun createAudioFileReader(filepath: String): AudioFileReader = TODO("WasmJs uapmd binding not yet implemented")
actual fun createScanTool(): ScanTool = TODO("WasmJs uapmd binding not yet implemented")
actual fun createFormatManager(): FormatManager = TODO("WasmJs uapmd binding not yet implemented")
actual fun createPluginInstancing(scanTool: ScanTool, format: String, pluginId: String): PluginInstancing = TODO("WasmJs uapmd binding not yet implemented")
