package dev.atsushieno.uapmd

import com.sun.jna.Pointer
import com.sun.jna.StringArray
import dev.atsushieno.uapmd.jna.*

// ─── DocumentProvider ────────────────────────────────────────────────────────

private val documentHandleSize by lazy { UapmdDocumentHandle().size() }

private fun readDocumentHandles(handles: Pointer?, count: Int): List<DocumentHandle> {
    if (handles == null || count <= 0) return emptyList()
    return (0 until count).map { i ->
        val h = UapmdDocumentHandle(handles.share(i.toLong() * documentHandleSize)).apply { read() }
        DocumentHandle(h.id ?: "", h.display_name ?: "", h.mime_type ?: "")
    }
}

private fun folderPickCb(callback: (FolderPickResult) -> Unit): FolderPickCb {
    lateinit var cb: FolderPickCb
    cb = object : FolderPickCb {
        override fun invoke(result: Pointer?, userData: Pointer?) {
            liveAppCallbacks.remove(cb)
            if (result == null) {
                callback(FolderPickResult(false, "", emptyList(), "", "", null))
                return
            }
            val r = UapmdFolderPickResult(result).apply { read() }
            callback(FolderPickResult(
                success = r.success != 0.toByte(),
                path = r.path ?: "",
                documents = readDocumentHandles(r.documents, r.document_count),
                token = r.token ?: "",
                displayName = r.display_name ?: "",
                error = r.error
            ))
        }
    }
    liveAppCallbacks.add(cb)
    return cb
}

class JvmDocumentProvider internal constructor(internal val handle: Pointer) : DocumentProvider {
    override fun tick() = lib.uapmd_document_provider_tick(handle)

    override fun pickOpenDocuments(
        filters: List<DocumentFilter>,
        allowMultiple: Boolean,
        callback: (DocumentPickResult) -> Unit
    ) {
        lateinit var cb: DocumentPickCb
        cb = object : DocumentPickCb {
            override fun invoke(result: UapmdDocumentPickResult.ByValue, userData: Pointer?) {
                liveAppCallbacks.remove(cb)
                callback(DocumentPickResult(
                    success = result.success != 0.toByte(),
                    handles = readDocumentHandles(result.handles, result.handle_count),
                    error = result.error
                ))
            }
        }
        liveAppCallbacks.add(cb)
        // The provider copies the filters before pick_open returns, so the
        // native memory only has to outlive the call.
        val keepAlive = mutableListOf<StringArray>()
        @Suppress("UNCHECKED_CAST")
        val nativeFilters = if (filters.isEmpty()) null
            else UapmdDocumentFilter().toArray(filters.size) as Array<UapmdDocumentFilter>
        filters.forEachIndexed { i, f ->
            val n = nativeFilters!![i]
            n.label = f.label
            if (f.mimeTypes.isNotEmpty())
                n.mime_types = StringArray(f.mimeTypes.toTypedArray(), "UTF-8").also { keepAlive += it }
            n.mime_type_count = f.mimeTypes.size
            if (f.extensions.isNotEmpty())
                n.extensions = StringArray(f.extensions.toTypedArray(), "UTF-8").also { keepAlive += it }
            n.extension_count = f.extensions.size
        }
        lib.uapmd_document_provider_pick_open(handle, nativeFilters, filters.size, allowMultiple, null, cb)
        keepAlive.clear()
    }

    override fun readDocument(handle: DocumentHandle, callback: (DocumentIOResult, ByteArray) -> Unit) {
        lateinit var cb: DocumentReadCb
        cb = object : DocumentReadCb {
            override fun invoke(result: UapmdDocumentIoResult.ByValue, data: Pointer?, dataSize: Long, userData: Pointer?) {
                liveAppCallbacks.remove(cb)
                val bytes = if (data != null && dataSize > 0) data.getByteArray(0, dataSize.toInt()) else ByteArray(0)
                callback(DocumentIOResult(result.success != 0.toByte(), result.error), bytes)
            }
        }
        liveAppCallbacks.add(cb)
        val h = UapmdDocumentHandle.ByValue().also {
            it.id = handle.id
            it.display_name = handle.displayName
            it.mime_type = handle.mimeType
        }
        lib.uapmd_document_provider_read(this.handle, h, null, cb)
    }

