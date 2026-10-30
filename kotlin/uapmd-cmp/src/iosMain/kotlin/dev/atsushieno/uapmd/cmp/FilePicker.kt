package dev.atsushieno.uapmd.cmp

/** The sandbox's temp directory: a picked document's folder is not writable here. */
actual fun defaultStemOutputDirectory(audioFilePath: String): String =
    platform.Foundation.NSTemporaryDirectory()

actual fun startupImportPath(): String? = null

actual fun startupInstantiateFormat(): String? = null

actual fun startupAddTracks(): Int = 0

actual fun startupInstantiateCount(): Int = 1

actual fun startupLoadProjectPath(): String? = null

@androidx.compose.runtime.Composable
actual fun PlatformQuitBackHandler() {}

actual fun startupSaveProjectPath(): String? = null

actual fun dumpThreadStacks(): String = "(not available)"

actual fun startupForceRescan(): Boolean = false
actual fun startupLoadCount(): Int = 1

actual fun startupShowLoadedUi(): String? = null

actual fun startupPreloadPlugin(): String? = null

actual fun startupShowPreloadUi(): Boolean = false

actual fun startupPlaySeconds(): Int = 0

actual fun startupSuppressPolling(): Boolean = false

actual fun startupRenderPath(): String? = null

actual fun startupBufferSize(): Int = 0
