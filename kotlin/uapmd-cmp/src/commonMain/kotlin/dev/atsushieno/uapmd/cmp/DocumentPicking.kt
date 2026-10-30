package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.ClipType
import dev.atsushieno.uapmd.DocumentFilter
import dev.atsushieno.uapmd.DocumentHandle
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/*
 * Every document the app opens or saves is picked through AppModel's document
 * provider, as uapmd-app picks it. The provider knows how each platform hands a
 * document out - a path on desktop, a SAF document on Android, a MEMFS copy or a
 * download in a browser - so saves go back through the picked handle and reads
 * through the path it resolves to, and nothing here branches on the platform.
 */

private val allFiles = DocumentFilter("All Files", emptyList(), listOf("*"))

private fun filter(label: String, vararg extensions: String) =
    DocumentFilter(label, emptyList(), extensions.toList())

/** uapmd-app's filters, named after the call site each comes from. */
internal object DocumentFilters {
    /** `MainWindow::handleLoadProject` */
    val projectToOpen = listOf(
        filter("UAPMD Project Archive", "*.uapmdz"),
        filter("Legacy UAPMD Project", "*.uapmd"),
        allFiles
    )

    /** `MainWindow::handleSaveProject` */
    val projectToSave = listOf(filter("UAPMD Project Archive", "*.uapmdz"), allFiles)

    /** `MainWindow::savePluginState` / `loadPluginState` */
    val pluginState = listOf(filter("Plugin State Files", "*.state"), allFiles)

    /** `ExporterWindow` */
    val renderOutput = listOf(filter("WAV", "*.wav"), allFiles)

    /** `TimelineEditor::importMidiTracksWithPicker` */
    val midiTracks = listOf(filter("MIDI Files", "*.mid", "*.midi", "*.smf"), allFiles)

    /** `TimelineEditor::addSmfClipToTrack` */
    val midiClip = listOf(filter("SMF Files", "*.mid", "*.midi", "*.smf"), allFiles)

    /** `TimelineEditor::changeClipFile` */
    val audioClip = listOf(
        filter("Audio Files", "*.wav", "*.flac", "*.ogg"),
        filter("WAV Files", "*.wav"),
        filter("FLAC Files", "*.flac"),
        filter("OGG Files", "*.ogg"),
        allFiles
    )

    /** `AudioImportWindow::browseForAudioFile` */
    val splitAudioSource = listOf(filter("Audio Files", "*.wav", "*.flac", "*.ogg"), allFiles)

    /** `AudioImportWindow::browseForModelFile` */
    fun stemModel(label: String, extensions: List<String>) =
        listOf(DocumentFilter(label, emptyList(), extensions), allFiles)
}

/** One picked document, or null when the user cancels or there is no provider. */
suspend fun UapmdHost.pickDocumentToOpen(filters: List<DocumentFilter>): DocumentHandle? {
    val provider = documentProvider ?: return null
    return suspendCancellableCoroutine { cont ->
        provider.pickOpenDocuments(filters, false) { result ->
            cont.resume(result.handles.firstOrNull()?.takeIf { result.success })
        }
    }
}

/** A document to write to, or null when the user cancels or there is no provider. */
suspend fun UapmdHost.pickDocumentToSave(defaultName: String, filters: List<DocumentFilter>): DocumentHandle? {
    val provider = documentProvider ?: return null
    return suspendCancellableCoroutine { cont ->
        provider.pickSaveDocument(defaultName, filters) { result ->
            cont.resume(result.handles.firstOrNull()?.takeIf { result.success })
        }
    }
}

/**
 * A picked document as a path to read from, as uapmd-app's `resolveDocumentHandle`
 * gives it: the document itself on desktop, the provider's copy elsewhere. A failure
 * to resolve is reported through [UapmdHost.reportFileError] and returns null.
 */
suspend fun UapmdHost.pickFileToRead(filters: List<DocumentFilter>): String? {
    val handle = pickDocumentToOpen(filters) ?: return null
    val provider = documentProvider ?: return null
    return suspendCancellableCoroutine { cont ->
        provider.resolveToPath(handle) { result, path ->
            reportFileError(if (result.success) null else "Open failed: ${result.error ?: handle.label}")
            cont.resume(path.takeIf { result.success && it.isNotEmpty() })
        }
    }
}

/** For "change this clip's source file": an audio clip wants audio, a MIDI clip an SMF. */
suspend fun UapmdHost.pickForClip(clipType: ClipType): String? =
    pickFileToRead(if (clipType == ClipType.Audio) DocumentFilters.audioClip else DocumentFilters.midiClip)

/** What a picked document is called in the UI; uapmd-app shows the same. */
val DocumentHandle.label: String get() = displayName.ifEmpty { id }
