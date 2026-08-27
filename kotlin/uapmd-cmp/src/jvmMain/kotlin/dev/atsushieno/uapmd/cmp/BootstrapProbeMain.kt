package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.cleanupAppModel
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

    // ── project save/load (struct RETURNED by value: the sret ABI path) ──────
    val projectPath = System.getProperty("java.io.tmpdir") + "/uapmd-cmp-probe.uapmd"
    val saved = model.saveProjectSync(projectPath)
    println("   saveProjectSync -> success=${saved.success} error=${saved.error}")
    check("saveProjectSync returned a decodable struct", saved.success || saved.error != null)
    if (saved.success) {
        check("project file exists on disk", java.io.File(projectPath).length() > 0)
        val loaded = model.loadProject(projectPath)
        println("   loadProject -> success=${loaded.success} error=${loaded.error}")
        check("loadProject round-tripped", loaded.success)
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
