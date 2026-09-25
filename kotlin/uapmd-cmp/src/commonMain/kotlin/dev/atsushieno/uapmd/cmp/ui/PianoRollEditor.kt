package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import dev.atsushieno.uapmd.PianoRollAction
import dev.atsushieno.uapmd.PianoRollNote
import dev.atsushieno.uapmd.cmp.UapmdHost
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt



/** Note durations relative to a whole note; "1/16" is a sixteenth-note grid. uapmd-app's kSnapLabels. */
private val SnapOptions = listOf("Free", "1/8", "1/16", "1/24", "1/32", "1/48", "1/64")
/** Quarter-note beats matching [SnapOptions]; index 0 is Free. uapmd-app's kSnapValues. */
private val SnapBeats = listOf(0f, 4f / 8f, 4f / 16f, 4f / 24f, 4f / 32f, 4f / 48f, 4f / 64f)
/** Defaults to 1/16, as uapmd-app does. */
private const val DefaultSnapIndex = 2
private val BlackKeys = setOf(1, 3, 6, 8, 10)

private val KeyColumnWidth = 44.dp

/** The grid runs the whole MIDI range, top row first, as uapmd-app's does. */
private const val TopNote = 127
private const val NoteCount = 128

/** uapmd-app inserts a quarter note at ~100/127 (`PianoRollEditor.cpp:1141`). */
private const val DefaultNoteVelocity = 0.787f

/** uapmd-app's kMinNoteDuration. */
private const val MinNoteSeconds = 0.01

/** uapmd-app's `kResizeEdgePx`. */
private const val ResizeEdgePx = 8f

/** Below this row height a label cannot be drawn legibly, so none is. */
private const val MinLabelRowPx = 14f

/** One bar of 4/4 at the tight end, a long song at the loose end. */
private const val MinVisibleBeats = 1f
private const val MaxVisibleBeats = 512f

/**
 * Piano roll for a MIDI clip, following uapmd-app's editor
 * (`PianoRollEditor.cpp`) in both layout and interaction.
 *
 * The editing model is `uapmd_app::PianoRollSession`, bound from uapmd-app-model
 * rather than reimplemented, so every host shares one interpretation of what a
 * clip's UMP stream means as notes, including the MIDI2 attributes and per-note
 * automation a plain note-on/note-off pairing loses. This file draws and
 * gestures; it does not decide what a note is.
 *
 * Positions are in **seconds**, as the session holds them (uapmd-app's editor is
 * `pxPerSec`-based for the same reason). The clip's own tempo only sets where the
 * snap lines fall.
 *
 * Layout: a fixed key column on the left and the note grid to its right, the two
 * scrolling vertically together (`renderPianoKeys`, :815). The keys are not
 * decoration - they name the row a note sits on, and clicking one previews it.
 *
 * Interaction, from `:941-1155`:
 *  - click selects; ctrl/cmd toggles; shift extends;
 *  - drag a note's middle to move the whole selection in time and pitch;
 *  - drag within the resize zone at either end to change its length;
 *  - double-click empty space to insert a quarter note at the snapped position;
 *  - double-click a note to delete it;
 *  - copy/cut/paste/select-all act on the selection, through the session.
 *
 * Every edit is applied to the session and then committed, which writes the clip
 * back through the undo history. The commit source is recorded so the timeline's
 * own change notification does not bounce back and reload the editor mid-edit.
 */
