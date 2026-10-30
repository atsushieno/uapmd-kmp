package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.AppModel
import dev.atsushieno.uapmd.FreezeRuntimeState
import dev.atsushieno.uapmd.TimelinePosition
import dev.atsushieno.uapmd.cleanupAppModel
import dev.atsushieno.uapmd.getAppModel
import dev.atsushieno.uapmd.initJvmEventLoop
import dev.atsushieno.uapmd.instantiateAppModel
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import java.io.File
import kotlin.test.Test

/**
 * What the freeze command actually does, read back from the engine.
 *
 * FrozenTrackManager keeps policy and runtime state on separate axes and defers
 * the render while the transport is not quiet, so "nothing happened" in the UI
 * can mean queued, refused or failed. This prints the pair over time, which is
 * the only way to tell those apart from outside.
 *
 * Both tracks get the same MIDI clip: `-Duapmd.probe.smf=<file>`, or the default
 * MIDI file the other timeline tests use.
 *
 * Run with: ./gradlew :uapmd-cmp:jvmIntegrationTest --tests '*FreezeIntegrationTest' [-Duapmd.probe.smf=/path/to.mid]
 */
class FreezeIntegrationTest {
    companion object {
        private lateinit var model: AppModel

        @BeforeClass
        @JvmStatic
        fun startModel() {
            val smf = File(System.getProperty("uapmd.probe.smf") ?: defaultMidiFile().path)
            assumeTrue("MIDI file $smf exists", smf.isFile)
            initJvmEventLoop()
            instantiateAppModel()
            model = getAppModel()
            model.notifyUiReady()
            model.notifyPersistentStorageReady()
            for (t in 0..1) {
                val r = model.sequencer.engine.timeline.addMidiClipFromFile(t, TimelinePosition(0L, 0.0), smf.path)
                println("clip on track $t: id=${r.clipId} ok=${r.success} err=${r.error}")
            }
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() = cleanupAppModel()
    }

    private val engine get() = model.sequencer.engine

    private fun state(t: Int) = runCatching { engine.trackFreezeState(t) }.getOrNull()

    private fun report(label: String) {
        val cells = (0..1).joinToString("  |  ") { t ->
            val policy = runCatching { engine.trackFreezePolicy(t) }.getOrNull()
            "T$t policy=%-4s state=%-9s".format(policy, state(t))
        }
        println("%-24s %s".format(label, cells))
    }

    private fun waitFor(timeoutMs: Long, done: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (!done() && System.currentTimeMillis() - start < timeoutMs) Thread.sleep(1)
        return done()
    }

    private fun bothFrozen() =
        state(0) == FreezeRuntimeState.Frozen && state(1) == FreezeRuntimeState.Frozen

    /** Each test starts from unfrozen tracks with the engine running. */
    private fun startFromLiveTracks(check: (String, Boolean) -> Unit) {
        for (t in 0..1) engine.timeline.commands.setTrackFreezePolicyEnabled(t, false)
        model.setAudioEngineEnabled(true)
        check("audio engine is on", waitFor(5_000) { model.isAudioEngineEnabled })
        check("both tracks are live", waitFor(2_000) {
            state(0) == FreezeRuntimeState.Live && state(1) == FreezeRuntimeState.Live
        })
        report("before")
    }

    /**
     * The reported sequence: freeze, then hit play while it is still rendering, then
     * stop. FrozenTrackManager cancels the render on a playback request and keeps it
     * deferred until the transport goes quiet again; the question is whether it ever
     * restarts.
     */
    @Test
    fun aRenderInterruptedByPlayResumesAfterStop() {
        val checks = Checks()
        val check = checks::check
        startFromLiveTracks(check)

        check("freezing T0 is accepted", engine.timeline.commands.setTrackFreezePolicyEnabled(0, true))
        check("freezing T1 is accepted", engine.timeline.commands.setTrackFreezePolicyEnabled(1, true))
        check("T0 was caught mid-render", waitFor(2_000) { state(0) == FreezeRuntimeState.Rendering })
        report("rendering")

        model.transport.play()
        Thread.sleep(300)
        report("after play")
        check("the transport plays", model.transport.isPlaying)

        model.transport.stop()
        check("both tracks freeze after stop", waitFor(8_000) { bothFrozen() })
        report("after stop")
        checks.assertAllPassed()
    }

    /**
     * Freeze one track, hit play while it renders, queue another track during
     * playback, then turn the audio engine off. The engine used to resume the first
     * render and drop the queued one, because shutdown removes the callbacks that
     * would report the transport quiet (fixed in uapmd 5bdea9a9).
     */
    @Test
    fun aFreezeQueuedDuringPlaybackSurvivesEngineShutdown() {
        val checks = Checks()
        val check = checks::check
        startFromLiveTracks(check)

        check("freezing T0 is accepted", engine.timeline.commands.setTrackFreezePolicyEnabled(0, true))
        check("T0 was caught mid-render", waitFor(2_000) { state(0) == FreezeRuntimeState.Rendering })
        model.transport.play()
        // Play waits for the cancelled render to give the plugins back.
        check("the deferred play starts", waitFor(5_000) { model.transport.isPlaying })
        check("freezing T1 is accepted", engine.timeline.commands.setTrackFreezePolicyEnabled(1, true))
        report("T1 queued")

        model.setAudioEngineEnabled(false)
        // The muted shutdown drain alone may take up to 8 seconds.
        check("both tracks freeze after the engine goes off", waitFor(20_000) { bothFrozen() })
        report("after engine off")
        checks.assertAllPassed()
    }
}
