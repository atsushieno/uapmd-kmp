package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.AppModel
import dev.atsushieno.uapmd.cleanupAppModel

/*
 * Remote scanning launches a separate process and talks to it over a local socket.
 * uapmd launches the *host's own executable* with `--scan-only --ipc-client …`,
 * which a native app can serve from its own main(). A JVM app cannot: its executable
 * is `java`, and relaunching that connects to nothing — the scan fails with "Remote
 * scanner failed to connect". uapmd's standalone `uapmd-scan` understands the same
 * arguments, so pointing the launcher at it makes out-of-process scanning work here.
 *
 * Resolution order, first hit wins:
 *   -Duapmd.cmp.scannerExe=<path>      explicit override
 *   UAPMD_SCAN_EXECUTABLE=<path>       same, from the environment
 *   uapmd-scan next to the native library, then next to the app's working directory
 *
 * When none is found we stay on the in-process scanner: a scan that runs and risks a
 * crash is better than one that cannot start at all.
 */
private val resolvedRemoteScanner: String? by lazy {
    val candidates = buildList {
        System.getProperty("uapmd.cmp.scannerExe")?.let { add(it) }
        System.getenv("UAPMD_SCAN_EXECUTABLE")?.let { add(it) }
        System.getProperty("jna.library.path")?.split(java.io.File.pathSeparator)?.forEach {
            add("$it/uapmd-scan")
        }
        add(System.getProperty("user.dir") + "/uapmd-scan")
    }
    candidates.firstOrNull { path ->
        val f = java.io.File(path)
        f.isFile && f.canExecute()
    }
}

/** VST3, AU, LV2 and CLAP bundles must be opened to be described. */
actual val platformNeedsAudioEngineForScan: Boolean = false

actual val platformNeedsSlowScan: Boolean = true

actual val platformSupportsRemoteScanner: Boolean
    get() = resolvedRemoteScanner != null

/**
 * Tells uapmd what to launch for an out-of-process scan. Safe to call more than
 * once; does nothing when no scanner was found.
 */
fun installRemoteScanner() {
    resolvedRemoteScanner?.let { dev.atsushieno.uapmd.setRemoteScannerExecutable(it) }
}

actual val platformStartsWithAudioEngineEnabled: Boolean = true

actual fun notifyPersistentStorageReadyForPlatform(model: AppModel) {
    // Before the first scan, so the initial one can already run out of process.
    installRemoteScanner()
    model.notifyPersistentStorageReady()
}

actual fun cleanupUapmdAppModel() = cleanupAppModel()
