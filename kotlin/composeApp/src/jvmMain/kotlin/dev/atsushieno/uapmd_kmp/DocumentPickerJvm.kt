package dev.atsushieno.uapmd_kmp

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import dev.atsushieno.uapmd.jna.DocumentPathCb
import dev.atsushieno.uapmd.jna.DocumentPickCb
import dev.atsushieno.uapmd.jna.UapmdDocumentFilter
import dev.atsushieno.uapmd.jna.UapmdDocumentHandle
import dev.atsushieno.uapmd.jna.UapmdLibrary
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private data class DesktopDocumentFilter(
    val label: String,
    val extensions: List<String>,
    val mimeTypes: List<String> = emptyList()
)

private data class BuiltFilters(
    val filters: Array<UapmdDocumentFilter>,
    val allocations: List<Memory>
)

actual object PlatformDocumentPicker {
    private val library = UapmdLibrary.INSTANCE
    private val provider = requireNotNull(library.uapmd_document_provider_create()) {
        "Failed to create document provider"
    }
    private val liveCallbacks = mutableSetOf<Any>()

    actual fun tick() {
        library.uapmd_document_provider_tick(provider)
    }

    actual suspend fun pickOpenPath(kind: DocumentPickerKind): DocumentPickPathResult =
        suspendCancellableCoroutine { continuation ->
            val pickCallback = object : DocumentPickCb {
                override fun invoke(result: dev.atsushieno.uapmd.jna.UapmdDocumentPickResult.ByValue, userData: Pointer?) {
                    if (result.success.toInt() == 0) {
                        finish(this)
                        continuation.resume(DocumentPickPathResult(error = result.error))
                        return
                    }
                    if (result.handle_count <= 0 || result.handles == null) {
                        finish(this)
                        continuation.resume(DocumentPickPathResult())
                        return
                    }
                    val handle = UapmdDocumentHandle(result.handles)
                    handle.read()
                    val pathCallback = object : DocumentPathCb {
                        override fun invoke(
                            pathResult: dev.atsushieno.uapmd.jna.UapmdDocumentIoResult.ByValue,
                            path: String?,
                            userData: Pointer?
                        ) {
                            finish(this)
                            continuation.resume(
                                if (pathResult.success.toInt() != 0 && !path.isNullOrBlank())
                                    DocumentPickPathResult(path = path)
                                else
                                    DocumentPickPathResult(error = pathResult.error ?: "Failed to resolve selected file path.")
                            )
                        }
                    }
                    keep(pathCallback)
                    finish(this)
                    library.uapmd_document_provider_resolve_to_path(provider, handle, Pointer.NULL, pathCallback)
                }
            }
            keep(pickCallback)
            val builtFilters = buildFilters(kind)
            library.uapmd_document_provider_pick_open(
                provider,
                builtFilters.filters,
                builtFilters.filters.size,
                false,
                Pointer.NULL,
                pickCallback
            )
        }

    private fun keep(callback: Any) {
        synchronized(liveCallbacks) {
            liveCallbacks += callback
        }
    }

    private fun finish(callback: Any) {
        synchronized(liveCallbacks) {
            liveCallbacks -= callback
        }
    }

    private fun buildFilters(kind: DocumentPickerKind): BuiltFilters {
        val spec = when (kind) {
            DocumentPickerKind.Project -> listOf(
                DesktopDocumentFilter("UAPMD Project Archive", listOf("*.uapmdz")),
                DesktopDocumentFilter("Legacy UAPMD Project", listOf("*.uapmd")),
                DesktopDocumentFilter("All Files", listOf("*"))
            )
            DocumentPickerKind.Audio -> listOf(
                DesktopDocumentFilter("Audio Files", listOf("*.wav", "*.flac", "*.ogg")),
                DesktopDocumentFilter("All Files", listOf("*"))
            )
            DocumentPickerKind.Midi -> listOf(
                DesktopDocumentFilter("MIDI Files", listOf("*.mid", "*.midi", "*.smf", "*.midi2")),
                DesktopDocumentFilter("All Files", listOf("*"))
            )
        }
        val allocations = mutableListOf<Memory>()
        val template = UapmdDocumentFilter()
        @Suppress("UNCHECKED_CAST")
        val filters = template.toArray(spec.size) as Array<UapmdDocumentFilter>
        spec.forEachIndexed { index, filter ->
            filters[index].apply {
                label = filter.label
                extensions = toCStringArray(filter.extensions, allocations)
                extension_count = filter.extensions.size
                mime_types = toCStringArray(filter.mimeTypes, allocations)
                mime_type_count = filter.mimeTypes.size
            }
        }
        filters.forEach { it.write() }
        return BuiltFilters(filters, allocations)
    }

    private fun toCStringArray(values: List<String>, allocations: MutableList<Memory>): Pointer? {
        if (values.isEmpty())
            return null
        val memory = Memory((Native.POINTER_SIZE * values.size).toLong())
        allocations += memory
        values.forEachIndexed { index, value ->
            val stringMemory = Memory((value.toByteArray(Charsets.UTF_8).size + 1).toLong())
            stringMemory.setString(0, value)
            allocations += stringMemory
            memory.setPointer((index * Native.POINTER_SIZE).toLong(), stringMemory)
        }
        return memory
    }
}
