package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.cmp.UapmdHost

private val KeyWhite = Color(0xFF2A2A32)
private val KeyBlack = Color(0xFF1B1B20)
private val GridLine = Color(0xFF3A3A45)
private val NoteFill = Color(0xFF7FA9DE)
private val NoteSelected = Color(0xFFE8C547)

private val SnapOptions = listOf("Free", "1/1", "1/2", "1/4", "1/8", "1/16", "1/32")
private val BlackKeys = setOf(1, 3, 6, 8, 10)

/**
 * Piano roll for a MIDI clip: horizontal/vertical zoom and scroll, a snap grid,
 * note selection and live preview on click.
 *
 * Notes are parsed straight from the clip's UMP stream, so a drag can write the
 * same events back through `replaceMidiClipContent()` — one tick entry per UMP
 * word, which is what the engine expects. Working in ticks throughout avoids a
 * lossy seconds↔ticks conversion on every edit.
 */
@Composable
fun PianoRollEditor(host: UapmdHost, trackIndex: Int, clipId: Int) {
    var pixelsPerTick by remember { mutableStateOf(0.25f) }
    var noteHeight by remember { mutableStateOf(9f) }
    var snapIndex by remember { mutableStateOf(3) }
    var snapMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<UmpNote?>(null) }
    var scrollNote by remember { mutableStateOf(0f) }
    var revision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var dragTicks by remember { mutableStateOf(0L) }

    val events = remember(trackIndex, clipId, revision) {
        host.model.getMidiClipUmpEvents(trackIndex, clipId).events
    }
    val notes = remember(events) { parseUmpNotes(events) }
    val lowest = remember(notes) { (notes.minOfOrNull { it.note } ?: 48) - 2 }
    val highest = remember(notes) { (notes.maxOfOrNull { it.note } ?: 72) + 2 }
    // Ticks per quarter is not exposed; 480 is the usual SMF resolution and only
    // affects grid spacing, not the data.
    val ticksPerQuarter = 480L

    fun commitMove(note: UmpNote, deltaTicks: Long) {
        if (deltaTicks == 0L) return
        val (words, ticks) = rebuildClipContent(
            events, mapOf(note.onIndex to deltaTicks, note.offIndex to deltaTicks)
        )
        val ok = host.model.sequencer.engine.timeline
            .replaceMidiClipContent(trackIndex, clipId, words, ticks)
        status = if (ok) null else "The engine rejected the edit."
        host.invalidateMidiCache()
        revision++
        selected = null
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Zoom", style = MaterialTheme.typography.bodySmall)
            Slider(pixelsPerTick, { pixelsPerTick = it }, valueRange = 0.02f..2f, modifier = Modifier.width(120.dp))
            Text("Rows", style = MaterialTheme.typography.bodySmall)
            Slider(noteHeight, { noteHeight = it }, valueRange = 5f..22f, modifier = Modifier.width(90.dp))
            Box {
                Button(onClick = { snapMenu = true }) { Text("Snap ${SnapOptions[snapIndex]}") }
                DropdownMenu(expanded = snapMenu, onDismissRequest = { snapMenu = false }) {
                    SnapOptions.forEachIndexed { i, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { snapIndex = i; snapMenu = false })
                    }
                }
            }
        }
        Text(
            "${notes.size} notes · " +
                (selected?.let { "note ${it.note} @ tick ${it.startTick}" } ?: "drag a note to move it"),
            style = MaterialTheme.typography.bodySmall
        )
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        Box(Modifier.fillMaxSize()
            .pointerInput(notes, pixelsPerTick, noteHeight, scrollNote) {
                detectDragGestures(
                    onDragStart = { offset ->
                        selected = noteAtPoint(notes, offset, pixelsPerTick, noteHeight, highest, scrollNote)
                        dragTicks = 0L
                    },
                    onDragEnd = {
                        selected?.let { note ->
                            val snap = snapTicks(snapIndex, ticksPerQuarter)
                            val delta = if (snap > 0) (dragTicks / snap) * snap else dragTicks
                            commitMove(note, delta)
                        }
                        dragTicks = 0L
                    }
                ) { change, delta ->
                    change.consume()
                    if (selected != null) dragTicks += (delta.x / pixelsPerTick).toLong()
                    else scrollNote = (scrollNote - delta.y / noteHeight).coerceIn(-60f, 60f)
                }
            }
            .pointerInput(notes, pixelsPerTick, noteHeight, scrollNote) {
                detectTapGestures { offset ->
                    val hit = noteAtPoint(notes, offset, pixelsPerTick, noteHeight, highest, scrollNote)
                    selected = hit
                    hit?.let { n ->
                        host.trackInstances.getOrNull(trackIndex)?.firstOrNull()?.let { inst ->
                            host.model.sequencer.engine.sendNoteOn(inst.instanceId, n.note)
                        }
                    }
                }
            }) {
            Canvas(Modifier.fillMaxSize()) {
                val rows = highest - lowest + 1
                for (r in 0 until rows) {
                    val midi = highest - r
                    val y = r * noteHeight + scrollNote * noteHeight
                    if (y + noteHeight < 0 || y > size.height) continue
                    drawRect(
                        if (BlackKeys.contains(((midi % 12) + 12) % 12)) KeyBlack else KeyWhite,
                        Offset(0f, y), Size(size.width, noteHeight - 0.5f)
                    )
                }
                val snap = snapTicks(snapIndex, ticksPerQuarter)
                if (snap > 0) {
                    var t = 0L
                    while (t * pixelsPerTick < size.width) {
                        val x = t * pixelsPerTick
                        drawLine(GridLine, Offset(x, 0f), Offset(x, size.height), 1f)
                        t += snap
                    }
                }
                notes.forEach { n ->
                    val shift = if (n == selected) dragTicks else 0L
                    val x = (n.startTick + shift) * pixelsPerTick
                    val w = (n.durationTicks * pixelsPerTick).coerceAtLeast(2f)
                    val y = (highest - n.note) * noteHeight + scrollNote * noteHeight
                    if (y + noteHeight < 0 || y > size.height) return@forEach
                    drawRect(
                        if (n == selected) NoteSelected else NoteFill.copy(alpha = 0.4f + 0.6f * n.velocity),
                        Offset(x, y + 1f), Size(w, noteHeight - 2f)
                    )
                }
            }
        }
    }
}

/** Snap in ticks; index 0 is Free. 1/1 is a whole note = 4 quarters. */
private fun snapTicks(snapIndex: Int, ticksPerQuarter: Long): Long =
    if (snapIndex == 0) 0L else (ticksPerQuarter * 4) / (1L shl (snapIndex - 1))

private fun noteAtPoint(
    notes: List<UmpNote>,
    offset: Offset,
    pixelsPerTick: Float,
    noteHeight: Float,
    highest: Int,
    scrollNote: Float
): UmpNote? = notes.firstOrNull { n ->
    val x = n.startTick * pixelsPerTick
    val w = (n.durationTicks * pixelsPerTick).coerceAtLeast(2f)
    val y = (highest - n.note) * noteHeight + scrollNote * noteHeight
    offset.x in x..(x + w) && offset.y in y..(y + noteHeight)
}
