package dev.atsushieno.uapmd

import uapmd.*

actual fun createPluginHost(): PluginHost =
    NativePluginHost(uapmd_plugin_host_create() ?: error("uapmd_plugin_host_create failed"))

actual fun createPluginGraph(eventBufferSizeInBytes: Long): PluginGraph =
    NativePluginGraph(uapmd_graph_create(eventBufferSizeInBytes.toULong()) ?: error("uapmd_graph_create failed"))

actual fun createSequencerEngine(sampleRate: Int, audioBufferSize: UInt, umpBufferSize: UInt): SequencerEngine =
    NativeSequencerEngine(uapmd_engine_create(sampleRate, audioBufferSize, umpBufferSize) ?: error("uapmd_engine_create failed"))

actual fun createRealtimeSequencer(
    bufferSize: UInt,
    umpBufferSize: UInt,
    sampleRate: Int,
    dispatcher: DeviceIODispatcher
): RealtimeSequencer = NativeRealtimeSequencer(
    uapmd_rt_sequencer_create(
        bufferSize, umpBufferSize, sampleRate,
        (dispatcher as NativeDeviceIODispatcher).handle
    ) ?: error("uapmd_rt_sequencer_create failed")
)

actual fun getDefaultDeviceIODispatcher(): DeviceIODispatcher =
    NativeDeviceIODispatcher(uapmd_default_device_io_dispatcher() ?: error("uapmd_default_device_io_dispatcher failed"))

actual fun getAudioDeviceManager(driverName: String): AudioDeviceManager =
    NativeAudioDeviceManager(uapmd_audio_device_mgr_instance(driverName.ifEmpty { null }) ?: error("uapmd_audio_device_mgr_instance failed"))

actual fun getMidiIODevice(driverName: String): MidiIODevice =
    NativeMidiIODevice(uapmd_midi_device_instance(driverName.ifEmpty { null }) ?: error("uapmd_midi_device_instance failed"))

actual fun createAudioFileReader(filepath: String): AudioFileReader =
    NativeAudioFileReader(uapmd_audio_file_reader_create(filepath) ?: error("uapmd_audio_file_reader_create failed for: $filepath"))

actual fun createScanTool(): ScanTool =
    NativeScanTool(uapmd_scan_tool_create() ?: error("uapmd_scan_tool_create failed"))

actual fun createFormatManager(): FormatManager =
    NativeFormatManager(uapmd_format_manager_create() ?: error("uapmd_format_manager_create failed"))

actual fun createPluginInstancing(scanTool: ScanTool, format: String, pluginId: String): PluginInstancing =
    NativePluginInstancing(
        uapmd_instancing_create((scanTool as NativeScanTool).handle, format, pluginId)
            ?: error("uapmd_instancing_create failed for $format / $pluginId")
    )
