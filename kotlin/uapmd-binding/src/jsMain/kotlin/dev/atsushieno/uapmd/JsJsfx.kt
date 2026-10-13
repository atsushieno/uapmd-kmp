package dev.atsushieno.uapmd

// wasm32 struct layouts: see wasmJsMain's WasmJsJsfx.kt.
private const val DOCUMENT_HANDLE_SIZE = 12
private const val DOCUMENT_FILTER_SIZE = 20
private const val JSFX_IMPORT_RESULT_SIZE = 16
private const val JSFX_REGISTERED_FOLDER_SIZE = 8

private fun jsStrOrNull(ptr: Int): String? = jsGetPtr(ptr).let { if (it != 0) jsMod.UTF8ToString(it) as String else null }
private fun jsCStr(ptr: Int): String = if (ptr != 0) jsMod.UTF8ToString(ptr) as String else ""

private fun <T> withJsStruct(size: Int, block: (Int) -> T): T {
    val ptr = jsMod._malloc(size) as Int
    return try { block(ptr) } finally { jsMod._free(ptr) }
}

private fun <T> withJsBytes(data: ByteArray, block: (Int) -> T): T {
    if (data.isEmpty()) return block(0)
    return withJsStruct(data.size) { p ->
        data.forEachIndexed { i, b -> jsSetI8(p + i, b.toInt()) }
        block(p)
    }
}

private fun <T> withJsCStringArray(strings: List<String>, block: (Int) -> T): T {
    if (strings.isEmpty()) return block(0)
    val mod = jsMod
    val ptrs = strings.map { s ->
        val len = (mod.lengthBytesUTF8(s) as Int) + 1
        (mod._malloc(len) as Int).also { mod.stringToUTF8(s, it, len) }
    }
    return try {
        withJsStruct(ptrs.size * 4) { array ->
            ptrs.forEachIndexed { i, p -> jsSetI32(array + i * 4, p) }
            block(array)
        }
    } finally {
        ptrs.forEach { mod._free(it) }
    }
}

private fun readJsDocumentHandles(handles: Int, count: Int): List<DocumentHandle> {
    if (handles == 0 || count <= 0) return emptyList()
    return (0 until count).map { i ->
        val p = handles + i * DOCUMENT_HANDLE_SIZE
        DocumentHandle(jsGetStr(p), jsGetStr(p + 4), jsGetStr(p + 8))
    }
}

private fun makeJsFolderPick(callback: (FolderPickResult) -> Unit): Int {
    var slot = 0
    val fn: (dynamic, dynamic) -> Unit = { resultPtr, _ ->
        try {
            val p = resultPtr as Int
            callback(if (p == 0) FolderPickResult(false, "", emptyList(), "", "", null) else FolderPickResult(
                success = jsGetBool(p),
                path = jsGetStr(p + 4),
                documents = readJsDocumentHandles(jsGetPtr(p + 12), jsGetI32(p + 8)),
                token = jsGetStr(p + 16),
                displayName = jsGetStr(p + 20),
                error = jsStrOrNull(p + 24)
            ))
        } finally {
            removeJsCallback(slot)
        }
    }
    slot = addJsCallback(fn.asDynamic(), "vii")
    return slot
}

internal fun makeJsFolderSync(callback: (UInt, String?) -> Unit): Int {
    var slot = 0
    val fn: (dynamic, dynamic, dynamic) -> Unit = { written, errorPtr, _ ->
        try {
            val err = if ((errorPtr as Int) != 0) jsMod.UTF8ToString(errorPtr) as String else null
            callback((written as Int).toUInt(), err)
        } finally {
            removeJsCallback(slot)
        }
    }
    slot = addJsCallback(fn.asDynamic(), "viii")
    return slot
}

class JsDocumentProvider internal constructor(internal val handle: Int) : DocumentProvider {
    override fun tick() { jsMod._uapmd_document_provider_tick(handle) }

