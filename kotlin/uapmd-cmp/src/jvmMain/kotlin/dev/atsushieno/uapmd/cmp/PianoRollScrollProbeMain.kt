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
import dev.atsushieno.uapmd.cmp.ui.EditorScrollbarThickness
import dev.atsushieno.uapmd.cmp.ui.PianoRollEditor
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat

/** `EditorPalette.scrollThumb` in the dark scheme, as the bitmap reports it. */
private val ThumbArgb = 0xFF5E5E70.toInt()

/**
 * Scrolls the piano roll with the wheel and with its scrollbars, and checks the
 * view actually moves.
 *
 * "Scrolling" here means what an editor means by it - wheel, trackpad, scrollbar -
 * not dragging the canvas. An earlier version of this probe drove drags, which is
 * why it passed while the editor did not scroll at all: it was testing a pan that
 * nobody uses instead of the mechanism that was missing. The scrollbar checks
 * matter most on a full clip, where there is no empty grid left to drag and a
 * drag that lands on a note moves the note.
 *
 *   ./gradlew :uapmd-cmp:runPianoRollScrollProbe
 */
private const val SceneWidth = 900
private const val SceneHeight = 600
private const val SceneDensity = 1.5f

/**
 * The middle of a bar's footprint, in scene pixels. Taken from the real constant
 * rather than repeated here, so widening the bar for touch cannot leave the probe
 * pressing beside it.
 */
private val ScrollbarMidPx = EditorScrollbarThickness.value * SceneDensity / 2f

private fun newScene(clipId: Int): ImageComposeScene {
    val scene = ImageComposeScene(
        width = SceneWidth, height = SceneHeight, density = Density(SceneDensity)
    )
    scene.setContent {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(color = MaterialTheme.colorScheme.background) {
                val host = remember { UapmdHost.attach(getAppModel()) }
                host.refresh()
                PianoRollEditor(host, 0, clipId)
            }
        }
    }
    return scene
}

/**
 * Where the thumb of the bar hugging the given edge is, found in the rendered
 * pixels rather than computed from the layout the probe cannot see. Guessing a
 * point on the track instead is what made an earlier version of these checks
 * "fail": they were pressing the track beside the thumb, which pages rather than
 * drags. Reading it back also proves the thumb is on screen at all.
 */
private fun findThumbCenter(bmp: Bitmap, vertical: Boolean): Offset? {
    val across = (if (vertical) SceneWidth else SceneHeight) - ScrollbarMidPx.toInt()
    val along = if (vertical) SceneHeight else SceneWidth
    var bestStart = -1
    var bestLen = 0
    var start = -1
    fun close(end: Int) {
        if (start >= 0 && end - start > bestLen) { bestLen = end - start; bestStart = start }
        start = -1
    }
    for (i in 0 until along) {
        val color = if (vertical) bmp.getColor(across, i) else bmp.getColor(i, across)
        if (color == ThumbArgb) { if (start < 0) start = i } else close(i)
    }
    close(along)
    if (bestLen <= 0) return null
    val mid = bestStart + bestLen / 2f
    return if (vertical) Offset(across.toFloat(), mid) else Offset(mid, across.toFloat())
}

private fun ImageComposeScene.pixels(now: Long): Bitmap {
    val bitmap = Bitmap()
    bitmap.allocN32Pixels(SceneWidth, SceneHeight)
    render(now).readPixels(bitmap)
    return bitmap
}

/**
 * Drags a scrollbar thumb the way a pointer does: press on the thumb, a run of
 * small moves - one big jump would be swallowed as touch slop - then release.
 */
private fun renderAfterThumbDrag(clipId: Int, vertical: Boolean, step: Float, steps: Int,
    pointer: PointerType = PointerType.Mouse, acrossOffset: Float = 0f): ByteArray {
    val scene = newScene(clipId)
    try {
        var now = 0L
        fun frame() { now += 16_000_000L; scene.render(now) }
        // Two passes: a bar only learns its track and viewport sizes from the first
        // measure, so its thumb is not there to be grabbed until the second.
        frame()
        frame()

        val painted = findThumbCenter(scene.pixels(now), vertical)
            ?: error("no ${if (vertical) "vertical" else "horizontal"} thumb on screen")
        // acrossOffset moves the press off the painted thumb and into the transparent
        // gutter beside it, which is the half of the touch target that is not visible.
        val from =
            if (vertical) Offset(painted.x + acrossOffset, painted.y)
            else Offset(painted.x, painted.y + acrossOffset)
        fun at(i: Int) =
            if (vertical) Offset(from.x, from.y + step * i) else Offset(from.x + step * i, from.y)

        scene.sendPointerEvent(PointerEventType.Enter, from, type = pointer)
        scene.sendPointerEvent(PointerEventType.Press, from, type = pointer)
        frame()
        for (i in 1..steps) {
            scene.sendPointerEvent(PointerEventType.Move, at(i), type = pointer)
            frame()
        }
        scene.sendPointerEvent(PointerEventType.Release, at(steps), type = pointer)
        return scene.render(now).encodeToData(EncodedImageFormat.PNG)!!.bytes
    } finally {
        scene.close()
    }
}

private fun renderAfterScroll(clipId: Int, dx: Float, dy: Float, ticks: Int): ByteArray {
    val width = SceneWidth
    val height = SceneHeight
    val scene = ImageComposeScene(width = width, height = height, density = Density(SceneDensity))
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

    // The scrollbars are the point of the exercise: a clip full of notes leaves no
    // empty grid to drag, so these are the only handle the pointer has.
    val vThumbDown = renderAfterThumbDrag(added.clipId, vertical = true, step = 6f, steps = 20)
    val vThumbUp = renderAfterThumbDrag(added.clipId, vertical = true, step = -6f, steps = 20)
    val hThumbRight = renderAfterThumbDrag(added.clipId, vertical = false, step = 6f, steps = 20)

    check("dragging the vertical thumb scrolls", !vThumbDown.contentEquals(still))
    check("the vertical thumb scrolls both ways", !vThumbUp.contentEquals(vThumbDown))
    check("dragging the horizontal thumb scrolls", !hThumbRight.contentEquals(still))
    val vThumbTouch = renderAfterThumbDrag(added.clipId, vertical = true, step = 6f, steps = 20,
        pointer = PointerType.Touch)
    check("a finger drags the vertical thumb too", !vThumbTouch.contentEquals(still))

    // The bar is painted narrower than it is: the gutter around the visible thumb is
    // transparent but still takes the press, which is what makes it a touch target
    // rather than the 2 mm sliver it looks like. A press two pixels inside the
    // footprint's edge has to drag the same way one on the paint does.
    val edge = ScrollbarMidPx - 2f
    val vThumbGutter = renderAfterThumbDrag(added.clipId, vertical = true, step = 6f, steps = 20,
        pointer = PointerType.Touch, acrossOffset = -edge)
    check("the gutter beside the thumb drags it too", !vThumbGutter.contentEquals(still))
    check("the two thumbs move different axes", !vThumbDown.contentEquals(hThumbRight))

    cleanupAppModel()
    if (failures > 0) {
        println("$failures CHECK(S) FAILED")
        kotlin.system.exitProcess(1)
    }
    println("ALL CHECKS PASSED")
}
