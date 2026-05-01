package dev.atsushieno.uapmd

actual fun createPluginHost(): PluginHost =
    JvmPluginHost(lib.uapmd_plugin_host_create() ?: error("uapmd_plugin_host_create failed"))

actual fun createPluginGraph(eventBufferSizeInBytes: Long): PluginGraph =
    JvmPluginGraph(lib.uapmd_graph_create(eventBufferSizeInBytes) ?: error("uapmd_graph_create failed"))

actual fun createSequencerEngine(sampleRate: Int, audioBufferSize: UInt, umpBufferSize: UInt): SequencerEngine =
    JvmSequencerEngine(
        lib.uapmd_engine_create(sampleRate, audioBufferSize.toInt(), umpBufferSize.toInt())
            ?: error("uapmd_engine_create failed")
    )

actual fun createRealtimeSequencer(
    bufferSize: UInt,
    umpBufferSize: UInt,
    sampleRate: Int,
    dispatcher: DeviceIODispatcher
): RealtimeSequencer =
    JvmRealtimeSequencer(
        lib.uapmd_rt_sequencer_create(
            bufferSize.toInt(), umpBufferSize.toInt(), sampleRate,
            (dispatcher as JvmDeviceIODispatcher).handle
        ) ?: error("uapmd_rt_sequencer_create failed")
    )

actual fun getDefaultDeviceIODispatcher(): DeviceIODispatcher =
    JvmDeviceIODispatcher(lib.uapmd_default_device_io_dispatcher() ?: error("uapmd_default_device_io_dispatcher failed"))

actual fun getAudioDeviceManager(driverName: String): AudioDeviceManager =
    JvmAudioDeviceManager(lib.uapmd_audio_device_mgr_instance(driverName.ifEmpty { null }) ?: error("uapmd_audio_device_mgr_instance failed"))

actual fun getMidiIODevice(driverName: String): MidiIODevice =
    JvmMidiIODevice(lib.uapmd_midi_device_instance(driverName.ifEmpty { null }) ?: error("uapmd_midi_device_instance failed"))

actual fun createAudioFileReader(filepath: String): AudioFileReader =
    JvmAudioFileReader(lib.uapmd_audio_file_reader_create(filepath) ?: error("uapmd_audio_file_reader_create failed for: $filepath"))

actual fun createScanTool(): ScanTool =
    JvmScanTool(lib.uapmd_scan_tool_create() ?: error("uapmd_scan_tool_create failed"))

actual fun createFormatManager(): FormatManager =
    JvmFormatManager(lib.uapmd_format_manager_create() ?: error("uapmd_format_manager_create failed"))

actual fun createPluginInstancing(scanTool: ScanTool, format: String, pluginId: String): PluginInstancing =
    JvmPluginInstancing(
        lib.uapmd_instancing_create(
            (scanTool as JvmScanTool).handle, format, pluginId
        ) ?: error("uapmd_instancing_create failed for $format / $pluginId")
    )
