package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.cleanupAppModel
import dev.atsushieno.uapmd.getAppModel
import dev.atsushieno.uapmd.initJvmEventLoop
import dev.atsushieno.uapmd.instantiateAppModel

/**
 * Headless check of the Phase 0 bootstrap (docs/uapmd-cmp-plan.md §2.1, §2.5).
 *
 * Asserts that the audio engine starts, *cleanly* stops — AppModel's shutdown is
 * asynchronous: it drains plugin tails on a worker, then deactivates plugins via
 * a task posted to the event loop — and restarts. A restart that finds audio
 * still playing is the failure `composeApp`'s setActive+stopAudio would produce.
 *
 * Run with: ./gradlew :uapmd-cmp:runBootstrapProbe
 */
fun main() {
    var failures = 0
    fun check(label: String, ok: Boolean) {
        println("${if (ok) "PASS" else "FAIL"}  $label")
        if (!ok) failures++
    }

    println("-- installing event loop")
    initJvmEventLoop()

    println("-- instantiating AppModel")
    instantiateAppModel()
    val model = getAppModel()
    model.notifyUiReady()
    model.notifyPersistentStorageReady()
    println("   sampleRate=${model.sampleRate} tracks=${model.trackCount}")

    // Borrow rather than own: AppModel destroys the real handle in cleanup.
    val seq = BorrowedRealtimeSequencer(model.sequencer)

    // ── engine on ────────────────────────────────────────────────────────────
    model.setAudioEngineEnabled(true)
    val startedAt = System.currentTimeMillis()
    while (seq.isAudioPlaying() == 0 && System.currentTimeMillis() - startedAt < 5_000)
        Thread.sleep(50)
    check("engine reports enabled", model.isAudioEngineEnabled)
    check("audio is playing after enable", seq.isAudioPlaying() != 0)

    // ── engine off (asynchronous drain + main-thread deactivation) ───────────
    model.setAudioEngineEnabled(false)
    check("engine reports disabled immediately", !model.isAudioEngineEnabled)
    val offAt = System.currentTimeMillis()
    while (seq.isAudioPlaying() != 0 && System.currentTimeMillis() - offAt < 15_000)
        Thread.sleep(50)
    val drainMs = System.currentTimeMillis() - offAt
    check("audio stopped within 15s (took ${drainMs}ms)", seq.isAudioPlaying() == 0)

    // ── engine back on ───────────────────────────────────────────────────────
    model.setAudioEngineEnabled(true)
    val restartAt = System.currentTimeMillis()
    while (seq.isAudioPlaying() == 0 && System.currentTimeMillis() - restartAt < 5_000)
        Thread.sleep(50)
    check("audio is playing after restart", seq.isAudioPlaying() != 0)
    check("engine reports enabled after restart", model.isAudioEngineEnabled)

    // ── tracks (asynchronous since 0.5.6) ────────────────────────────────────
    val tracksBefore = model.timelineTrackCount.toInt()
    var addedIndex = -1
    var addError: String? = "(callback never fired)"
    model.addTrack { index, error -> addedIndex = index; addError = error }
    val addAt = System.currentTimeMillis()
    while (addError == "(callback never fired)" && System.currentTimeMillis() - addAt < 10_000)
        Thread.sleep(50)
    check("addTrack callback fired (index=$addedIndex, error=$addError)", addError != "(callback never fired)")
    check("track count grew ($tracksBefore -> ${model.timelineTrackCount})",
        model.timelineTrackCount.toInt() == tracksBefore + 1)

    // ── history ──────────────────────────────────────────────────────────────
    val afterAdd = model.historyState
    println("   history: canUndo=${afterAdd.canUndo} undo='${afterAdd.undoDescription}' busy=${afterAdd.busy}")
    check("adding a track produced an undoable step", afterAdd.canUndo)

    var undoError: String? = "(callback never fired)"
    model.undo { error -> undoError = error }
    val undoAt = System.currentTimeMillis()
    while (undoError == "(callback never fired)" && System.currentTimeMillis() - undoAt < 10_000)
        Thread.sleep(50)
    check("undo callback fired (error=$undoError)", undoError == null)
    check("undo restored the track count", model.timelineTrackCount.toInt() == tracksBefore)
    check("redo is now available", model.historyState.canRedo)

    // ── timeline state ───────────────────────────────────────────────────────
    val tl = model.getTimelineState()
    println("   timeline: tempo=${tl?.tempo} sig=${tl?.timeSignatureNumerator}/${tl?.timeSignatureDenominator} sr=${tl?.sampleRate}")
    check("timeline state readable with a sane tempo", tl != null && tl.tempo > 0.0)

    // ── ordered teardown: engine off, then cleanup (§2.5) ────────────────────
    model.setAudioEngineEnabled(false)
    val teardownAt = System.currentTimeMillis()
    while (seq.isAudioPlaying() != 0 && System.currentTimeMillis() - teardownAt < 15_000)
        Thread.sleep(50)
    cleanupAppModel()
    println("-- cleaned up")

    println(if (failures == 0) "ALL CHECKS PASSED" else "$failures CHECK(S) FAILED")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
