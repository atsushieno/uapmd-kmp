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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.UmpEvent
import dev.atsushieno.uapmd.cmp.UapmdHost

private val KeyWhite = Color(0xFF2A2A32)
private val KeyBlack = Color(0xFF1B1B20)
private val GridLine = Color(0xFF3A3A45)
private val NoteFill = Color(0xFF7FA9DE)
private val NoteSelected = Color(0xFFE8C547)

private val KeyPanelBg = Color(0xFF17171C)
private val KeyColumnWhite = Color(0xFFE8E8E8)
private val KeyColumnBlack = Color(0xFF1B1B20)
private val KeyPreviewWhite = Color(0xFF9FD8B0)
private val KeyPreviewBlack = Color(0xFF3F7A54)
private val KeySeparator = Color(0xFF3A3A45)
private val KeyLabel = Color(0xFF303030)

private val SnapOptions = listOf("Free", "1/1", "1/2", "1/4", "1/8", "1/16", "1/32")
private val BlackKeys = setOf(1, 3, 6, 8, 10)

private val KeyColumnWidth = 44.dp

/** The grid runs the whole MIDI range, top row first, as uapmd-app's does. */
private const val TopNote = 127

/** uapmd-app inserts a quarter note at ~100/127 (`PianoRollEditor.cpp:1141`). */
private const val DefaultNoteVelocity = 0.787f

/** A note may not be resized shorter than this. */
private const val MinNoteTicks = 8L

/** uapmd-app's `kResizeEdgePx`. */
private const val ResizeEdgePx = 8f

/** Below this row height a label cannot be drawn legibly, so none is. */
private const val MinLabelRowPx = 14f

/**
 * Piano roll for a MIDI clip, following uapmd-app's editor
 * (`PianoRollEditor.cpp`) in both layout and interaction.
 *
 * Layout: a fixed key column on the left and the note grid to its right, the two
 * scrolling vertically together (`renderPianoKeys`, :815). The keys are not
 * decoration — they name the row a note sits on, which a bare grid cannot, and
 * clicking one previews that pitch.
 *
 * Interaction, from `:941-1155`:
 *  - drag a note's middle to move it in *both* time and pitch;
 *  - drag within the resize zone at either end to change its length;
 *  - double-click empty space to insert a quarter note at the snapped position;
 *  - double-click a note to delete it;
 *  - the selected note's velocity is editable.
 *
 * Notes are parsed straight from the clip's UMP stream and written back through
 * `replaceMidiClipContent()`, one tick entry per UMP word, so every edit carries
 * the clip's non-note events through untouched. Working in ticks throughout avoids
 * a lossy seconds↔ticks conversion on every edit.
 */
