package dev.atsushieno.uapmd_kmp

import kotlinx.coroutines.await
import kotlin.js.Promise

@JsFun("(accept) => globalThis.__uapmdWasmAdapter.pickFile(accept)")
private external fun pickFileJs(accept: String): Promise<JsString?>

actual object PlatformDocumentPicker {
    actual fun tick() {}

    actual suspend fun pickOpenPath(kind: DocumentPickerKind): DocumentPickPathResult {
        val accept = when (kind) {
            DocumentPickerKind.Project -> ".uapmdz,.uapmd"
            DocumentPickerKind.Audio   -> "audio/*,.wav,.ogg,.flac,.aiff,.mp3"
            DocumentPickerKind.Midi    -> ".mid,.midi"
        }
        val path = pickFileJs(accept).await<JsString?>()?.toString()
        return if (path != null) DocumentPickPathResult(path = path)
               else DocumentPickPathResult()
    }
}
