package dev.atsushieno.uapmd.cmp

/** Beside the source audio, which is where uapmd-app's default lands too. */
actual fun defaultStemOutputDirectory(audioFilePath: String): String =
    java.io.File(audioFilePath).absoluteFile.parent ?: System.getProperty("java.io.tmpdir")

actual fun startupImportPath(): String? = System.getProperty("uapmd.cmp.importMidi")

actual fun startupInstantiateFormat(): String? = System.getProperty("uapmd.cmp.instantiate")

actual fun startupAddTracks(): Int = System.getProperty("uapmd.cmp.addTracks")?.toIntOrNull() ?: 0

actual fun startupInstantiateCount(): Int =
    System.getProperty("uapmd.cmp.instantiateCount")?.toIntOrNull() ?: 1

actual fun startupLoadProjectPath(): String? = System.getProperty("uapmd.cmp.loadProject")

@androidx.compose.runtime.Composable
actual fun PlatformQuitBackHandler() {}

actual fun startupSaveProjectPath(): String? = System.getProperty("uapmd.cmp.saveProject")

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

actual fun startupForceRescan(): Boolean = System.getProperty("uapmd.cmp.forceRescan") == "1"
actual fun startupLoadCount(): Int = System.getProperty("uapmd.cmp.loadCount")?.toIntOrNull() ?: 1

actual fun startupShowLoadedUi(): String? = System.getProperty("uapmd.cmp.showLoadedUi")

actual fun startupPreloadPlugin(): String? = System.getProperty("uapmd.cmp.preloadPlugin")

actual fun startupShowPreloadUi(): Boolean = System.getProperty("uapmd.cmp.showPreloadUi") == "1"

actual fun startupPlaySeconds(): Int = System.getProperty("uapmd.cmp.playSeconds")?.toIntOrNull() ?: 0

actual fun startupSuppressPolling(): Boolean = System.getProperty("uapmd.cmp.noPoll") == "1"

actual fun startupRenderPath(): String? = System.getProperty("uapmd.cmp.renderTo")

actual fun startupBufferSize(): Int = System.getProperty("uapmd.cmp.bufferSize")?.toIntOrNull() ?: 0
