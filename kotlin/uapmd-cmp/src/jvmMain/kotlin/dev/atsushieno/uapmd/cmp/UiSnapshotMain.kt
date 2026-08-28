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
import dev.atsushieno.uapmd.cmp.ui.Timeline
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

    val scene = ImageComposeScene(width = width, height = height, density = Density(density))
    try {
        scene.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val host = remember { UapmdHost.attach(model) }
                    host.refresh()
                    Timeline(host, rememberFloatingWindowManager())
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
