package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.TimeReference
import dev.atsushieno.uapmd.TimeReferenceType
import dev.atsushieno.uapmd.TimelinePosition
import dev.atsushieno.uapmd.cleanupAppModel
import dev.atsushieno.uapmd.getAppModel
import dev.atsushieno.uapmd.initJvmEventLoop
import dev.atsushieno.uapmd.PluginInstanceResult
import dev.atsushieno.uapmd.instantiateAppModel

/**
 * Repro attempt for "moving a MIDI2 clip on a track can crash the app".
 *
 * `UapmdHost.moveClip` is `setClipAnchor(ContainerStart, offset)`, which the
 * BootstrapProbe never exercises — it covers rename/gain/mute/resize only. The
 * timeline drag calls it once per gesture end, and the UI poll reads the clip
 * list continuously while the audio thread plays the same timeline, so this
 * drives all three together rather than a move in isolation.
 *
 * Run with: ./gradlew :uapmd-cmp:runClipMoveProbe
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

    val sampleRate = model.sampleRate.takeIf { it > 0 } ?: 48000
    val cmds = model.sequencer.engine.timeline.commands

    // Put an instrument on track 0 unless told not to. In the app a track being
    // dragged on almost always has one, and a clip move re-schedules its events
    // into that live instance — a moved clip with nothing downstream skips that.
    if (System.getProperty("uapmd.probe.noPlugin") == null) {
        val pluginHost = model.sequencer.engine.pluginHost
        val catalogCount = pluginHost.catalogEntryCount.toInt()
        val candidates = (0 until catalogCount)
            .mapNotNull { pluginHost.getCatalogEntry(it.toUInt()) }
            .sortedBy { if (it.format == "AU") 0 else 1 }
            .take(8)
        var placed: PluginInstanceResult? = null
        for (entry in candidates) {
            var r: PluginInstanceResult? = null
            model.createPluginInstance(entry.format, entry.pluginId, 0) { res -> r = res }
            val at = System.currentTimeMillis()
            while (r == null && System.currentTimeMillis() - at < 30_000) Thread.sleep(50)
            val got = r
            if (got != null && got.error == null && got.instanceId >= 0) { placed = got; break }
        }
        println("   instrument on track 0: " +
            (placed?.let { pluginHost.getInstance(it.instanceId)?.displayName } ?: "(none instantiated)"))
    }

    // A populated clip where possible: the timeline decodes a clip's notes every
    // time it draws it, and every move clears that cache, so an empty clip skips
    // the whole decode path the real UI runs on each drag.
    val smf = System.getProperty("uapmd.probe.smf")
    val clip = if (smf != null) {
        val r = model.sequencer.engine.timeline
            .addMidiClipFromFile(0, TimelinePosition(0L, 0.0), smf)
        println("   imported '$smf' -> id=${r.clipId} ok=${r.success} err=${r.error}")
        r
    } else {
        model.createEmptyMidiClip(0, 0L, 480u, 120.0)
    }
    check("MIDI2 clip created (id=${clip.clipId} err=${clip.error})", clip.success)
    if (!clip.success) {
        cleanupAppModel(); kotlin.system.exitProcess(1)
    }
    val id = clip.clipId

    fun moveTo(seconds: Double): Boolean =
        cmds.setClipAnchor(0, id, TimeReference(TimeReferenceType.ContainerStart, "", seconds))

    fun positionSeconds(): Double? =
        model.getTimelineTrack(0u).getClips()
            .firstOrNull { it.clipId == id }
            ?.let { it.positionSamples.toDouble() / sampleRate }

    /** What the timeline does to draw the clip, on every frame after a move. */
    fun decodeNotes(): Int =
        model.sequencer.engine.timeline.getMidiClipNotes(0, id)?.size ?: -1

    // ── 1. a plain move, engine off ─────────────────────────────────────────
    check("move to 4.0s accepted", moveTo(4.0))
    val afterFirst = positionSeconds()
    println("   position now ${afterFirst}s")
    check("move round-tripped", afterFirst != null && kotlin.math.abs(afterFirst - 4.0) < 0.05)

    // ── 2. edge values a drag can produce ───────────────────────────────────
    check("move to 0.0 accepted", moveTo(0.0))
    check("move to a long offset accepted", moveTo(3600.0))
    check("move back to 0.0 accepted", moveTo(0.0))

    // ── 2b. NEGATIVE offsets ────────────────────────────────────────────────
    // The timeline drag coerces to >= 0, but the Clip Properties and Sequence
    // Editor position fields hand whatever was typed straight to moveClip, so a
    // negative anchor is reachable from the UI.
    println("-- negative offsets")
    for (neg in listOf(-0.001, -0.5, -1.0, -10.0, -3600.0)) {
        val accepted = moveTo(neg)
        val pos = positionSeconds()
        val notes = decodeNotes()
        println("   move to ${neg}s -> accepted=$accepted position=${pos}s notes=$notes")
    }
    check("survived negative offsets", true)
    // and back to something sane before the hammer phase
    moveTo(0.0)

    // ── 3. many moves with the clip list read in between, engine ON ─────────
    // The drag end fires one move, but the poll re-reads clips every frame and
    // the audio thread is walking the same timeline; that combination is what a
    // real drag looks like from the engine's side.
    model.setAudioEngineEnabled(true)
    val onAt = System.currentTimeMillis()
    while (!model.isAudioEngineEnabled && System.currentTimeMillis() - onAt < 5_000)
        Thread.sleep(50)
    check("engine enabled for the hammer phase", model.isAudioEngineEnabled)

    var moveFailures = 0
    var readFailures = 0
    val iterations = 400
    for (i in 0 until iterations) {
        // Sweep forward and back across the playhead, in fractional steps, the
        // way a drag does — not a tidy sequence of whole seconds.
        val target = (i % 40) * 0.37
        if (!moveTo(target)) moveFailures++
        if (positionSeconds() == null) readFailures++
        if (decodeNotes() < 0) readFailures++
    }
    println("   $iterations moves: $moveFailures rejected, $readFailures reads lost the clip")
    check("no move was rejected", moveFailures == 0)
    check("clip stayed readable throughout", readFailures == 0)

    // ── 4. move while transport is rolling ──────────────────────────────────
    model.transport.play()
    Thread.sleep(300)
    var playMoveFailures = 0
    for (i in 0 until 200) {
        if (!moveTo((i % 20) * 0.53)) playMoveFailures++
        positionSeconds()
        decodeNotes()
    }
    model.transport.stop()
    println("   200 moves while playing: $playMoveFailures rejected")
    check("no move rejected while playing", playMoveFailures == 0)

    // ── 4b. negative offset WHILE the transport rolls ───────────────────────
    // A clip whose start is before zero, being played through. Anything that
    // turns a clip-relative time into a buffer index has to cope with the
    // playhead sitting inside a clip that began before the timeline did.
    println("-- negative offsets while playing")
    model.transport.play()
    for (neg in listOf(-0.25, -1.0, -5.0, -0.001, -120.0)) {
        moveTo(neg)
        decodeNotes()
        Thread.sleep(250)   // let the audio thread actually process buffers there
        println("   played at ${neg}s -> position=${positionSeconds()}s playing=${model.transport.isPlaying}")
    }
    model.transport.stop()
    moveTo(0.0)
    check("survived playing a clip anchored before zero", true)

    // ── 5. the clip survives it all ─────────────────────────────────────────
    val finalPos = positionSeconds()
    println("   final position ${finalPos}s, notes=${decodeNotes()}")
    check("clip still present at the end", finalPos != null)

    model.setAudioEngineEnabled(false)
    val offAt = System.currentTimeMillis()
    while (model.isAudioEngineEnabled && System.currentTimeMillis() - offAt < 10_000)
        Thread.sleep(50)

    println(if (failures == 0) "ALL CHECKS PASSED" else "$failures CHECK(S) FAILED")
    cleanupAppModel()
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
