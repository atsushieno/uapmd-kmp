package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.TimelinePosition
import dev.atsushieno.uapmd.cleanupAppModel
import dev.atsushieno.uapmd.getAppModel
import dev.atsushieno.uapmd.initJvmEventLoop
import dev.atsushieno.uapmd.instantiateAppModel

/**
 * What the freeze command actually does, read back from the engine.
 *
 * FrozenTrackManager keeps policy and runtime state on separate axes and defers
 * the render while the transport is not quiet, so "nothing happened" in the UI
 * can mean queued, refused or failed. This prints the pair over time, which is
 * the only way to tell those apart from outside.
 *
 * Run with: ./gradlew :uapmd-cmp:runFreezeProbe -Duapmd.probe.smf=/path/to.mid
 */
fun main() {
    initJvmEventLoop()
    instantiateAppModel()
    val model = getAppModel()
    model.notifyUiReady()
    model.notifyPersistentStorageReady()

    val engine = model.sequencer.engine
    val smf = System.getProperty("uapmd.probe.smf")
    if (smf != null) {
        for (t in 0..1) {
            val r = engine.timeline.addMidiClipFromFile(t, TimelinePosition(0L, 0.0), smf)
            println("clip on track $t: id=${r.clipId} ok=${r.success} err=${r.error}")
        }
    }

    fun report(label: String) {
        val cells = (0..1).joinToString("  |  ") { t ->
            val policy = runCatching { engine.trackFreezePolicy(t) }.getOrNull()
            val state = runCatching { engine.trackFreezeState(t) }.getOrNull()
            "T$t policy=%-4s state=%-9s".format(policy, state)
        }
        println("%-24s %s".format(label, cells))
    }

    report("before")

    // The reported sequence: freeze, then hit play while it is still rendering,
    // then stop. FrozenTrackManager cancels the render on a playback request and
    // keeps it deferred until the transport goes quiet again; the question is
    // whether it ever restarts.
    model.setAudioEngineEnabled(true)
    val onAt = System.currentTimeMillis()
    while (!model.isAudioEngineEnabled && System.currentTimeMillis() - onAt < 5_000) Thread.sleep(50)
    println("audio engine on: ${model.isAudioEngineEnabled}")

    println("freeze T0 -> ${engine.timeline.commands.setTrackFreezePolicyEnabled(0, true)}")
    println("freeze T1 -> ${engine.timeline.commands.setTrackFreezePolicyEnabled(1, true)}")
    // catch it mid-render
    var sawRendering = false
    val t0 = System.currentTimeMillis()
    while (System.currentTimeMillis() - t0 < 2_000) {
        if (runCatching { engine.trackFreezeState(0) }.getOrNull() == dev.atsushieno.uapmd.FreezeRuntimeState.Rendering) {
            sawRendering = true; break
        }
        Thread.sleep(1)
    }
    report("rendering (saw=$sawRendering)")

    println("-- play() during the render")
    model.transport.play()
    Thread.sleep(300)
    report("after play")
    println("   transport.isPlaying=${model.transport.isPlaying}")

    println("-- stop()")
    model.transport.stop()
    for (i in 1..16) {
        Thread.sleep(500)
        report("t+${i * 500}ms after stop")
    }

    println("-- does a later transport-quiet ever restart it?")
    report("final")
    cleanupAppModel()
    kotlin.system.exitProcess(0)
}
