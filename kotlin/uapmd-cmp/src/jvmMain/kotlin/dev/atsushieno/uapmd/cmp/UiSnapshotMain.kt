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
    val view = System.getProperty("uapmd.cmp.snapshotView")
    if (view == "pianoroll") {
        val midi = System.getProperty("uapmd.probe.midi")
            ?: "/Users/atsushi/sources/uapmd-kmp/external/uapmd/cmake-build-debug/_deps/" +
            "libremidi-src/tests/corpus/You're No Good.mid"
        val added = model.sequencer.engine.timeline
            .addMidiClipFromFile(0, dev.atsushieno.uapmd.TimelinePosition(0L, 0.0), midi)
        pianoRollClip = added.clipId
        println("piano roll snapshot: clip ${added.clipId} ok=${added.success} err=${added.error}")
        java.awt.EventQueue.invokeAndWait { }
    }
    if (view == "graph" || view == "instance") {
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
                    val host = remember { UapmdHost.attach(model) }
                    host.refresh()
                    // -Duapmd.cmp.snapshotView picks the view: "selector" for the
                    // Plugin Selector, "graph" for a track's graph editor, the
                    // timeline otherwise.
                    when (view) {
                        "selector" -> PluginSelector(host)
                        "graph" -> TrackGraphEditor(host, graphTrack)
                        "pianoroll" -> PianoRollEditor(host, 0, pianoRollClip)
                        "instance" -> host.trackInstances.flatten().firstOrNull()
                            ?.let { InstanceDetails(host, it) }
                            ?: Timeline(host, rememberFloatingWindowManager())
                        else -> Timeline(host, rememberFloatingWindowManager())
                    }
                }
            }
        }
        val image = scene.render()
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
