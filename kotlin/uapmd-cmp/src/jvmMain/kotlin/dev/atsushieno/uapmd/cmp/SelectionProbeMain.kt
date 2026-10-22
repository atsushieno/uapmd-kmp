package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.*

/**
 * Headless check of the project selection bindings against the real native library: the
 * service round-trips a selection (objects, all three region kinds), reports the active
 * context, notifies listeners, projects a selection onto clips/tracks/time, and the piano roll
 * session publishes its notes in its own context.
 *
 * Run with: ./gradlew :uapmd-cmp:runSelectionProbe
 */
fun main() {
    var failures = 0
    fun check(label: String, ok: Boolean) {
        println("${if (ok) "PASS" else "FAIL"}  $label")
        if (!ok) failures++
    }

    initJvmEventLoop()
    instantiateAppModel()
    val model = getAppModel()
    model.notifyUiReady()
    model.notifyPersistentStorageReady()

    val timeline = model.sequencer.engine.timeline
    var trackError: String? = "pending"
    model.addTrack { _, err -> trackError = err }
    val waitedFrom = System.currentTimeMillis()
    while (trackError == "pending" && System.currentTimeMillis() - waitedFrom < 10_000) Thread.sleep(20)
    check("track added (err=$trackError)", trackError == null)

    val clip = model.createEmptyMidiClip(0, 0L, 480u, 120.0)
    check("MIDI clip created (err=${clip.error})", clip.success)
    val clipRef = timeline.addresses.clipAddress(0, clip.clipId)?.clipReferenceId
    val trackRef = timeline.addresses.trackReferenceId(0)
    check("clip reference id resolved ($clipRef / track $trackRef)", clipRef != null && trackRef != null)
    if (clipRef == null || trackRef == null) { cleanupAppModel(); kotlin.system.exitProcess(1) }

    val svc = timeline.selections
    val ctx = svc.defaultContext
    check("default context is positive and known ($ctx)", ctx > 0 && svc.hasContext(ctx))

    // ── notifications ──────────────────────────────────────────────────────
    val changes = mutableListOf<Triple<Long, ProjectSelection, ProjectSelection>>()
    val actives = mutableListOf<Pair<Long?, Long?>>()
    val removed = mutableListOf<Long>()
    val token = svc.addListener(object : ProjectSelectionListener {
        override fun selectionChanged(context: SelectionContextId, previous: ProjectSelection, current: ProjectSelection) {
            changes += Triple(context, previous, current)
        }
        override fun activeContextChanged(previous: SelectionContextId?, current: SelectionContextId?) {
            actives += previous to current
        }
        override fun selectionContextRemoved(context: SelectionContextId) { removed += context }
    })
    check("listener registered (token=$token)", token > 0)

    // ── objects ────────────────────────────────────────────────────────────
    check("starts empty", svc.snapshot(ctx)?.isEmpty == true)
    val one = ProjectSelection(objects = listOf(clipRef))
    val r1 = svc.replace(ctx, one)
    check("replace with the clip accepted (${r1.error})", r1.success)
    check("snapshot round-trips the object", svc.snapshot(ctx)?.objects == listOf(clipRef))
    check("listener saw the change", changes.lastOrNull()?.let { it.first == ctx && it.third.objects == listOf(clipRef) } == true)

    val bogus = svc.replace(ctx, ProjectSelection(objects = listOf("no-such-object")))
    println("   replace with an unknown object -> success=${bogus.success} error='${bogus.error}' now=${svc.snapshot(ctx)}")
    check("unknown object leaves a defined state", bogus.success || bogus.error.isNotEmpty())
    check("unknown context has no snapshot", svc.snapshot(Long.MAX_VALUE) == null)

    // ── regions ────────────────────────────────────────────────────────────
    val timelineRegion = TimelineSelectionRegion(0.5, 1.5, listOf(trackRef))
    val noteRegion = MidiNoteSelectionRegion(clipRef, 0, 480, 60..72, group = 0, channel = 1)
    val laneRegion = MidiAutomationSelectionRegion(
        MidiAutomationLane(clipRef, null, group = 0, channel = 2, status = 0xB0, parameter = 7), 0, 240, 0.25..0.75
    )
    val withRegions = ProjectSelection(listOf(clipRef), listOf(timelineRegion, noteRegion, laneRegion))
    val r2 = svc.replace(ctx, withRegions)
    println("   replace with regions -> success=${r2.success} error='${r2.error}'")
    val back = svc.snapshot(ctx)
    println("   snapshot: $back")
    check("timeline region round-trips", back?.regions?.contains(timelineRegion) == true)
    check("note region round-trips", back?.regions?.contains(noteRegion) == true)
    check("automation region round-trips", back?.regions?.contains(laneRegion) == true)

    val unrestricted = TimelineSelectionRegion(2.0, 3.0, null)
    svc.replace(ctx, ProjectSelection(regions = listOf(unrestricted)))
    check("all-tracks timeline region keeps trackIds null", svc.snapshot(ctx)?.regions?.firstOrNull() == unrestricted)

    // ── projection ─────────────────────────────────────────────────────────
    val projection = svc.project(ProjectSelection(listOf(clipRef), listOf(TimelineSelectionRegion(0.0, 1.0, listOf(trackRef)))))
    println("   projection: $projection")
    check("projection mentions the clip", clipRef in projection.clipIds)
    check("projection has the time range", projection.timeRange?.let { it.start <= 0.0 && it.endInclusive >= 1.0 } == true)

    // ── contexts ───────────────────────────────────────────────────────────
    val own = svc.createContext(clipRef)
    check("created a context ($own)", own > 0 && own != ctx && svc.hasContext(own))
    check("making it active", svc.setActiveContext(own) && svc.activeContext() == own)
    check("active-context notification arrived", actives.lastOrNull()?.second == own)
    svc.replace(own, ProjectSelection(objects = listOf(clipRef)))
    check("active selection follows the active context", svc.activeSelection().objects == listOf(clipRef))
    svc.clear(own)
    check("clear empties the context", svc.snapshot(own)?.isEmpty == true)
    svc.removeContext(own)
    check("removal notified and forgets the context", own in removed && !svc.hasContext(own))
    check("unknown context cannot be activated", !svc.setActiveContext(own))

    // ── piano roll session ─────────────────────────────────────────────────
    val session = model.openPianoRollSession(0, clip.clipId)
    check("piano roll session opened", session != null)
    if (session != null) {
        session.createNote(0.0, 0.5, 64, 0.8f)
        val noteRef = session.noteReferenceId(0)
        val sessionCtx = session.selectionContext
        println("   session context=$sessionCtx clip=${session.clipReferenceId} note0=$noteRef")
        check("session has a context and a clip reference", sessionCtx != null && session.clipReferenceId == clipRef)
        check("note has a reference id", noteRef != null && session.noteReferenceId(99) == null)
        if (noteRef != null && sessionCtx != null) {
            session.selectObject(noteRef)
            check("select by reference id shows in the session", session.isObjectSelected(noteRef))
            check("and in the selection service", svc.snapshot(sessionCtx)?.objects?.contains(noteRef) == true)
            svc.clear(sessionCtx)
            session.syncSelection()
            check("sync picks up a selection cleared elsewhere", !session.isObjectSelected(noteRef))
        }
    }

    svc.removeListener(token)
    println(if (failures == 0) "ALL PASS" else "$failures FAILED")
    cleanupAppModel()
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
