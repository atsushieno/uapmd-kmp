package dev.atsushieno.uapmd_kmp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ── Layout constants ──────────────────────────────────────────────────────────
private const val OCTAVES     = 2
private const val START_OCTAVE = 4   // C4 = MIDI note 60
private const val START_NOTE  = START_OCTAVE * 12   // 48... wait C4=60: octave 4 => 4*12=48, but MIDI middle C is C4=60
// Convention: MIDI note 60 = C4 (middle C). C4 = octave index 5 in some, 4 in others.
// We'll define C4 = note 60: octaveNum=4, so startNote = (4+1)*12 = 60.
private const val MIDI_C4     = 60
private val WHITE_SEMITONES   = intArrayOf(0, 2, 4, 5, 7, 9, 11) // C D E F G A B
private val BLACK_SEMITONES   = intArrayOf(1, 3, 6, 8, 10)        // C# D# F# G# A#
// Black key x-offsets relative to start of octave (in white-key-widths)
private val BLACK_X_OFFSETS   = floatArrayOf(0.75f, 1.75f, 3.75f, 4.75f, 5.75f)

private val whiteKeyColor       = Color(0xFFF8F8F8)
private val whiteKeyPressedColor = Color(0xFFBBDDFF)
private val blackKeyColor       = Color(0xFF222222)
private val blackKeyPressedColor = Color(0xFF2255AA)
private val keyBorderColor      = Color(0xFF888888)

// Returns the MIDI note for a given octave offset and semitone, or null if out of range
private fun noteOf(octaveOffset: Int, semitone: Int): Int = MIDI_C4 + octaveOffset * 12 + semitone

// ── Keyboard geometry ─────────────────────────────────────────────────────────

private data class KeyRect(val note: Int, val rect: Rect, val isBlack: Boolean)

private fun buildKeys(totalWidth: Float, whiteH: Float, blackH: Float): List<KeyRect> {
    val totalWhites = OCTAVES * 7
    val wkw = totalWidth / totalWhites
    val bkw = wkw * 0.6f
    val keys = mutableListOf<KeyRect>()
    // White keys first (drawn bottom)
    for (oct in 0 until OCTAVES) {
        WHITE_SEMITONES.forEachIndexed { i, semi ->
            val x = (oct * 7 + i) * wkw
            keys += KeyRect(noteOf(oct, semi), Rect(x, 0f, x + wkw, whiteH), false)
        }
    }
    // Black keys on top
    for (oct in 0 until OCTAVES) {
        BLACK_X_OFFSETS.forEachIndexed { i, xOff ->
            val x = (oct * 7 + xOff) * wkw - bkw / 2f
            keys += KeyRect(noteOf(oct, BLACK_SEMITONES[i]), Rect(x, 0f, x + bkw, blackH), true)
        }
    }
    return keys
}

private fun hitTest(keys: List<KeyRect>, pos: Offset): Int? {
    // Check black keys first (they overlap whites visually)
    keys.filter { it.isBlack }.forEach { if (pos in it.rect) return it.note }
    keys.filter { !it.isBlack }.forEach { if (pos in it.rect) return it.note }
    return null
}

private operator fun Rect.contains(pos: Offset) =
    pos.x >= left && pos.x < right && pos.y >= top && pos.y < bottom

// ── Composable ────────────────────────────────────────────────────────────────

@Composable
fun MidiKeyboard(
    onNoteOn:  (note: Int) -> Unit,
    onNoteOff: (note: Int) -> Unit,
    whiteKeyHeight: Dp = 72.dp,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val whiteH = with(density) { whiteKeyHeight.toPx() }
    val blackH = whiteH * 0.62f

    // Set of currently held notes (supports multi-touch / re-entry)
    val heldNotes = remember { mutableStateOf(setOf<Int>()) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(whiteKeyHeight)
            .pointerInput(Unit) {
                val keys = buildKeys(size.width.toFloat(), whiteH, blackH)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var activeNote = hitTest(keys, down.position)
                    if (activeNote != null) {
                        heldNotes.value = heldNotes.value + activeNote
                        onNoteOn(activeNote)
                    }
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull() ?: break
                        if (event.type == PointerEventType.Move) {
                            val newNote = hitTest(keys, change.position)
                            if (newNote != activeNote) {
                                activeNote?.let { onNoteOff(it); heldNotes.value = heldNotes.value - it }
                                activeNote = newNote
                                newNote?.let { onNoteOn(it); heldNotes.value = heldNotes.value + it }
                            }
                        }
                        if (!change.pressed) break
                    }
                    activeNote?.let { onNoteOff(it); heldNotes.value = heldNotes.value - it }
                }
            }
    ) {
        val keys = buildKeys(size.width, whiteH, blackH)
        drawKeys(keys, heldNotes.value, whiteH, blackH)
    }
}

private fun DrawScope.drawKeys(keys: List<KeyRect>, held: Set<Int>, whiteH: Float, blackH: Float) {
    // White keys
    keys.filter { !it.isBlack }.forEach { key ->
        val pressed = key.note in held
        drawRoundRect(
            color = if (pressed) whiteKeyPressedColor else whiteKeyColor,
            topLeft = Offset(key.rect.left + 1f, 0f),
            size = Size(key.rect.width - 2f, whiteH - 1f),
            cornerRadius = CornerRadius(2f, 2f)
        )
        drawRoundRect(
            color = keyBorderColor,
            topLeft = Offset(key.rect.left + 1f, 0f),
            size = Size(key.rect.width - 2f, whiteH - 1f),
            cornerRadius = CornerRadius(2f, 2f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
        )
    }
    // Black keys on top
    keys.filter { it.isBlack }.forEach { key ->
        val pressed = key.note in held
        drawRoundRect(
            color = if (pressed) blackKeyPressedColor else blackKeyColor,
            topLeft = Offset(key.rect.left, 0f),
            size = Size(key.rect.width, blackH),
            cornerRadius = CornerRadius(2f, 2f)
        )
    }
}
