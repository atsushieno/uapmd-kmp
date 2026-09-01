package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.JniBridge
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/*
 * Android file picking goes through uapmd's own document provider, the route
 * composeApp proved: Storage Access Framework under the hood, but uapmd owns the
 * SAF handle and hands back a real path, so the engine can open it directly.
 *
 * Three things have to be in place, all wired from MainActivity:
 *  - uapmdDocumentProviderInit(activity) before any pick
 *  - onActivityResult forwarded, since the SAF result lands on the Activity
 *  - the provider ticked, which is how pending picks complete
 */

/** Mirrors the C `uapmd_document_filter` kinds the JNI layer switches on. */
private const val KIND_PROJECT = 0
private const val KIND_AUDIO = 1
private const val KIND_MIDI = 2

private fun interface PickPathCallback {
    fun onResult(success: Boolean, path: String?, error: String?)
}

internal object AndroidDocumentPicker {
    /** Created lazily: the provider needs the Activity registered first. */
    private val handle by lazy { JniBridge.uapmdDocumentProviderCreate() }

    var initialized = false
        private set

    fun init() { initialized = true }

    fun tick() {
        if (initialized) JniBridge.uapmdDocumentProviderTick(handle)
    }

    suspend fun pickOpen(kind: Int): String? = suspendCancellableCoroutine { cont ->
        if (!initialized) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        JniBridge.uapmdDocumentProviderPickOpenPath(
            handle, kind,
            PickPathCallback { success, path, _ ->
                cont.resume(if (success && !path.isNullOrBlank()) path else null)
            }
        )
    }

    suspend fun pickSave(kind: Int, defaultName: String): String? =
        suspendCancellableCoroutine { cont ->
            if (!initialized) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            JniBridge.uapmdDocumentProviderPickSavePath(
                handle, kind, defaultName,
                PickPathCallback { success, path, _ ->
                    cont.resume(if (success && !path.isNullOrBlank()) path else null)
                }
            )
        }
}

actual suspend fun pickProjectFileToOpen(): String? = AndroidDocumentPicker.pickOpen(KIND_PROJECT)

actual suspend fun pickProjectFileToSave(defaultName: String): String? =
    AndroidDocumentPicker.pickSave(KIND_PROJECT, defaultName)

/** The Storage Access Framework already chose a real destination. */
actual fun deliverSavedFile(path: String) = Unit

actual suspend fun pickMidiFileToOpen(): String? = AndroidDocumentPicker.pickOpen(KIND_MIDI)

actual suspend fun pickAudioFileToOpen(): String? = AndroidDocumentPicker.pickOpen(KIND_AUDIO)

// Set by MainActivity from launch-intent extras; see MainActivity.onCreate.
internal var androidStartupImportPath: String? = null
internal var androidStartupInstantiateFormat: String? = null
internal var androidStartupAddTracks: Int = 0
internal var androidStartupInstantiateCount: Int = 1
internal var androidStartupLoadProject: String? = null
internal var androidStartupSaveProject: String? = null
internal var androidStartupForceRescan: Boolean = false
internal var androidStartupLoadCount: Int = 1
internal var androidStartupPlaySeconds: Int = 0
internal var androidStartupNoPoll: Boolean = false
internal var androidStartupRenderPath: String? = null
internal var androidStartupBufferSize: Int = 0

actual fun startupImportPath(): String? = androidStartupImportPath

actual fun startupInstantiateFormat(): String? = androidStartupInstantiateFormat

actual fun startupAddTracks(): Int = androidStartupAddTracks

actual fun startupInstantiateCount(): Int = androidStartupInstantiateCount

actual fun tickPlatformFilePicker() = AndroidDocumentPicker.tick()

actual fun startupLoadProjectPath(): String? = androidStartupLoadProject

/**
 * Back once warns, back again within 2s quits — following AAP's own Compose host
 * (`GenericPluginManagerMain.kt:108`). The delayed `exitProcess` is the part that
 * matters here: like that host, uapmd-cmp binds out-of-process plug-in services,
 * and `finish()` alone leaves the process alive with those connections held.
 */
@androidx.compose.runtime.Composable
actual fun PlatformQuitBackHandler() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lastBackPressed = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(0L)
    }
    androidx.activity.compose.BackHandler {
        if (System.currentTimeMillis() - lastBackPressed.value < 2000) {
            (context as android.app.Activity).finish()
            // FIXME (as upstream notes): this should wait for the plug-in service
            // connections to drop rather than relying on a delay.
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch {
                kotlinx.coroutines.delay(5000)
                kotlin.system.exitProcess(0)
            }
        } else {
            android.widget.Toast
                .makeText(context, "Tap once more to quit", android.widget.Toast.LENGTH_SHORT)
                .show()
        }
        lastBackPressed.value = System.currentTimeMillis()
    }
}

actual fun startupSaveProjectPath(): String? = androidStartupSaveProject

actual fun dumpThreadStacks(): String = buildString {
    Thread.getAllStackTraces()
        .entries
        .sortedBy { it.key.name }
        .forEach { (thread, frames) ->
            if (frames.isEmpty()) return@forEach
            appendLine("--- ${thread.name} (${thread.state})")
            frames.take(24).forEach { appendLine("      at $it") }
        }
}

actual fun startupForceRescan(): Boolean = androidStartupForceRescan
actual fun startupLoadCount(): Int = androidStartupLoadCount

actual fun startupShowLoadedUi(): String? = null

actual fun startupPreloadPlugin(): String? = null

actual fun startupShowPreloadUi(): Boolean = false

actual fun startupPlaySeconds(): Int = androidStartupPlaySeconds

actual fun startupSuppressPolling(): Boolean = androidStartupNoPoll

actual fun startupRenderPath(): String? = androidStartupRenderPath

actual fun startupBufferSize(): Int = androidStartupBufferSize

/** A real filesystem: the picker chose the destination, so write straight to it. */
actual suspend fun saveProjectToPlatform(host: UapmdHost, defaultName: String): String? {
    val path = pickProjectFileToSave(defaultName) ?: return null
    host.saveProject(path)
    return host.lastProjectResult?.takeIf { !it.success }?.error
}
