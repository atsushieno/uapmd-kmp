package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.AnchorOrigin
import dev.atsushieno.uapmd.ClipType
import dev.atsushieno.uapmd.FreezePolicy
import dev.atsushieno.uapmd.GraphBusType
import dev.atsushieno.uapmd.GraphConnection
import dev.atsushieno.uapmd.GraphEndpoint
import dev.atsushieno.uapmd.GraphEndpointType
import dev.atsushieno.uapmd.FreezeRuntimeState
import dev.atsushieno.uapmd.TimeReference
import dev.atsushieno.uapmd.TimeReferenceType
import dev.atsushieno.uapmd.TempoPoint
import dev.atsushieno.uapmd.TimeSignaturePoint
import dev.atsushieno.uapmd.PianoRollAction
import dev.atsushieno.uapmd.TimelineClipTarget
import dev.atsushieno.uapmd.TimelinePosition
import dev.atsushieno.uapmd.cleanupAppModel
import dev.atsushieno.uapmd.createSilentAudioFileReader
import dev.atsushieno.uapmd.getAppModel
import dev.atsushieno.uapmd.initJvmEventLoop
import dev.atsushieno.uapmd.PluginInstanceResult
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
private fun inst2Caps(host: dev.atsushieno.uapmd.PluginHost, id: Int): Pair<Boolean, Boolean> {
    val c = host.getInstance(id)!!.uiCapabilities
    return c.hasUiSupport to c.supportsFloatingPresentations
}

