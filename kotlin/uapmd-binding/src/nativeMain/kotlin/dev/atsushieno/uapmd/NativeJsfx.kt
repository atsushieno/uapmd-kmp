package dev.atsushieno.uapmd

import kotlinx.cinterop.*
import uapmd.*

// ─── DocumentProvider ────────────────────────────────────────────────────────

private fun readDocumentHandles(handles: CPointer<uapmd_document_handle_t>?, count: Int): List<DocumentHandle> {
    if (handles == null || count <= 0) return emptyList()
    return (0 until count).map { i ->
        val h = handles[i]
        DocumentHandle(h.id?.toKString() ?: "", h.display_name?.toKString() ?: "", h.mime_type?.toKString() ?: "")
    }
}

private val documentPickTrampoline =
    staticCFunction<CValue<uapmd_document_pick_result_t>, COpaquePointer?, Unit> { result, userData ->
        if (userData != null) {
            val ref = userData.asStableRef<(DocumentPickResult) -> Unit>()
            val r = result.useContents {
                DocumentPickResult(success, readDocumentHandles(handles, handle_count.toInt()), error?.toKString())
            }
            ref.get()(r)
            ref.dispose()
        }
    }

private val documentReadTrampoline =
    staticCFunction<CValue<uapmd_document_io_result_t>, CPointer<UByteVar>?, ULong, COpaquePointer?, Unit> {
        result, data, size, userData ->
        if (userData != null) {
            val ref = userData.asStableRef<(DocumentIOResult, ByteArray) -> Unit>()
            val r = result.useContents { DocumentIOResult(success, error?.toKString()) }
            val bytes = if (data != null && size > 0u) data.reinterpret<ByteVar>().readBytes(size.toInt()) else ByteArray(0)
            ref.get()(r, bytes)
            ref.dispose()
        }
    }

private val folderPickTrampoline =
    staticCFunction<CPointer<uapmd_folder_pick_result_t>?, COpaquePointer?, Unit> { result, userData ->
        if (userData != null) {
            val ref = userData.asStableRef<(FolderPickResult) -> Unit>()
            val r = result?.pointed?.let {
                FolderPickResult(
                    success = it.success,
                    path = it.path?.toKString() ?: "",
                    documents = readDocumentHandles(it.documents, it.document_count.toInt()),
                    token = it.token?.toKString() ?: "",
                    displayName = it.display_name?.toKString() ?: "",
                    error = it.error?.toKString()
                )
            } ?: FolderPickResult(false, "", emptyList(), "", "", null)
            ref.get()(r)
            ref.dispose()
        }
    }

internal val appCatalogRefreshTrampoline =
    staticCFunction<CPointer<ByteVar>?, COpaquePointer?, Unit> { error, userData ->
        if (userData != null) {
            val ref = userData.asStableRef<(String?) -> Unit>()
            ref.get()(error?.toKString())
            ref.dispose()
        }
    }

internal val jsfxFolderSyncTrampoline =
    staticCFunction<UInt, CPointer<ByteVar>?, COpaquePointer?, Unit> { written, error, userData ->
        if (userData != null) {
            val ref = userData.asStableRef<(UInt, String?) -> Unit>()
            ref.get()(written, error?.toKString())
            ref.dispose()
        }
    }

class NativeDocumentProvider internal constructor(internal val handle: uapmd_document_provider_t) : DocumentProvider {
    override fun tick() = uapmd_document_provider_tick(handle)

    override fun pickOpenDocuments(
        filters: List<DocumentFilter>,
        allowMultiple: Boolean,
        callback: (DocumentPickResult) -> Unit
    ) = memScoped {
        // The provider copies the filters before pick_open returns.
        val nativeFilters = if (filters.isEmpty()) null else allocArray<uapmd_document_filter_t>(filters.size)
        filters.forEachIndexed { i, f ->
            val n = nativeFilters!![i]
            n.label = f.label.cstr.ptr
            n.mime_types = if (f.mimeTypes.isEmpty()) null else f.mimeTypes.toCStringArray(this)
            n.mime_type_count = f.mimeTypes.size.toUInt()
            n.extensions = if (f.extensions.isEmpty()) null else f.extensions.toCStringArray(this)
            n.extension_count = f.extensions.size.toUInt()
        }
        uapmd_document_provider_pick_open(handle, nativeFilters, filters.size.toUInt(), allowMultiple,
            StableRef.create(callback).asCPointer(), documentPickTrampoline)
    }

    override fun readDocument(handle: DocumentHandle, callback: (DocumentIOResult, ByteArray) -> Unit) = memScoped {
        val h = cValue<uapmd_document_handle_t> {
            id = handle.id.cstr.ptr
            display_name = handle.displayName.cstr.ptr
            mime_type = handle.mimeType.cstr.ptr
        }
        uapmd_document_provider_read(this@NativeDocumentProvider.handle, h,
            StableRef.create(callback).asCPointer(), documentReadTrampoline)
    }

