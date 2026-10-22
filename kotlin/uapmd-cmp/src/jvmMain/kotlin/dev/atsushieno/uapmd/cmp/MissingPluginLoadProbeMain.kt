package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.cleanupAppModel
import dev.atsushieno.uapmd.getAppModel
import dev.atsushieno.uapmd.initJvmEventLoop
import dev.atsushieno.uapmd.instantiateAppModel
import dev.atsushieno.uapmd.prepareProjectLoad

/**
 * Loads a project whose plug-ins (JSFX effects, say) are not in the catalogue, and fails if the
 * load does not come back. Pass the project with `-Duapmd.probe.project=<file>`.
 *
 * Run with: ./gradlew :uapmd-cmp:runMissingPluginLoadProbe -Duapmd.probe.project=<file>
 */
fun main() {
    val path = System.getProperty("uapmd.probe.project") ?: error("-Duapmd.probe.project is required")
    initJvmEventLoop()
    instantiateAppModel()
    val model = getAppModel()
    model.notifyUiReady()
    model.notifyPersistentStorageReady()

    val loader = Thread {
        // An archive is unpacked first, as UapmdHost does; the timeline loads the project file.
        val prepared = prepareProjectLoad(path)
        println("prepared success=${prepared.success} error=${prepared.error} path=${prepared.path}")
        val result = model.sequencer.engine.timeline.loadProject(prepared.path)
        println("LOAD RETURNED success=${result.success} error=${result.error}")
    }.also { it.isDaemon = true; it.start() }
    loader.join(60_000)
    if (loader.isAlive) {
        println("HANG: loadProject did not return in 60s")
        loader.stackTrace.forEach { println("   at $it") }
        Runtime.getRuntime().halt(2)
    }
    cleanupAppModel()
    kotlin.system.exitProcess(0)
}
