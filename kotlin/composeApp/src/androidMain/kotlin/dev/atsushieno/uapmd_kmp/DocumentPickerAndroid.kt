package dev.atsushieno.uapmd_kmp

import android.app.Activity
import android.content.Intent
import dev.atsushieno.uapmd.JniBridge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

fun initializePlatformDocumentPicker(activity: Activity) {
    JniBridge.uapmdDocumentProviderInit(activity)
}

fun platformDocumentPickerOnActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    JniBridge.uapmdDocumentProviderOnActivityResult(requestCode, resultCode, data)
}

private fun interface AndroidPickPathCallback {
    fun onResult(success: Boolean, path: String?, error: String?)
}

actual object PlatformDocumentPicker {
    private val providerHandle by lazy { JniBridge.uapmdDocumentProviderCreate() }

    actual fun tick() {
        JniBridge.uapmdDocumentProviderTick(providerHandle)
    }

    actual suspend fun pickOpenPath(kind: DocumentPickerKind): DocumentPickPathResult =
        suspendCancellableCoroutine { continuation ->
            val callback = AndroidPickPathCallback { success, path, error ->
                continuation.resume(
                    if (success && !path.isNullOrBlank())
                        DocumentPickPathResult(path = path)
                    else
                        DocumentPickPathResult(error = error)
                )
            }
            JniBridge.uapmdDocumentProviderPickOpenPath(providerHandle, kind.ordinal, callback)
        }
}