@Composable
fun PianoRollEditor(
    host: UapmdHost,
    trackIndex: Int,
    clipId: Int,
    /**
     * Starting horizontal scroll in seconds, for headless rendering; the user's
     * drags take over after. Seconds, not ticks: the editor works in the same
     * unit the session does.
     */
    initialScrollSeconds: Float = 0f
) {
    val c = editorPalette
    // The horizontal zoom is how many beats fit across the grid, on a
    // logarithmic control: "16 beats in view" is a thing a musician can ask for,
    // where "137 px/s" is not. Pixels per second is derived from the viewport,
    // so the same setting means the same span on any window size.
    var visibleBeats by remember { mutableStateOf(16f) }
    var lastPixelsPerSecond by remember { mutableStateOf(0f) }
    var rowHeightDp by remember { mutableStateOf(11f) }
    var snapIndex by remember { mutableStateOf(DefaultSnapIndex) }
    var snapMenu by remember { mutableStateOf(false) }
    // Bumped after every edit so the note list is re-read from the session.
    var revision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var previewNote by remember { mutableStateOf<Int?>(null) }

    // Drag state. The session owns the note positions; these only describe the
    // gesture in progress so the canvas can draw it before it is committed.
    var dragMode by remember { mutableStateOf(DragMode.None) }
    var dragIndex by remember { mutableStateOf(-1) }
    var dragOriginStart by remember { mutableStateOf(0.0) }
    var dragOriginEnd by remember { mutableStateOf(0.0) }
    var dragOriginNote by remember { mutableStateOf(0) }

    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    val density = LocalDensity.current.density
    val noteHeight = rowHeightDp * density

    val model = host.model

    // The clip's own tempo decides how wide a beat is. The viewport width is 0
    // until the grid has been laid out once, so fall back to the whole window
    // for that first frame rather than dividing by zero.
    val clipTempo = remember(trackIndex, clipId, host.projectRevision) {
        host.clipTempo(trackIndex, clipId)
    }
    val gridWidthPx = hScroll.viewportSize.takeIf { it > 0 }?.toFloat()
        ?: (240f * density)
    val pixelsPerSecond =
        (gridWidthPx * clipTempo.toFloat() / (60f * visibleBeats.coerceAtLeast(0.01f)))
            .coerceAtLeast(1f)

    // Changing the zoom must leave the same music under the pointer, so the
    // scroll offset is rescaled by however much the scale just moved.
    LaunchedEffect(pixelsPerSecond) {
        val previous = lastPixelsPerSecond
        lastPixelsPerSecond = pixelsPerSecond
        if (previous > 0f && previous != pixelsPerSecond && hScroll.value > 0)
            hScroll.scrollTo((hScroll.value * pixelsPerSecond / previous).roundToInt())
    }

    // The session is the editing model; the snapshot is how a clip becomes one.
    // Reloading only when the session does not already match the clip is what
    // lets an edit survive the timeline change its own commit provoked.
    val session = remember(trackIndex, clipId, host.projectRevision) {
        val snapshot = model.pianoRollClipSnapshot(trackIndex, clipId, 4.0)
        val opened = model.openPianoRollSession(trackIndex, clipId)
        if (opened != null && snapshot != null && !opened.matchesSource(snapshot))
            opened.loadNotes(snapshot)
        snapshot?.close()
        opened
    }

    // Closing releases the session the model is holding for this clip.
    DisposableEffect(trackIndex, clipId) {
        onDispose { model.closePianoRollSession(trackIndex, clipId) }
    }

    // `deleted` notes keep their slot so indexes stay stable across an edit, so
    // the visible list carries its own index along for every session call.
    val notes: List<IndexedNote> = remember(session, revision) {
        session?.notes.orEmpty()
            .mapIndexed { index, note -> IndexedNote(index, note) }
            .filter { !it.note.deleted }
    }
    val selectedCount = remember(session, revision) { session?.selectedNoteCount ?: 0 }

    val highest = TopNote
    val clipSeconds = remember(session, revision) { session?.durationSeconds ?: 4.0 }
    // Only sets where the snap lines fall; the session itself is in seconds.
    val bpm = remember(host.timeline?.tempo) {
        host.timeline?.tempo?.takeIf { it > 0.0 } ?: 120.0
    }
    val snapSeconds = remember(snapIndex, bpm) {
        val beats = SnapBeats[snapIndex.coerceIn(0, SnapBeats.lastIndex)]
        if (beats > 0f) beats * 60.0 / bpm else 0.0
    }

    /** Content width: the clip's span plus a bar to add notes past the end. */
    val contentSeconds = remember(notes, clipSeconds, bpm) {
        val last = notes.maxOfOrNull { it.note.startSeconds + it.note.durationSeconds } ?: 0.0
        (maxOf(last, clipSeconds) + 4.0 * 60.0 / bpm).coerceAtLeast(2.0)
    }

    fun snap(seconds: Double): Double =
        if (snapSeconds > 0.0) kotlin.math.round(seconds / snapSeconds) * snapSeconds else seconds

    /**
     * Writes the session back to the clip. Recording the commit source first is
     * what stops the timeline's resulting change notification from being read as
     * someone else's edit and reloading the editor out from under the user.
     */
    fun commit(what: String) {
        val s = session ?: return
        model.recordPianoRollCommitSource(trackIndex, clipId)
        val ok = s.commit(model)
        status = if (ok) null else s.error.ifEmpty { "The engine rejected the $what." }
        host.invalidateMidiCache()
        revision++
    }

    fun act(action: PianoRollAction, pasteSeconds: Double = 0.0, what: String) {
        val s = session ?: return
        s.performAction(action, pasteSeconds)
        commit(what)
    }

    /**
     * Auditions a pitch. Every note-on here has to be matched by a note-off: the
     * synth holds the note until it gets one, so previewing without releasing leaves
     * notes sounding forever and eventually jams every voice.
     */
    fun previewOn(note: Int) {
        host.trackInstances.getOrNull(trackIndex)?.firstOrNull()?.let { inst ->
            host.model.sequencer.engine.sendNoteOn(inst.instanceId, note)
        }
    }

    fun previewOff(note: Int) {
        host.trackInstances.getOrNull(trackIndex)?.firstOrNull()?.let { inst ->
            host.model.sequencer.engine.sendNoteOff(inst.instanceId, note)
        }
    }

    // Opens centred on the clip's own pitch range, as uapmd-app does
    // (`showClip`, :180): the midpoint of min..max, backed off eight rows. Scrolling
    // to the *top* note instead opens a whole song on its highest outlier, with the
    // bulk of the music below the viewport.
    var scrolledToContent by remember(clipId) { mutableStateOf(false) }
    LaunchedEffect(notes, clipId, noteHeight, session) {
        if (!scrolledToContent && notes.isNotEmpty()) {
            val s = session
            val mid = if (s != null && s.maxNote >= s.minNote) (s.minNote + s.maxNote) * 0.5f else 60f
            val midRow = (NoteCount - 1) - mid
            vScroll.scrollTo(((midRow - 8f).coerceAtLeast(0f) * noteHeight).toInt())
            if (initialScrollSeconds > 0f)
                hScroll.scrollTo((initialScrollSeconds * pixelsPerSecond).toInt())
            scrolledToContent = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Beats", style = MaterialTheme.typography.bodySmall)
            // Compose has no logarithmic slider, so the control carries log2 of
            // the beat count: each step doubles, which is what makes the range
            // from one bar to a whole song usable on one short slider.
            Slider(
                value = log2(visibleBeats.coerceIn(MinVisibleBeats, MaxVisibleBeats)),
                onValueChange = { visibleBeats = (2f).pow(it).coerceIn(MinVisibleBeats, MaxVisibleBeats) },
                valueRange = log2(MinVisibleBeats)..log2(MaxVisibleBeats),
                modifier = Modifier.width(120.dp)
            )
            Text(fixed(visibleBeats.toDouble(), 1), style = MaterialTheme.typography.labelSmall)
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

        // Clipboard row. These act on the selection through the session, which is
        // where the note clipboard lives, so they behave as uapmd-app's do.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = { act(PianoRollAction.Copy, what = "copy") },
                enabled = selectedCount > 0
            ) { Text("Copy") }
            Button(
                onClick = { act(PianoRollAction.Cut, what = "cut") },
                enabled = selectedCount > 0
            ) { Text("Cut") }
            Button(
                onClick = { act(PianoRollAction.Paste, host.playheadSeconds, what = "paste") },
                enabled = (session?.clipboardCount ?: 0) > 0
            ) { Text("Paste") }
            Button(onClick = { act(PianoRollAction.SelectAll, what = "select all") }) { Text("Select All") }
            Button(
                onClick = { act(PianoRollAction.Delete, what = "delete") },
                enabled = selectedCount > 0
            ) { Text("Delete") }
        }

        // The focused note's detail, which uapmd-app edits in its side panel (:1363).
        val focused = session?.focusedNote?.takeIf { it >= 0 }
            ?.let { idx -> notes.firstOrNull { it.index == idx } }
        focused?.let { entry ->
            val note = entry.note
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${noteName(note.note)} · vel ${(note.velocity * 127).toInt()}" +
                        (if (note.attributeType != 0) " · attr ${note.attributeType}/${note.attributeValue}" else "") +
                        (if (note.automationEventCount > 0) " · ${note.automationEventCount} automation" else ""),
                    style = MaterialTheme.typography.bodySmall
                )
                Text("ch ${note.channel} · grp ${note.umpGroup}", style = MaterialTheme.typography.labelSmall)
            }
        }

        Text(
            "${notes.size} notes" +
                (if (selectedCount > 0) " · $selectedCount selected" else "") +
                " · double-tap empty space to add, a note to delete · drag to move, edges to resize",
            style = MaterialTheme.typography.bodySmall
        )
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        // The grid is a scrollable viewport over content sized to the whole clip and
        // all 128 notes, rather than a screen-sized canvas drawn at an offset. That
        // is what gives the wheel, the trackpad and a scrollbar something to move,
        // and it leaves pointer coordinates in content space so hit testing needs no
        // scroll arithmetic at all.
        val contentWidth = with(LocalDensity.current) {
            ((contentSeconds * pixelsPerSecond).toFloat().coerceAtLeast(1f)).toDp()
        }
        val contentHeight = with(LocalDensity.current) { (NoteCount * noteHeight).toDp() }

        // Scrollbars on the viewport's edges, where the pointer can always reach them.
        // A clip that is full of notes leaves no empty grid to drag, and a drag that
        // starts on a note moves the note, so the grid cannot be its own scroller.
        Row(Modifier.fillMaxWidth().weight(1f)) {
            PianoKeyColumn(
                noteHeight = noteHeight,
                contentHeight = contentHeight,
                vScroll = vScroll,
                highest = highest,
                previewNote = previewNote,
                onKeyDown = { previewNote = it; previewOn(it) },
                onKeyUp = { previewOff(it); previewNote = null }
            )
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll).verticalScroll(vScroll)) {
                Box(Modifier.size(contentWidth, contentHeight)
                    // Press selects, with uapmd-app's modifier rules: plain click
                    // replaces unless the note is already selected (so a multi-note
                    // drag does not collapse to one), ctrl/cmd toggles, shift extends.
                    .pointerInput(notes, pixelsPerSecond, noteHeight, session) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type != PointerEventType.Press) continue
                                if (event.buttons.isSecondaryPressed) continue
                                val position = event.changes.firstOrNull()?.position ?: continue
                                val s = session ?: continue
                                val hit = noteAtPoint(notes, position, pixelsPerSecond, noteHeight, highest)
                                    ?: continue
                                val toggle = event.keyboardModifiers.isCtrlPressed ||
                                    event.keyboardModifiers.isMetaPressed
                                val extend = event.keyboardModifiers.isShiftPressed
                                if (toggle || extend || !s.isNoteSelected(hit.index))
                                    s.selectNote(hit.index, extend || toggle, toggle)
                                else
                                    s.focusedNote = hit.index
                                revision++
                            }
                        }
                    }
                    .pointerInput(notes, pixelsPerSecond, noteHeight, session) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val s = session
                                val hit = noteAtPoint(notes, offset, pixelsPerSecond, noteHeight, highest)
                                if (s == null || hit == null) {
                                    dragMode = DragMode.None
                                    dragIndex = -1
                                    return@detectDragGestures
                                }
                                dragIndex = hit.index
                                dragOriginStart = hit.note.startSeconds
                                dragOriginEnd = hit.note.startSeconds + hit.note.durationSeconds
                                dragOriginNote = hit.note.note
                                // Edge zones, as uapmd-app sizes them: 8px, but never
                                // more than 30% of the note, so a short note still has
                                // a middle you can grab to move it (:1045).
                                val width = (hit.note.durationSeconds * pixelsPerSecond).toFloat().coerceAtLeast(2f)
                                val edge = minOf(ResizeEdgePx, width * 0.3f)
                                val x0 = (hit.note.startSeconds * pixelsPerSecond).toFloat()
                                // A multi-note selection always moves: resizing one
                                // edge of many notes at once is not a gesture.
                                dragMode = when {
                                    s.selectedNoteCount > 1 -> DragMode.Move
                                    offset.x >= x0 + width - edge -> DragMode.ResizeRight
                                    offset.x <= x0 + edge -> DragMode.ResizeLeft
                                    else -> DragMode.Move
                                }
                                if (dragMode == DragMode.Move) {
                                    // The session snapshots the selection here, so
                                    // each update applies one delta from the start
                                    // rather than accumulating rounding.
                                    if (!s.isNoteSelected(hit.index))
                                        s.selectNote(hit.index, false, false)
                                    s.beginDrag()
                                }
                            },
                            onDragEnd = {
                                val s = session
                                if (s != null && dragIndex >= 0 && dragMode != DragMode.None) {
                                    s.finishDrag(dragIndex, dragOriginStart, dragOriginEnd, dragOriginNote)
                                    commit(if (dragMode == DragMode.Move) "move" else "resize")
                                }
                                dragMode = DragMode.None
                                dragIndex = -1
                            },
                            onDragCancel = {
                                if (dragMode == DragMode.Move) session?.cancelDrag()
                                dragMode = DragMode.None
                                dragIndex = -1
                                revision++
                            }
                        ) { change, _ ->
                            val s = session
                            if (s == null || dragMode == DragMode.None || dragIndex < 0)
                                return@detectDragGestures
                            // Only consumed when it is a note edit, so a drag that
                            // grabbed nothing still reaches the scroll containers.
                            change.consume()
                            val dx = (change.position.x - (dragOriginStart * pixelsPerSecond).toFloat())
                            when (dragMode) {
                                DragMode.Move -> {
                                    val rawStart = change.position.x / pixelsPerSecond
                                    val timeDelta = snap(rawStart.toDouble()) - dragOriginStart
                                    val pitch = (highest - (change.position.y / noteHeight).toInt())
                                        .coerceIn(0, 127)
                                    s.moveSelection(timeDelta, pitch - dragOriginNote)
                                }
                                DragMode.ResizeRight -> {
                                    val newEnd = snap((change.position.x / pixelsPerSecond).toDouble())
                                    s.resizeNote(
                                        dragIndex, dragOriginStart,
                                        maxOf(MinNoteSeconds, newEnd - dragOriginStart), dragOriginNote
                                    )
                                }
                                DragMode.ResizeLeft -> {
                                    val newStart = snap((change.position.x / pixelsPerSecond).toDouble())
                                        .coerceIn(0.0, dragOriginEnd - MinNoteSeconds)
                                    s.resizeNote(
                                        dragIndex, newStart, dragOriginEnd - newStart, dragOriginNote
                                    )
                                }
                                DragMode.None -> Unit
                            }
                            revision++
                        }
                    }
                    .pointerInput(notes, pixelsPerSecond, noteHeight, session) {
                        detectTapGestures(
                            onPress = { offset ->
                                // Audition on press and release on lift, so a preview
                                // cannot leave a note sounding.
                                val hit = noteAtPoint(notes, offset, pixelsPerSecond, noteHeight, highest)
                                if (hit != null) {
                                    previewOn(hit.note.note)
                                    tryAwaitRelease()
                                    previewOff(hit.note.note)
                                }
                            },
                            onDoubleTap = { offset ->
                                val s = session ?: return@detectTapGestures
                                val hit = noteAtPoint(notes, offset, pixelsPerSecond, noteHeight, highest)
                                if (hit != null) {
                                    s.deleteNote(hit.index)
                                    commit("delete")
                                } else {
                                    val start = snap((offset.x / pixelsPerSecond).toDouble())
                                        .coerceAtLeast(0.0)
                                    val pitch = (highest - (offset.y / noteHeight).toInt()).coerceIn(0, 127)
                                    val quarter = 60.0 / bpm
                                    s.createNote(start, quarter, pitch, DefaultNoteVelocity)
                                    commit("insert")
                                }
                            }
                        )
                    }) {
                    Canvas(Modifier.fillMaxSize()) {
                        for (r in 0 until NoteCount) {
                            val midi = highest - r
                            val y = r * noteHeight
                            drawRect(
                                if (BlackKeys.contains(((midi % 12) + 12) % 12)) c.rowBlack else c.rowWhite,
                                Offset(0f, y), Size(size.width, noteHeight - 0.5f)
                            )
                        }
                        if (snapSeconds > 0.0 && snapSeconds * pixelsPerSecond >= 3.0) {
                            var t = 0.0
                            while (t * pixelsPerSecond < size.width) {
                                val x = (t * pixelsPerSecond).toFloat()
                                drawLine(c.gridLine, Offset(x, 0f), Offset(x, size.height), 1f)
                                t += snapSeconds
                            }
                        }
                        // Drawn straight from the session: a drag has already been
                        // applied to it, so there is no pending offset to add here.
                        notes.forEach { entry ->
                            val n = entry.note
                            val isSelected = session?.isNoteSelected(entry.index) == true
                            val x = (n.startSeconds * pixelsPerSecond).toFloat()
                            val w = (n.durationSeconds * pixelsPerSecond).toFloat().coerceAtLeast(2f)
                            val y = (highest - n.note) * noteHeight
                            drawRect(
                                if (isSelected) c.noteSelected
                                else c.noteFill.copy(alpha = 0.4f + 0.6f * n.velocity),
                                Offset(x, y + 1f), Size(w, noteHeight - 2f)
                            )
                        }
                    }
                }
            }
            VerticalEditorScrollbar(vScroll, Modifier.fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth()) {
            // Indented past the key column so the bar spans exactly the grid it moves.
            Spacer(Modifier.width(KeyColumnWidth))
            HorizontalEditorScrollbar(hScroll, Modifier.weight(1f))
            // The corner the two bars would otherwise fight over.
            Spacer(Modifier.width(EditorScrollbarThickness))
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
    contentHeight: Dp,
    vScroll: ScrollState,
    highest: Int,
    previewNote: Int?,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit
) {
    val c = editorPalette
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = c.keyLabel)
    // Shares the grid's vertical scroll state, so the keys cannot drift out of line
    // with the rows they name.
    Box(Modifier.width(KeyColumnWidth).fillMaxHeight().verticalScroll(vScroll)) {
        Canvas(
            Modifier.width(KeyColumnWidth).height(contentHeight)
                .pointerInput(noteHeight, highest) {
                    detectTapGestures(onPress = { offset ->
                        val pitch = highest - (offset.y / noteHeight).toInt()
                        if (pitch in 0..127) {
                            onKeyDown(pitch)
                            // Held for as long as the key is held, then released:
                            // a preview that never sends note-off jams the synth.
                            tryAwaitRelease()
                            onKeyUp(pitch)
                        }
                    })
                }
        ) {
            drawRect(c.keyPanelBackground, Offset.Zero, Size(size.width, size.height))
            val blackWidth = size.width * 0.62f
            for (r in 0 until NoteCount) {
                val midi = highest - r
                if (midi < 0) break
                val y = r * noteHeight
                val isBlack = BlackKeys.contains(((midi % 12) + 12) % 12)
                val preview = midi == previewNote
                if (isBlack) {
                    drawRect(
                        if (preview) c.keyPreviewBlack else c.keyBlack,
                        Offset(0f, y), Size(blackWidth, noteHeight - 0.5f)
                    )
                } else {
                    drawRect(
                        if (preview) c.keyPreviewWhite else c.keyWhite,
                        Offset(0f, y), Size(size.width, noteHeight - 0.5f)
                    )
                    if (((midi % 12) + 12) % 12 == 0 && noteHeight >= MinLabelRowPx) {
                        val layout = measurer.measure(
                            noteName(midi),
                            labelStyle.copy(fontSize = (noteHeight * 0.62f).toSp())
                        )
                        drawText(layout, topLeft = Offset(2f, y + (noteHeight - layout.size.height) / 2f))
                    }
                }
                drawLine(c.keySeparator, Offset(0f, y + noteHeight - 0.5f), Offset(size.width, y + noteHeight - 0.5f), 1f)
            }
            drawLine(c.gridLine, Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), 1f)
        }
    }
}

private val NoteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** uapmd-app's `fullNoteName`: name plus octave, numbering octaves as note / 12. */
private fun noteName(midi: Int) = "${NoteNames[((midi % 12) + 12) % 12]}${midi / 12}"

/**
 * A session note with the index it sits at.
 *
 * Every session call addresses a note by its position in `editNotes`, which keeps
 * deleted notes so that indexes stay stable across an edit. The drawn list skips
 * those, so each visible note has to carry its real index along.
 */
private data class IndexedNote(val index: Int, val note: PianoRollNote)

/** [offset] is in content coordinates, which is what the scrolled canvas reports. */
private fun noteAtPoint(
    notes: List<IndexedNote>,
    offset: Offset,
    pixelsPerSecond: Float,
    noteHeight: Float,
    highest: Int
): IndexedNote? = notes.firstOrNull { entry ->
    val n = entry.note
    val x = (n.startSeconds * pixelsPerSecond).toFloat()
    val w = (n.durationSeconds * pixelsPerSecond).toFloat().coerceAtLeast(2f)
    val y = (highest - n.note) * noteHeight
    offset.x in x..(x + w) && offset.y in y..(y + noteHeight)
}
