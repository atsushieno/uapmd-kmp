package dev.atsushieno.uapmd.cmp

import java.awt.FileDialog
import java.awt.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** AWT FileDialog gives a native chooser on macOS and Windows without extra deps. */
private suspend fun pick(mode: Int): String? = withContext(Dispatchers.Main) {
    val dialog = FileDialog(null as Frame?, "uapmd project", mode)
    dialog.file = "*.uapmd"
    dialog.isVisible = true
    val dir = dialog.directory
    val file = dialog.file
    if (dir == null || file == null) null else dir + file
}

actual suspend fun pickProjectFileToOpen(): String? = pick(FileDialog.LOAD)

actual suspend fun pickMediaFileToOpen(): String? = withContext(Dispatchers.Main) {
    val dialog = FileDialog(null as Frame?, "MIDI or audio file", FileDialog.LOAD)
    dialog.isVisible = true
    val dir = dialog.directory
    val file = dialog.file
    if (dir == null || file == null) null else dir + file
}
actual suspend fun pickProjectFileToSave(): String? = pick(FileDialog.SAVE)

actual fun startupImportPath(): String? = System.getProperty("uapmd.cmp.importMidi")

actual fun startupInstantiateFormat(): String? = System.getProperty("uapmd.cmp.instantiate")

actual fun startupAddTracks(): Int = System.getProperty("uapmd.cmp.addTracks")?.toIntOrNull() ?: 0

actual fun startupInstantiateCount(): Int =
    System.getProperty("uapmd.cmp.instantiateCount")?.toIntOrNull() ?: 1

actual fun tickPlatformFilePicker() {}

actual fun startupLoadProjectPath(): String? = System.getProperty("uapmd.cmp.loadProject")

@androidx.compose.runtime.Composable
actual fun PlatformQuitBackHandler() {}

actual fun startupSaveProjectPath(): String? = System.getProperty("uapmd.cmp.saveProject")

actual fun dumpThreadStacks(): String = buildString {
    Thread.getAllStackTraces()
        .entries
        .sortedBy { it.key.name }
        .forEach { (thread, frames) ->
            if (frames.isEmpty()) return@forEach
            appendLine("--- ${thread.name} (${thread.state})")
            frames.take(24).forEach { appendLine("      at $it") }
        }
}

actual fun startupForceRescan(): Boolean = System.getProperty("uapmd.cmp.forceRescan") == "1"
actual fun startupLoadCount(): Int = System.getProperty("uapmd.cmp.loadCount")?.toIntOrNull() ?: 1

actual fun startupPlaySeconds(): Int = System.getProperty("uapmd.cmp.playSeconds")?.toIntOrNull() ?: 0

actual fun startupSuppressPolling(): Boolean = System.getProperty("uapmd.cmp.noPoll") == "1"

actual fun startupRenderPath(): String? = System.getProperty("uapmd.cmp.renderTo")

actual fun startupBufferSize(): Int = System.getProperty("uapmd.cmp.bufferSize")?.toIntOrNull() ?: 0
