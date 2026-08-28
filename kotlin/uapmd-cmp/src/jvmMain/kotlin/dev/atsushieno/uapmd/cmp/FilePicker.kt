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
