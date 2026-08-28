package dev.atsushieno.uapmd.cmp

// No picker on this target yet. Android needs the Storage Access Framework
// (composeApp's DocumentPicker is the reference), iOS UIDocumentPicker, and web
// the File System Access API plus uapmd's browser document provider.
actual suspend fun pickProjectFileToOpen(): String? = null
actual suspend fun pickProjectFileToSave(): String? = null
actual suspend fun pickMediaFileToOpen(): String? = null

actual fun startupImportPath(): String? = null

actual fun startupInstantiateFormat(): String? = null

actual fun startupAddTracks(): Int = 0

actual fun startupInstantiateCount(): Int = 1
