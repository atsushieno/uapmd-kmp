package dev.atsushieno.uapmd.cmp

// No picker on this target yet. Android needs the Storage Access Framework
// (composeApp's DocumentPicker is the reference), iOS UIDocumentPicker, and web
// the File System Access API plus uapmd's browser document provider.
actual suspend fun pickProjectFileToOpen(): String? = null
actual suspend fun pickProjectFileToSave(): String? = null
