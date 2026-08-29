package dev.atsushieno.uapmd

expect fun createPluginHost(): PluginHost
expect fun createPluginGraph(eventBufferSizeInBytes: Long): PluginGraph
expect fun createSequencerEngine(sampleRate: Int, audioBufferSize: UInt, umpBufferSize: UInt): SequencerEngine
expect fun createRealtimeSequencer(
    bufferSize: UInt,
    umpBufferSize: UInt,
    sampleRate: Int,
    dispatcher: DeviceIODispatcher
): RealtimeSequencer
expect fun getDefaultDeviceIODispatcher(): DeviceIODispatcher
expect fun getAudioDeviceManager(driverName: String = ""): AudioDeviceManager
expect fun getMidiIODevice(driverName: String = ""): MidiIODevice
expect fun createAudioFileReader(filepath: String): AudioFileReader

/** A reader that yields silence, for clips with no source file. */
expect fun createSilentAudioFileReader(
    numFrames: Long,
    numChannels: Int,
    sampleRate: Int
): AudioFileReader
expect fun createScanTool(): ScanTool
expect fun createFormatManager(): FormatManager
expect fun createPluginInstancing(scanTool: ScanTool, format: String, pluginId: String): PluginInstancing
expect fun createAddinManager(): AddinManager

/**
 * Creates the process-wide [AppModel]. Install an event loop first (see the
 * note on [AppModel]). Calling it twice replaces the previous instance.
 */
expect fun instantiateAppModel()
expect fun getAppModel(): AppModel
expect fun cleanupAppModel()

/** Unpacks a `.uapmdz` archive (or passes a `.uapmd` through) before loading. */
expect fun prepareProjectLoad(filePath: String): PreparedProject

/**
 * Points remote plug-in scanning at a standalone scanner executable.
 *
 * Remote scanning relaunches the host's own executable with `--scan-only
 * --ipc-client …`. On a JVM that executable is `java`, which connects to nothing, so
 * out-of-process scanning fails outright unless it is told what to launch. uapmd's
 * `uapmd-scan` understands the same arguments.
 *
 * Pass null or an empty string to restore the default.
 */
expect fun setRemoteScannerExecutable(path: String?)
