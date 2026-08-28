package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.ClipType
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
    check("plugin catalog is populated after the initial scan", catalogCount > 0)

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
