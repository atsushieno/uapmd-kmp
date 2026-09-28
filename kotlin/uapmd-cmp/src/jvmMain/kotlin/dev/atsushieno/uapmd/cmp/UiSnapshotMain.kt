package dev.atsushieno.uapmd.cmp

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.atsushieno.uapmd.cleanupAppModel
import dev.atsushieno.uapmd.initJvmEventLoop
import dev.atsushieno.uapmd.getAppModel
import dev.atsushieno.uapmd.instantiateAppModel
import dev.atsushieno.uapmd.cmp.ui.PluginSelector
import dev.atsushieno.uapmd.cmp.ui.PluginList
import dev.atsushieno.uapmd.cmp.ui.PluginGroupMode
import dev.atsushieno.uapmd.cmp.ui.Toolbar
import dev.atsushieno.uapmd.cmp.ui.DeviceSettings
import dev.atsushieno.uapmd.cmp.ui.VirtualMidiDevicesWindow
import dev.atsushieno.uapmd.cmp.ui.Augene2Window
import dev.atsushieno.uapmd.cmp.ui.StepSequencerEditor
import dev.atsushieno.uapmd.cmp.ui.Timeline
import dev.atsushieno.uapmd.cmp.ui.InstanceDetails
import dev.atsushieno.uapmd.cmp.ui.PianoRollEditor
import dev.atsushieno.uapmd.cmp.ui.TrackGraphEditor
import dev.atsushieno.uapmd.cmp.ui.rememberFloatingWindowManager
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders the timeline to a PNG without a window or a device.
 *
 * The point is to be able to *look* at a layout change rather than assume it:
 * the Android device is usually behind its keyguard, and a clipped legend or a
 * button pushed out of view is invisible to a compile or to the headless probe.
 * `ImageComposeScene` composes and rasterises off-screen, so the same check runs
 * anywhere.
 *
 *   ./gradlew :uapmd-cmp:renderUiSnapshot \
 *       -Duapmd.cmp.snapshot=/tmp/timeline.png -Duapmd.cmp.snapshotSize=1080x900
 *
 * Widths matter here: the legend must fit its whole button set at a phone width,
 * which is exactly what regressed when it was pinned to 150dp.
 */
