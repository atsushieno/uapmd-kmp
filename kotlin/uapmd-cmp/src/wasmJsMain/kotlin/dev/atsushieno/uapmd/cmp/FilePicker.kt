package dev.atsushieno.uapmd.cmp

/**
 * Emscripten's in-memory filesystem. Stems written here never reach the user's
 * disk, but the import only needs them long enough to become clips.
 */
actual fun defaultStemOutputDirectory(audioFilePath: String): String = "/tmp"

actual fun startupImportPath(): String? = null

actual fun startupInstantiateFormat(): String? = null

actual fun startupAddTracks(): Int = 0

actual fun startupInstantiateCount(): Int = 1

actual fun startupLoadProjectPath(): String? = null

@androidx.compose.runtime.Composable
actual fun PlatformQuitBackHandler() {}

actual fun startupSaveProjectPath(): String? = null

actual fun dumpThreadStacks(): String = "(single threaded)"

actual fun startupForceRescan(): Boolean = false
actual fun startupLoadCount(): Int = 1

actual fun startupShowLoadedUi(): String? = null

actual fun startupPreloadPlugin(): String? = null

actual fun startupShowPreloadUi(): Boolean = false

actual fun startupPlaySeconds(): Int = 0

actual fun startupSuppressPolling(): Boolean = false

actual fun startupRenderPath(): String? = null

actual fun startupBufferSize(): Int = 0
