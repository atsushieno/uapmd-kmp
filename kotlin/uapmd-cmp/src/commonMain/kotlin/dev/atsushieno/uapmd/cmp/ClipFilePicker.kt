package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.ClipType

/**
 * The chooser matching a clip's own type, for the "change this clip's source file"
 * buttons: an audio clip wants audio, a MIDI clip wants an SMF.
 *
 * Kept out of FilePicker.kt on purpose — that file is pure `expect` declarations,
 * which generate no JVM class; a function body there collides with the jvmMain
 * file of the same name.
 */
suspend fun pickForClip(clipType: ClipType): String? =
    if (clipType == ClipType.Audio) pickAudioFileToOpen() else pickMidiFileToOpen()
