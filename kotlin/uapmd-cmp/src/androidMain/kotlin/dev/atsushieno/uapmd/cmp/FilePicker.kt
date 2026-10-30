package dev.atsushieno.uapmd.cmp

import kotlinx.coroutines.launch

/*
 * Document picking goes through AppModel's document provider (DocumentPicking.kt).
 * On Android that provider is the Storage Access Framework, and MainActivity wires
 * it up: uapmdDocumentProviderInit(activity) before any pick, and onActivityResult
 * forwarded, since the SAF result lands on the Activity.
 */

/**
 * The app's own files directory. A SAF path is not a filesystem path the
 * separator could write next to, and external storage needs a permission this
 * app does not ask for.
 */
actual fun defaultStemOutputDirectory(audioFilePath: String): String =
    androidAppContext?.filesDir?.absolutePath ?: "/data/local/tmp"

/**
 * Set by MainActivity, for the few places that need a real filesystem path
 * (stem separation writes files; SAF gives content URIs, not paths).
 */
internal var androidAppContext: android.content.Context? = null

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
internal var androidStartupPreloadPlugin: String? = null

actual fun startupImportPath(): String? = androidStartupImportPath

actual fun startupInstantiateFormat(): String? = androidStartupInstantiateFormat

actual fun startupAddTracks(): Int = androidStartupAddTracks

actual fun startupInstantiateCount(): Int = androidStartupInstantiateCount

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

actual fun startupPreloadPlugin(): String? = androidStartupPreloadPlugin

actual fun startupShowPreloadUi(): Boolean = androidStartupPreloadPlugin != null

actual fun startupPlaySeconds(): Int = androidStartupPlaySeconds

actual fun startupSuppressPolling(): Boolean = androidStartupNoPoll

actual fun startupRenderPath(): String? = androidStartupRenderPath

actual fun startupBufferSize(): Int = androidStartupBufferSize

