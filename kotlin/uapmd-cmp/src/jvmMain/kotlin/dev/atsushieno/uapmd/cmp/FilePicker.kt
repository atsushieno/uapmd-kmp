package dev.atsushieno.uapmd.cmp

import java.awt.FileDialog
import java.awt.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** AWT FileDialog gives a native chooser on macOS and Windows without extra deps. */
private suspend fun pick(mode: Int, name: String = "*.uapmd"): String? = withContext(Dispatchers.Main) {
    val dialog = FileDialog(null as Frame?, "uapmd project", mode)
    dialog.file = name
    dialog.isVisible = true
    val dir = dialog.directory
    val file = dialog.file
    if (dir == null || file == null) null else dir + file
}

actual suspend fun pickProjectFileToOpen(): String? = pick(FileDialog.LOAD)

private suspend fun pickMedia(title: String, extensions: List<String>): String? =
    withContext(Dispatchers.Main) {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        // Honoured on Windows and most Linux desktops; the macOS peer ignores it,
        // which is why the title says what is wanted too.
        dialog.setFilenameFilter { _, name -> extensions.any { name.lowercase().endsWith(it) } }
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        if (dir == null || file == null) null else dir + file
    }

actual suspend fun pickMidiFileToOpen(): String? =
    pickMedia("SMF Files (.mid, .midi, .smf)", listOf(".mid", ".midi", ".smf", ".midi2"))

actual suspend fun pickAudioFileToOpen(): String? =
    pickMedia("Audio Files (.wav, .flac, .ogg)", listOf(".wav", ".flac", ".ogg", ".mp3", ".aiff"))
actual suspend fun pickProjectFileToSave(defaultName: String): String? =
    pick(FileDialog.SAVE, defaultName)

/** The dialog already chose a real destination. */
actual fun deliverSavedFile(path: String) = Unit

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

/** A real filesystem: the picker chose the destination, so write straight to it. */
actual suspend fun saveProjectToPlatform(host: UapmdHost, defaultName: String): String? {
    val path = pickProjectFileToSave(defaultName) ?: return null
    host.saveProject(path)
    return host.lastProjectResult?.takeIf { !it.success }?.error
}
