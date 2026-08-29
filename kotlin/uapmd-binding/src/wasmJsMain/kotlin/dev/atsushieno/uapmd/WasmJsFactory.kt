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

actual fun createSilentAudioFileReader(numFrames: Long, numChannels: Int, sampleRate: Int): AudioFileReader =
    WasmJsAudioFileReader(
        wasmAudioFileReaderCreateSilent(wasmMod, numFrames.toString(), numChannels, sampleRate)
    )

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

/** A plain `.uapmd` needs no unpacking; the engine opens the path as it stands. */
private class PassThroughPreparedProject(override val path: String) : PreparedProject {
    override val success = true
    override val error = ""
    override fun close() = Unit
}

private var nextExtractedProjectId = 1

/**
 * A `.uapmdz` unpacked into MEMFS.
 *
 * The browser does have a filesystem to unpack into — Emscripten's — and the engine
 * reads the extracted project from it exactly as it would on disk. Passing the
 * archive straight through instead, as this used to, hands the engine a ZIP where it
 * expects the project document, so every packed project failed to open.
 *
 * Each load gets its own directory: the extracted files stay alive for as long as the
 * project is loaded, because its clips reference them.
 */
private class ExtractedPreparedProject(archivePath: String) : PreparedProject {
    private val destDir = "/tmp/uapmd_project_${nextExtractedProjectId++}"
    override val path: String
    override val success: Boolean
    override val error: String

    init {
        val extracted = runCatching { extractProjectArchive(archivePath, destDir) }
        val projectFile = extracted.getOrNull()
        path = projectFile ?: ""
        success = projectFile != null
        error = when {
            projectFile != null -> ""
            extracted.isFailure -> "Could not unpack $archivePath: ${extracted.exceptionOrNull()?.message}"
            else -> "Could not unpack $archivePath."
        }
        if (!success) runCatching { removeExtractedArchive(destDir) }
    }

    override fun close() {
        runCatching { removeExtractedArchive(destDir) }
    }
}

actual fun prepareProjectLoad(filePath: String): PreparedProject =
    if (runCatching { isProjectArchive(filePath) }.getOrDefault(false))
        ExtractedPreparedProject(filePath)
    else
        PassThroughPreparedProject(filePath)

/** Remote scanning needs a launchable process; this platform has none. */
actual fun setRemoteScannerExecutable(path: String?) = Unit