@Composable
fun PianoRollEditor(host: UapmdHost, trackIndex: Int, clipId: Int) {
    var dpPerTick by remember { mutableStateOf(0.25f) }
    var rowHeightDp by remember { mutableStateOf(11f) }
    var snapIndex by remember { mutableStateOf(3) }
    var snapMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<UmpNote?>(null) }
    var scrollNote by remember { mutableStateOf(0f) }
    var revision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var dragTicks by remember { mutableStateOf(0L) }
    var dragPitch by remember { mutableStateOf(0) }
    var dragMode by remember { mutableStateOf(DragMode.None) }
    var previewNote by remember { mutableStateOf<Int?>(null) }

    // The canvas measures in pixels while these are densities-independent, so the
    // conversion happens once here rather than at every use.
    val density = LocalDensity.current.density
    val pixelsPerTick = dpPerTick * density
    val noteHeight = rowHeightDp * density

    val events = remember(trackIndex, clipId, revision) {
        host.model.getMidiClipUmpEvents(trackIndex, clipId).events
    }
    val notes = remember(events) { parseUmpNotes(events) }
    // The grid spans the whole MIDI range, as uapmd-app's does, so a note can be
    // dragged anywhere rather than only within the range the clip happens to use.
    val highest = TopNote
    // Ticks per quarter is not exposed; 480 is the usual SMF resolution and only
    // affects grid spacing, not the data.
    val ticksPerQuarter = 480L

    // The grid spans all 128 notes so a note can be dragged anywhere, but opening at
    // note 127 would show empty air above the music. Scroll to the clip's own range
    // once, then leave the view where the user puts it.
    var scrolledToContent by remember(clipId) { mutableStateOf(false) }
    LaunchedEffect(notes, clipId) {
        if (!scrolledToContent && notes.isNotEmpty()) {
            val top = notes.maxOf { it.note }
            scrollNote = -((highest - top - 2).coerceAtLeast(0)).toFloat()
            scrolledToContent = true
        }
    }

    fun apply(
        edits: Map<Int, EventEdit> = emptyMap(),
        removed: Set<Int> = emptySet(),
        added: List<UmpEvent> = emptyList(),
        what: String
    ) {
        val (words, ticks) = editClipContent(events, edits, removed, added)
        val ok = host.model.sequencer.engine.timeline
            .replaceMidiClipContent(trackIndex, clipId, words, ticks)
        status = if (ok) null else "The engine rejected the $what."
        host.invalidateMidiCache()
        revision++
    }

    fun previewPitch(note: Int) {
        host.trackInstances.getOrNull(trackIndex)?.firstOrNull()?.let { inst ->
            host.model.sequencer.engine.sendNoteOn(inst.instanceId, note)
        }
    }

    fun commitDrag(note: UmpNote) {
        val snap = snapTicks(snapIndex, ticksPerQuarter)
        val delta = if (snap > 0) (dragTicks / snap) * snap else dragTicks
        when (dragMode) {
            DragMode.Move -> {
                if (delta == 0L && dragPitch == 0) return
                val pitch = (note.note + dragPitch).coerceIn(0, 127)
                apply(
                    edits = mapOf(
                        note.onIndex to EventEdit(tickDelta = delta, note = pitch),
                        note.offIndex to EventEdit(tickDelta = delta, note = pitch)
                    ),
                    what = "move"
                )
            }
            // Resizing moves one end only, so the other stays put.
            DragMode.ResizeRight -> {
                if (delta == 0L) return
                val end = (note.endTick + delta).coerceAtLeast(note.startTick + MinNoteTicks)
                apply(edits = mapOf(note.offIndex to EventEdit(tickDelta = end - note.endTick)), what = "resize")
            }
            DragMode.ResizeLeft -> {
                if (delta == 0L) return
                val start = (note.startTick + delta).coerceIn(0L, note.endTick - MinNoteTicks)
                apply(edits = mapOf(note.onIndex to EventEdit(tickDelta = start - note.startTick)), what = "resize")
            }
            DragMode.None -> Unit
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Zoom", style = MaterialTheme.typography.bodySmall)
            Slider(dpPerTick, { dpPerTick = it }, valueRange = 0.02f..2f, modifier = Modifier.width(120.dp))
            Text("Rows", style = MaterialTheme.typography.bodySmall)
            Slider(rowHeightDp, { rowHeightDp = it }, valueRange = 5f..22f, modifier = Modifier.width(90.dp))
            Box {
                Button(onClick = { snapMenu = true }) { Text("Snap ${SnapOptions[snapIndex]}") }
                DropdownMenu(expanded = snapMenu, onDismissRequest = { snapMenu = false }) {
                    SnapOptions.forEachIndexed { i, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { snapIndex = i; snapMenu = false })
                    }
                }
            }
        }
        // The selected note's velocity, which uapmd-app edits in its side panel (:1363).
        selected?.let { note ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${noteName(note.note)} · vel ${(note.velocity * 127).toInt()}",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = note.velocity,
                    onValueChange = { v ->
                        apply(edits = mapOf(note.onIndex to EventEdit(velocity = v)), what = "velocity change")
                        selected = null
                    },
                    modifier = Modifier.width(140.dp)
                )
                Button(onClick = {
                    apply(removed = setOf(note.onIndex, note.offIndex), what = "delete")
                    selected = null
                }) { Text("Delete") }
            }
        }
        Text(
            "${notes.size} notes · double-tap empty space to add, a note to delete · drag to move, edges to resize",
            style = MaterialTheme.typography.bodySmall
        )
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        Row(Modifier.fillMaxSize()) {
            PianoKeyColumn(
                noteHeight = noteHeight,
                scrollNote = scrollNote,
                highest = highest,
                previewNote = previewNote,
                onKeyPressed = { previewNote = it; previewPitch(it) }
            )
            Box(Modifier.fillMaxSize()
                .pointerInput(notes, pixelsPerTick, noteHeight, scrollNote) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val hit = noteAtPoint(notes, offset, pixelsPerTick, noteHeight, highest, scrollNote)
                            selected = hit
                            dragTicks = 0L
                            dragPitch = 0
                            dragMode = if (hit == null) DragMode.None else {
                                // Edge zones, as uapmd-app sizes them: 8px, but never
                                // more than 30% of the note, so a short note still has
                                // a middle you can grab to move it (:1045).
                                val width = (hit.durationTicks * pixelsPerTick).coerceAtLeast(2f)
                                val edge = minOf(ResizeEdgePx, width * 0.3f)
                                val x0 = hit.startTick * pixelsPerTick
                                when {
                                    offset.x >= x0 + width - edge -> DragMode.ResizeRight
                                    offset.x <= x0 + edge -> DragMode.ResizeLeft
                                    else -> DragMode.Move
                                }
                            }
                        },
                        onDragEnd = {
                            selected?.let { commitDrag(it) }
                            dragTicks = 0L
                            dragPitch = 0
                            dragMode = DragMode.None
                        }
                    ) { change, delta ->
                        change.consume()
                        val note = selected
                        if (note != null && dragMode != DragMode.None) {
                            dragTicks += (delta.x / pixelsPerTick).toLong()
                            // Only a move changes pitch; a resize keeps the note's row.
                            if (dragMode == DragMode.Move)
                                dragPitch = -((change.position.y - (highest - note.note - scrollNote) * noteHeight)
                                    / noteHeight).toInt()
                        } else {
                            scrollNote = (scrollNote - delta.y / noteHeight).coerceIn(-127f, 0f)
                        }
                    }
                }
                .pointerInput(notes, pixelsPerTick, noteHeight, scrollNote) {
                    detectTapGestures(
                        onTap = { offset ->
                            val hit = noteAtPoint(notes, offset, pixelsPerTick, noteHeight, highest, scrollNote)
                            selected = hit
                            hit?.let { previewPitch(it.note) }
                        },
                        onDoubleTap = { offset ->
                            val hit = noteAtPoint(notes, offset, pixelsPerTick, noteHeight, highest, scrollNote)
                            if (hit != null) {
                                apply(removed = setOf(hit.onIndex, hit.offIndex), what = "delete")
                                selected = null
                            } else {
                                val snap = snapTicks(snapIndex, ticksPerQuarter)
                                val raw = (offset.x / pixelsPerTick).toLong().coerceAtLeast(0L)
                                val startTick = if (snap > 0) ((raw + snap / 2) / snap) * snap else raw
                                val row = ((offset.y / noteHeight) - scrollNote).toInt()
                                val pitch = (highest - row).coerceIn(0, 127)
                                apply(
                                    added = midi2NotePair(
                                        group = 0, channel = 0, note = pitch,
                                        // uapmd-app's default: a quarter note at ~100/127.
                                        velocity = DefaultNoteVelocity,
                                        startTick = startTick, durationTicks = ticksPerQuarter
                                    ),
                                    what = "insert"
                                )
                            }
                        }
                    )
                }) {
                Canvas(Modifier.fillMaxSize()) {
                    val firstRow = (-scrollNote).toInt().coerceAtLeast(0)
                    val rows = (size.height / noteHeight).toInt() + 2
                    for (r in firstRow until (firstRow + rows)) {
                        val midi = highest - r
                        if (midi < 0) break
                        val y = r * noteHeight + scrollNote * noteHeight
                        drawRect(
                            if (BlackKeys.contains(((midi % 12) + 12) % 12)) KeyBlack else KeyWhite,
                            Offset(0f, y), Size(size.width, noteHeight - 0.5f)
                        )
                    }
                    val snap = snapTicks(snapIndex, ticksPerQuarter)
                    if (snap > 0 && snap * pixelsPerTick >= 3f) {
                        var t = 0L
                        while (t * pixelsPerTick < size.width) {
                            val x = t * pixelsPerTick
                            drawLine(GridLine, Offset(x, 0f), Offset(x, size.height), 1f)
                            t += snap
                        }
                    }
                    notes.forEach { n ->
                        val isSelected = n == selected
                        val shiftTicks = if (isSelected && dragMode == DragMode.Move) dragTicks else 0L
                        val shiftPitch = if (isSelected && dragMode == DragMode.Move) dragPitch else 0
                        val growLeft = if (isSelected && dragMode == DragMode.ResizeLeft) dragTicks else 0L
                        val growRight = if (isSelected && dragMode == DragMode.ResizeRight) dragTicks else 0L
                        val x = (n.startTick + shiftTicks + growLeft) * pixelsPerTick
                        val w = ((n.durationTicks - growLeft + growRight) * pixelsPerTick).coerceAtLeast(2f)
                        val y = (highest - (n.note + shiftPitch)) * noteHeight + scrollNote * noteHeight
                        if (y + noteHeight < 0 || y > size.height) return@forEach
                        drawRect(
                            if (isSelected) NoteSelected else NoteFill.copy(alpha = 0.4f + 0.6f * n.velocity),
                            Offset(x, y + 1f), Size(w, noteHeight - 2f)
                        )
                    }
                }
            }
        }
    }
}

