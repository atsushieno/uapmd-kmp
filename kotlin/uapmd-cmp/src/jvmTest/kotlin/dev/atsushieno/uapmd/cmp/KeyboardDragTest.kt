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
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Drags a finger across the on-screen keyboard and checks the notes it produced.
 *
 * This exists because reading `DiatonicKeyboard`'s source was not enough to explain
 * "dragging stops sending notes after a few": the null-note path that drops pointer
 * tracking is reachable from the mouse branch of `getNoteFromPosition`, but the
 * touch branch matches by nearest distance and cannot return null. `ImageComposeScene`
 * takes real pointer events with a real `PointerType`, so both can be driven here and
 * the answer read off rather than argued.
 *
 * Needs no native library, so unlike the *IntegrationTest classes it runs in `jvmTest`.
 *
 * A sweep from 10% to 90% of a 4-octave keyboard crosses most of its 28 white keys;
 * single digits mean the drag stopped being tracked partway.
 */
class KeyboardDragTest {
    private val drags: List<Pair<String, (PointerType) -> List<String>>> = listOf(
        "bare keyboard" to { t -> runDrag(t, 240) },
        "with a 100ms poll churning state" to { t -> runDragWithPoll(t, 240) }
    )

    private fun assertDragKeepsProducingNotes(pointerType: PointerType) {
        for ((label, drive) in drags) {
            val events = drive(pointerType)
            val notesOn = events.filter { it.startsWith("on ") }.map { it.removePrefix("on ").toInt() }
            val distinct = notesOn.distinct().size
            val hanging = notesOn.size - events.count { it.startsWith("off ") }
            println("── $pointerType, $label: ${notesOn.size} note-ons, $distinct distinct: $notesOn")
            assertTrue(distinct >= 15, "$pointerType, $label: the drag stopped after $distinct distinct note(s)")
            assertTrue(hanging <= 1, "$pointerType, $label: $hanging notes left hanging")
        }
    }

    @Test
    fun aTouchDragKeepsProducingNotes() = assertDragKeepsProducingNotes(PointerType.Touch)

    /**
     * Known defect in compose-audio-controls 0.7.3, which [composeDeliversEveryPointerMove]
     * shows is not Compose's: `DiatonicKeyboard` drops a pointer from `pointerIdToNote`
     * the moment `getNoteFromPosition` returns null, and never re-registers it, so the
     * drag is dead until the button is released. The mouse branch resolves by exact
     * rect containment and so returns null in the 1px gaps left between white-key rects
     * (`Size(wkWidth.toPx() - 1f, ...)`), which a drag samples into every few keys; a
     * sweep through the black-key band, whose rects cover the gaps, survives. Touch
     * takes the nearest-match branch and cannot hit it. See docs/uapmd-cmp-ui-audit.md.
     */
    @Ignore
    @Test
    fun aMouseDragKeepsProducingNotes() = assertDragKeepsProducingNotes(PointerType.Mouse)

    /**
     * Separates the two explanations that produce the same symptom: if Compose stopped
     * delivering Move events partway, the fault would be in Compose Multiplatform's
     * desktop pointer handling rather than in the widget.
     */
    @Test
    fun composeDeliversEveryPointerMove() {
        for (pointerType in listOf(PointerType.Mouse, PointerType.Touch)) {
            val (moves, _) = runDragCountingEvents(pointerType, 240)
            assertTrue(moves >= 200, "$pointerType: Compose delivered $moves of 240 pointer moves")
        }
    }
}

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

private fun runDrag(pointerType: PointerType, steps: Int = 240): List<String> {
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
    return events
}