    override fun pickFolder(callback: (FolderPickResult) -> Unit) =
        lib.uapmd_document_provider_pick_folder(handle, null, folderPickCb(callback))

    override val folderPathsAreUsable: Boolean
        get() = lib.uapmd_document_provider_folder_paths_are_usable(handle)

    override fun listFolderDocuments(token: String, callback: (FolderPickResult) -> Unit) =
        lib.uapmd_document_provider_list_folder_documents(handle, token, null, folderPickCb(callback))

    override fun releaseFolder(token: String) = lib.uapmd_document_provider_release_folder(handle, token)
}

// ─── JSFX ────────────────────────────────────────────────────────────────────

class JvmJsfxPluginFormat internal constructor(internal val handle: Pointer) : JsfxPluginFormat {
    override fun setSearchPaths(paths: List<String>, alsoUseDefaults: Boolean) =
        lib.uapmd_jsfx_format_set_search_paths(handle, paths.toTypedArray(), paths.size, alsoUseDefaults)

    override val searchPaths: List<String>
        get() = (0 until lib.uapmd_jsfx_format_search_path_count(handle)).map {
            lib.uapmd_jsfx_format_get_search_path(handle, it) ?: ""
        }

    override val useDefaultSearchPaths: Boolean
        get() = lib.uapmd_jsfx_format_use_default_search_paths(handle)

    override val defaultSearchPaths: List<String>
        get() = (0 until lib.uapmd_jsfx_format_default_search_path_count(handle)).map {
            lib.uapmd_jsfx_format_get_default_search_path(handle, it) ?: ""
        }

    override var duplicateListing: JsfxDuplicateListing
        get() = JsfxDuplicateListing.fromNative(lib.uapmd_jsfx_format_duplicate_listing(handle))
        set(value) = lib.uapmd_jsfx_format_set_duplicate_listing(handle, value.nativeValue)
}

private fun UapmdJsfxImportResult.toKotlin() = JsfxImportResult(
    success = success != 0.toByte(),
    filesWritten = files_written.toUInt(),
    destination = destination ?: "",
    error = error ?: ""
)

internal actual val jsfxBridge: JsfxBridge = object : JsfxBridge {
    override fun available() = lib.uapmd_jsfx_available()
    override fun setEditorFontData(data: ByteArray) = lib.uapmd_jsfx_set_editor_font_data(data, data.size.toLong())
    override fun usesOwnFontBackend() = lib.uapmd_jsfx_uses_own_font_backend()
    override fun userContentDirectory() = lib.uapmd_jsfx_user_content_directory() ?: ""
    override fun looksLikeArchive(fileName: String) = lib.uapmd_jsfx_looks_like_archive(fileName)

    override fun importContent(fileName: String, data: ByteArray, subdirectory: String): JsfxImportResult {
        val out = UapmdJsfxImportResult()
        lib.uapmd_jsfx_import_content(fileName, data, data.size.toLong(), subdirectory, out)
        out.read()
        return out.toKotlin()
    }

    override fun folderMirrorRoot() = lib.uapmd_jsfx_folder_mirror_root() ?: ""

    override fun registeredFolders(): List<JsfxRegisteredFolder> =
        (0 until lib.uapmd_jsfx_registered_folder_count()).mapNotNull { i ->
            val out = UapmdJsfxRegisteredFolder()
            if (!lib.uapmd_jsfx_get_registered_folder(i, out)) return@mapNotNull null
            out.read()
            JsfxRegisteredFolder(out.token ?: "", out.name ?: "")
        }

    override fun registerFolder(token: String, name: String) = lib.uapmd_jsfx_register_folder(token, name) ?: ""
    override fun unregisterFolder(token: String) = lib.uapmd_jsfx_unregister_folder(token)
    override fun clearFolderMirror(name: String) = lib.uapmd_jsfx_clear_folder_mirror(name)

    override fun writeFolderMirrorFile(name: String, relativePath: String, data: ByteArray): JsfxImportResult {
        val out = UapmdJsfxImportResult()
        lib.uapmd_jsfx_write_folder_mirror_file(name, relativePath, data, data.size.toLong(), out)
        out.read()
        return out.toKotlin()
    }
}
