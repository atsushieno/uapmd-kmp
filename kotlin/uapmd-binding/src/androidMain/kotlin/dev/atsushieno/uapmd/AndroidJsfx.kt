package dev.atsushieno.uapmd

/*
 * Completion objects for uapmd_jni_jsfx.cpp. Each declares invoke() with the exact
 * parameter types the native side resolves; see AndroidAppModel.kt for why a bare
 * Kotlin lambda cannot be used.
 */

private fun Array<String?>.toDocumentHandles(): List<DocumentHandle> =
    (0 until size / 3).map { DocumentHandle(this[it * 3] ?: "", this[it * 3 + 1] ?: "", this[it * 3 + 2] ?: "") }

private class DocumentPickCallback(private val callback: (DocumentPickResult) -> Unit) {
    @Suppress("unused")
    fun invoke(success: Boolean, handles: Array<String?>, error: String?) =
        callback(DocumentPickResult(success, handles.toDocumentHandles(), error))
}

private class DocumentReadCallback(private val callback: (DocumentIOResult, ByteArray) -> Unit) {
    @Suppress("unused")
    fun invoke(success: Boolean, error: String?, data: ByteArray) =
        callback(DocumentIOResult(success, error), data)
}

private class FolderPickCallback(private val callback: (FolderPickResult) -> Unit) {
    @Suppress("unused")
    fun invoke(success: Boolean, path: String, documents: Array<String?>, token: String, displayName: String, error: String?) =
        callback(FolderPickResult(success, path, documents.toDocumentHandles(), token, displayName, error))
}

internal class JsfxFolderSyncCallback(private val callback: (UInt, String?) -> Unit) {
    @Suppress("unused")
    fun invoke(filesWritten: Int, error: String?) = callback(filesWritten.toUInt(), error)
}

class AndroidDocumentProvider internal constructor(internal val handle: Long) : DocumentProvider {
    override fun tick() = JniBridge.uapmdDocumentProviderTick(handle)

    override fun pickOpenDocuments(
        filters: List<DocumentFilter>,
        allowMultiple: Boolean,
        callback: (DocumentPickResult) -> Unit
    ) = JniBridge.uapmdDocumentProviderPickOpen(
        handle,
        filters.map { it.label }.toTypedArray(),
        filters.map { it.mimeTypes.toTypedArray() }.toTypedArray(),
        filters.map { it.extensions.toTypedArray() }.toTypedArray(),
        allowMultiple,
        DocumentPickCallback(callback)
    )

    override fun readDocument(handle: DocumentHandle, callback: (DocumentIOResult, ByteArray) -> Unit) =
        JniBridge.uapmdDocumentProviderRead(this.handle, handle.id, handle.displayName, handle.mimeType,
            DocumentReadCallback(callback))

    override fun pickFolder(callback: (FolderPickResult) -> Unit) =
        JniBridge.uapmdDocumentProviderPickFolder(handle, FolderPickCallback(callback))

    override val folderPathsAreUsable: Boolean
        get() = JniBridge.uapmdDocumentProviderFolderPathsAreUsable(handle)

    override fun listFolderDocuments(token: String, callback: (FolderPickResult) -> Unit) =
        JniBridge.uapmdDocumentProviderListFolderDocuments(handle, token, FolderPickCallback(callback))

    override fun releaseFolder(token: String) = JniBridge.uapmdDocumentProviderReleaseFolder(handle, token)
}

class AndroidJsfxPluginFormat internal constructor(internal val handle: Long) : JsfxPluginFormat {
    override fun setSearchPaths(paths: List<String>, alsoUseDefaults: Boolean) =
        JniBridge.uapmdJsfxFormatSetSearchPaths(handle, paths.toTypedArray(), alsoUseDefaults)

    override val searchPaths: List<String>
        get() = JniBridge.uapmdJsfxFormatSearchPaths(handle).map { it ?: "" }

    override val useDefaultSearchPaths: Boolean
        get() = JniBridge.uapmdJsfxFormatUseDefaultSearchPaths(handle)

    override val defaultSearchPaths: List<String>
        get() = JniBridge.uapmdJsfxFormatDefaultSearchPaths(handle).map { it ?: "" }

    override var duplicateListing: JsfxDuplicateListing
        get() = JsfxDuplicateListing.fromNative(JniBridge.uapmdJsfxFormatDuplicateListing(handle))
        set(value) = JniBridge.uapmdJsfxFormatSetDuplicateListing(handle, value.nativeValue)
}

private fun importResult(values: IntArray, strings: Array<String?>) = JsfxImportResult(
    success = values[0] != 0,
    filesWritten = values[1].toUInt(),
    destination = strings[0] ?: "",
    error = strings[1] ?: ""
)

internal actual val jsfxBridge: JsfxBridge = object : JsfxBridge {
    override fun available() = JniBridge.uapmdJsfxAvailable()
    override fun setEditorFontData(data: ByteArray) = JniBridge.uapmdJsfxSetEditorFontData(data)
    override fun usesOwnFontBackend() = JniBridge.uapmdJsfxUsesOwnFontBackend()
    override fun userContentDirectory() = JniBridge.uapmdJsfxUserContentDirectory()
    override fun looksLikeArchive(fileName: String) = JniBridge.uapmdJsfxLooksLikeArchive(fileName)

    override fun importContent(fileName: String, data: ByteArray, subdirectory: String): JsfxImportResult {
        val strings = arrayOfNulls<String>(2)
        return importResult(JniBridge.uapmdJsfxImportContent(fileName, data, subdirectory, strings), strings)
    }

    override fun folderMirrorRoot() = JniBridge.uapmdJsfxFolderMirrorRoot()

    override fun registeredFolders(): List<JsfxRegisteredFolder> {
        val flat = JniBridge.uapmdJsfxRegisteredFolders()
        return (0 until flat.size / 2).mapNotNull { i ->
            val token = flat[i * 2] ?: return@mapNotNull null
            JsfxRegisteredFolder(token, flat[i * 2 + 1] ?: "")
        }
    }

    override fun registerFolder(token: String, name: String) = JniBridge.uapmdJsfxRegisterFolder(token, name)
    override fun unregisterFolder(token: String) = JniBridge.uapmdJsfxUnregisterFolder(token)
    override fun clearFolderMirror(name: String) = JniBridge.uapmdJsfxClearFolderMirror(name)

    override fun writeFolderMirrorFile(name: String, relativePath: String, data: ByteArray): JsfxImportResult {
        val strings = arrayOfNulls<String>(2)
        return importResult(JniBridge.uapmdJsfxWriteFolderMirrorFile(name, relativePath, data, strings), strings)
    }
}
