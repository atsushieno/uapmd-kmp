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
                    // exactly what refresh() used to do: two independent counts,
                    // unguarded lookups
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

        // and the live path: rebuilding from the model must not throw
        val maxSeconds = model.refreshMasterTempoMap()
        println("   master tempo map: ${model.masterTempoPoints.size} tempo point(s), " +
            "${model.masterTimeSignaturePoints.size} signature point(s), maxTime=${maxSeconds}s")
        check("master tempo map readable", maxSeconds >= 0.0)
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
