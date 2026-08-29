package dev.atsushieno.uapmd.cmp

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.delay
import dev.atsushieno.uapmd.cmp.ui.MidiKeyboard

/**
 * Drags a finger across the on-screen keyboard and reports every note it produced.
 *
 * This exists because reading `DiatonicKeyboard`'s source was not enough to explain
 * "dragging stops sending notes after a few": the null-note path that drops pointer
 * tracking is reachable from the mouse branch of `getNoteFromPosition`, but the
 * touch branch matches by nearest distance and cannot return null. `ImageComposeScene`
 * takes real pointer events with a real `PointerType`, so both can be driven here and
 * the answer read off rather than argued.
 *
 *   ./gradlew :uapmd-cmp:runKeyboardDragProbe
 *
 * A drag across N white keys should report N distinct notes, ascending.
 */
/**
 * Drives the same drag while a 100 ms ticker churns state around the keyboard, the
 * way `UapmdHost`'s poll does in the real app (playhead, spectra and the rest are
 * rewritten ten times a second). The bare-keyboard run above cannot see anything
 * the poll disturbs, which is exactly the gap this fills: if a recomposition from
 * outside the widget tears down its `pointerInput`, the drag dies mid-gesture and
 * the note count collapses — intermittently, depending on where the tick lands.
 */
private fun runDragWithPoll(pointerType: PointerType, steps: Int = 240): List<String> {
    val events = mutableListOf<String>()
    val width = 1080
    val height = 200
    val scene = ImageComposeScene(width = width, height = height, density = Density(2.625f))
    try {
        scene.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var tick by remember { mutableStateOf(0) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(100)
                            tick++
                        }
                    }
                    Column {
                        MidiKeyboard(
                            onNoteOn = { events += "on $it" },
                            onNoteOff = { events += "off $it" }
                        )
                        // Reads the ticking state so every tick recomposes this
                        // subtree, as the poll's writes do. It goes *below* the
                        // keyboard on purpose: putting it above shifts the keys down,
                        // and the drag then sweeps the black-key band instead of the
                        // white one, which is a different test entirely.
                        Text("tick $tick")
                    }
                }
            }
        }
        var now = 0L
        fun frame() {
            // ~60fps of virtual time, so the 100 ms ticker fires repeatedly during
            // the drag rather than never.
            now += 16_000_000L
            scene.render(now)
        }
        frame()
        val y = height * 0.8f
        val from = width * 0.10f
        val to = width * 0.90f
        scene.sendPointerEvent(PointerEventType.Press, Offset(from, y), type = pointerType)
        frame()
        for (i in 1..steps) {
            val x = from + (to - from) * i / steps
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, y), type = pointerType)
            frame()
        }
        scene.sendPointerEvent(PointerEventType.Release, Offset(to, y), type = pointerType)
        frame()
    } finally {
        scene.close()
    }
    return events
}

/**
 * Counts the pointer events Compose actually delivers during the drag, alongside the
 * notes the keyboard emits from them.
 *
 * This separates two explanations that produce the same symptom. If Compose stops
 * delivering Move events partway, the fault is in Compose Multiplatform's desktop
 * pointer handling; if the Moves keep arriving while the notes stop, the fault is in
 * the widget's own state machine. The counting handler sits above the keyboard and
 * reads events on the Initial pass, so nothing a child does can hide them from it.
 */
private fun runDragCountingEvents(pointerType: PointerType, steps: Int = 240): Pair<Int, List<String>> {
    val events = mutableListOf<String>()
    var moves = 0
    val width = 1080
    val height = 200
    val scene = ImageComposeScene(width = width, height = height, density = Density(2.625f))
    try {
        scene.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Move) moves++
                            }
                        }
                    }) {
                        MidiKeyboard(
                            onNoteOn = { events += "on $it" },
                            onNoteOff = { events += "off $it" }
                        )
                    }
                }
            }
        }
        scene.render()
        val y = height * 0.8f
        val from = width * 0.10f
        val to = width * 0.90f
        scene.sendPointerEvent(PointerEventType.Press, Offset(from, y), type = pointerType)
        scene.render()
        for (i in 1..steps) {
            val x = from + (to - from) * i / steps
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, y), type = pointerType)
            scene.render()
        }
        scene.sendPointerEvent(PointerEventType.Release, Offset(to, y), type = pointerType)
        scene.render()
    } finally {
        scene.close()
    }
    return moves to events
}

