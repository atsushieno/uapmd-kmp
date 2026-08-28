package dev.atsushieno.uapmd.cmp

// No picker on this target yet. Android needs the Storage Access Framework
// (composeApp's DocumentPicker is the reference), iOS UIDocumentPicker, and web
// the File System Access API plus uapmd's browser document provider.
actual suspend fun pickProjectFileToOpen(): String? = null
actual suspend fun pickProjectFileToSave(): String? = null
actual suspend fun pickMediaFileToOpen(): String? = null

// Set by MainActivity from launch-intent extras; see MainActivity.onCreate.
internal var androidStartupImportPath: String? = null
internal var androidStartupInstantiateFormat: String? = null
internal var androidStartupAddTracks: Int = 0
internal var androidStartupInstantiateCount: Int = 1

actual fun startupImportPath(): String? = androidStartupImportPath

actual fun startupInstantiateFormat(): String? = androidStartupInstantiateFormat

actual fun startupAddTracks(): Int = androidStartupAddTracks

actual fun startupInstantiateCount(): Int = androidStartupInstantiateCount
