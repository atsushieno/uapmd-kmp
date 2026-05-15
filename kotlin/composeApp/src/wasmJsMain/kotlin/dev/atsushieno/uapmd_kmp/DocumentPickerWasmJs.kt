package dev.atsushieno.uapmd_kmp

actual object PlatformDocumentPicker {
    actual fun tick() {}

    actual suspend fun pickOpenPath(kind: DocumentPickerKind): DocumentPickPathResult =
        DocumentPickPathResult(error = "Document picker is not implemented for Wasm yet.")
}
