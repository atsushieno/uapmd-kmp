package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.getAppModel
import dev.atsushieno.uapmd.createSaveScratchPath
import dev.atsushieno.uapmd.downloadFileFromMemfs
import dev.atsushieno.uapmd.pickDocumentToOpen
import dev.atsushieno.uapmd.saveProjectAsDocument
import dev.atsushieno.uapmd.tickDocumentProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/*
 * Web file picking goes through uapmd's own document provider, the same route
 * Android takes. The Emscripten implementation opens a hidden `<input type=file>`,
 * copies the chosen file into MEMFS and resolves the handle to a path the engine can
 * open directly (`DocumentProviderEmscripten.cpp:93`), so nothing here has to deal
 * with browser file objects.
 *
 * Saving is a different shape on the web - the provider defers it to a download
 * rather than handing back a writable path - so it is left unimplemented rather
 * than wired to something that would silently not save.
 */
private suspend fun pick(label: String, extensions: List<String>): String? =
    suspendCancellableCoroutine { cont ->
        pickDocumentToOpen(getAppModel(), label, extensions) { path -> cont.resume(path) }
    }

actual suspend fun pickProjectFileToOpen(): String? =
    pick("uapmd project", listOf(".uapmd", ".uapmdz"))

/*
 * Saving on the web is a two-step: the engine writes into the in-memory filesystem
 * exactly as it would to disk, and `deliverSavedFile` then offers those bytes as a
 * download. uapmd's own provider models saving the same way — its `pickSaveDocument`
 * hands back a deferred handle and refuses to resolve one to a path — so the scratch
 * path is ours to make, and the browser's download UI is where the user names it.
 */
actual suspend fun pickProjectFileToSave(defaultName: String): String? =
    runCatching { createSaveScratchPath(defaultName) }.getOrNull()

actual fun deliverSavedFile(path: String) {
    runCatching { downloadFileFromMemfs(path) }
}

actual suspend fun saveProjectToPlatform(host: UapmdHost, defaultName: String): String? =
    suspendCancellableCoroutine { cont ->
        // The archive name, not the document name: what leaves the browser is the
        // packed project, so `.uapmdz` is what the user should be offered.
        val name = if (defaultName.endsWith(".uapmdz")) defaultName
        else defaultName.substringBeforeLast('.') + ".uapmdz"
        saveProjectAsDocument(getAppModel(), name) { error -> cont.resume(error) }
    }

actual suspend fun pickMidiFileToOpen(): String? =
    pick("SMF Files", listOf(".mid", ".midi", ".smf", ".midi2"))

actual suspend fun pickAudioFileToOpen(): String? =
    pick("Audio Files", listOf(".wav", ".flac", ".ogg", ".mp3", ".aiff"))

actual fun startupImportPath(): String? = null

actual fun startupInstantiateFormat(): String? = null

actual fun startupAddTracks(): Int = 0

actual fun startupInstantiateCount(): Int = 1

// The provider completes pending picks from its tick, which the UI poll drives.
actual fun tickPlatformFilePicker() = tickDocumentProvider()

actual fun startupLoadProjectPath(): String? = null

@androidx.compose.runtime.Composable
actual fun PlatformQuitBackHandler() {}

actual fun startupSaveProjectPath(): String? = null

actual fun dumpThreadStacks(): String = "(single threaded)"

actual fun startupForceRescan(): Boolean = false
actual fun startupLoadCount(): Int = 1

actual fun startupPlaySeconds(): Int = 0

actual fun startupSuppressPolling(): Boolean = false

actual fun startupRenderPath(): String? = null

actual fun startupBufferSize(): Int = 0
