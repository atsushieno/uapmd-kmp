@file:Suppress("unused")

package dev.atsushieno.uapmd

// wasm32 layouts (verify with emcc -fdump-record-layouts-complete when they change):
//   uapmd_document_handle_t       id@0 display_name@4 mime_type@8              (12)
//   uapmd_document_filter_t       label@0 mime_types@4 mime_type_count@8
//                                 extensions@12 extension_count@16             (20)
//   uapmd_document_pick_result_t  success@0 handle_count@4 handles@8 error@12  (16)
//   uapmd_document_io_result_t    success@0 error@4                            (8)
//   uapmd_folder_pick_result_t    success@0 path@4 document_count@8 documents@12
//                                 token@16 display_name@20 error@24            (28)
//   uapmd_jsfx_import_result_t    success@0 files_written@4 destination@8 error@12 (16)
//   uapmd_jsfx_registered_folder_t token@0 name@4                              (8)
private const val DOCUMENT_HANDLE_SIZE = 12
private const val DOCUMENT_FILTER_SIZE = 20
private const val JSFX_IMPORT_RESULT_SIZE = 16
private const val JSFX_REGISTERED_FOLDER_SIZE = 8

private fun wasmStrOrNull(ptr: Int): String? = wasmGetI32(ptr).let { if (it != 0) wasmMod.utf8ToString(it) else null }
private fun cStr(ptr: Int): String = if (ptr != 0) wasmMod.utf8ToString(ptr) else ""

private fun readDocumentHandles(handles: Int, count: Int): List<DocumentHandle> {
    if (handles == 0 || count <= 0) return emptyList()
    return (0 until count).map { i ->
        val p = handles + i * DOCUMENT_HANDLE_SIZE
        DocumentHandle(wasmGetStr(p), wasmGetStr(p + 4), wasmGetStr(p + 8))
    }
}

/** Copies [data] into wasm memory for the duration of [block]; 0 for an empty array. */
private fun <T> withWasmBytes(data: ByteArray, block: (Int) -> T): T {
    if (data.isEmpty()) return block(0)
    return withWasmStruct(data.size) { p ->
        data.forEachIndexed { i, b -> wasmSetI8(p + i, b.toInt()) }
        block(p)
    }
}

/** A C `const char**` of [strings], freed after [block]; 0 for an empty list. */
private fun <T> withWasmCStringArray(strings: List<String>, block: (Int) -> T): T {
    if (strings.isEmpty()) return block(0)
    val mod = wasmMod
    val ptrs = strings.map { s ->
        val len = mod.lengthBytesUTF8(s) + 1
        mod.malloc(len).also { mod.stringToUTF8(s, it, len) }
    }
    return try {
        withWasmStruct(ptrs.size * 4) { array ->
            ptrs.forEachIndexed { i, p -> wasmSetI32(array + i * 4, p) }
            block(array)
        }
    } finally {
        ptrs.forEach { mod.free(it) }
    }
}

// ─── Callback dispatch ───────────────────────────────────────────────────────

private val pendingDocumentPickResults = mutableMapOf<Int, (DocumentPickResult) -> Unit>()
private val pendingDocumentReads = mutableMapOf<Int, (DocumentIOResult, ByteArray) -> Unit>()
private val pendingFolderPicks = mutableMapOf<Int, (FolderPickResult) -> Unit>()
private val pendingJsfxFolderSyncs = mutableMapOf<Int, (UInt, String?) -> Unit>()

/** The C callback takes uapmd_document_pick_result_t by value, i.e. as a pointer. */
@JsExport
fun uapmdDispatchDocumentPickResult(cbId: Int, resultPtr: Int) {
    val callback = pendingDocumentPickResults.remove(cbId) ?: return
    callback(DocumentPickResult(
        success = wasmGetBool(resultPtr),
        handles = readDocumentHandles(wasmGetI32(resultPtr + 8), wasmGetI32(resultPtr + 4)),
        error = wasmStrOrNull(resultPtr + 12)
    ))
}

@JsExport
fun uapmdDispatchDocumentRead(cbId: Int, resultPtr: Int, dataPtr: Int, size: Int) {
    val callback = pendingDocumentReads.remove(cbId) ?: return
    val data = if (dataPtr != 0 && size > 0)
        ByteArray(size) { i -> wasmMod.getValue(dataPtr + i, "i8").toInt().toByte() }
    else ByteArray(0)
    callback(DocumentIOResult(wasmGetBool(resultPtr), wasmStrOrNull(resultPtr + 4)), data)
}

