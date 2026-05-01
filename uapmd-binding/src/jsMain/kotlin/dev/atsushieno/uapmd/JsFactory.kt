package dev.atsushieno.uapmd

actual fun createPluginHost(): PluginHost =
    JsPluginHost(jsMod._uapmd_plugin_host_create() as Int)

actual fun createPluginGraph(eventBufferSizeInBytes: Long): PluginGraph =
    JsPluginGraph(jsMod._uapmd_graph_create() as Int)

actual fun createSequencerEngine(sampleRate: Int, audioBufferSize: UInt, umpBufferSize: UInt): SequencerEngine =
    JsSequencerEngine(jsMod._uapmd_engine_create(sampleRate, audioBufferSize.toInt(), umpBufferSize.toInt()) as Int)

actual fun createRealtimeSequencer(
    bufferSize: UInt,
    umpBufferSize: UInt,
    sampleRate: Int,
    dispatcher: DeviceIODispatcher
): RealtimeSequencer =
    JsRealtimeSequencer(
        jsMod._uapmd_rt_sequencer_create(
            bufferSize.toInt(), umpBufferSize.toInt(), sampleRate,
            (dispatcher as JsDeviceIODispatcher).handle
        ) as Int
    )

actual fun getDefaultDeviceIODispatcher(): DeviceIODispatcher =
    JsDeviceIODispatcher(jsMod._uapmd_default_device_io_dispatcher() as Int)

actual fun getAudioDeviceManager(driverName: String): AudioDeviceManager =
    JsAudioDeviceManager(
        withJsCString(driverName.ifEmpty { null }) { ptr -> jsMod._uapmd_audio_device_mgr_instance(ptr) as Int }
    )

actual fun getMidiIODevice(driverName: String): MidiIODevice =
    JsMidiIODevice(
        withJsCString(driverName.ifEmpty { null }) { ptr -> jsMod._uapmd_midi_device_instance(ptr) as Int }
    )

actual fun createAudioFileReader(filepath: String): AudioFileReader =
    withJsCString(filepath) { ptr -> JsAudioFileReader(jsMod._uapmd_audio_file_reader_create(ptr) as Int) }

actual fun createScanTool(): ScanTool =
    JsScanTool(jsMod._uapmd_scan_tool_create() as Int)

actual fun createFormatManager(): FormatManager =
    JsFormatManager(jsMod._uapmd_format_manager_create() as Int)

actual fun createPluginInstancing(scanTool: ScanTool, format: String, pluginId: String): PluginInstancing =
    withJsTwoCStrings(format, pluginId) { fmtPtr, idPtr ->
        JsPluginInstancing(
            jsMod._uapmd_instancing_create(
                (scanTool as JsScanTool).handle, fmtPtr, idPtr
            ) as Int
        )
    }