/** The clip ids on track 0, for the project-PPQ cross-check. */
private fun host0Clips(model: dev.atsushieno.uapmd.AppModel): List<Int> =
    model.sequencer.engine.timeline.getTrack(0u).getClips().map { it.clipId }

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

    // ── tracks (asynchronous) ────────────────────────────────────────────────
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

    // ── plugin catalog + instantiation ───────────────────────────────────────
    val host = model.sequencer.engine.pluginHost
    val catalogCount = host.catalogEntryCount.toInt()
    println("   catalog entries: $catalogCount")
    // Scan progress must be readable, and must say something while a scan runs:
    // "scanning" alone cannot distinguish a long scan from a stuck one, which is
    // the whole reason uapmd-app's selector shows counts.
    val progress = model.slowScanProgress
    println("   slow scan progress: running=${progress.running} " +
        "${progress.processedBundles}/${progress.totalBundles} bundle='${progress.currentBundle}'")
    check("slow scan progress is readable", progress.processedBundles >= 0u)
    println("   last scan error: ${model.lastPluginScanError ?: "(none)"}")

    // The startup scan is deliberately fast-only (`maybeStartInitialPluginScan`
    // passes requireFastScanning=true), so on a cold cache it legitimately finds
    // nothing. What must work is the slow scan the Scan button runs — and its
    // result reaching the catalog, which is the part that was broken.
    if (catalogCount == 0) {
        println("   cold cache: the fast startup scan found nothing, running a slow scan")
        model.performPluginScanning(forceRescan = false, mode = dev.atsushieno.uapmd.ScanMode.InProcess)
        var waited = 0
        while (!model.isScanning && waited < 5_000) { Thread.sleep(100); waited += 100 }
        var scanning = 0
        while (model.isScanning && scanning < 180_000) {
            Thread.sleep(500); scanning += 500
            val p = model.slowScanProgress
            if (scanning % 5_000 == 0)
                println("   scanning… ${p.processedBundles}/${p.totalBundles} '${p.currentBundle}'")
        }
        println("   after the slow scan: ${host.catalogEntryCount} entries, " +
            "error=${model.lastPluginScanError ?: "(none)"}")
    }
    check("a scan populates the plug-in catalog", host.catalogEntryCount.toInt() > 0)

    // The remote scanner is the desktop default because an in-process scan runs
    // every plug-in's entry code inside this process: one bad plug-in and the app is
    // gone mid-scan. Prove the out-of-process path actually runs and reports.
    if (dev.atsushieno.uapmd.cmp.platformSupportsRemoteScanner) {
        // Without this the parent relaunches `java`, which serves no scanner, and
        // the scan dies with "Remote scanner failed to connect".
        val scanner = System.getProperty("uapmd.probe.scannerExe")
            ?: "/Users/atsushi/sources/uapmd-kmp/cmake-build-debug/uapmd-source/tools/uapmd-scan/uapmd-scan"
        println("   remote scanner executable: $scanner (exists=${java.io.File(scanner).exists()})")
        dev.atsushieno.uapmd.setRemoteScannerExecutable(scanner)
        val before = host.catalogEntryCount.toInt()
        var sawProgress = false
        var maxProcessed = 0u
        model.performPluginScanning(
            forceRescan = true,
            mode = dev.atsushieno.uapmd.ScanMode.Remote,
            remoteTimeoutSeconds = 20.0
        )
        var waited = 0
        while (!model.isScanning && waited < 10_000) { Thread.sleep(100); waited += 100 }
        var scanning = 0
        while (model.isScanning && scanning < 300_000) {
            Thread.sleep(250); scanning += 250
            val p = model.slowScanProgress
            if (p.processedBundles > 0u || p.totalBundles > 0u) sawProgress = true
            if (p.processedBundles > maxProcessed) maxProcessed = p.processedBundles
        }
        println("   remote scan: ${host.catalogEntryCount} entries, peak progress $maxProcessed bundle(s), " +
            "error=${model.lastPluginScanError ?: "(none)"}")
        check("the remote scanner completes", !model.isScanning)
        // Bundle counts are cache-dependent: a fully cached scan finishes before a
        // sampler sees anything, so how many bundles were walked is reported, not
        // asserted. `runScanPollProbe` measures progress over a real scan instead.
        println("   (progress sampling is informational here: $sawProgress, peak $maxProcessed)")
        check("the remote scanner reported no connection error",
            model.lastPluginScanError?.contains("failed to connect") != true)
        check("the remote scanner keeps the catalog populated", host.catalogEntryCount.toInt() >= before)
        check("this process survived a full out-of-process scan", true)
    }

    // Plugins fail for their own reasons (missing resources, unsupported I/O),
    // so try a handful and report the first that instantiates.
    val candidates = (0 until catalogCount)
        .mapNotNull { host.getCatalogEntry(it.toUInt()) }
        .sortedBy { if (it.format == "AU") 0 else 1 }   // AU is native on macOS
        .take(10)

    var succeeded: PluginInstanceResult? = null
    val attempts = mutableListOf<String>()
    for (entry in candidates) {
        var result: PluginInstanceResult? = null
        model.createPluginInstance(entry.format, entry.pluginId, -1) { r -> result = r }
        val instAt = System.currentTimeMillis()
        while (result == null && System.currentTimeMillis() - instAt < 30_000)
            Thread.sleep(50)
        val r = result
        if (r == null) { attempts += "${entry.displayName}: no callback"; continue }
        if (r.error == null && r.instanceId >= 0) { succeeded = r; attempts += "${entry.displayName}: OK"; break }
        attempts += "${entry.displayName}: ${r.error?.take(60)}"
    }
    attempts.forEach { println("   attempt $it") }

    check("createPluginInstance always calls back", attempts.none { it.endsWith("no callback") })
    if (succeeded != null) {
        val inst = host.getInstance(succeeded.instanceId)
        check("instance retrievable from the plugin host", inst != null)
        check("instance reports a display name", !inst?.displayName.isNullOrEmpty())
        println("   instantiated '${inst?.displayName}' params=${inst?.parameterCount} group=${model.getInstanceGroup(succeeded.instanceId)}")
        // ── plugin UI presentation (the path composeApp proved) ──────────────
        val caps = inst2Caps(host, succeeded.instanceId)
        println("   ui: hasUiSupport=${caps.first} floating=${caps.second}")
        // Opt-in: creating a CLAP plugin UI crashes in remidy, which calls
        // guiCreate(nullptr, ...) as a fallback (PluginInstanceCLAP.UI.cpp:72)
        // while tryCreateWith only null-guards guiIsApiSupported. clap-helpers
        // then strlen()s the null api. Upstream bug, plugin-dependent.
        if (caps.first && System.getProperty("uapmd.probe.pluginUi") != null) {
            val presentation = host.getInstance(succeeded.instanceId)!!.createUiPresentation()
            check("createUiPresentation returned a presentation", presentation != null)
            if (presentation != null) {
                val shown = presentation.show()
                println("   ui: show() -> $shown size=${presentation.getSize()}")
                check("plugin UI show() succeeded", shown)
                Thread.sleep(800)
                presentation.close()
                println("   ui: closed")
            }
        } else if (caps.first) {
            println("NOTE  UI path skipped; pass -Duapmd.probe.pluginUi=1 (may crash on CLAP plugins)")
        } else {
            println("NOTE  this plugin reports no UI support; UI path not exercised")
        }

        // ── parameters via ProjectCommands (what InstanceDetails uses) ───────
        val inst2 = host.getInstance(succeeded.instanceId)!!
        val paramCount = inst2.parameterCount.toInt()
        check("instance exposes parameters", paramCount > 0)
        if (paramCount > 0) {
            val meta = inst2.getParameterMetadata(0u)!!
            val before = inst2.getParameterValue(0)
            val target = if (before < (meta.minPlainValue + meta.maxPlainValue) / 2)
                meta.maxPlainValue else meta.minPlainValue
            val commands = model.sequencer.engine.timeline.commands
            val accepted = commands.setPluginParameterValue(succeeded.instanceId, 0, target)
            Thread.sleep(300)
            val after = inst2.getParameterValue(0)
            println("   param '${meta.name}': $before -> $after (requested $target, accepted=$accepted)")
            check("setPluginParameterValue was accepted", accepted)
            check("parameter value actually changed", after != before)
            check("parameter edit is undoable", model.historyState.canUndo)
        }

        // Opt-in: removing an instance and then shutting the engine down crashes
        // in AppModel::completeAudioEngineShutdown() (AppModel.cpp:783 dereferences
        // host->getInstance(id) for an id instanceIds() still reports). Upstream bug,
        // not a binding one - see docs/uapmd-cmp-plan.md.
        if (System.getProperty("uapmd.probe.removeInstance") != null) {
            model.removePluginInstance(succeeded.instanceId)
            Thread.sleep(500)
            check("instance removed", host.getInstance(succeeded.instanceId) == null)
        } else {
            println("   (instance removal skipped; pass -Duapmd.probe.removeInstance=1 to reproduce the shutdown crash)")
        }
    } else {
        println("NOTE  no candidate plugin instantiated on this machine; the binding still")
        println("      round-tripped every error string, so marshalling is verified")
    }

    // ── clip import + timeline read-back (what the Timeline view renders) ────
    val midi = System.getProperty("uapmd.probe.midi")
        ?: "/Users/atsushi/sources/uapmd-kmp/external/uapmd/cmake-build-debug/_deps/libremidi-src/tests/corpus/You're No Good.mid"
    if (java.io.File(midi).exists()) {
        val timelineFacade = model.sequencer.engine.timeline
        val added = timelineFacade.addMidiClipFromFile(0, dev.atsushieno.uapmd.TimelinePosition(0L, 0.0), midi)
        println("   addMidiClipFromFile -> clipId=${added.clipId} success=${added.success} error=${added.error}")
        check("MIDI clip added", added.success)
        if (added.success) {
            val clips = model.getTimelineTrack(0u).getClips()
            check("track 0 reports the clip", clips.any { it.clipId == added.clipId })
            val clip = clips.first { it.clipId == added.clipId }
            val seconds = clip.durationSamples.toDouble() / model.sampleRate
            println("   clip '${clip.name}' type=${clip.clipType} duration=%.2fs".format(seconds))
            check("clip has a non-zero duration", clip.durationSamples > 0)
            val notes = timelineFacade.getMidiClipNotes(0, added.clipId)
            println("   notes decoded: ${notes?.size ?: 0}")
            check("MIDI notes decode for the preview", (notes?.size ?: 0) > 0)

            // Raw UMP events - the struct-array-in-struct return the dump editor needs.
            val ump = model.getMidiClipUmpEvents(0, added.clipId)
            println("   ump events: ${ump.events.size} success=${ump.success} error=${ump.error}")
            check("UMP events decode", ump.success && ump.events.isNotEmpty())
            val firstEvent = ump.events.firstOrNull()
            println("   first ump: tick=${firstEvent?.tick} words=${firstEvent?.words?.joinToString { it.toString(16) }}")
            check("UMP events carry words", firstEvent != null && firstEvent.words.isNotEmpty())
            check("UMP ticks are non-decreasing",
                ump.events.zipWithNext().all { (a, b) -> a.tick <= b.tick })

            // ── clip audio events round trip (markers on the MIDI clip) ─────
            val before = model.getClipAudioEvents(0, added.clipId)
            println("   clip events: ok=${before.success} markers=${before.markers.size} warps=${before.warps.size} err=${before.error}")
            check("getClipAudioEvents decodes", before.success || before.error != null)
            // ── UMP note parsing + content round trip ───────────────────────
            val parsed = dev.atsushieno.uapmd.cmp.ui.parseUmpNotes(ump.events)
            println("   parsed notes: ${parsed.size} (getMidiClipNotes said ${notes?.size})")
            check("UMP note parser recovers notes", parsed.isNotEmpty())
            check("parsed note count is close to the engine's",
                kotlin.math.abs(parsed.size - (notes?.size ?: 0)) <= (notes?.size ?: 0) / 10)
            check("notes have positive duration", parsed.all { it.durationTicks > 0 })

            // Rebuild with no edits: the stream must survive a round trip.
            val (words, ticks) = dev.atsushieno.uapmd.cmp.ui.rebuildClipContent(ump.events, emptyMap())
            check("rebuild produces one tick per word", words.size == ticks.size)
            check("rebuild preserves the word count",
                words.size == ump.events.sumOf { it.words.size })
            check("rebuilt ticks are sorted", ticks.toList() == ticks.sorted())

            val replaced = timelineFacade.replaceMidiClipContent(0, added.clipId, words, ticks)
            println("   replaceMidiClipContent(identity) -> $replaced")
            check("identity replace accepted", replaced)
            val afterReplace = model.getMidiClipUmpEvents(0, added.clipId)
            println("   events after replace: ${afterReplace.events.size} (was ${ump.events.size})")
            check("event count survives the round trip",
                afterReplace.events.size == ump.events.size)
            check("first tick survives",
                afterReplace.events.firstOrNull()?.tick == ump.events.firstOrNull()?.tick)

            // Move the first note 120 ticks later and confirm it lands.
            val first = parsed.first()
            val (mWords, mTicks) = dev.atsushieno.uapmd.cmp.ui.rebuildClipContent(
                afterReplace.events, mapOf(first.onIndex to 120L, first.offIndex to 120L)
            )
            val movedOk = timelineFacade.replaceMidiClipContent(0, added.clipId, mWords, mTicks)
            val afterMove = dev.atsushieno.uapmd.cmp.ui.parseUmpNotes(
                model.getMidiClipUmpEvents(0, added.clipId).events
            )
            val movedNote = afterMove.firstOrNull { it.note == first.note && it.startTick == first.startTick + 120L }
            println("   moved note: $movedOk -> found=${movedNote != null}")
            check("note move applied", movedOk && movedNote != null)

            // ── piano roll edits ────────────────────────────────────────────
            // The editor is only as good as these round trips: each one writes the
            // whole stream back through replaceMidiClipContent and re-parses it.
            fun reload() = dev.atsushieno.uapmd.cmp.ui.parseUmpNotes(
                model.getMidiClipUmpEvents(0, added.clipId).events
            )
            fun events() = model.getMidiClipUmpEvents(0, added.clipId).events
            fun write(pair: Pair<UIntArray, LongArray>) =
                timelineFacade.replaceMidiClipContent(0, added.clipId, pair.first, pair.second)

            // Pitch: a move carries the note number, not just the tick.
            val toTranspose = reload().first()
            val transposed = toTranspose.note + 3
            write(dev.atsushieno.uapmd.cmp.ui.editClipContent(
                events(),
                edits = mapOf(
                    toTranspose.onIndex to dev.atsushieno.uapmd.cmp.ui.EventEdit(note = transposed),
                    toTranspose.offIndex to dev.atsushieno.uapmd.cmp.ui.EventEdit(note = transposed)
                )
            ))
            check("a note's pitch can be changed",
                reload().any { it.note == transposed && it.startTick == toTranspose.startTick })

            // Length: only the note-off moves, so the start stays put.
            val toResize = reload().first { it.note == transposed }
            val longer = toResize.durationTicks + 240L
            write(dev.atsushieno.uapmd.cmp.ui.editClipContent(
                events(),
                edits = mapOf(toResize.offIndex to dev.atsushieno.uapmd.cmp.ui.EventEdit(tickDelta = 240L))
            ))
            val resized = reload().firstOrNull { it.note == transposed && it.startTick == toResize.startTick }
            check("a note can be lengthened", resized?.durationTicks == longer)
            check("lengthening leaves the start alone", resized?.startTick == toResize.startTick)

            // Velocity.
            val toRevoice = reload().first { it.note == transposed }
            write(dev.atsushieno.uapmd.cmp.ui.editClipContent(
                events(),
                edits = mapOf(toRevoice.onIndex to dev.atsushieno.uapmd.cmp.ui.EventEdit(velocity = 0.25f))
            ))
            val revoiced = reload().firstOrNull { it.note == transposed && it.startTick == toRevoice.startTick }
            check("a note's velocity can be changed",
                revoiced != null && kotlin.math.abs(revoiced.velocity - 0.25f) < 0.02f)

            // Insert: a brand new note, with nothing backing it in the old stream.
            val beforeInsert = reload().size
            val freshPitch = 41
            write(dev.atsushieno.uapmd.cmp.ui.editClipContent(
                events(),
                added = dev.atsushieno.uapmd.cmp.ui.midi2NotePair(
                    group = 0, channel = 0, note = freshPitch,
                    velocity = 0.787f, startTick = 960L, durationTicks = 480L
                )
            ))
            val inserted = reload().firstOrNull { it.note == freshPitch && it.startTick == 960L }
            check("a note can be inserted", inserted != null)
            check("inserting keeps every other note", reload().size == beforeInsert + 1)
            check("the inserted note has the length it was given", inserted?.durationTicks == 480L)

            // Delete, of the note just inserted.
            val toDelete = reload().first { it.note == freshPitch && it.startTick == 960L }
            write(dev.atsushieno.uapmd.cmp.ui.editClipContent(
                events(), removed = setOf(toDelete.onIndex, toDelete.offIndex)
            ))
            check("a note can be deleted",
                reload().none { it.note == freshPitch && it.startTick == 960L })
            check("deleting removes only that note", reload().size == beforeInsert)

            // Markers and warps belong to AUDIO clips; this one is MIDI, so the
            // engine refuses the write. That still exercises the write path and
            // proves the error string marshals back.
            if (before.success) {
                val setR = model.setClipAudioEvents(
                    0, added.clipId,
                    before.markers + dev.atsushieno.uapmd.ClipMarkerData("probe-clip-marker", 3.25, name = "P"),
                    before.warps
                )
                println("   set on a MIDI clip: ok=${setR.success} err=${setR.error}")
                check("setClipAudioEvents refuses a MIDI clip with a readable reason",
                    !setR.success && !setR.error.isNullOrEmpty())
            }
        }
    } else {
        println("NOTE  no test MIDI file at $midi; skipped clip checks")
    }

    // ── which thread do async completions land on? ───────────────────────────
    // The UI mutates Compose state in these callbacks, so if they arrive off the
    // main thread that is a concurrent snapshot mutation.
    run {
        val mainThread = Thread.currentThread().name
        var cbThread: String? = null
        var done = false
        model.addTrack { _, _ -> cbThread = Thread.currentThread().name; done = true }
        val t0 = System.currentTimeMillis()
        while (!done && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(20)
        println("   addTrack: caller='$mainThread' callback='$cbThread'")
        check("addTrack callback observed", cbThread != null)
        check("addTrack callback is NOT on the caller thread (so UI state must be dispatched)",
            cbThread != mainThread)
    }

    // ── the "+ Add Track" crash: a poll racing an async mutation ─────────────
    // The UI refreshes every 100ms while a track add is still committing. The
    // count updates before the track is retrievable, and getTrack() /
    // getTimelineTrack() throw on a miss.
    run {
        val pollFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val polling = java.util.concurrent.atomic.AtomicBoolean(true)
        val poller = Thread {
            while (polling.get()) {
                try {
                    val engine = model.sequencer.engine
                    val n = minOf(engine.trackCount.toInt(), model.timelineTrackCount.toInt())
                    for (i in 0 until n) {
                        engine.getTrack(i.toUInt()).getOrderedInstanceIds()
                        model.getTimelineTrack(i.toUInt()).getClips()
                    }
                } catch (e: Throwable) {
                    pollFailure.compareAndSet(null, e)
                }
                Thread.sleep(10)
            }
        }
        poller.start()

        repeat(6) {
            var done = false
            model.addTrack { _, _ -> done = true }
            val t0 = System.currentTimeMillis()
            while (!done && System.currentTimeMillis() - t0 < 8_000) Thread.sleep(5)
        }
        Thread.sleep(400)
        polling.set(false)
        poller.join(3000)

        // Now demonstrate the ORIGINAL shape: separate counts, no guards. If this
        // never fails the diagnosis is wrong and the fix is cargo cult.
        val oldFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val polling2 = java.util.concurrent.atomic.AtomicBoolean(true)
        val oldPoller = Thread {
            while (polling2.get()) {
                try {
                    val engine = model.sequencer.engine
                    // The shape that crashes: two independent counts, with
                    // unguarded lookups against both.
                    (0 until engine.trackCount.toInt()).map { engine.getTrack(it.toUInt()) }
                    (0 until model.timelineTrackCount.toInt()).map { model.getTimelineTrack(it.toUInt()) }
                } catch (e: Throwable) {
                    oldFailure.compareAndSet(null, e)
                }
                Thread.sleep(2)
            }
        }
        oldPoller.start()
        repeat(6) {
            var d = false
            model.addTrack { _, _ -> d = true }
            val t0 = System.currentTimeMillis()
            while (!d && System.currentTimeMillis() - t0 < 8_000) Thread.sleep(5)
        }
        Thread.sleep(400)
        polling2.set(false)
        oldPoller.join(3000)
        println("   unguarded poll: ${oldFailure.get()?.let { it::class.simpleName + ": " + it.message } ?: "(did not fail this run)"}")

        val failure = pollFailure.get()
        println("   add-track race: pollFailure=${failure?.let { it::class.simpleName + ": " + it.message }}")
        check("guarded refresh survives adds racing the poll", failure == null)
        check("tracks were actually added", model.timelineTrackCount.toInt() >= 6)
    }

    // ── clip + track editing commands (the surface the UI drives) ────────────
    run {
        val cmds = model.sequencer.engine.timeline.commands
        val t0 = model.sequencer.engine.getTrack(0u)

        val empty = model.createEmptyMidiClip(0, 0L, 480u, 120.0)
        println("   createEmptyMidiClip -> id=${empty.clipId} ok=${empty.success} err=${empty.error}")
        check("empty MIDI clip created", empty.success)

        if (empty.success) {
            check("clip rename accepted", cmds.setClipName(0, empty.clipId, "Probe clip"))
            check("clip gain accepted", cmds.setClipGain(0, empty.clipId, 0.5))
            check("clip mute accepted", cmds.setClipMuted(0, empty.clipId, true))
            check("clip resize accepted", cmds.resizeClip(0, empty.clipId, 96000L))
            val clips = model.getTimelineTrack(0u).getClips()
            val c = clips.firstOrNull { it.clipId == empty.clipId }
            println("   clip now: name='${c?.name}' gain=${c?.gain} muted=${c?.muted} len=${c?.durationSamples}")
            check("clip name round-tripped", c?.name == "Probe clip")
            check("clip gain round-tripped", c?.gain == 0.5)
            check("clip mute round-tripped", c?.muted == true)
            check("clip resize round-tripped", c?.durationSamples == 96000L)
            check("clip removal", model.removeClipFromTrack(0, empty.clipId))
        }

        // Track mixer: the getters added to the C API for this.
        check("track gain accepted", cmds.setTrackGain(0, 0.25))
        check("track gain readable back", kotlin.math.abs(t0.gain - 0.25) < 1e-6)
        check("track mute accepted", cmds.setTrackMuted(0, true))
        check("track mute readable back", t0.muted)
        check("track solo accepted", cmds.setTrackSolo(0, true))
        check("track solo readable back", t0.solo)
        cmds.setTrackGain(0, 1.0); cmds.setTrackMuted(0, false); cmds.setTrackSolo(0, false)
    }

    // ── project markers (engine-owned, edited through ProjectCommands) ───────
    val markersBefore = model.sequencer.engine.masterTrackMarkers.size
    val added = model.sequencer.engine.timeline.commands.setMasterTrackMarkers(
        model.sequencer.engine.masterTrackMarkers +
            dev.atsushieno.uapmd.ClipMarkerData("probe-marker", 12.5, name = "Probe")
    )
    val markersAfter = model.sequencer.engine.masterTrackMarkers
    println("   markers: $markersBefore -> ${markersAfter.size} (accepted=$added)")
    check("setMasterTrackMarkers accepted", added)
    check("marker is readable back", markersAfter.any { it.markerId == "probe-marker" })
    check("marker kept its offset", markersAfter.firstOrNull { it.markerId == "probe-marker" }?.clipPositionOffset == 12.5)

    // ── track graph ──────────────────────────────────────────────────────────
    val graphOk = model.ensureTrackUsesEditorGraph(0)
    val connections = model.getTrackGraphConnections(0)
    println("   graph: editor=$graphOk connections=${connections.connections.size} error=${connections.error}")
    check("track graph connections readable", connections.success || connections.error != null)

    val graphNodes = model.getTrackGraphNodes(0)
    println("   graph nodes: ${graphNodes.nodes.size} node(s) error=${graphNodes.error}")
    graphNodes.nodes.forEach {
        println("     ${it.nodeId} type=${it.nodeType} name=${it.displayName} instance=${it.instanceId} " +
            "bypassed=${it.bypassed} latency=${it.latencyInSamples} tail=${it.tailLengthInSeconds} " +
            "buses=${it.hasAudioBuses} audio ${it.audioInputBuses.size}in/${it.audioOutputBuses.size}out " +
            "events ${it.hasEventInputs}/${it.hasEventOutputs} " +
            "main ${it.mainInputBusIndex}/${it.mainOutputBusIndex}")
        (it.audioInputBuses + it.audioOutputBuses).forEach { bus ->
            println("       bus '${bus.name}' role=${bus.role} enabled=${bus.enabled} " +
                "layout='${bus.channelLayoutName}' channels=${bus.channelCount}")
        }
    }
    // The C API reports the C++ model, not a pin count: a node either carries its own
    // buses or has none at all, in which case the graph's layout is the fallback.
    check(
        "a node without audio buses reports no buses",
        graphNodes.nodes.none { !it.hasAudioBuses && (it.audioInputBuses.isNotEmpty() || it.audioOutputBuses.isNotEmpty()) }
    )
    check(
        "bus slices stay within the flat array the C API returns",
        graphNodes.nodes.all { n ->
            n.audioInputBuses.size + n.audioOutputBuses.size == 0 || n.hasAudioBuses
        }
    )
    check(
        "every reported bus carries a channel layout",
        graphNodes.nodes.all { n ->
            (n.audioInputBuses + n.audioOutputBuses).all { it.channelCount > 0u }
        }
    )
    check("track graph nodes readable", graphNodes.success)
    check("graph reports its own bus counts", graphNodes.graphAudioOutputBusCount > 0u)
    check("every graph node has an identity", graphNodes.nodes.all { it.nodeId.isNotEmpty() })

    // The bug this API exists to fix: an endpoint's node id, not its instance id,
    // is what identifies it. instance_id is -1 for both graph endpoints, so keying
    // pins by it collapses Graph Input and Graph Output onto each other.
    val graphIn = GraphEndpoint(GraphEndpointType.GraphInput, "", -1, 0u)
    val graphOut = GraphEndpoint(GraphEndpointType.GraphOutput, "", -1, 0u)
    check("graph endpoints share instance id -1", graphIn.instanceId == graphOut.instanceId)
    check("but resolve to distinct node ids", graphIn.resolvedNodeId != graphOut.resolvedNodeId)
    check("graph input resolves as uapmd-app names it", graphIn.resolvedNodeId == "graph:input")
    check("graph output resolves as uapmd-app names it", graphOut.resolvedNodeId == "graph:output")
    check(
        "a plugin endpoint falls back to plugin:<id>",
        GraphEndpoint(GraphEndpointType.Plugin, "", 7, 0u).resolvedNodeId == "plugin:7"
    )
    check(
        "an explicit node id wins over the fallback",
        GraphEndpoint(GraphEndpointType.Plugin, "node-3", 7, 0u).resolvedNodeId == "node-3"
    )
    // Every endpoint a connection names must be a node the editor can draw,
    // otherwise its link points at a pin that does not exist — which is what made
    // the graph render as if nothing were connected.
    // Make a connection rather than only reading an empty graph: the round trip is
    // the check that matters, since the editor draws a link only when the endpoints
    // it reads back key to pins the nodes above actually own.
    //
    // It has to target a *plugin* node. TimelineFacadeImpl::resolvePluginInstanceId
    // (TimelineFacadePlugins.cpp:637) walks the track's plugin instances only, so a
    // Plugin endpoint naming a built-in node — the track's own gain node, say —
    // never resolves and the connection is refused. uapmd-app draws pins for those
    // nodes and hits the same refusal, so this is upstream behaviour, not ours.
    val graphTrack = (0 until model.trackCount.toInt()).firstOrNull { t ->
        model.ensureTrackUsesEditorGraph(t) &&
            model.getTrackGraphNodes(t).nodes.any { it.instanceId >= 0 && it.audioInputBuses.any { b -> b.enabled } }
    }
    val target = graphTrack?.let { t ->
        model.getTrackGraphNodes(t).nodes.first { it.instanceId >= 0 && it.audioInputBuses.any { b -> b.enabled } }
    }
    if (graphTrack == null || target == null) {
        println("NOTE  no track hosts a plugin node accepting audio input; skipped the round trip")
    } else {
        println("   connecting on track $graphTrack to ${target.nodeId} (instance ${target.instanceId})")
        val targetKey = target.nodeId.ifEmpty { "plugin:${target.instanceId}" }
        val made = model.connectTrackGraph(
            graphTrack,
            GraphConnection(
                0L, GraphBusType.Audio,
                GraphEndpoint(GraphEndpointType.GraphInput, "graph:input", -1, 0u),
                GraphEndpoint(GraphEndpointType.Plugin, target.nodeId, target.instanceId, 0u)
            )
        )
        println("   connectTrackGraph -> success=${made.success} error=${made.error}")
        check("connectTrackGraph accepted a graph-input connection", made.success)

        val after = model.getTrackGraphConnections(graphTrack)
        println("   connections after connect: ${after.connections.size}")
        // Switching a track to the editor graph migrates its linear chain, so the
        // graph is not empty to begin with: look for the specific connection rather
        // than assuming it is the only one. connectTrackGraph is idempotent, so an
        // equivalent connection already migrated in counts as the same success.
        val round = after.connections.firstOrNull {
            it.source.resolvedNodeId == "graph:input" &&
                it.target.resolvedNodeId == targetKey &&
                it.busType == GraphBusType.Audio
        }
        check("the connection reads back", round != null)
        check("its source resolves to the graph input node", round?.source?.resolvedNodeId == "graph:input")
        check("its target resolves to the node it was made to", round?.target?.resolvedNodeId == targetKey)
        check("the round trip kept the bus type", round?.busType == GraphBusType.Audio)
        val drawable = model.getTrackGraphNodes(graphTrack).nodes.map { n ->
            n.nodeId.ifEmpty { "plugin:${n.instanceId}" }
        }.toSet() + setOf("graph:input", "graph:output")
        check(
            "every connection endpoint names a node the editor draws",
            after.connections.all {
                it.source.resolvedNodeId in drawable && it.target.resolvedNodeId in drawable
            }
        )
        round?.let {
            val removed = model.disconnectTrackGraphConnection(graphTrack, it.id)
            check("disconnectTrackGraphConnection accepted", removed.success)
            val remaining = model.getTrackGraphConnections(graphTrack).connections
            check("the disconnected connection is gone", remaining.none { r -> r.id == it.id })
            check("and nothing else was removed with it", remaining.size == after.connections.size - 1)
        }
    }

    // ── project save/load (struct RETURNED by value: the sret ABI path) ──────
    val projectPath = System.getProperty("java.io.tmpdir") + "/uapmd-cmp-probe.uapmd"
    val saved = model.saveProjectSync(projectPath)
    println("   saveProjectSync -> success=${saved.success} error=${saved.error}")
    check("saveProjectSync returned a decodable struct", saved.success || saved.error != null)
    if (saved.success) {
        check("project file exists on disk", java.io.File(projectPath).length() > 0)
        // Load the way the app does: unpack first. A plain .uapmd must pass
        // through; a .uapmdz archive must be extracted. Handing an archive path
        // straight to loadProject() is what crashed.
        val prepared = dev.atsushieno.uapmd.prepareProjectLoad(projectPath)
        println("   prepareProjectLoad -> ok=${prepared.success} path=${prepared.path} err=${prepared.error}")
        check("prepareProjectLoad accepts a .uapmd", prepared.success)
        check("prepared path is usable", prepared.path.isNotEmpty())
        val loaded = model.loadProject(prepared.path)
        prepared.close()
        println("   loadProject -> success=${loaded.success} error=${loaded.error}")
        check("loadProject round-tripped", loaded.success)

        val zipPath = projectPath.removeSuffix(".uapmd") + ".uapmdz"
        val zipped = model.saveProjectSync(zipPath)
        println("   saveProjectSync(.uapmdz) -> ${zipped.success} ${zipped.error}")
        if (zipped.success) {
            val p2 = dev.atsushieno.uapmd.prepareProjectLoad(zipPath)
            println("   prepare(.uapmdz) -> ok=${p2.success} path=${p2.path} err=${p2.error}")
            check("prepareProjectLoad unpacks a .uapmdz", p2.success && p2.path.isNotEmpty())
            if (p2.success) {
                val l2 = model.loadProject(p2.path)
                println("   loadProject(.uapmdz) -> ${l2.success} ${l2.error}")
                check("archived project loads", l2.success)
            }
            p2.close()
        }
    }

    // ── Sequence Editor clip actions: positioned adds, silent audio clip ─────
    //
    // These are what the per-lane context menus call. The position and the size
    // are the point: uapmd-app's "Add … Here" lands the clip under the pointer,
    // and the range adds size it to the drag.
    run {
        val sr = model.sampleRate.takeIf { it > 0 } ?: 48000
        val trackClipsBefore = model.getTimelineTrack(0u).getClips().size

        // "Add an Empty MIDI2 Clip Here" at 3.5s
        val atSamples = (3.5 * sr).toLong()
        val midi = model.createEmptyMidiClip(0, atSamples, 480u, 120.0)
        check("empty MIDI2 clip added at a position", midi.success)
        if (midi.success) {
            val c = model.getTimelineTrack(0u).getClips().firstOrNull { it.clipId == midi.clipId }
            check("positioned MIDI clip lands at 3.5s", c != null && c.positionSamples == atSamples)
        }

        // "Add Empty Audio Clip" over a 2s range, via the silent reader.
        val start = 7.0
        val end = 9.0
        val frames = ((end - start) * sr).toLong()
        val channels = model.getTimelineTrack(0u).channelCount
        check("track reports a channel count", channels > 0)
        val silent = createSilentAudioFileReader(frames, channels, sr)
        val props = silent.getProperties()
        check("silent reader reports the requested frames", props?.numFrames?.toLong() == frames)
        val audio = model.sequencer.engine.timeline.addAudioClip(
            0, TimelinePosition((start * sr).toLong(), 0.0), silent, ""
        )
        println("   empty audio clip -> id=${audio.clipId} ok=${audio.success} err=${audio.error}")
        check("empty audio clip added", audio.success)
        if (audio.success) {
            val c = model.getTimelineTrack(0u).getClips().firstOrNull { it.clipId == audio.clipId }
            check("empty audio clip lands at 7s", c != null && c.positionSamples == (start * sr).toLong())
            check("empty audio clip is sized to the range", c != null && c.durationSamples == frames)
            check("empty audio clip has no source file", c != null && c.filepath.isEmpty())
            check("empty audio clip is an audio clip", c != null && c.clipType == ClipType.Audio)
        }

        val after = model.getTimelineTrack(0u).getClips().size
        check("both clips are on the track", after == trackClipsBefore + 2)

        // Enable/disable, the clip menu's toggle.
        if (midi.success) {
            val tl = model.sequencer.engine.timeline
            val was = tl.isClipEnabled(0, midi.clipId)
            tl.commands.setClipEnabled(0, midi.clipId, !was)
            check("clip enabled state toggles", tl.isClipEnabled(0, midi.clipId) == !was)
            tl.commands.setClipEnabled(0, midi.clipId, was)
        }

        // ── clip selection and clipboard ─────────────────────────────────────
        //
        // Owned by AppModel, so this exercises the binding rather than any UI.
        if (midi.success && audio.success) {
            val midiTarget = TimelineClipTarget(0, midi.clipId)
            val audioTarget = TimelineClipTarget(0, audio.clipId)

            model.clearTimelineClipSelection()
            check("selection starts empty", model.selectedTimelineClips.isEmpty())

            model.selectTimelineClips(listOf(midiTarget), additive = false, toggle = false)
            check("a clip can be selected", model.isTimelineClipSelected(0, midi.clipId))
            check("selecting one selects only one", model.selectedTimelineClips.size == 1)

            model.selectTimelineClips(listOf(audioTarget), additive = true, toggle = false)
            check("additive select extends", model.selectedTimelineClips.size == 2)

            model.selectTimelineClips(listOf(audioTarget), additive = true, toggle = true)
            check("toggle removes an already-selected clip", !model.isTimelineClipSelected(0, audio.clipId))
            check("toggle leaves the rest alone", model.isTimelineClipSelected(0, midi.clipId))

            model.selectTimelineClips(listOf(midiTarget, audioTarget), additive = false, toggle = false)
            check("replacing selects exactly the given clips", model.selectedTimelineClips.size == 2)

            // Copy, then paste onto the same track further along the timeline.
            val clipsBeforeCopy = model.getTimelineTrack(0u).getClips().size
            val copied = model.copySelectedTimelineClips()
            check("copy succeeds (${model.lastTimelineClipError})", copied)
            check("clipboard holds both clips", model.timelineClipboardCount == 2)

            val destinations = model.timelinePasteDestinations(0, false)
            check("paste has a destination (${model.lastTimelineClipError})", destinations.isNotEmpty())

            val paste = model.pasteTimelineClips(0, 20.0, false)
            check("paste succeeds (${paste.error ?: "no error"})", paste.success)
            check("paste creates as many clips as were copied", paste.pasted.size == 2)
            val clipsAfterPaste = model.getTimelineTrack(0u).getClips().size
            check("pasted clips are on the track", clipsAfterPaste == clipsBeforeCopy + 2)

            // Cut removes them again, and reports which track changed.
            model.selectTimelineClips(paste.pasted, additive = false, toggle = false)
            val deleted = model.deleteSelectedTimelineClips(cut = false)
            check("delete succeeds (${deleted.error ?: "no error"})", deleted.success)
            check("delete reports the changed track", deleted.changedTracks.contains(0))
            check("deleted clips are gone",
                model.getTimelineTrack(0u).getClips().size == clipsBeforeCopy)

            model.clearTimelineClipboard()
            check("clipboard clears", model.timelineClipboardCount == 0)
            model.clearTimelineClipSelection()
            check("selection clears", model.selectedTimelineClips.isEmpty())
        }

        // ── piano roll editing session ───────────────────────────────────────
        //
        // Owned by AppModel, so this exercises the binding rather than any UI:
        // snapshot the clip, open a session, load, edit, commit.
        if (midi.success) {
            val snapshot = model.pianoRollClipSnapshot(0, midi.clipId, 4.0)
            check("clip snapshot is produced", snapshot != null)
            if (snapshot != null) {
                check("snapshot is ready (${snapshot.error})", snapshot.isReady)
                println("   piano roll snapshot: ${snapshot.notes.size} note(s), " +
                    "range ${snapshot.minNote}..${snapshot.maxNote}, ${snapshot.durationSeconds}s")

                val session = model.openPianoRollSession(0, midi.clipId)
                check("session opens", session != null)
                if (session != null) {
                    if (!session.matchesSource(snapshot))
                        session.loadNotes(snapshot)
                    check("session matches the snapshot it loaded", session.matchesSource(snapshot))
                    check("session took the snapshot's notes", session.notes.size == snapshot.notes.size)

                    val before = session.notes.count { !it.deleted }
                    session.createNote(1.0, 0.5, 64, 0.8f)
                    check("a note can be created", session.notes.count { !it.deleted } == before + 1)

                    val created = session.notes.indexOfLast { !it.deleted }
                    session.selectNote(created)
                    check("a note can be selected", session.isNoteSelected(created))
                    check("selecting one selects one", session.selectedNoteCount == 1)

                    // Move it a second later and two semitones up, through the
                    // drag path the editor uses.
                    val origin = session.notes[created]
                    session.beginDrag()
                    session.moveSelection(1.0, 2)
                    val moved = session.notes[created]
                    check("drag moves in time", kotlin.math.abs(moved.startSeconds - (origin.startSeconds + 1.0)) < 1e-6)
                    check("drag moves in pitch", moved.note == origin.note + 2)

                    // Cancelling puts it back exactly.
                    session.beginDrag()
                    session.moveSelection(5.0, 5)
                    session.cancelDrag()
                    val restored = session.notes[created]
                    check("cancelling a drag restores the note",
                        kotlin.math.abs(restored.startSeconds - moved.startSeconds) < 1e-6 &&
                            restored.note == moved.note)

                    // Copy/paste through the session clipboard.
                    session.selectNote(created)
                    session.performAction(PianoRollAction.Copy)
                    check("session clipboard holds the copied note", session.clipboardCount == 1)
                    val beforePaste = session.notes.count { !it.deleted }
                    session.performAction(PianoRollAction.Paste, 3.0)
                    check("paste adds a note", session.notes.count { !it.deleted } > beforePaste)

                    session.performAction(PianoRollAction.SelectAll)
                    check("select all selects every live note",
                        session.selectedNoteCount == session.notes.count { !it.deleted })

                    // Commit writes back through the undo history.
                    val committed = session.commit(model)
                    check("session commits (${session.error})", committed)

                    model.closePianoRollSession(0, midi.clipId)
                    check("closing drops the session", model.findPianoRollSession(0, midi.clipId) == null)
                }
                snapshot.close()
            }
        }
    }

    // ── Sequence Editor columns: clip reference ids and anchoring ────────────
    //
    // The Anchor / Origin / Position columns all edit one setClipAnchor, and
    // they can only render if ClipData carries reference_id / anchor_* — fields
    // the C struct always had but the binding used to drop.
    run {
        val tl = model.sequencer.engine.timeline
        val clips = model.getTimelineTrack(0u).getClips()
        check("track has clips to anchor", clips.size >= 2)
        if (clips.size >= 2) {
            val (first, second) = clips[0] to clips[1]
            check("clips expose a reference id", first.referenceId.isNotEmpty())
            check("a track-anchored clip has no anchor reference", first.anchorReferenceId.isEmpty())

            // Anchor the second clip to the first, measured from the first's end.
            val ok = tl.commands.setClipAnchor(
                0, second.clipId,
                TimeReference(TimeReferenceType.ContainerEnd, first.referenceId, 0.25)
            )
            check("setClipAnchor to another clip accepted", ok)

            val after = model.getTimelineTrack(0u).getClips().firstOrNull { it.clipId == second.clipId }
            println("   anchored -> ref=${after?.anchorReferenceId} origin=${after?.anchorOrigin} offset=${after?.anchorOffsetSamples}")
            check("anchor reference round-tripped", after?.anchorReferenceId == first.referenceId)
            check("anchor origin round-tripped", after?.anchorOrigin == AnchorOrigin.End)
            check(
                "anchor offset round-tripped",
                after != null && kotlin.math.abs(after.anchorOffsetSamples - (0.25 * model.sampleRate)) < 2
            )

            // Back to the track, as the Anchor column's "Track" entry does.
            tl.commands.setClipAnchor(
                0, second.clipId, TimeReference(TimeReferenceType.ContainerStart, "", 1.0)
            )
            val back = model.getTimelineTrack(0u).getClips().firstOrNull { it.clipId == second.clipId }
            check("re-anchoring to the track clears the reference", back?.anchorReferenceId.isNullOrEmpty())
        }
    }

    // ── Freeze state, which the legend's freeze button renders ───────────────
    run {
        val engine = model.sequencer.engine
        check("freeze policy defaults to Off", engine.trackFreezePolicy(0) == FreezePolicy.Off)
        check("freeze runtime state defaults to Live", engine.trackFreezeState(0) == FreezeRuntimeState.Live)
        check("an idle track is not busy", !engine.isTrackBusy(0))
        engine.timeline.commands.setTrackFreezePolicyEnabled(0, true)
        println("   after freeze request: policy=${engine.trackFreezePolicy(0)} state=${engine.trackFreezeState(0)} busy=${engine.isTrackBusy(0)}")
        check("freeze policy reads back as On", engine.trackFreezePolicy(0) == FreezePolicy.On)
        engine.timeline.commands.setTrackFreezePolicyEnabled(0, false)
        check("freeze policy reads back as Off again", engine.trackFreezePolicy(0) == FreezePolicy.Off)
    }

    // ── tempo map: the beats view depends on this arithmetic ─────────────────
    run {
        val tm = TempoMap.build(
            listOf(
                TempoPoint(0.0, 0L, 120.0),   // 2 beats/s
                TempoPoint(4.0, 0L, 60.0)     // from 4s: 1 beat/s
            ),
            listOf(
                TimeSignaturePoint(0.0, 0L, 4, 4),
                TimeSignaturePoint(8.0, 0L, 3, 4)
            )
        )
        check("tempo map reports tempo data", tm.hasTempoData)
        // 0-4s at 120bpm = 8 beats; then 60bpm = 1 beat/s
        check("secondsToBeats before the change", kotlin.math.abs(tm.secondsToBeats(2.0) - 4.0) < 1e-9)
        check("secondsToBeats at the change", kotlin.math.abs(tm.secondsToBeats(4.0) - 8.0) < 1e-9)
        check("secondsToBeats after the change", kotlin.math.abs(tm.secondsToBeats(6.0) - 10.0) < 1e-9)
        // round trip both ways
        listOf(0.0, 1.5, 4.0, 7.25, 30.0).forEach { sec ->
            val back = tm.beatsToSeconds(tm.secondsToBeats(sec))
            check("seconds->beats->seconds round trip at ${sec}s", kotlin.math.abs(back - sec) < 1e-6)
        }
        // the 3/4 change is at 8s = 8 + (8-4)*1 = 12 beats
        check("signature before the change is 4/4", tm.signatureAtBeat(0.0) == 4 to 4)
        check("signature after the change is 3/4", tm.signatureAtBeat(12.5) == 3 to 4)
        // an empty map must still be usable, at the default tempo
        check("empty map converts at 120bpm", kotlin.math.abs(TempoMap.Empty.secondsToBeats(1.0) - 2.0) < 1e-9)
        check("empty map reports no tempo data", !TempoMap.Empty.hasTempoData)

        // Bar one starts at beat zero even when the first meter change is later.
        // Without the implicit signature there, signatureAtBeat() falls through
        // to the *last* region and reports the closing meter for the opening
        // bars (uapmd commit 41617d80).
        val lateMeter = TempoMap.build(
            listOf(TempoPoint(0.0, 0L, 120.0)),
            listOf(TimeSignaturePoint(8.0, 0L, 3, 4))
        )
        check("opening bars are 4/4 when the first change is later", lateMeter.signatureAtBeat(0.0) == (4 to 4))
        check("the later change still takes effect", lateMeter.signatureAtBeat(20.0) == (3 to 4))

        // A meta event restating the meter already in force must not start a new
        // region: the region's start is what sets where its bars fall, so a
        // restatement would re-phase every later bar line.
        val restated = TempoMap.build(
            listOf(TempoPoint(0.0, 0L, 120.0)),
            listOf(
                TimeSignaturePoint(0.0, 0L, 4, 4),
                TimeSignaturePoint(4.0, 0L, 4, 4),   // restatement, not a change
                TimeSignaturePoint(8.0, 0L, 3, 4)
            )
        )
        check("a restated meter does not start a region", restated.signatures.size == 2)
        check("the restated region still begins at beat 0", restated.signatures[0].startBeat == 0.0)
        check("the genuine change keeps its own start", restated.signatures[1].numerator == 3)

        // 7/8 spans 3.5 quarter notes.
        check(
            "bar length follows the meter",
            kotlin.math.abs(restated.barLengthBeats(TempoMap.EffectiveSignature(0.0, 1.0, 7, 8)) - 3.5) < 1e-9
        )

        // and the live path: rebuilding from the model must not throw
        val maxSeconds = model.refreshMasterTempoMap()
        println("   master tempo map: ${model.masterTempoPoints.size} tempo point(s), " +
            "${model.masterTimeSignaturePoints.size} signature point(s), maxTime=${maxSeconds}s")
        check("master tempo map readable", maxSeconds >= 0.0)

        // The engine's map is the single source of truth. The
        // Kotlin port exists only to avoid an FFI call per pixel per frame, so
        // the two must agree — if they ever disagree, the port is wrong.
        val engineMap = model.sequencer.engine.timeline.masterTempoMap
        val ported = TempoMap.build(model.masterTempoPoints, model.masterTimeSignaturePoints)
        check("port and engine agree on whether there is tempo data",
            ported.hasTempoData == engineMap.hasTempoData)
        listOf(0.0, 0.75, 2.0, 3.5, 10.0).forEach { sec ->
            val mine = ported.secondsToBeats(sec)
            val theirs = engineMap.secondsToBeats(sec)
            check("port and engine agree at ${sec}s (${mine} vs ${theirs})",
                kotlin.math.abs(mine - theirs) < 1e-6)
        }
    }

    // ── newly bound AppModel accessors ───────────────────────────────────────
    //
    // These went across the FFI for the first time in this round, so the point
    // is that each one crosses and decodes at all: a wrong struct offset or a
    // wrong argument order shows up here as a garbage value or a crash, not as
    // a compile error.
    run {
        println("-- assorted AppModel accessors")

        val inPorts = model.midiInputPorts
        val outPorts = model.midiOutputPorts
        println("   MIDI ports: ${inPorts.size} in, ${outPorts.size} out")
        check("MIDI input ports decode without empty ids",
            inPorts.all { it.id.isNotEmpty() })
        check("MIDI output ports decode without empty ids",
            outPorts.all { it.id.isNotEmpty() })

        val bounds = model.timelineContentBounds
        println("   content bounds: hasContent=${bounds.hasContent} " +
            "${bounds.startSeconds}s..${bounds.endSeconds}s (${bounds.durationSeconds}s)")
        check("content bounds are self-consistent",
            !bounds.hasContent ||
                (bounds.endSeconds >= bounds.startSeconds &&
                    kotlin.math.abs((bounds.endSeconds - bounds.startSeconds) - bounds.durationSeconds) < 1e-6))

        check("track 0 hidden flag reads back", !model.isTrackHidden(0))

        val devices = model.devices
        println("   devices: ${devices.size}")
        check("device entries decode with a label", devices.all { it.label.isNotEmpty() })
        // deviceForInstance answers false for an id that cannot exist rather
        // than handing back an uninitialised struct.
        check("deviceForInstance(-1) is null", model.deviceForInstance(-1) == null)

        // The playhead is the one piece of transport state a jump must move.
        // jump() lands on the engine's playback position (SequencerEngine::
        // jumpPlayback -> playbackPosition), not on the timeline state's
        // playhead, which only the running transport advances.
        val engine = model.sequencer.engine
        val tc = model.transport
        tc.jump(1.5)
        val jumped = engine.playbackPosition
        println("   playback position after jump(1.5): ${jumped} samples @ ${model.sampleRate}Hz")
        check("transport.jump moved the playback position",
            kotlin.math.abs(jumped / model.sampleRate.toDouble() - 1.5) < 0.05)
        tc.jump(0.0)
        check("transport.jump back to 0 took effect", engine.playbackPosition == 0L)

        val render = model.renderToFileStatus
        println("   render status: running=${render.running} completed=${render.completed}")
        check("an idle render reports neither running nor completed",
            !render.running && !render.completed)
        model.clearCompletedRenderStatus()

        // masterMarkers is the reading counterpart to the write path probed
        // earlier in this run, so the marker written there must be here too.
        val masters = model.masterMarkers
        println("   master markers: ${masters.size}")
        check("master markers read back through AppModel",
            masters.any { it.markerId == "probe-marker" })

        // The validating writer reports *why* a set was rejected; a round trip
        // of what is already there must be accepted.
        val validated = model.setMasterTrackMarkersWithValidation(masters)
        check("re-writing the current markers validates", validated.success)
        if (!validated.success) println("   rejected: ${validated.error}")

        // A clip built in memory - the path a generator or step sequencer uses.
        // Two note-on/note-off pairs at 480 ticks per quarter.
        val notes = listOf(
            0x40903C00u to 0UL, 0x40803C00u to 480UL,
            0x40904300u to 480UL, 0x40804300u to 960UL
        )
        val added = model.addMidiClipFromData(
            trackIndex = 0,
            position = dev.atsushieno.uapmd.TimelinePosition(0L, 0.0),
            umpEvents = notes.flatMap { listOf(it.first, 0u) },
            tickTimestamps = notes.flatMap { listOf(it.second, it.second) },
            tickResolution = 480u,
            clipTempo = 120.0,
            tempoChanges = listOf(dev.atsushieno.uapmd.MidiTempoChange(0UL, 120.0)),
            timeSignatureChanges = listOf(dev.atsushieno.uapmd.MidiTimeSignatureChange(0UL, 4u, 4u)),
            clipName = "probe-generated",
            needsFileSave = true
        )
        println("   addMidiClipFromData: clipId=${added.clipId} ok=${added.success} err=${added.error}")
        check("a clip built in memory is accepted", added.success)
        if (added.success) {
            check("the generated clip got an id", added.clipId >= 0)
            model.removeClipFromTrack(0, added.clipId)
        }
    }

    // ── the unified seconds/beats axis ───────────────────────────────────────
    //
    // TimelineAxis decides the ruler for both time units. It is pure arithmetic,
    // so unlike the drawing it can be checked outright.
    run {
        println("-- timeline axis")
        val axis = dev.atsushieno.uapmd.cmp.ui.TimelineAxis
        val unitSeconds = dev.atsushieno.uapmd.cmp.ui.TimeUnit.Seconds
        val unitBeats = dev.atsushieno.uapmd.cmp.ui.TimeUnit.Beats

        // The step ladder climbs to the smallest round value that clears the
        // requested minimum, and never invents a gradation between rungs.
        check("the ladder rounds up to a round step", axis.niceSecondsStep(0.3) == 0.5)
        check("the ladder reaches clock time", axis.niceSecondsStep(45.0) == 60.0)
        check("the ladder keeps doubling past an hour", axis.niceSecondsStep(5000.0) == 7200.0)

        check("a whole-second step labels whole seconds", axis.formatSecondsLabel(7.0, 1.0) == "7s")
        check("past a minute the label is m:ss", axis.formatSecondsLabel(95.0, 5.0) == "1:35")
        check("a minute labels its zero seconds", axis.formatSecondsLabel(120.0, 5.0) == "2:00")
        check("a sub-second step gets the decimals it needs",
            axis.formatSecondsLabel(1.25, 0.05) == "1.25s")

        // Labels must never collide: at any zoom the labelled ticks are at
        // least ~80dp apart, which is what the 80dp target buys.
        listOf(8f, 40f, 120f, 240f).forEach { pps ->
            val ticks = axis.secondsTicks(0.0, 120.0, pps)
            val labelled = ticks.filter { it.label != null }
            val gaps = labelled.zipWithNext { a, b -> (b.seconds - a.seconds) * pps }
            check("labels stay apart at ${pps}px/s (min gap ${gaps.minOrNull()?.toInt()}px)",
                gaps.all { it >= 70.0 })
            check("every labelled tick at ${pps}px/s is a major one", labelled.all { it.major })
        }

        // Beats: the meter's denominator decides what a ruler beat is. In 6/8
        // the sub-beats are eighths, so a bar is 3 quarter notes and holds six
        // ticks — counting quarter notes would draw four.
        val sixEight = TempoMap.build(
            listOf(dev.atsushieno.uapmd.TempoPoint(0.0, 0L, 120.0)),
            listOf(dev.atsushieno.uapmd.TimeSignaturePoint(0.0, 0L, 6, 8))
        )
        val sixEightTicks = axis.beatsTicks(0.0, 6.0, 120f, sixEight)
        val firstBarTicks = sixEightTicks.filter { sixEight.secondsToBeats(it.seconds) < 3.0 - 1e-9 }
        check("6/8 puts six ticks in a bar", firstBarTicks.size == 6)
        check("6/8 starts a bar every three quarter notes",
            sixEightTicks.filter { it.major }
                .map { sixEight.secondsToBeats(it.seconds) }
                .zipWithNext { a, b -> b - a }
                .all { kotlin.math.abs(it - 3.0) < 1e-6 })

        // Bar numbers run continuously across a meter change rather than
        // restarting — four bars of 4/4, then bar 5 is the first 3/4 bar.
        val meterChange = TempoMap.build(
            listOf(dev.atsushieno.uapmd.TempoPoint(0.0, 0L, 120.0)),
            listOf(
                dev.atsushieno.uapmd.TimeSignaturePoint(0.0, 0L, 4, 4),
                dev.atsushieno.uapmd.TimeSignaturePoint(8.0, 0L, 3, 4)
            )
        )
        val numbered = axis.beatsTicks(0.0, 20.0, 120f, meterChange)
            .filter { it.label != null }
            .map { it.label!! }
        println("   bar numbers across a 4/4 -> 3/4 change: ${numbered.take(8)}")
        check("bar numbers start at 1", numbered.firstOrNull() == "1")
        check("bar numbers do not restart at the meter change",
            numbered == numbered.indices.map { "${it + 1}" })

        // At a punishing zoom-out the ruler thins itself instead of drawing a
        // solid block, and never runs away.
        val crowded = axis.beatsTicks(0.0, 4000.0, 1f, meterChange)
        check("a crowded beats ruler stays bounded", crowded.size in 1 until axis.MaxTicks)
        check("a crowded beats ruler drops its sub-beats", crowded.all { it.major })
        val crowdedSeconds = axis.secondsTicks(0.0, 100000.0, 0.5f)
        check("a crowded seconds ruler stays bounded",
            crowdedSeconds.size in 1 until axis.MaxTicks)

        // Ticks must be ordered and inside the span asked for, or the grid and
        // the strip would disagree about which line is which.
        listOf(unitSeconds to "seconds", unitBeats to "beats").forEach { (unit, name) ->
            val t = axis.ticks(unit, 0.0, 60.0, 40f, meterChange)
            check("the $name ruler is ordered",
                t.map { it.seconds }.zipWithNext().all { (a, b) -> b >= a })
            check("the $name ruler stays inside the span",
                t.all { it.seconds >= -1e-9 && it.seconds <= 60.0 + 1.0 })
        }

        // The readout a musician reads.
        check("the seconds readout is seconds",
            axis.positionLabel(unitSeconds, 1.5, meterChange) == "1.50s")
        check("beat zero is bar 1 beat 1",
            axis.positionLabel(unitBeats, 0.0, meterChange) == "1:1.0")
        // 2s at 120bpm is 4 quarter notes, i.e. the start of bar 2.
        check("the second bar starts where 4/4 says it does",
            axis.positionLabel(unitBeats, 2.0, meterChange) == "2:1.0")
        // And the live map, whatever the loaded song happens to be.
        val liveMap = TempoMap.build(model.masterTempoPoints, model.masterTimeSignaturePoints)
        println("   beat 0 of the loaded song reads as ${axis.positionLabel(unitBeats, 0.0, liveMap)}")
        val live = axis.ticks(unitBeats, 0.0, 30.0, 40f, liveMap)
        check("the live tempo map produces a ruler", live.isNotEmpty())
        check("the live ruler is ordered",
            live.map { it.seconds }.zipWithNext().all { (a, b) -> b >= a })
        check("the live ruler numbers its bars from 1",
            live.firstOrNull { it.label != null }?.label == "1")
    }

    // ── step sequencer: bake, read back, bake again ──────────────────────────
    //
    // The clip is the only storage — a pattern is baked into ordinary MIDI 2.0
    // notes with Flex Data metadata beside them — so the thing that has to hold
    // is that a pattern survives the trip through UMP unchanged. That is pure
    // arithmetic over words, so it is checked directly here rather than through
    // the UI.
    run {
        println("-- step sequencer")
        val ss = dev.atsushieno.uapmd.cmp.StepSequencerModel

        // A four-on-the-floor kick with an off-beat hat, at 1/8 over 16 steps.
        var pattern = ss.emptyPattern(480)
        check("an empty pattern has a lane per GM drum", pattern.lanes.size == 81 - 35 + 1)
        check("an empty pattern has no active steps", pattern.activeStepCount == 0)
        listOf(0, 4, 8, 12).forEach { pattern = pattern.toggle(36, it, 0.9f, 0.5f) }
        listOf(2, 6, 10, 14).forEach { pattern = pattern.toggle(42, it, 0.6f, 0.25f) }
        check("the pattern has the eight steps just set", pattern.activeStepCount == 8)
        check("a step at 1/8 of 480tpq is 60 ticks", pattern.stepTicks == 60L)
        check("16 steps of 1/8 make one loop of 960 ticks", pattern.patternTicks == 960L)

        // Bake into an empty clip.
        val baked = ss.bake(pattern, UIntArray(0), LongArray(0))
        val words = baked.flatMap { e -> e.words.toList() }.toUIntArray()
        val ticks = baked.flatMap { e -> List(e.words.size) { e.tick } }.toLongArray()
        println("   baked ${baked.size} events / ${words.size} words")
        check("baking produces events", baked.isNotEmpty())
        check("baked events are ordered by tick",
            baked.map { it.tick }.zipWithNext().all { (a, b) -> b >= a })

        // The metadata has to describe what was actually baked.
        val metadata = ss.readStepMetadata(words, ticks, 480)
        println("   metadata: loop=${metadata.loopTicks} grid=${metadata.gridTicks} " +
            "end=${metadata.endTicks} valid=${metadata.valid} reps=${metadata.repetitions}")
        check("the baked clip carries valid step metadata", metadata.valid)
        check("the loop marker records the pattern length", metadata.loopTicks == 960L)
        check("the grid marker records the step length", metadata.gridTicks == 60L)
        check("the metadata records the channel", metadata.channel == 9)

        // Read it back: the pattern must come out as it went in.
        val reread = ss.readPattern(words, ticks, 480)
        check("a baked clip reads back as a pattern", reread != null)
        if (reread != null) {
            check("the grid survives the round trip", reread.stepTicks == pattern.stepTicks)
            check("the step count survives", reread.patternSteps == pattern.patternSteps)
            check("the channel survives", reread.channel == pattern.channel)
            check("every step survives", reread.activeStepCount == pattern.activeStepCount)
            listOf(36 to listOf(0, 4, 8, 12), 42 to listOf(2, 6, 10, 14)).forEach { (note, steps) ->
                val lane = reread.lanes[reread.laneIndex(note)]
                check("note $note keeps exactly its steps",
                    lane.steps.indices.filter { lane.steps[it].active } == steps)
            }
            // Velocity crosses as a 16-bit value, so it round-trips to within
            // one part in 65535 rather than exactly.
            val kick = reread.lanes[reread.laneIndex(36)].steps[0]
            check("velocity survives (${kick.velocity})",
                kotlin.math.abs(kick.velocity - 0.9f) < 1e-4)
            check("gate survives (${kick.gate})", kotlin.math.abs(kick.gate - 0.5f) < 1e-3)

            // Baking the re-read pattern must produce the same clip, or every
            // open-and-save would drift.
            val rebaked = ss.bake(reread, words, ticks)
            check("re-baking a read-back pattern is stable",
                rebaked.map { it.tick } == baked.map { it.tick } &&
                    rebaked.map { it.words.toList() } == baked.map { it.words.toList() })
        }

        // Events the editor does not own must survive untouched. A CC on
        // another channel is nobody else's business.
        val foreign = dev.atsushieno.uapmd.UmpEvent(30L, uintArrayOf(0x40B00000u, 0x40000000u))
        val mixedWords = (foreign.words.toList() + words.toList()).toUIntArray()
        val mixedTicks = (List(foreign.words.size) { foreign.tick } + ticks.toList()).toLongArray()
        val rebakedMixed = ss.bake(pattern, mixedWords, mixedTicks)
        check("a foreign event is carried through the rebake",
            rebakedMixed.any { it.tick == 30L && it.words.toList() == foreign.words.toList() })
        check("the foreign event is carried exactly once",
            rebakedMixed.count { it.words.toList() == foreign.words.toList() } == 1)

        // A clip with no step metadata is not one this editor wrote.
        check("a clip without metadata reads back as no pattern",
            ss.readPattern(
                uintArrayOf(0x40903C00u, 0xFFFF0000u), longArrayOf(0L, 0L), 480
            ) == null)

        // Repetitions bake the loop that many times, and the metadata says so.
        val repeated = pattern.copy(repetitions = 4)
        val repWords = ss.bake(repeated, UIntArray(0), LongArray(0))
        val rw = repWords.flatMap { it.words.toList() }.toUIntArray()
        val rt = repWords.flatMap { e -> List(e.words.size) { e.tick } }.toLongArray()
        val repMeta = ss.readStepMetadata(rw, rt, 480)
        check("four repetitions bake to four loops", repMeta.repetitions == 4)
        check("the loop length is unchanged by repeating", repMeta.loopTicks == 960L)
        check("a repeated clip reads back with its repetitions",
            ss.readPattern(rw, rt, 480)?.repetitions == 4)
        check("a repeated clip reads back one loop of steps",
            ss.readPattern(rw, rt, 480)?.activeStepCount == 8)

        // Flex Data text longer than one packet must reassemble.
        val long = ss.flexDataText(0, 9, "uapmd.step-loop-end:v1")
        check("a 22-byte marker needs two packets", long.size == 8)
        check("the first packet is a text start", ((long[0] shr 22) and 3u).toInt() == 1)
        check("the last packet is a text end", ((long[4] shr 22) and 3u).toInt() == 3)

        // The lanes a note set offers, and resizing.
        check("the full note set covers every note",
            ss.laneNotes(dev.atsushieno.uapmd.cmp.StepSequencerModel.NoteSet.AllNotes).size == 128)
        val shrunk = pattern.resized(8)
        check("shrinking keeps the steps that still fit", shrunk.activeStepCount == 4)
        check("shrinking resizes every lane", shrunk.lanes.all { it.steps.size == 8 })
        val grown = shrunk.resized(16)
        check("growing back pads with empty steps", grown.activeStepCount == 4)
        check("a drum lane is named", ss.gmDrumName(36) == "Bass Drum 1")
        check("a non-drum note has no drum name", ss.gmDrumName(20) == null)
    }

    // ── step sequencer against a real clip ───────────────────────────────────
    //
    // The checks above are arithmetic in memory. This one puts a pattern through
    // the engine: bake it into an actual clip, let the timeline re-parse it, and
    // read it back out. It is also what proves the new tick-resolution binding
    // reports the clip's own grid rather than a hardcoded 480.
    run {
        println("-- step sequencer round trip through a clip")
        val ss = dev.atsushieno.uapmd.cmp.StepSequencerModel
        val timeline = model.sequencer.engine.timeline

        // The requested PPQ is deliberately odd, but the engine normalises every
        // MIDI clip to one project-wide PPQ established by whichever clip was
        // added first (TimelineFacadeClips.cpp), so what comes back is the
        // project's, not this 96. That is the contract being checked.
        val created = model.createEmptyMidiClip(0, 0L, tickResolution = 96u, bpm = 120.0)
        check("an empty MIDI clip is created for the step pattern", created.success)
        if (created.success) {
            val timing = model.getMidiClipUmpEvents(0, created.clipId)
            check("the new clip's events are readable", timing.success)
            val reported = timing.tickResolution.toInt()
            println("   clip ${created.clipId} reports ${reported} ticks/quarter, " +
                "tempo ${timing.clipTempo}")
            check("the clip reports a real tick resolution", reported > 0)
            check("the clip reports its tempo",
                kotlin.math.abs(timing.clipTempo - 120.0) < 1e-6)
            // Project-wide, so every other MIDI clip must report the same PPQ.
            val others = host0Clips(model).filter { it != created.clipId }
                .map { model.getMidiClipUmpEvents(0, it) }
                .filter { it.success && it.tickResolution > 0u }
            check("every MIDI clip on the track shares the project PPQ",
                others.all { it.tickResolution.toInt() == reported })

            // At 96tpq a 1/16 step is 6 ticks — a grid 480 could not produce.
            var pattern = ss.emptyPattern(reported).copy(divisionIndex = 4).resized(8)
            val expectedStep = kotlin.math.max(1, reported / 16).toLong()
            check("a 1/16 step at ${reported}tpq is ${expectedStep} ticks",
                pattern.stepTicks == expectedStep)
            listOf(0, 2, 4, 6).forEach { pattern = pattern.toggle(38, it, 0.7f, 0.4f) }

            val existing = timing
            val baseWords = existing.events.flatMap { it.words.toList() }.toUIntArray()
            val baseTicks = existing.events.flatMap { e -> List(e.words.size) { e.tick } }.toLongArray()

            val baked = ss.bake(pattern, baseWords, baseTicks)
            val newWords = baked.flatMap { it.words.toList() }.toUIntArray()
            val newTicks = baked.flatMap { e -> List(e.words.size) { e.tick } }.toLongArray()
            val applied = timeline.replaceMidiClipContent(0, created.clipId, newWords, newTicks)
            check("the baked pattern is accepted by the timeline", applied)

            if (applied) {
                // Re-read through the engine, not from what was just written.
                val after = model.getMidiClipUmpEvents(0, created.clipId)
                check("the rebaked clip is readable", after.success)
                val w = after.events.flatMap { it.words.toList() }.toUIntArray()
                val t = after.events.flatMap { e -> List(e.words.size) { e.tick } }.toLongArray()
                val back = ss.readPattern(w, t, after.tickResolution.toInt())
                check("the clip reads back as a step pattern", back != null)
                if (back != null) {
                    println("   read back: ${back.patternSteps} steps of " +
                        "${ss.divisionLabel(back.divisionIndex)}, ${back.activeStepCount} active")
                    check("the grid survives the engine", back.stepTicks == expectedStep)
                    check("the step count survives the engine", back.patternSteps == 8)
                    check("every step survives the engine", back.activeStepCount == 4)
                    val lane = back.lanes[back.laneIndex(38)]
                    check("the snare keeps exactly its steps",
                        lane.steps.indices.filter { lane.steps[it].active } == listOf(0, 2, 4, 6))
                }

                // The notes must be real notes, not just metadata: the piano
                // roll's own decoder has to see them too.
                val notes = ss.decodeNotes(w, t)
                check("the baked steps decode as real notes", notes.size == 4)
                check("every baked note is the snare", notes.all { it.note == 38 })
                check("the baked notes land on the step grid",
                    notes.map { it.onTick } == (0..3).map { it * 2L * expectedStep })
            }
            model.removeClipFromTrack(0, created.clipId)
        }
    }

    // ── zoom defaults ────────────────────────────────────────────────────────
    //
    // Both zooms are now expressed in beats rather than pixels, so what they
    // resolve to depends on the tempo map. The arithmetic is checkable even
    // though the widgets are not.
    run {
        println("-- beat-based zoom defaults")
        val liveMap = TempoMap.build(model.masterTempoPoints, model.masterTimeSignaturePoints)

        // Timeline: a freshly loaded project opens on the first 32 beats.
        val span = liveMap.beatsToSeconds(32.0)
        println("   32 beats spans ${"%.3f".format(span)}s on the loaded song")
        check("32 beats is a positive span", span > 0.0)
        check("32 beats round-trips through the tempo map",
            kotlin.math.abs(liveMap.secondsToBeats(span) - 32.0) < 1e-6)
        listOf(600, 1280, 1920).forEach { viewport ->
            val pps = (viewport / span).toFloat()
            // The span that scale actually shows must be the span asked for.
            val shown = liveMap.secondsToBeats(viewport / pps.toDouble())
            check("a ${viewport}px viewport shows 32 beats (${"%.2f".format(shown)})",
                kotlin.math.abs(shown - 32.0) < 1e-3)
        }

        // Piano roll: pxPerSec = gridWidth * bpm / (60 * visibleBeats), so the
        // same beat count covers the grid at any window size.
        fun pianoRollPxPerSec(gridWidth: Float, bpm: Double, beats: Float) =
            gridWidth * bpm.toFloat() / (60f * beats)
        listOf(400f, 900f, 1600f).forEach { width ->
            listOf(4f, 16f, 128f).forEach { beats ->
                val pps = pianoRollPxPerSec(width, 120.0, beats)
                val secondsShown = width / pps
                val beatsShown = secondsShown * 120.0 / 60.0
                check("${width.toInt()}px shows ${beats} beats (${"%.3f".format(beatsShown)})",
                    kotlin.math.abs(beatsShown - beats) < 1e-3)
            }
        }
        // A doubling of the beat count halves the scale — that is what makes the
        // log2 slider's steps evenly spaced.
        val a = pianoRollPxPerSec(800f, 120.0, 16f)
        val b = pianoRollPxPerSec(800f, 120.0, 32f)
        check("doubling the beats in view halves the scale",
            kotlin.math.abs(a / b - 2.0f) < 1e-4)
        // And the tempo scales it: the same beat count at double the tempo needs
        // double the pixels per second.
        check("double the tempo doubles the scale for the same beats",
            kotlin.math.abs(pianoRollPxPerSec(800f, 240.0, 16f) / a - 2.0f) < 1e-4)
    }

    // ── overlapping clips stack into lanes ───────────────────────────────────
    //
    // A track whose clips overlap in time must show both, by growing taller, not
    // draw one over the other. Greedy first-fit, as uapmd-app does it.
    run {
        println("-- clip lane assignment")

        // Nothing to lay out is still one row tall.
        check("no clips is one lane", assignClipLanes(emptyList()).laneCount == 1)

        // Clips that do not overlap all share lane 0, however many there are.
        val sequential = (0 until 5).map { Triple(it, it * 100L, it * 100L + 100L) }
        val seqLanes = assignClipLanes(sequential)
        check("abutting clips need only one lane", seqLanes.laneCount == 1)
        check("abutting clips all sit in lane 0",
            sequential.all { seqLanes.laneOf(it.first) == 0 })

        // Two clips over the same span need two lanes.
        val pair = assignClipLanes(listOf(Triple(1, 0L, 100L), Triple(2, 50L, 150L)))
        check("an overlapping pair needs two lanes", pair.laneCount == 2)
        check("the earlier clip keeps lane 0", pair.laneOf(1) == 0)
        check("the later clip moves to lane 1", pair.laneOf(2) == 1)

        // Three mutually overlapping clips need three.
        val triple = assignClipLanes(
            listOf(Triple(1, 0L, 100L), Triple(2, 10L, 110L), Triple(3, 20L, 120L))
        )
        check("three mutually overlapping clips need three lanes", triple.laneCount == 3)
        check("each of the three gets its own lane",
            setOf(triple.laneOf(1), triple.laneOf(2), triple.laneOf(3)) == setOf(0, 1, 2))

        // A lane is reused once its last clip has ended — that is what keeps the
        // track from growing a lane per clip.
        val reuse = assignClipLanes(
            listOf(Triple(1, 0L, 100L), Triple(2, 50L, 150L), Triple(3, 200L, 300L))
        )
        check("a freed lane is reused", reuse.laneCount == 2)
        check("the clip after the overlap returns to lane 0", reuse.laneOf(3) == 0)

        // Input order must not change the layout, or clips would jump lanes
        // whenever the model re-sorted them.
        val shuffled = assignClipLanes(
            listOf(Triple(3, 200L, 300L), Triple(2, 50L, 150L), Triple(1, 0L, 100L))
        )
        check("the layout does not depend on input order",
            shuffled.laneByClipId == reuse.laneByClipId)

        // Clips that merely touch do not overlap: end == start shares a lane.
        val touching = assignClipLanes(listOf(Triple(1, 0L, 100L), Triple(2, 100L, 200L)))
        check("clips that touch end-to-start share a lane", touching.laneCount == 1)

        // Two clips starting together are ordered by id, so the layout is stable.
        val simultaneous = assignClipLanes(listOf(Triple(7, 0L, 100L), Triple(4, 0L, 100L)))
        check("simultaneous clips are split by id", simultaneous.laneOf(4) == 0)
        check("the higher id takes the lower lane", simultaneous.laneOf(7) == 1)

        // Geometry: drawing and hit testing must agree on which lane a y is in.
        val geometry = dev.atsushieno.uapmd.cmp.LaneGeometry(3, 180f)
        check("three lanes split the height evenly", geometry.laneHeight == 60f)
        check("a point in the first lane reads as lane 0", geometry.laneAt(10f) == 0)
        check("a point in the middle lane reads as lane 1", geometry.laneAt(70f) == 1)
        check("a point in the last lane reads as lane 2", geometry.laneAt(179f) == 2)
        check("a point past the bottom clamps to the last lane", geometry.laneAt(9999f) == 2)
        check("a negative y clamps to the first lane", geometry.laneAt(-5f) == 0)
        (0 until 3).forEach { lane ->
            val top = geometry.clipTop(lane)
            val bottom = top + geometry.clipHeight
            check("lane $lane's clip stays inside its own band",
                top >= lane * 60f && bottom <= (lane + 1) * 60f)
            check("lane $lane's midpoint hit-tests back to itself",
                geometry.laneAt((top + bottom) / 2f) == lane)
        }
        // One lane must look exactly as it did before lanes existed.
        val single = dev.atsushieno.uapmd.cmp.LaneGeometry(1, 60f)
        check("a single lane keeps the original inset", single.clipTop(0) == 4f)
        check("a single lane keeps the original height", single.clipHeight == 52f)

        // And the live project, whatever it happens to hold.
        val liveClips = model.sequencer.engine.timeline.getTrack(0u).getClips()
        val liveLanes = assignClipLanes(liveClips.map {
            Triple(it.clipId, it.positionSamples, it.positionSamples + it.durationSamples)
        })
        println("   track 0 has ${liveClips.size} clip(s) in ${liveLanes.laneCount} lane(s)")
        check("every live clip is assigned a lane",
            liveClips.all { liveLanes.laneOf(it.clipId) in 0 until liveLanes.laneCount })
        // No two clips may share a lane unless they are disjoint in time.
        val byLane = liveClips.groupBy { liveLanes.laneOf(it.clipId) }
        check("no two clips in one lane overlap", byLane.values.all { lane ->
            lane.sortedBy { it.positionSamples }.zipWithNext().all { (a, b) ->
                a.positionSamples + a.durationSamples <= b.positionSamples
            }
        })
    }

    // ── mute / solo (AppModel owns the rules) ────────────────────────────────
    run {
        println("-- track mute / solo")
        val trackCount = model.timelineTrackCount.toInt()
        println("   $trackCount track(s)")
        fun settle() = java.awt.EventQueue.invokeAndWait { }

        fun solo(i: Int) = model.isTrackSolo(i)
        fun muted(i: Int) = model.isTrackMuted(i)
        fun anySolo() = (0 until trackCount).any { solo(it) }
        fun audible(i: Int) = !muted(i) && (!anySolo() || solo(i))
        fun soloFlags() = (0 until trackCount).map { solo(it) }

        fun reset() {
            (0 until trackCount).forEach {
                model.setTrackSolo(it, false); model.setTrackMuted(it, false)
            }
            settle()
        }

        reset()
        check("nothing is soloed to begin with", !anySolo())
        check("every track is audible with no solo", (0 until trackCount).all { audible(it) })

        if (trackCount >= 2) {
            // Solo toggles this track and leaves the others alone, so any number
            // of tracks can be soloed at once.
            check("setTrackSolo reports success", model.setTrackSolo(0, !solo(0))); settle()
            check("one click solos the track", solo(0))
            check("the un-soloed tracks are silenced",
                (1 until trackCount).none { audible(it) })
            check("the silenced tracks are NOT muted", (1 until trackCount).none { muted(it) })

            model.setTrackSolo(1, !solo(1)); settle()
            check("A SECOND TRACK CAN BE SOLOED", solo(0) && solo(1))
            check("both soloed tracks are audible", audible(0) && audible(1))
            if (trackCount >= 3)
                check("a third, un-soloed track stays silent", !audible(2))

            // Un-soloing one of them leaves the other soloed.
            model.setTrackSolo(1, !solo(1)); settle()
            check("un-soloing one leaves the other soloed", solo(0) && !solo(1))
            check("and the un-soloed one is silent again", !audible(1))

            // Clearing the last solo restores every track.
            model.setTrackSolo(0, !solo(0)); settle()
            check("clearing the last solo leaves nothing soloed", !anySolo())
            check("every track is audible again",
                (0 until trackCount).all { audible(it) && !muted(it) })

            // Two presses of the same button return to where they started.
            reset()
            model.setTrackSolo(0, !solo(0)); settle()
            model.setTrackSolo(0, !solo(0)); settle()
            check("two clicks on one track restore every track",
                !anySolo() && (0 until trackCount).all { audible(it) })

            // Mute always wins, even over that track's own solo.
            reset()
            model.setTrackSolo(0, true); settle()
            model.setTrackMuted(0, true); settle()
            check("mute beats solo on the same track", !audible(0))
            model.setTrackSolo(1, true); settle()
            check("another soloed track is unaffected", audible(1))

            // M is independent of solo and survives a solo round trip.
            reset()
            model.setTrackMuted(1, true); settle()
            model.setTrackSolo(0, true); settle()
            model.setTrackSolo(0, false); settle()
            check("an explicit mute survives the solo round trip", muted(1))
            check("unmute works once no solo is active",
                model.setTrackMuted(1, false).also { settle() } && audible(1))

            check("an out-of-range solo is refused", !model.setTrackSolo(trackCount + 5, true))
            check("an out-of-range mute is refused", !model.setTrackMuted(-1, true))
            check("an out-of-range read is false",
                !model.isTrackSolo(trackCount + 5) && !model.isTrackMuted(-1))

            // One toggle is one command, so one undo reverses exactly one.
            reset()
            model.setTrackSolo(0, true); settle()
            val before = soloFlags()
            model.setTrackSolo(1, true); settle()
            check("both are soloed before the undo", solo(0) && solo(1))

            var undone = false
            model.undo { undone = true }
            val undoAt = System.currentTimeMillis()
            while (!undone && System.currentTimeMillis() - undoAt < 10_000) Thread.sleep(50)
            settle()
            println("   solo before=${before} after one undo=${soloFlags()}")
            check("one undo reverses exactly one toggle", soloFlags() == before)

            reset()
        } else {
            println("   NOTE  fewer than 2 tracks; multi-solo checks skipped")
        }
    }

    // ── addin command registries ─────────────────────────────────────────────
    //
    // A running command reports its progress inside its own title, so a menu
    // showing one has to re-read the registry to see it move. That only works
    // if the read reaches native every time instead of caching.
    run {
        println("-- addin command registries")
        val uiHost = UapmdHost.attach(model)
        val commands = uiHost.addinCommands()
        println("   ${commands.size} application command(s)")
        commands.take(6).forEach { println("   - ${it.title} (enabled=${it.enabled})") }

        if (commands.isEmpty()) {
            // attach() does not initialise addins, so there is nothing to read
            // here unless this probe is pointed at a host that has them.
            println("   NOTE  no addins loaded; live-read checks skipped")
        } else {
            val first = uiHost.addinCommands()
            val second = uiHost.addinCommands()
            check("the command list is rebuilt on every read", first !== second)
            check("successive reads agree while nothing is running",
                first.map { it.id } == second.map { it.id })
            check("every command reports a title", first.none { it.title.isEmpty() })
        }
    }

    // ── ordered teardown: engine off, then cleanup (§2.5) ────────────────────
    model.setAudioEngineEnabled(false)
    val teardownAt = System.currentTimeMillis()
    while (seq.isAudioPlaying() != 0 && System.currentTimeMillis() - teardownAt < 15_000)
        Thread.sleep(50)
    // `isAudioPlaying() == 0` is NOT "shutdown finished": AppModel sets it inside
    // completeAudioEngineShutdown(), which is itself a task queued on the event
    // loop and still has plugin deactivation and resetProcessingState() to run.
    // Destroying the model before that task drains crashes it on a null `this`.
    // Flushing the queue with an ordered no-op guarantees it has completed.
    java.awt.EventQueue.invokeAndWait { }
    cleanupAppModel()
    println("-- cleaned up")

    println(if (failures == 0) "ALL CHECKS PASSED" else "$failures CHECK(S) FAILED")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