@JsExport
fun uapmdDispatchFolderPick(cbId: Int, resultPtr: Int) {
    val callback = pendingFolderPicks.remove(cbId) ?: return
    if (resultPtr == 0) {
        callback(FolderPickResult(false, "", emptyList(), "", "", null))
        return
    }
    callback(FolderPickResult(
        success = wasmGetBool(resultPtr),
        path = wasmGetStr(resultPtr + 4),
        documents = readDocumentHandles(wasmGetI32(resultPtr + 12), wasmGetI32(resultPtr + 8)),
        token = wasmGetStr(resultPtr + 16),
        displayName = wasmGetStr(resultPtr + 20),
        error = wasmStrOrNull(resultPtr + 24)
    ))
}

@JsExport
fun uapmdDispatchJsfxFolderSync(cbId: Int, filesWritten: Int, errorPtr: Int) {
    val error = if (errorPtr != 0) wasmMod.utf8ToString(errorPtr) else null
    pendingJsfxFolderSyncs.remove(cbId)?.invoke(filesWritten.toUInt(), error)
}

private fun folderPickPtr(callback: (FolderPickResult) -> Unit): Int {
    val cbId = nextCallbackId()
    pendingFolderPicks[cbId] = callback
    return makeCFunctionPtr(cbId, "uapmdDispatchFolderPick", "vii")
}

internal fun jsfxFolderSyncPtr(callback: (UInt, String?) -> Unit): Int {
    val cbId = nextCallbackId()
    pendingJsfxFolderSyncs[cbId] = callback
    return makeCFunctionPtr(cbId, "uapmdDispatchJsfxFolderSync", "viii")
}

// ─── DocumentProvider ────────────────────────────────────────────────────────

class WasmJsDocumentProvider internal constructor(internal val handle: Int) : DocumentProvider {
    override fun tick() = wasmMod.uapmdDocumentProviderTick(handle)

    override fun pickOpenDocuments(
        filters: List<DocumentFilter>,
        allowMultiple: Boolean,
        callback: (DocumentPickResult) -> Unit
    ) {
        val cbId = nextCallbackId()
        pendingDocumentPickResults[cbId] = callback
        val cbPtr = makeCFunctionPtr(cbId, "uapmdDispatchDocumentPickResult", "vii")
        val mod = wasmMod
        // The provider copies the filters before pick_open returns.
        val allocations = mutableListOf<Int>()
        fun cString(s: String): Int {
            val len = mod.lengthBytesUTF8(s) + 1
            return mod.malloc(len).also { mod.stringToUTF8(s, it, len); allocations += it }
        }
        fun cStringArray(list: List<String>): Int {
            if (list.isEmpty()) return 0
            val array = mod.malloc(list.size * 4).also { allocations += it }
            list.forEachIndexed { i, s -> wasmSetI32(array + i * 4, cString(s)) }
            return array
        }
        try {
            val array = if (filters.isEmpty()) 0
                else mod.malloc(filters.size * DOCUMENT_FILTER_SIZE).also { allocations += it }
            filters.forEachIndexed { i, f ->
                val p = array + i * DOCUMENT_FILTER_SIZE
                wasmSetI32(p, cString(f.label))
                wasmSetI32(p + 4, cStringArray(f.mimeTypes))
                wasmSetI32(p + 8, f.mimeTypes.size)
                wasmSetI32(p + 12, cStringArray(f.extensions))
                wasmSetI32(p + 16, f.extensions.size)
            }
            mod.uapmdDocumentProviderPickOpen(handle, array, filters.size, allowMultiple, 0, cbPtr)
        } finally {
            allocations.forEach { mod.free(it) }
        }
    }

    override fun readDocument(handle: DocumentHandle, callback: (DocumentIOResult, ByteArray) -> Unit) {
        val cbId = nextCallbackId()
        pendingDocumentReads[cbId] = callback
        val cbPtr = makeCFunctionPtr(cbId, "uapmdDispatchDocumentRead", "viiii")
        // The handle crosses by value, i.e. as a pointer; the provider copies it.
        withCStringKt(handle.id) { id ->
            withCStringKt(handle.displayName) { name ->
                withCStringKt(handle.mimeType) { mime ->
                    withWasmStruct(DOCUMENT_HANDLE_SIZE) { p ->
                        wasmSetI32(p, id)
                        wasmSetI32(p + 4, name)
                        wasmSetI32(p + 8, mime)
                        wasmMod.uapmdDocumentProviderRead(this.handle, p, 0, cbPtr)
                    }
                }
            }
        }
    }

    override fun pickFolder(callback: (FolderPickResult) -> Unit) =
        wasmMod.uapmdDocumentProviderPickFolder(handle, 0, folderPickPtr(callback))

    override val folderPathsAreUsable: Boolean
        get() = wasmMod.uapmdDocumentProviderFolderPathsAreUsable(handle)