    override fun pickOpenDocuments(
        filters: List<DocumentFilter>,
        allowMultiple: Boolean,
        callback: (DocumentPickResult) -> Unit
    ) {
        var slot = 0
        val fn: (dynamic, dynamic) -> Unit = { resultPtr, _ ->
            try {
                val p = resultPtr as Int
                callback(DocumentPickResult(
                    success = jsGetBool(p),
                    handles = readJsDocumentHandles(jsGetPtr(p + 8), jsGetI32(p + 4)),
                    error = jsStrOrNull(p + 12)
                ))
            } finally {
                removeJsCallback(slot)
            }
        }
        slot = addJsCallback(fn.asDynamic(), "vii")
        // The provider copies the filters before pick_open returns.
        val mod = jsMod
        val allocations = mutableListOf<Int>()
        fun cString(s: String): Int {
            val len = (mod.lengthBytesUTF8(s) as Int) + 1
            return (mod._malloc(len) as Int).also { mod.stringToUTF8(s, it, len); allocations += it }
        }
        fun cStringArray(list: List<String>): Int {
            if (list.isEmpty()) return 0
            val array = (mod._malloc(list.size * 4) as Int).also { allocations += it }
            list.forEachIndexed { i, s -> jsSetI32(array + i * 4, cString(s)) }
            return array
        }
        try {
            val array = if (filters.isEmpty()) 0
                else (mod._malloc(filters.size * DOCUMENT_FILTER_SIZE) as Int).also { allocations += it }
            filters.forEachIndexed { i, f ->
                val p = array + i * DOCUMENT_FILTER_SIZE
                jsSetI32(p, cString(f.label))
                jsSetI32(p + 4, cStringArray(f.mimeTypes))
                jsSetI32(p + 8, f.mimeTypes.size)
                jsSetI32(p + 12, cStringArray(f.extensions))
                jsSetI32(p + 16, f.extensions.size)
            }
            mod._uapmd_document_provider_pick_open(handle, array, filters.size, allowMultiple, 0, slot)
        } finally {
            allocations.forEach { mod._free(it) }
        }
    }

    override fun readDocument(handle: DocumentHandle, callback: (DocumentIOResult, ByteArray) -> Unit) {
        var slot = 0
        val fn: (dynamic, dynamic, dynamic, dynamic) -> Unit = { resultPtr, dataPtr, size, _ ->
            try {
                val p = resultPtr as Int
                val d = dataPtr as Int
                val n = size as Int
                val data = if (d != 0 && n > 0) ByteArray(n) { i -> (jsGetI8(d + i)).toByte() } else ByteArray(0)
                callback(DocumentIOResult(jsGetBool(p), jsStrOrNull(p + 4)), data)
            } finally {
                removeJsCallback(slot)
            }
        }
        slot = addJsCallback(fn.asDynamic(), "viiii")
        // The handle crosses by value, i.e. as a pointer; the provider copies it.
        withJsThreeCStrings(handle.id, handle.displayName, handle.mimeType) { id, name, mime ->
            withJsStruct(DOCUMENT_HANDLE_SIZE) { p ->
                jsSetI32(p, id)
                jsSetI32(p + 4, name)
                jsSetI32(p + 8, mime)
                jsMod._uapmd_document_provider_read(this.handle, p, 0, slot)
            }
        }
    }

    override fun pickFolder(callback: (FolderPickResult) -> Unit) {
        jsMod._uapmd_document_provider_pick_folder(handle, 0, makeJsFolderPick(callback))
    }

    override val folderPathsAreUsable: Boolean
        get() = (jsMod._uapmd_document_provider_folder_paths_are_usable(handle) as Int) != 0

    override fun listFolderDocuments(token: String, callback: (FolderPickResult) -> Unit) {
        withJsCString(token) { t -> jsMod._uapmd_document_provider_list_folder_documents(handle, t, 0, makeJsFolderPick(callback)) }
    }

    override fun releaseFolder(token: String) {
        withJsCString(token) { t -> jsMod._uapmd_document_provider_release_folder(handle, t) }
    }
}

class JsJsfxPluginFormat internal constructor(internal val handle: Int) : JsfxPluginFormat {
    override fun setSearchPaths(paths: List<String>, alsoUseDefaults: Boolean) {
        withJsCStringArray(paths) { p -> jsMod._uapmd_jsfx_format_set_search_paths(handle, p, paths.size, alsoUseDefaults) }
    }

