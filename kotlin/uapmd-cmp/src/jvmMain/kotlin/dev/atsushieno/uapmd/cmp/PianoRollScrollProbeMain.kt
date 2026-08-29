package dev.atsushieno.uapmd.cmp

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import dev.atsushieno.uapmd.TimelinePosition
import dev.atsushieno.uapmd.cleanupAppModel
import dev.atsushieno.uapmd.getAppModel
import dev.atsushieno.uapmd.initJvmEventLoop
import dev.atsushieno.uapmd.instantiateAppModel
import dev.atsushieno.uapmd.cmp.ui.PianoRollEditor
import org.jetbrains.skia.EncodedImageFormat

/**
 * Scrolls the piano roll with the wheel and checks the view actually moves.
 *
 * "Scrolling" here means what an editor means by it - wheel, trackpad, scrollbar -
 * not dragging the canvas. An earlier version of this probe drove drags, which is
 * why it passed while the editor did not scroll at all: it was testing a pan that
 * nobody uses instead of the mechanism that was missing.
 *
 *   ./gradlew :uapmd-cmp:runPianoRollScrollProbe
 */
private fun renderAfterScroll(clipId: Int, dx: Float, dy: Float, ticks: Int): ByteArray {
    val width = 900
    val height = 600
    val scene = ImageComposeScene(width = width, height = height, density = Density(1.5f))
    try {
        scene.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val host = remember { UapmdHost.attach(getAppModel()) }
                    host.refresh()
                    PianoRollEditor(host, 0, clipId)
                }
            }
        }
        var now = 0L
        fun frame() { now += 16_000_000L; scene.render(now) }
        frame()

        // Over the grid, right of the key column.
        val at = Offset(600f, 420f)
        // A wheel needs the pointer to be over the target first, or the scroll has
        // nothing to land on.
        scene.sendPointerEvent(PointerEventType.Enter, at, type = PointerType.Mouse)
        frame()
        repeat(ticks) {
            scene.sendPointerEvent(
                PointerEventType.Scroll, at, scrollDelta = Offset(dx, dy), type = PointerType.Mouse
            )
            frame()
        }
        return scene.render(now).encodeToData(EncodedImageFormat.PNG)!!.bytes
    } finally {
        scene.close()
    }
}

fun main() {
    initJvmEventLoop()
    instantiateAppModel()
    val model = getAppModel()
    model.notifyUiReady()
    notifyPersistentStorageReadyForPlatform(model)

    val midi = System.getProperty("uapmd.probe.midi")
        ?: "/Users/atsushi/sources/uapmd-kmp/external/uapmd/cmake-build-debug/_deps/" +
        "libremidi-src/tests/corpus/You're No Good.mid"
    val added = model.sequencer.engine.timeline
        .addMidiClipFromFile(0, TimelinePosition(0L, 0.0), midi)
    println("clip ${added.clipId} ok=${added.success} err=${added.error}")
    java.awt.EventQueue.invokeAndWait { }

    var failures = 0
    fun check(label: String, ok: Boolean) {
        println((if (ok) "PASS  " else "FAIL  ") + label)
        if (!ok) failures++
    }

    val still = renderAfterScroll(added.clipId, 0f, 0f, 0)

    // One notch versus many: a viewport that only moves once — the symptom of a
    // gesture that gets cancelled, or of a pan that is not real scrolling — renders
    // these the same.
    val oneDown = renderAfterScroll(added.clipId, 0f, 1f, 1)
    val manyDown = renderAfterScroll(added.clipId, 0f, 1f, 12)
    val oneRight = renderAfterScroll(added.clipId, 1f, 0f, 1)
    val manyRight = renderAfterScroll(added.clipId, 1f, 0f, 12)

    println("rendered: still=${still.size}B  down 1/12=${oneDown.size}/${manyDown.size}B" +
        "  right 1/12=${oneRight.size}/${manyRight.size}B")

    check("the wheel scrolls vertically", !oneDown.contentEquals(still))
    check("vertical scrolling keeps going", !manyDown.contentEquals(oneDown))
    check("the wheel scrolls horizontally", !oneRight.contentEquals(still))
    check("horizontal scrolling keeps going", !manyRight.contentEquals(oneRight))
    check("the two axes differ", !manyDown.contentEquals(manyRight))

    cleanupAppModel()
    if (failures > 0) {
        println("$failures CHECK(S) FAILED")
        kotlin.system.exitProcess(1)
    }
    println("ALL CHECKS PASSED")
}