    override fun listFolderDocuments(token: String, callback: (FolderPickResult) -> Unit) =
        withCStringKt(token) { t -> wasmMod.uapmdDocumentProviderListFolderDocuments(handle, t, 0, folderPickPtr(callback)) }

    override fun releaseFolder(token: String) =
        withCStringKt(token) { t -> wasmMod.uapmdDocumentProviderReleaseFolder(handle, t) }
}

// ─── JSFX ────────────────────────────────────────────────────────────────────

class WasmJsJsfxPluginFormat internal constructor(internal val handle: Int) : JsfxPluginFormat {
    override fun setSearchPaths(paths: List<String>, alsoUseDefaults: Boolean) =
        withWasmCStringArray(paths) { p -> wasmMod.uapmdJsfxFormatSetSearchPaths(handle, p, paths.size, alsoUseDefaults) }

    override val searchPaths: List<String>
        get() = (0 until wasmMod.uapmdJsfxFormatSearchPathCount(handle)).map {
            cStr(wasmMod.uapmdJsfxFormatGetSearchPath(handle, it))
        }

    override val useDefaultSearchPaths: Boolean
        get() = wasmMod.uapmdJsfxFormatUseDefaultSearchPaths(handle)

    override val defaultSearchPaths: List<String>
        get() = (0 until wasmMod.uapmdJsfxFormatDefaultSearchPathCount(handle)).map {
            cStr(wasmMod.uapmdJsfxFormatGetDefaultSearchPath(handle, it))
        }

    override var duplicateListing: JsfxDuplicateListing
        get() = JsfxDuplicateListing.fromNative(wasmMod.uapmdJsfxFormatDuplicateListing(handle))
        set(value) = wasmMod.uapmdJsfxFormatSetDuplicateListing(handle, value.nativeValue)
}

private fun readImportResult(p: Int) = JsfxImportResult(
    success = wasmGetBool(p),
    filesWritten = wasmGetI32(p + 4).toUInt(),
    destination = wasmGetStr(p + 8),
    error = wasmGetStr(p + 12)
)

internal actual val jsfxBridge: JsfxBridge = object : JsfxBridge {
    override fun available() = wasmMod.uapmdJsfxAvailable()
    override fun setEditorFontData(data: ByteArray) =
        withWasmBytes(data) { p -> wasmMod.uapmdJsfxSetEditorFontData(p, data.size) }
    override fun usesOwnFontBackend() = wasmMod.uapmdJsfxUsesOwnFontBackend()
    override fun userContentDirectory() = cStr(wasmMod.uapmdJsfxUserContentDirectory())
    override fun looksLikeArchive(fileName: String) =
        withCStringKt(fileName) { f -> wasmMod.uapmdJsfxLooksLikeArchive(f) }

    override fun importContent(fileName: String, data: ByteArray, subdirectory: String): JsfxImportResult =
        withCStringKt(fileName) { f ->
            withCStringKt(subdirectory) { sub ->
                withWasmBytes(data) { d ->
                    withWasmStruct(JSFX_IMPORT_RESULT_SIZE) { out ->
                        wasmMod.uapmdJsfxImportContent(f, d, data.size, sub, out)
                        readImportResult(out)
                    }
                }
            }
        }

    override fun folderMirrorRoot() = cStr(wasmMod.uapmdJsfxFolderMirrorRoot())

    override fun registeredFolders(): List<JsfxRegisteredFolder> =
        withWasmStruct(JSFX_REGISTERED_FOLDER_SIZE) { out ->
            (0 until wasmMod.uapmdJsfxRegisteredFolderCount()).mapNotNull { i ->
                if (!wasmMod.uapmdJsfxGetRegisteredFolder(i, out)) return@mapNotNull null
                JsfxRegisteredFolder(wasmGetStr(out), wasmGetStr(out + 4))
            }
        }

    override fun registerFolder(token: String, name: String): String =
        withTwoCStringsKt(token, name) { t, n -> cStr(wasmMod.uapmdJsfxRegisterFolder(t, n)) }

    override fun unregisterFolder(token: String) =
        withCStringKt(token) { t -> wasmMod.uapmdJsfxUnregisterFolder(t) }

    override fun clearFolderMirror(name: String) =
        withCStringKt(name) { n -> wasmMod.uapmdJsfxClearFolderMirror(n) }

    override fun writeFolderMirrorFile(name: String, relativePath: String, data: ByteArray): JsfxImportResult =
        withTwoCStringsKt(name, relativePath) { n, rel ->
            withWasmBytes(data) { d ->
                withWasmStruct(JSFX_IMPORT_RESULT_SIZE) { out ->
                    wasmMod.uapmdJsfxWriteFolderMirrorFile(n, rel, d, data.size, out)
                    readImportResult(out)
                }
            }
        }
}
