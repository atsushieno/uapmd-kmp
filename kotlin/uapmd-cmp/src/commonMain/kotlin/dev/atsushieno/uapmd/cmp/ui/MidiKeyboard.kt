package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.composeaudiocontrols.DiatonicKeyboard
import org.androidaudioplugin.composeaudiocontrols.DiatonicKeyboardMoveAction

/**
 * The on-screen keyboard in Instance Details.
 *
 * This was a hand-rolled Canvas with `detectTapGestures(onPress)`, which could
 * only start and stop one note under a single press — sliding across keys did
 * nothing, and a second finger was ignored. `compose-audio-controls`'
 * `DiatonicKeyboard` is the same widget AAP's own host UI uses
 * (`androidaudioplugin-ui-compose/…/ComposePluginView.kt`), and it handles the
 * drag and multi-touch behaviour properly.
 *
 * `moveAction = NoteChange` makes a slide across keys retrigger, which is what a
 * keyboard should do; `NoteExpression` is the alternative the library offers for
 * per-note expression, which we have nowhere to send yet.
 *
 * The octave controls follow uapmd-app rather than the library. The library's
 * `DiatonicKeyboardWithControllers` offers an octave *slider*, but it comes
 * bundled with an expression-mode toggle and a sensitivity slider that would go
 * nowhere here; uapmd-app instead flanks the keyboard with key-shaped `<` and `>`
 * buttons that shift the range one octave at a time
 * (`remidy-imgui-shared/MidiKeyboard.cpp:144`), which is what this reproduces.
 */
@Composable
fun MidiKeyboard(
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    modifier: Modifier = Modifier,
    octaveZeroBased: Int = DefaultStartOctave,
    numOctaves: Int = DefaultOctaveSpan
) {
    // The library renders from a note-on state list, so the caller's callbacks
    // are wrapped to keep it in step.
    // 0.7.3 carries per-note state as Long (it packs expression data).
    val noteOnStates = remember { mutableStateListOf(*List(128) { 0L }.toTypedArray()) }
    // The same clamp as MidiKeyboard::shiftOctave (:36): the highest start that
    // still leaves a full span below the top of the MIDI range.
    val maxStart = (MaxOctave - numOctaves).coerceAtLeast(0)
    var startOctave by remember(octaveZeroBased, maxStart) {
        mutableStateOf(octaveZeroBased.coerceIn(0, maxStart))
    }

    val whiteKeys = numOctaves * WhiteKeysPerOctave
    // The library always draws a key `whiteKeyWidth` wide and lays them out from
    // x=0, so `totalWidth` alone does not fit them to the space — a wider span just
    // runs off the end and is clipped. The width per key has to be divided out here.
    BoxWithConstraints(modifier.fillMaxWidth().height(KeyboardHeight)) {
        val keysWidth = maxWidth - ShiftKeyWidth * 2
        Row(Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            OctaveShiftKey("<", enabled = startOctave > 0) { startOctave-- }
            Box(Modifier.width(keysWidth).fillMaxHeight()) {
                DiatonicKeyboard(
                    noteOnStates = noteOnStates.toList(),
                    modifier = Modifier.fillMaxHeight(),
                    octaveZeroBased = startOctave,
                    numWhiteKeys = whiteKeys,
                    whiteKeyWidth = keysWidth / whiteKeys,
                    blackKeyHeight = KeyboardHeight * BlackKeyHeightRatio,
                    totalWidth = keysWidth,
                    totalHeight = KeyboardHeight,
                    moveAction = DiatonicKeyboardMoveAction.NoteChange,
                    onNoteOn = { note, _ ->
                        noteOnStates[note] = 1L
                        onNoteOn(note)
                    },
                    onNoteOff = { note, _ ->
                        noteOnStates[note] = 0L
                        onNoteOff(note)
                    }
                )
                CNoteLabels(startOctave, whiteKeys)
            }
            OctaveShiftKey(">", enabled = startOctave < maxStart) { startOctave++ }
        }
    }
}

/** InstanceDetails.cpp:90 — `setOctaveRange(3, 4)`. */
const val DefaultStartOctave = 3
const val DefaultOctaveSpan = 4

private const val WhiteKeysPerOctave = 7
private const val MaxOctave = 10
private val KeyboardHeight = 72.dp
private val ShiftKeyWidth = 26.dp

/** The library's own 35.dp black key against its 60.dp white key. */
private const val BlackKeyHeightRatio = 35f / 60f

private val ShiftKeyFace = Color(0xFFEBEBEB)
private val ShiftKeyFaceDisabled = Color(0xFF9A9A9A)
private val ShiftKeyBorder = Color(0xFF646464)

/**
 * The octave readout, drawn the way uapmd-app draws it: the note name on each C
 * key, at the foot of the key (`MidiKeyboard.cpp:200-210`). That names the octave
 * wherever the shift keys have moved the range to, and does it in place rather
 * than in a separate caption. The library draws no labels of its own, so these are
 * overlaid — a Canvas takes no pointer input, so the keys stay playable through it.
 *
 * uapmd-app's `getNoteName` numbers octaves as `note / 12`, which is the same
 * numbering `octaveZeroBased` uses, so the two agree on what to call each C.
 */
@Composable
private fun BoxScope.CNoteLabels(startOctave: Int, whiteKeys: Int) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = Color.Black, fontSize = 8.sp)
    Canvas(Modifier.matchParentSize()) {
        val keyWidth = size.width / whiteKeys
        var i = 0
        while (i < whiteKeys) {
            val layout = measurer.measure("C${startOctave + i / 7}", style)
            drawText(
                layout,
                topLeft = Offset(
                    i * keyWidth + (keyWidth - layout.size.width) / 2f,
                    size.height - layout.size.height - CLabelBottomInset
                )
            )
            i += WhiteKeysPerOctave
        }
    }
}

/** uapmd-app insets the label 5px from the bottom of the key. */
private const val CLabelBottomInset = 5f

/**
 * A shift button drawn as a key, as uapmd-app draws it: same height as a white
 * key, sitting flush against the keyboard so the two read as one control.
 */
@Composable
private fun OctaveShiftKey(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(ShiftKeyWidth)
            .fillMaxHeight()
            .background(if (enabled) ShiftKeyFace else ShiftKeyFaceDisabled)
            .border(1.dp, ShiftKeyBorder)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, color = Color.Black, style = MaterialTheme.typography.labelMedium)
    }
}
