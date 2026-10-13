package dev.atsushieno.uapmd

/** `uapmd_jsfx::JsfxDuplicateListing`: which JSFX entries a plugin list presents. */
enum class JsfxDuplicateListing(val nativeValue: Int) {
    PreferLatestVersion(0),
    HideExactDuplicates(1),
    ShowAll(2);

    companion object {
        fun fromNative(v: Int) = entries.firstOrNull { it.nativeValue == v } ?: ShowAll
    }
}

/** `uapmd_jsfx::JsfxImportResult`. */
data class JsfxImportResult(
    val success: Boolean,
    val filesWritten: UInt,
    val destination: String,
    /** Empty when [success]. */
    val error: String
)

/** `uapmd_jsfx::JsfxRegisteredFolder`. */
data class JsfxRegisteredFolder(
    /** Opaque; means something only to the [DocumentProvider] that picked the folder. */
    val token: String,
    /** The user-facing name, and the mirror's directory name. */
    val name: String
)

/**
 * `uapmd_jsfx::JsfxPluginFormat`, the format [AppModel.jsfxPluginFormat] registered.
 * Owned by the model.
 */
interface JsfxPluginFormat {
    /**
     * `searchPaths(paths, alsoUseDefaults)`: the locations searched in addition
     * to, or instead of, the platform's conventional ones. A rescan is needed
     * afterwards ([AppModel.refreshFastScannedPlugins]).
     */
    fun setSearchPaths(paths: List<String>, alsoUseDefaults: Boolean)
    val searchPaths: List<String>
    val useDefaultSearchPaths: Boolean
    /** Empty on Android, iOS and the web, which have no REAPER installation to borrow from. */
    val defaultSearchPaths: List<String>
    /** Controls only what a plugin list presents; every entry stays in the catalog. */
    var duplicateListing: JsfxDuplicateListing
}

/** The free functions of `uapmd-format-jsfx/uapmd-format-jsfx.hpp`. */
object Jsfx {
    /** False when this build does not include JSFX. */
    val isAvailable: Boolean get() = jsfxBridge.available()

    /** `setJsfxEditorFontData()`: TrueType data for the editor; copied. */
    fun setEditorFontData(data: ByteArray) = jsfxBridge.setEditorFontData(data)

    /** `jsfxUsesOwnFontBackend()`: whether [setEditorFontData] has any effect. */
    val usesOwnFontBackend: Boolean get() = jsfxBridge.usesOwnFontBackend()

    /** `jsfxUserContentDirectory()`: where imports are kept; empty where nothing is writable. */
    val userContentDirectory: String get() = jsfxBridge.userContentDirectory()

    /** `looksLikeJsfxArchive()`. */
    fun looksLikeArchive(fileName: String): Boolean = jsfxBridge.looksLikeArchive(fileName)

    /**
     * `importJsfxContent()`: copies a single file or unpacks an archive into the
     * user content directory, optionally under [subdirectory]. A rescan is needed
     * afterwards.
     */
    fun importContent(fileName: String, data: ByteArray, subdirectory: String = ""): JsfxImportResult =
        jsfxBridge.importContent(fileName, data, subdirectory)

    /** `jsfxFolderMirrorRoot()`. */
    val folderMirrorRoot: String get() = jsfxBridge.folderMirrorRoot()

    /** `jsfxRegisteredFolders()`. */
    val registeredFolders: List<JsfxRegisteredFolder> get() = jsfxBridge.registeredFolders()

    /** `registerJsfxFolder()`: the name actually used, or empty when it could not be registered. */
    fun registerFolder(token: String, name: String): String = jsfxBridge.registerFolder(token, name)

    /**
     * `unregisterJsfxFolder()`: forgets the folder and deletes its mirror. Give the
     * grant back with [DocumentProvider.releaseFolder] as well.
     */
    fun unregisterFolder(token: String) = jsfxBridge.unregisterFolder(token)

    /** `clearJsfxFolderMirror()`. */
    fun clearFolderMirror(name: String): Boolean = jsfxBridge.clearFolderMirror(name)

    /** `writeJsfxFolderMirrorFile()`. */
    fun writeFolderMirrorFile(name: String, relativePath: String, data: ByteArray): JsfxImportResult =
        jsfxBridge.writeFolderMirrorFile(name, relativePath, data)
}

internal interface JsfxBridge {
    fun available(): Boolean
    fun setEditorFontData(data: ByteArray)
    fun usesOwnFontBackend(): Boolean
    fun userContentDirectory(): String
    fun looksLikeArchive(fileName: String): Boolean
    fun importContent(fileName: String, data: ByteArray, subdirectory: String): JsfxImportResult
    fun folderMirrorRoot(): String
    fun registeredFolders(): List<JsfxRegisteredFolder>
    fun registerFolder(token: String, name: String): String
    fun unregisterFolder(token: String)
    fun clearFolderMirror(name: String): Boolean
    fun writeFolderMirrorFile(name: String, relativePath: String, data: ByteArray): JsfxImportResult
}

internal expect val jsfxBridge: JsfxBridge
