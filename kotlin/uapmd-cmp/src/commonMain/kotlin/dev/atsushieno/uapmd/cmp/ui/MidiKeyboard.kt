package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.dp

private const val FirstNote = 36   // C2
private const val OctaveCount = 4
private val WhiteOffsets = intArrayOf(0, 2, 4, 5, 7, 9, 11)
private val BlackOffsets = intArrayOf(1, 3, 6, 8, 10)

/**
 * Playable keyboard for a plugin instance, as in uapmd-app's Details window.
 * Press sends note-on, release sends note-off.
 */
@Composable
fun MidiKeyboard(
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val whiteCount = OctaveCount * 7
    Box(modifier.fillMaxWidth().height(72.dp).pointerInput(Unit) {
        detectTapGestures(
            onPress = { offset ->
                val note = noteAt(offset, size.width.toFloat(), size.height.toFloat(), whiteCount)
                if (note >= 0) {
                    onNoteOn(note)
                    tryAwaitRelease()
                    onNoteOff(note)
                }
            }
        )
    }) {
        Canvas(Modifier.fillMaxWidth().height(72.dp)) { drawKeyboard(whiteCount) }
    }
}

private fun DrawScope.drawKeyboard(whiteCount: Int) {
    val w = size.width / whiteCount
    for (i in 0 until whiteCount) {
        drawRect(Color.White, Offset(i * w, 0f), Size(w - 1f, size.height))
    }
    val blackW = w * 0.6f
    for (octave in 0 until OctaveCount) {
        for (semitone in BlackOffsets) {
            val whiteIndexInOctave = WhiteOffsets.indexOfLast { it < semitone }
            val x = (octave * 7 + whiteIndexInOctave + 1) * w - blackW / 2f
            drawRect(Color.Black, Offset(x, 0f), Size(blackW, size.height * 0.62f))
        }
    }
}

/** Black keys are on top, so they are hit-tested first. */
private fun noteAt(offset: Offset, width: Float, height: Float, whiteCount: Int): Int {
    val w = width / whiteCount
    val blackW = w * 0.6f
    if (offset.y <= height * 0.62f) {
        for (octave in 0 until OctaveCount) {
            for (semitone in BlackOffsets) {
                val whiteIndexInOctave = WhiteOffsets.indexOfLast { it < semitone }
                val x = (octave * 7 + whiteIndexInOctave + 1) * w - blackW / 2f
                if (offset.x >= x && offset.x <= x + blackW)
                    return FirstNote + octave * 12 + semitone
            }
        }
    }
    val whiteIndex = (offset.x / w).toInt().coerceIn(0, whiteCount - 1)
    return FirstNote + (whiteIndex / 7) * 12 + WhiteOffsets[whiteIndex % 7]
}