private fun runDrag(
    pointerType: PointerType,
    steps: Int = 240,
    yFraction: Float = 0.8f
): List<String> {
    val events = mutableListOf<String>()
    val width = 1080
    val height = 200
    val scene = ImageComposeScene(width = width, height = height, density = Density(2.625f))
    try {
        scene.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MidiKeyboard(
                        onNoteOn = { events += "on $it" },
                        onNoteOff = { events += "off $it" }
                    )
                }
            }
        }
        // Draw once so the keyboard populates the rect map it hit-tests against.
        scene.render()

        val y = height * yFraction
        val from = width * 0.10f
        val to = width * 0.90f
        scene.sendPointerEvent(PointerEventType.Press, Offset(from, y), type = pointerType)
        scene.render()
        for (i in 1..steps) {
            val x = from + (to - from) * i / steps
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, y), type = pointerType)
            scene.render()
        }
        scene.sendPointerEvent(PointerEventType.Release, Offset(to, y), type = pointerType)
        scene.render()
    } finally {
        scene.close()
    }
    return events
}

fun main() {
    var touchBroken = false
    val scenarios: List<Pair<String, (PointerType) -> List<String>>> = listOf(
        "bare keyboard" to { t -> runDrag(t, 240) },
        "with a 100ms poll churning state" to { t -> runDragWithPoll(t, 240) }
    )
    for ((label, drive) in scenarios) for (pointerType in listOf(PointerType.Touch, PointerType.Mouse)) {
        val events = drive(pointerType)
        val notesOn = events.filter { it.startsWith("on ") }.map { it.removePrefix("on ").toInt() }
        val distinct = notesOn.distinct().size
        println("── $pointerType, $label ──")
        println("   events: ${events.size}, note-ons: ${notesOn.size}, distinct: $distinct")
        println("   notes: $notesOn")

        // A sweep from 10% to 90% of a 4-octave keyboard crosses most of its 28 white
        // keys; single digits mean the drag stopped being tracked partway.
        val ok = distinct >= 15
        val hanging = notesOn.size - events.count { it.startsWith("off ") }

        when {
            pointerType == PointerType.Touch -> {
                println(if (ok) "   PASS  the drag kept producing notes"
                        else "   FAIL  the drag stopped after $distinct distinct note(s)")
                if (!ok || hanging > 1) touchBroken = true
                if (hanging > 1) println("   FAIL  $hanging notes left hanging")
            }
            ok -> println("   NOTE  mouse dragging now works — the upstream defect below looks fixed")
            else -> {
                // Known defect in compose-audio-controls 0.7.3, reproduced here rather
                // than argued from the source: DiatonicKeyboard drops a pointer from
                // `pointerIdToNote` the moment `getNoteFromPosition` returns null, and
                // never re-registers it, so the drag is dead until the button is
                // released. The mouse branch resolves by exact rect containment and so
                // returns null in the 1px gaps left between white-key rects
                // (`Size(wkWidth.toPx() - 1f, ...)`), which a drag samples into every
                // few keys. Touch takes the nearest-match branch and cannot hit it.
                println("   KNOWN the drag stopped after $distinct distinct note(s)")
                println("         upstream: compose-audio-controls 0.7.3 DiatonicKeyboard")
                println("         mouse/stylus only; touch is unaffected")
            }
        }
    }
    // Whose fault is the mouse case: Compose, or the widget?
    println()
    for (pointerType in listOf(PointerType.Mouse, PointerType.Touch)) {
        val (moves, events) = runDragCountingEvents(pointerType, 240)
        val distinct = events.filter { it.startsWith("on ") }.map { it.removePrefix("on ") }.distinct().size
        println("── $pointerType, counting delivered events ──")
        println("   Move events Compose delivered: $moves of 240 sent")
        println("   distinct notes the keyboard emitted: $distinct")
        println(
            when {
                moves < 200 -> "   → Compose stopped delivering pointer moves: the fault is below the widget"
                distinct >= 15 -> "   → moves delivered and notes followed them"
                else -> "   → Compose delivered every move; the widget stopped acting on them"
            }
        )
    }

    // Which part of the widget causes it. A sweep low down crosses only white-key
    // rects, whose 1px gaps resolve to null; a sweep higher up crosses the black-key
    // rects too, and those cover most of the gaps, so far fewer samples land in one.
    // If the gaps are the trigger, the same drag survives much longer up there.
    println()
    val lowBand = runDrag(PointerType.Mouse, 240, yFraction = 0.85f)
        .filter { it.startsWith("on ") }.distinct().size
    val highBand = runDrag(PointerType.Mouse, 240, yFraction = 0.35f)
        .filter { it.startsWith("on ") }.distinct().size
    println("── Mouse, white-key band vs black-key band ──")
    println("   distinct notes low (white keys only): $lowBand")
    println("   distinct notes high (black keys overlap the gaps): $highBand")
    println(
        if (highBand > lowBand * 2)
            "   → the 1px gaps between white-key rects are what ends the drag"
        else
            "   → the gaps do not explain it; the cause is elsewhere in the widget"
    )

    if (touchBroken) {
        println("CHECK(S) FAILED")
        kotlin.system.exitProcess(1)
    }
    println("ALL CHECKS PASSED")
}
