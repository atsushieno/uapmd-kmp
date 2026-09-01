package dev.atsushieno.uapmd.cmp

// No picker on this target yet. Android needs the Storage Access Framework
// (composeApp's DocumentPicker is the reference), iOS UIDocumentPicker, and web
// the File System Access API plus uapmd's browser document provider.
actual suspend fun pickProjectFileToOpen(): String? = null
actual suspend fun pickProjectFileToSave(defaultName: String): String? = null

actual fun deliverSavedFile(path: String) = Unit
actual suspend fun pickMidiFileToOpen(): String? = null
actual suspend fun pickAudioFileToOpen(): String? = null

actual fun startupImportPath(): String? = null

actual fun startupInstantiateFormat(): String? = null

actual fun startupAddTracks(): Int = 0

actual fun startupInstantiateCount(): Int = 1

actual fun tickPlatformFilePicker() {}

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

/** A real filesystem: the picker chose the destination, so write straight to it. */
actual suspend fun saveProjectToPlatform(host: UapmdHost, defaultName: String): String? {
    val path = pickProjectFileToSave(defaultName) ?: return null
    host.saveProject(path)
    return host.lastProjectResult?.takeIf { !it.success }?.error
}
