package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.ClipType
import dev.atsushieno.uapmd.cmp.ui.MasterTrackIndex
import org.junit.Assume.assumeTrue
import java.awt.EventQueue
import kotlin.math.abs
import kotlin.test.Test

/**
 * What the master track does to the rest of the UI, through [UapmdHost] as the app boots.
 *
 * Run with: ./gradlew :uapmd-cmp:jvmIntegrationTest --tests '*MasterTrackIntegrationTest'
 */
class MasterTrackIntegrationTest {
    /**
     * An edit or an undo can replace the master track's tempo data while nothing else about the
     * project changes, and both the ruler and the note previews are derived from it, as
     * uapmd-app's `TimelineEditor::renderMasterTrackRow` notes.
     */
    @Test
    fun tempoMapAndNotePreviewsFollowTheMasterClip() = withHost { host, check ->
        val archive = repoFile("external/uapmd/samples/mugene-fantasy-suite/1-das-stromreiters.uapmdz")
        assumeTrue("the sample project is checked out", archive.isFile)
        host.loadProject(archive.path)
        host.pumpUntil(60_000) { host.lastProjectResult != null && !host.isLoadingProject }
        check("the sample loads (${host.lastProjectResult?.error})", host.lastProjectResult?.success == true)

        val timeline = host.model.sequencer.engine.timeline
        val masterClip = host.masterClips.first()
        val track = host.trackClips.indexOfFirst { clips -> clips.any { it.clipType == ClipType.Midi } }
        val clip = host.trackClips[track].first { it.clipType == ClipType.Midi }
        val probeSeconds = 60.0

        fun engineBeats(): Double {
            host.model.refreshMasterTempoMap()
            return TempoMap.build(host.model.masterTempoPoints, host.model.masterTimeSignaturePoints)
                .secondsToBeats(probeSeconds)
        }

        fun checkAgreement(step: String) {
            check("$step: the ruler matches the engine (${host.tempoMap.secondsToBeats(probeSeconds)} vs ${engineBeats()})",
                abs(host.tempoMap.secondsToBeats(probeSeconds) - engineBeats()) < 1e-6)
            check("$step: the note preview matches the engine",
                host.midiNotes(track, clip.clipId) == timeline.getMidiClipNotes(track, clip.clipId))
        }

        checkAgreement("after the load")
        val loadedBeats = engineBeats()
        host.midiNotes(track, clip.clipId)

        check("the master clip is removed", host.removeClip(MasterTrackIndex, masterClip.clipId))
        host.pump(300)
        check("removing it changes the tempo (or this test proves nothing)", abs(engineBeats() - loadedBeats) > 1e-6)
        checkAgreement("after removing the master clip")
        host.midiNotes(track, clip.clipId)

        host.undo()
        host.pump(500)
        check("undo brings the tempo back", abs(engineBeats() - loadedBeats) < 1e-6)
        checkAgreement("after undo")
        host.midiNotes(track, clip.clipId)

        host.redo()
        host.pump(500)
        check("redo removes it again", abs(engineBeats() - loadedBeats) > 1e-6)
        checkAgreement("after redo")
    }

    /** The master track's M and S, which uapmd-app makes one undo step each. */
    @Test
    fun masterMuteAndSoloActOnEveryTrackAsOneUndoStep() = withHost { host, check ->
        val tracks = 3
        // One at a time: each runs on a background worker, and the engine does not take them concurrently.
        repeat(tracks) {
            val expected = host.trackCount + 1
            host.addTrack()
            host.pumpUntil(10_000) { host.refresh(); host.trackCount >= expected }
        }
        check("the tracks are there (${host.trackCount})", host.trackCount >= tracks)

        fun verify(
            label: String,
            set: (Boolean) -> Unit,
            flags: () -> List<Boolean>,
            onDescription: String,
            offDescription: String
        ) {
            set(true)
            host.pump(200)
            host.refresh()
            check("$label: all set", flags().size >= tracks && flags().all { it })
            check("$label: named in history (${host.history.undoDescription})", host.history.undoDescription == onDescription)

            host.undo()
            host.pump(300)
            check("$label: one undo clears them all", flags().none { it })

            host.redo()
            host.pump(300)
            check("$label: one redo sets them all", flags().all { it })

            set(false)
            host.pump(200)
            host.refresh()
            check("$label: all cleared", flags().none { it })
            check("$label: named in history (${host.history.undoDescription})", host.history.undoDescription == offDescription)
        }

        verify("mute", host::setAllTracksMuted, { host.trackMutedFlags }, "Mute all tracks", "Unmute all tracks")
        verify("solo", host::setAllTracksSolo, { host.trackSoloFlags }, "Solo all tracks", "Unsolo all tracks")

        // What the button does when only some are set: it is lit, so it clears them.
        host.setTrackMuted(1, true)
        check("one muted track lights the master M", host.trackMutedFlags.any { it })
        host.setAllTracksMuted(!host.trackMutedFlags.any { it })
        host.pump(200)
        host.refresh()
        check("and its click clears that track", host.trackMutedFlags.none { it })
    }
}

private fun withHost(body: (UapmdHost, (String, Boolean) -> Unit) -> Unit) {
    val checks = Checks()
    // UapmdHost.start() is the app's own bootstrap; this thread becomes the model thread.
    val host = UapmdHost.start()
    try {
        host.pump(500)
        body(host, checks::check)
    } finally {
        host.shutdown()
    }
    checks.assertAllPassed()
}

private fun UapmdHost.pump(millis: Long) {
    val until = System.currentTimeMillis() + millis
    while (System.currentTimeMillis() < until) {
        tickModelServices()
        // Everything the host posts to the UI thread has landed once this returns.
        EventQueue.invokeAndWait { }
        Thread.sleep(50)
    }
}

private fun UapmdHost.pumpUntil(limitMillis: Long, done: () -> Boolean) {
    val until = System.currentTimeMillis() + limitMillis
    while (!done() && System.currentTimeMillis() < until)
        pump(100)
    // done() can turn true partway through the UI thread's work, so let that finish.
    EventQueue.invokeAndWait { }
}