/** Which end of a note a drag grabbed, latched at the press. */
private enum class DragMode { None, Move, ResizeLeft, ResizeRight }

/**
 * The vertical keyboard down the left, matching `PianoRollEditor::renderPianoKeys`
 * (:815): black keys drawn narrower than white, a label on every C, and a row
 * separator per key so the column lines up with the grid's lanes.
 */
@Composable
private fun PianoKeyColumn(
    noteHeight: Float,
    scrollNote: Float,
    highest: Int,
    previewNote: Int?,
    onKeyPressed: (Int) -> Unit
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = KeyLabel)
    Canvas(
        Modifier.width(KeyColumnWidth).fillMaxHeight()
            .pointerInput(noteHeight, scrollNote, highest) {
                detectTapGestures { offset ->
                    val row = ((offset.y / noteHeight) - scrollNote).toInt()
                    val pitch = highest - row
                    if (pitch in 0..127) onKeyPressed(pitch)
                }
            }
    ) {
        drawRect(KeyPanelBg, Offset.Zero, Size(size.width, size.height))
        val blackWidth = size.width * 0.62f
        val firstRow = (-scrollNote).toInt().coerceAtLeast(0)
        val rows = (size.height / noteHeight).toInt() + 2
        for (r in firstRow until (firstRow + rows)) {
            val midi = highest - r
            if (midi < 0) break
            val y = r * noteHeight + scrollNote * noteHeight
            val isBlack = BlackKeys.contains(((midi % 12) + 12) % 12)
            val preview = midi == previewNote
            if (isBlack) {
                drawRect(
                    if (preview) KeyPreviewBlack else KeyColumnBlack,
                    Offset(0f, y), Size(blackWidth, noteHeight - 0.5f)
                )
            } else {
                drawRect(
                    if (preview) KeyPreviewWhite else KeyColumnWhite,
                    Offset(0f, y), Size(size.width, noteHeight - 0.5f)
                )
                // Octave label on every C, as uapmd-app labels them. Sized from the
                // row rather than fixed: a fixed sp label is taller than the row at
                // any usual zoom and spills over its neighbours.
                if (((midi % 12) + 12) % 12 == 0 && noteHeight >= MinLabelRowPx) {
                    val layout = measurer.measure(
                        noteName(midi),
                        labelStyle.copy(fontSize = (noteHeight * 0.62f).toSp())
                    )
                    drawText(layout, topLeft = Offset(2f, y + (noteHeight - layout.size.height) / 2f))
                }
            }
            drawLine(KeySeparator, Offset(0f, y + noteHeight - 0.5f), Offset(size.width, y + noteHeight - 0.5f), 1f)
        }
        drawLine(GridLine, Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), 1f)
    }
}

private val NoteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** uapmd-app's `fullNoteName`: name plus octave, numbering octaves as note / 12. */
private fun noteName(midi: Int) = "${NoteNames[((midi % 12) + 12) % 12]}${midi / 12}"

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