    override val searchPaths: List<String>
        get() = (0 until (jsMod._uapmd_jsfx_format_search_path_count(handle) as Int)).map {
            jsCStr(jsMod._uapmd_jsfx_format_get_search_path(handle, it) as Int)
        }

    override val useDefaultSearchPaths: Boolean
        get() = (jsMod._uapmd_jsfx_format_use_default_search_paths(handle) as Int) != 0

    override val defaultSearchPaths: List<String>
        get() = (0 until (jsMod._uapmd_jsfx_format_default_search_path_count(handle) as Int)).map {
            jsCStr(jsMod._uapmd_jsfx_format_get_default_search_path(handle, it) as Int)
        }

    override var duplicateListing: JsfxDuplicateListing
        get() = JsfxDuplicateListing.fromNative(jsMod._uapmd_jsfx_format_duplicate_listing(handle) as Int)
        set(value) { jsMod._uapmd_jsfx_format_set_duplicate_listing(handle, value.nativeValue) }
}

private fun readJsImportResult(p: Int) = JsfxImportResult(
    success = jsGetBool(p),
    filesWritten = jsGetI32(p + 4).toUInt(),
    destination = jsGetStr(p + 8),
    error = jsGetStr(p + 12)
)

internal actual val jsfxBridge: JsfxBridge = object : JsfxBridge {
    override fun available() = (jsMod._uapmd_jsfx_available() as Int) != 0
    override fun setEditorFontData(data: ByteArray) {
        withJsBytes(data) { p -> jsMod._uapmd_jsfx_set_editor_font_data(p, data.size) }
    }
    override fun usesOwnFontBackend() = (jsMod._uapmd_jsfx_uses_own_font_backend() as Int) != 0
    override fun userContentDirectory() = jsCStr(jsMod._uapmd_jsfx_user_content_directory() as Int)
    override fun looksLikeArchive(fileName: String) =
        withJsCString(fileName) { f -> (jsMod._uapmd_jsfx_looks_like_archive(f) as Int) != 0 }

    override fun importContent(fileName: String, data: ByteArray, subdirectory: String): JsfxImportResult =
        withJsTwoCStrings(fileName, subdirectory) { f, sub ->
            withJsBytes(data) { d ->
                withJsStruct(JSFX_IMPORT_RESULT_SIZE) { out ->
                    jsMod._uapmd_jsfx_import_content(f, d, data.size, sub, out)
                    readJsImportResult(out)
                }
            }
        }

    override fun folderMirrorRoot() = jsCStr(jsMod._uapmd_jsfx_folder_mirror_root() as Int)

    override fun registeredFolders(): List<JsfxRegisteredFolder> =
        withJsStruct(JSFX_REGISTERED_FOLDER_SIZE) { out ->
            (0 until (jsMod._uapmd_jsfx_registered_folder_count() as Int)).mapNotNull { i ->
                if ((jsMod._uapmd_jsfx_get_registered_folder(i, out) as Int) == 0) return@mapNotNull null
                JsfxRegisteredFolder(jsGetStr(out), jsGetStr(out + 4))
            }
        }

    override fun registerFolder(token: String, name: String): String =
        withJsTwoCStrings(token, name) { t, n -> jsCStr(jsMod._uapmd_jsfx_register_folder(t, n) as Int) }

    override fun unregisterFolder(token: String) {
        withJsCString(token) { t -> jsMod._uapmd_jsfx_unregister_folder(t) }
    }

    override fun clearFolderMirror(name: String) =
        withJsCString(name) { n -> (jsMod._uapmd_jsfx_clear_folder_mirror(n) as Int) != 0 }

    override fun writeFolderMirrorFile(name: String, relativePath: String, data: ByteArray): JsfxImportResult =
        withJsTwoCStrings(name, relativePath) { n, rel ->
            withJsBytes(data) { d ->
                withJsStruct(JSFX_IMPORT_RESULT_SIZE) { out ->
                    jsMod._uapmd_jsfx_write_folder_mirror_file(n, rel, d, data.size, out)
                    readJsImportResult(out)
                }
            }
        }
}