    override fun pickFolder(callback: (FolderPickResult) -> Unit) =
        uapmd_document_provider_pick_folder(handle, StableRef.create(callback).asCPointer(), folderPickTrampoline)

    override val folderPathsAreUsable: Boolean
        get() = uapmd_document_provider_folder_paths_are_usable(handle)

    override fun listFolderDocuments(token: String, callback: (FolderPickResult) -> Unit) =
        uapmd_document_provider_list_folder_documents(handle, token,
            StableRef.create(callback).asCPointer(), folderPickTrampoline)

    override fun releaseFolder(token: String) = uapmd_document_provider_release_folder(handle, token)
}

// ─── JSFX ────────────────────────────────────────────────────────────────────

class NativeJsfxPluginFormat internal constructor(internal val handle: uapmd_jsfx_plugin_format_t) : JsfxPluginFormat {
    override fun setSearchPaths(paths: List<String>, alsoUseDefaults: Boolean) = memScoped {
        uapmd_jsfx_format_set_search_paths(handle, if (paths.isEmpty()) null else paths.toCStringArray(this),
            paths.size.toUInt(), alsoUseDefaults)
    }

    override val searchPaths: List<String>
        get() = (0u until uapmd_jsfx_format_search_path_count(handle)).map {
            uapmd_jsfx_format_get_search_path(handle, it)?.toKString() ?: ""
        }

    override val useDefaultSearchPaths: Boolean
        get() = uapmd_jsfx_format_use_default_search_paths(handle)

    override val defaultSearchPaths: List<String>
        get() = (0u until uapmd_jsfx_format_default_search_path_count(handle)).map {
            uapmd_jsfx_format_get_default_search_path(handle, it)?.toKString() ?: ""
        }

    override var duplicateListing: JsfxDuplicateListing
        get() = JsfxDuplicateListing.fromNative(uapmd_jsfx_format_duplicate_listing(handle).toInt())
        set(value) = uapmd_jsfx_format_set_duplicate_listing(handle, value.nativeValue.toUInt())
}

/** Runs [block] with [data]'s bytes, or null for an empty array. */
private fun <R> withBytes(data: ByteArray, block: (CPointer<UByteVar>?) -> R): R =
    if (data.isEmpty()) block(null)
    else data.usePinned { block(it.addressOf(0).reinterpret()) }

private fun uapmd_jsfx_import_result_t.toKotlin() = JsfxImportResult(
    success = success,
    filesWritten = files_written,
    destination = destination?.toKString() ?: "",
    error = error?.toKString() ?: ""
)

internal actual val jsfxBridge: JsfxBridge = object : JsfxBridge {
    override fun available() = uapmd_jsfx_available()
    override fun setEditorFontData(data: ByteArray) =
        withBytes(data) { uapmd_jsfx_set_editor_font_data(it, data.size.toULong()) }
    override fun usesOwnFontBackend() = uapmd_jsfx_uses_own_font_backend()
    override fun userContentDirectory() = uapmd_jsfx_user_content_directory()?.toKString() ?: ""
    override fun looksLikeArchive(fileName: String) = uapmd_jsfx_looks_like_archive(fileName)

    override fun importContent(fileName: String, data: ByteArray, subdirectory: String): JsfxImportResult = memScoped {
        val out = alloc<uapmd_jsfx_import_result_t>()
        withBytes(data) { uapmd_jsfx_import_content(fileName, it, data.size.toULong(), subdirectory, out.ptr) }
        out.toKotlin()
    }

    override fun folderMirrorRoot() = uapmd_jsfx_folder_mirror_root()?.toKString() ?: ""

    override fun registeredFolders(): List<JsfxRegisteredFolder> = memScoped {
        val out = alloc<uapmd_jsfx_registered_folder_t>()
        (0u until uapmd_jsfx_registered_folder_count()).mapNotNull { i ->
            if (!uapmd_jsfx_get_registered_folder(i, out.ptr)) return@mapNotNull null
            JsfxRegisteredFolder(out.token?.toKString() ?: "", out.name?.toKString() ?: "")
        }
    }

    override fun registerFolder(token: String, name: String) =
        uapmd_jsfx_register_folder(token, name)?.toKString() ?: ""
    override fun unregisterFolder(token: String) = uapmd_jsfx_unregister_folder(token)
    override fun clearFolderMirror(name: String) = uapmd_jsfx_clear_folder_mirror(name)

    override fun writeFolderMirrorFile(name: String, relativePath: String, data: ByteArray): JsfxImportResult = memScoped {
        val out = alloc<uapmd_jsfx_import_result_t>()
        withBytes(data) { uapmd_jsfx_write_folder_mirror_file(name, relativePath, it, data.size.toULong(), out.ptr) }
        out.toKotlin()
    }
}
