package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
 */
@Composable
fun MidiKeyboard(
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    modifier: Modifier = Modifier,
    octaveZeroBased: Int = 4
) {
    // The library renders from a note-on state list, so the caller's callbacks
    // are wrapped to keep it in step.
    // 0.7.3 carries per-note state as Long (it packs expression data).
    val noteOnStates = remember { mutableStateListOf(*List(128) { 0L }.toTypedArray()) }
    DiatonicKeyboard(
        noteOnStates = noteOnStates.toList(),
        modifier = modifier.fillMaxWidth().height(72.dp),
        octaveZeroBased = octaveZeroBased,
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
}