fun main() {
    val out = System.getProperty("uapmd.cmp.snapshot") ?: "timeline.png"
    // Default to the test device: 1080x2342 px at ~2.625x, i.e. ~411x892 dp.
    val size = System.getProperty("uapmd.cmp.snapshotSize") ?: "1080x2342"
    val width = size.substringBefore('x').toIntOrNull() ?: 1080
    val height = size.substringAfter('x').toIntOrNull() ?: 900
    // The device screenshots are at ~2.6x; match that so dp sizes read the same.
    val density = System.getProperty("uapmd.cmp.snapshotDensity")?.toFloatOrNull() ?: 2.625f

    initJvmEventLoop()
    instantiateAppModel()
    val model = getAppModel()
    model.notifyUiReady()
    model.notifyPersistentStorageReady()

    // A couple of tracks, so the legend renders its full button set.
    repeat(2) { model.addTrack { _, _ -> } }
    Thread.sleep(1500)
    java.awt.EventQueue.invokeAndWait { }

    // The graph view is only worth looking at with a plugin on the track: an empty
    // track's editor graph has no connections, so it would prove nothing about
    // whether links render.
    var graphTrack = 0
    var pianoRollClip = -1
    var stepClip = -1

    // Renders a real project rather than an empty timeline, which is the only way
    // to see a layout that depends on what the clips actually are — overlapping
    // clips stacking into lanes, say. A .uapmdz is an archive and has to be
    // extracted first; handing one straight to loadProject() crashes.
    System.getProperty("uapmd.cmp.snapshotProject")?.let { path ->
        val prepared = dev.atsushieno.uapmd.prepareProjectLoad(path)
        if (!prepared.success) {
            println("snapshot project: could not prepare '$path': ${prepared.error}")
        } else {
            val loaded = model.loadProject(prepared.path)
            println("snapshot project: $path -> success=${loaded.success} error=${loaded.error}")
            java.awt.EventQueue.invokeAndWait { }
            val timeline = model.sequencer.engine.timeline
            (0 until timeline.trackCount.toInt()).forEach { t ->
                val clips = timeline.getTrack(t.toUInt()).getClips()
                val lanes = assignClipLanes(clips.map {
                    Triple(it.clipId, it.positionSamples, it.positionSamples + it.durationSamples)
                })
                println("   track $t: ${clips.size} clip(s) -> ${lanes.laneCount} lane(s)")
            }
        }
    }

    // Where a MIDI clip's first notes sit in absolute time across a move and
    // resizes: only the move may shift them.
    if (System.getProperty("uapmd.cmp.resizeProbe") != null) {
        val timeline = model.sequencer.engine.timeline
        val sr = (model.sampleRate.takeIf { it > 0 } ?: 48000).toDouble()
        val track = (0 until timeline.trackCount.toInt()).first { t ->
            timeline.getTrack(t.toUInt()).getClips().any { it.clipType == dev.atsushieno.uapmd.ClipType.Midi }
        }
        val clipId = timeline.getTrack(track.toUInt()).getClips().first { it.clipType == dev.atsushieno.uapmd.ClipType.Midi }.clipId
        fun report(label: String) {
            val clip = timeline.getTrack(track.toUInt()).getClips().first { it.clipId == clipId }
            val start = clip.positionSamples / sr
            val content = start - clip.sourceOffsetSamples / sr
            val notes = timeline.getMidiClipNotes(track, clipId).orEmpty().sortedBy { it.startSeconds }.take(3)
            println("resize probe [$label] start=${"%.4f".format(start)} offset=${clip.sourceOffsetSamples} len=${clip.durationSamples} " +
                "notes@" + notes.joinToString { "%.4f".format(content + it.startSeconds) })
        }
        val cmds = timeline.commands
        report("loaded")
        val clip0 = timeline.getTrack(track.toUInt()).getClips().first { it.clipId == clipId }
        cmds.setClipAnchor(track, clipId, dev.atsushieno.uapmd.TimeReference(dev.atsushieno.uapmd.TimeReferenceType.ContainerStart, "", clip0.positionSamples / sr + 7.3))
        report("moved +7.3s")
        cmds.resizeClip(track, clipId, clip0.durationSamples - (sr * 2).toLong())
        report("end -2s")
        cmds.trimClipStart(track, clipId, (sr * 1.5).toLong())
        report("start +1.5s")
        cmds.trimClipStart(track, clipId, -(sr * 4).toLong())
        report("start -4s")
        // What the timing should have been all along: adding and removing a clip
        // makes uapmd re-apply the tempo map to every MIDI clip where it is now.
        val dummy = model.createEmptyMidiClip(track, (sr * 600).toLong(), tickResolution = 480u, bpm = 120.0)
        timeline.removeClip(track, dummy.clipId)
        report("tempo re-applied")
    }

    val view = System.getProperty("uapmd.cmp.snapshotView")
    // The addin windows need the addins the app starts with.
    val sceneHost = UapmdHost.attach(model)
    if (view == "vmidi" || view == "augene2" || view == "toolbar") sceneHost.initAddins()
    if (view == "steps") {
        val added = model.createEmptyMidiClip(0, 0L, tickResolution = 480u, bpm = 120.0)
        stepClip = added.clipId
        // Bake a pattern in, or the editor opens on its "this clip did not come
        // from here" confirmation instead of the grid.
        val setupHost = UapmdHost.attach(model)
        var pattern = dev.atsushieno.uapmd.cmp.StepSequencerModel
            .emptyPattern(setupHost.clipTickResolution(0, stepClip))
        listOf(0, 4, 8, 12).forEach { pattern = pattern.toggle(36, it, 0.95f, 0.5f) }
        listOf(4, 12).forEach { pattern = pattern.toggle(38, it, 0.8f, 0.4f) }
        listOf(0, 2, 4, 6, 8, 10, 12, 14).forEach { pattern = pattern.toggle(42, it, 0.45f, 0.25f) }
        val err = setupHost.applyStepPattern(0, stepClip, pattern)
        println("step sequencer snapshot: clip ${added.clipId} ok=${added.success} apply=${err ?: "ok"}")
        java.awt.EventQueue.invokeAndWait { }
    }
    // The clip grips: two butted clips, as in the layout that used to make a grab
    // at one clip's end land on its neighbour, and a clip whose start was trimmed.
    if (view == "clipgrips") {
        val sampleRate = model.sampleRate.takeIf { it > 0 } ?: 48000
        val setupHost = UapmdHost.attach(model)
        fun patternClip(track: Int, atSeconds: Double): Int {
            val added = model.createEmptyMidiClip(track, (atSeconds * sampleRate).toLong(), tickResolution = 480u, bpm = 120.0)
            var pattern = dev.atsushieno.uapmd.cmp.StepSequencerModel
                .emptyPattern(setupHost.clipTickResolution(track, added.clipId))
            (0 until 16).forEach { pattern = pattern.toggle(48 + (it * 5) % 12, it, 0.8f, 0.5f) }
            setupHost.applyStepPattern(track, added.clipId, pattern)
            return added.clipId
        }
        val first = patternClip(0, 0.0)
        val firstClip = model.sequencer.engine.timeline.getTrack(0u).getClips().first { it.clipId == first }
        patternClip(0, (firstClip.positionSamples + firstClip.durationSamples).toDouble() / sampleRate)
        val trimmed = patternClip(1, 1.0)
        val ok = model.sequencer.engine.timeline.commands.trimClipStart(1, trimmed, sampleRate / 2L)
        val after = model.sequencer.engine.timeline.getTrack(1u).getClips().first { it.clipId == trimmed }
        println("clip grips snapshot: trim ok=$ok start=${after.positionSamples} offset=${after.sourceOffsetSamples} length=${after.durationSamples}")
        // A trim has to survive a save and a reload, which starts every clip over
        // from its source's full length.
        System.getProperty("uapmd.cmp.snapshotRoundTrip")?.let { dir ->
            val file = java.io.File(dir, "clipgrips.uapmd").absolutePath
            val saved = model.saveProjectSync(file)
            val loaded = model.loadProject(file)
            java.awt.EventQueue.invokeAndWait { }
            val reloaded = model.sequencer.engine.timeline.getTrack(1u).getClips().firstOrNull()
            println("clip grips round trip: saved=${saved.success} ${saved.error ?: ""} loaded=${loaded.success} ${loaded.error ?: ""} " +
                "start=${reloaded?.positionSamples} offset=${reloaded?.sourceOffsetSamples} length=${reloaded?.durationSamples}")
        }
        java.awt.EventQueue.invokeAndWait { }
    }
    if (view == "pianoroll") {
        val midi = System.getProperty("uapmd.probe.midi")
            ?: "/Users/atsushi/sources/uapmd-kmp/external/uapmd/cmake-build-debug/_deps/" +
            "libremidi-src/tests/corpus/You're No Good.mid"
        val added = model.sequencer.engine.timeline
            .addMidiClipFromFile(0, dev.atsushieno.uapmd.TimelinePosition(0L, 0.0), midi)
        pianoRollClip = added.clipId
        println("piano roll snapshot: clip ${added.clipId} ok=${added.success} err=${added.error}")
        // What the session actually makes of the clip, so an empty-looking grid can
        // be told from a grid scrolled away from its notes.
        model.pianoRollClipSnapshot(0, added.clipId, 4.0)?.use { snap ->
            val ns = snap.notes
            println("piano roll snapshot: ready=${snap.isReady} err='${snap.error}' " +
                "notes=${ns.size} pitch=${snap.minNote}..${snap.maxNote} dur=${snap.durationSeconds}s")
            ns.take(5).forEach { n ->
                println("   note ${n.note} @ ${n.startSeconds}s for ${n.durationSeconds}s vel=${n.velocity}")
            }
            if (ns.isNotEmpty())
                println("   start range ${ns.minOf { it.startSeconds }}..${ns.maxOf { it.startSeconds }}s")
        }
        java.awt.EventQueue.invokeAndWait { }
    }
    if (view == "graph" || view == "instance" || view == "vmidi") {
        val pluginHost = model.sequencer.engine.pluginHost
        val entry = (0 until pluginHost.catalogEntryCount.toInt())
            .mapNotNull { pluginHost.getCatalogEntry(it.toUInt()) }
            .sortedBy { if (it.format == "AU") 0 else 1 }
            .firstOrNull()
        if (entry != null) {
            var done = false
            model.createPluginInstance(entry.format, entry.pluginId, -1) { done = true }
            val started = System.currentTimeMillis()
            while (!done && System.currentTimeMillis() - started < 30_000) Thread.sleep(50)
            java.awt.EventQueue.invokeAndWait { }
        }
        if (view == "graph") {
            // Deliberately does NOT convert the track to the editor graph: opening
            // the editor has to do that itself, exactly as uapmd-app does. Converting
            // here would hide the case where the window opens on the simple chain and
            // draws every node unconnected.
            graphTrack = (0 until model.trackCount.toInt()).firstOrNull { t ->
                model.getTrackGraphNodes(t).nodes.any { it.instanceId >= 0 }
            } ?: 0
            println("graph snapshot: track $graphTrack, " +
                "${model.getTrackGraphConnections(graphTrack).connections.size} connection(s) before opening")
        }
    }

    val scene = ImageComposeScene(width = width, height = height, density = Density(density))
    try {
        scene.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val host = remember { sceneHost }
                    host.refresh()
                    // -Duapmd.cmp.snapshotView picks the view: "selector" for the
                    // Plugin Selector, "graph" for a track's graph editor, the
                    // timeline otherwise.
                    when (view) {
                        "selector" -> PluginSelector(host)
                        "pluginlist" -> {
                            host.refreshCatalog()
                            PluginList(
                                host.catalog, null, {},
                                initialGroupMode = PluginGroupMode.entries.firstOrNull {
                                    it.name == System.getProperty("uapmd.cmp.snapshotGroup")
                                } ?: PluginGroupMode.Format
                            )
                        }
                        "toolbar" -> Toolbar(host, {}, {}, {}, {}, {}, {}, {}, {})
                        "devices" -> DeviceSettings(host)
                        "vmidi" -> VirtualMidiDevicesWindow(host, rememberFloatingWindowManager())
                        "augene2" -> host.augene2?.let { Augene2Window(it) }
                        "steps" -> StepSequencerEditor(host, 0, stepClip)
                        "graph" -> TrackGraphEditor(host, graphTrack)
                        "pianoroll" -> PianoRollEditor(
                            host, 0, pianoRollClip,
                            initialScrollSeconds =
                                System.getProperty("uapmd.cmp.rollScrollSeconds")?.toFloatOrNull() ?: 0f
                        )
                        "instance" -> host.trackInstances.flatten().firstOrNull()
                            ?.let { InstanceDetails(host, it) }
                            ?: Timeline(host, rememberFloatingWindowManager())
                        else -> Timeline(host, rememberFloatingWindowManager())
                    }
                }
            }
        }
        // Three passes, not one: anything sized from a first measure pass - the piano
        // roll's scrollbar thumbs, which need the viewport and content sizes the
        // scrolling container only reports once measured - is missing from frame one,
        // and a snapshot that drops it does not show what the app shows.
        var now = 0L
        repeat(2) { now += 16_000_000L; scene.render(now) }
        val image = scene.render(now + 16_000_000L)
        val data = image.encodeToData(EncodedImageFormat.PNG)
            ?: error("failed to encode the snapshot")
        File(out).writeBytes(data.bytes)
        println("wrote $out (${width}x$height @ ${density}x)")
    } finally {
        scene.close()
        java.awt.EventQueue.invokeAndWait { }
        cleanupAppModel()
    }
}
