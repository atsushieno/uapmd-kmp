package dev.atsushieno.uapmd_kmp

enum class DocumentPickerKind {
    Project,
    Audio,
    Midi
}

data class DocumentPickPathResult(
    val path: String? = null,
    val error: String? = null
)

expect object PlatformDocumentPicker {
    fun tick()
    suspend fun pickOpenPath(kind: DocumentPickerKind): DocumentPickPathResult
}
