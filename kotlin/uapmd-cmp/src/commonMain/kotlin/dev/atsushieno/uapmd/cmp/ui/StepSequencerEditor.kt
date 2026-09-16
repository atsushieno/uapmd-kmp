package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.cmp.StepSequencerModel
import dev.atsushieno.uapmd.cmp.StepSequencerModel.NoteSet
import dev.atsushieno.uapmd.cmp.StepSequencerModel.Pattern
import dev.atsushieno.uapmd.cmp.UapmdHost

private val StepCell = 26.dp
private val LaneLabelWidth = 190.dp
private val LaneHeight = 26.dp
private val VelocityPanelHeight = 140.dp

/** MIDI 36 (C2), the usual kick-drum root a GM pattern is built around. */
private const val DrumRootNote = 36

/**
 * A step editor for a MIDI clip, following `uapmd-app/gui/StepSequencerEditor.cpp`.
 *
 * The clip is the only storage: the pattern is baked into ordinary MIDI 2.0
 * notes with the grid and loop length recorded beside them as Flex Data. All of
 * that lives in [StepSequencerModel]; this is its editor.
 *
 * Every edit is written to the clip as it is made — there is no Apply button,
 * only Reload to go back to what the clip holds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepSequencerEditor(host: UapmdHost, trackIndex: Int, clipId: Int) {
    val loaded = remember(trackIndex, clipId, host.projectRevision) {
        host.readStepPattern(trackIndex, clipId)
    }
    var pattern by remember(trackIndex, clipId) {
        mutableStateOf(loaded ?: host.newStepPattern(trackIndex, clipId))
    }
    var opened by remember(trackIndex, clipId) { mutableStateOf(loaded != null) }
    var closed by remember(trackIndex, clipId) { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var divisionMenu by remember { mutableStateOf(false) }
    var noteSetMenu by remember { mutableStateOf(false) }

    // What a newly activated step is given. Editor state, not part of the
    // pattern: the clip records what each step actually holds, not the defaults
    // it was drawn with, so these survive a reload untouched.
    var defaultVelocity by remember { mutableStateOf(0.8f) }
    var defaultGate by remember { mutableStateOf(0.8f) }
    var focusedDrumRoot by remember(trackIndex, clipId) { mutableStateOf(false) }

    if (closed) return

    if (!opened) {
        AlertDialog(
            onDismissRequest = { closed = true },
            title = { Text("Clip not from Step Sequencer") },
            text = {
                Text(
                    "This clip has no step sequencer Flex Data metadata and is not recognized " +
                        "as originating from the step sequencer.\n\n" +
                        "Edits apply immediately and may discard unsupported notes or other " +
                        "clip data. Opening alone will not change the clip."
                )
            },
            confirmButton = { TextButton(onClick = { closed = true }) { Text("Close without changes") } },
            dismissButton = { TextButton(onClick = { opened = true }) { Text("Open anyway") } }
        )
        return
    }

    fun edit(transform: (Pattern) -> Pattern) {
        pattern = transform(pattern)
        status = host.applyStepPattern(trackIndex, clipId, pattern) ?: ""
    }

    fun reload() {
        pattern = host.readStepPattern(trackIndex, clipId)
            ?: host.newStepPattern(trackIndex, clipId)
        status = ""
    }

    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Pattern: ${pattern.patternSteps} steps at " +
                    StepSequencerModel.divisionLabel(pattern.divisionIndex),
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                "Loop repetitions: ${pattern.repetitions}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Note set", style = MaterialTheme.typography.labelSmall)
            Box(Modifier.padding(horizontal = 6.dp)) {
                Button(onClick = { noteSetMenu = true }) {
                    Text(if (pattern.noteSet == NoteSet.GmDrums) "GM Drums" else "All Notes")
                }
                DropdownMenu(expanded = noteSetMenu, onDismissRequest = { noteSetMenu = false }) {
                    listOf(NoteSet.GmDrums to "GM Drums", NoteSet.AllNotes to "All Notes").forEach { (set, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                noteSetMenu = false
                                // uapmd-app re-reads the clip on a note-set change
                                // rather than relabelling the lanes it already has.
                                pattern = (host.readStepPattern(trackIndex, clipId)
                                    ?: host.newStepPattern(trackIndex, clipId)).withNoteSet(set)
                                focusedDrumRoot = false
                            }
                        )
                    }
                }
            }
            Text("Division", style = MaterialTheme.typography.labelSmall)
            Box(Modifier.padding(horizontal = 6.dp)) {
                Button(onClick = { divisionMenu = true }) {
                    Text(StepSequencerModel.divisionLabel(pattern.divisionIndex))
                }
                DropdownMenu(expanded = divisionMenu, onDismissRequest = { divisionMenu = false }) {
                    StepSequencerModel.Divisions.indices.forEach { index ->
                        DropdownMenuItem(
                            text = { Text(StepSequencerModel.divisionLabel(index)) },
                            onClick = { divisionMenu = false; edit { it.copy(divisionIndex = index) } }
                        )
                    }
                }
            }
        }

        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Steps ${pattern.patternSteps}", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = pattern.patternSteps.toFloat(),
                onValueChange = { edit { p -> p.resized(it.toInt()) } },
                valueRange = 1f..StepSequencerModel.MaxSteps.toFloat(),
                modifier = Modifier.width(120.dp).padding(horizontal = 6.dp)
            )
            Text("Repetitions ${pattern.repetitions}", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = pattern.repetitions.toFloat(),
                onValueChange = { edit { p -> p.copy(repetitions = it.toInt().coerceAtLeast(1)) } },
                valueRange = 1f..StepSequencerModel.MaxRepetitions.toFloat(),
                modifier = Modifier.width(120.dp).padding(horizontal = 6.dp)
            )
        }

        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Default Velocity ${fixed(defaultVelocity.toDouble(), 2)}",
                style = MaterialTheme.typography.labelSmall)
            Slider(
                value = defaultVelocity,
                onValueChange = { defaultVelocity = it; edit { p -> p } },
                modifier = Modifier.width(100.dp).padding(horizontal = 6.dp)
            )
            Text("Default Gate ${fixed(defaultGate.toDouble(), 2)}",
                style = MaterialTheme.typography.labelSmall)
            Slider(
                value = defaultGate,
                onValueChange = { defaultGate = it; edit { p -> p } },
                valueRange = 0.05f..1f,
                modifier = Modifier.width(100.dp).padding(horizontal = 6.dp)
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text(
            "Pattern length: ${pattern.patternTicks} ticks | " +
                "Expanded length: ${pattern.patternTicks * pattern.repetitions} ticks",
            style = MaterialTheme.typography.labelSmall
        )

        // ── Lanes ────────────────────────────────────────────────────────────
        val hScroll = rememberScrollState()
        val vScroll = rememberScrollState()
        val density = LocalDensity.current

        // Every lane is drawn; the grid simply opens scrolled to the kick-drum
        // root so a GM pattern's rows are the ones in view.
        val drumRootOffsetPx = with(density) {
            (LaneHeight * pattern.lanes.indexOfFirst { it.note == DrumRootNote }.coerceAtLeast(0)).toPx()
        }
        LaunchedEffect(pattern.noteSet, drumRootOffsetPx) {
            if (focusedDrumRoot || pattern.noteSet != NoteSet.GmDrums) return@LaunchedEffect
            if (drumRootOffsetPx > 0f) {
                vScroll.scrollTo(drumRootOffsetPx.toInt())
                focusedDrumRoot = true
            }
        }

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(vScroll)) {
            Row {
                Text("Note", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(LaneLabelWidth))
                Row(Modifier.horizontalScroll(hScroll)) {
                    (0 until pattern.patternSteps).forEach { step ->
                        Box(Modifier.size(StepCell, 16.dp), contentAlignment = Alignment.Center) {
                            Text("${step + 1}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            HorizontalDivider()

            pattern.lanes.forEach { lane ->
                val laneColor = noteColor(lane.note)
                val label = StepSequencerModel.noteLabel(lane.note, pattern.noteSet == NoteSet.GmDrums)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = laneColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(LaneLabelWidth).padding(end = 4.dp)
                    )
                    Row(Modifier.horizontalScroll(hScroll)) {
                        lane.steps.forEachIndexed { index, step ->
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
                                tooltip = {
                                    PlainTooltip {
                                        Text(
                                            "$label | Step ${index + 1} | " +
                                                "velocity ${fixed(step.velocity.toDouble(), 2)} | " +
                                                "gate ${fixed(step.gate * 100.0, 0)}%\n" +
                                                "Right-click to edit note attributes."
                                        )
                                    }
                                },
                                state = rememberTooltipState()
                            ) {
                                Box(
                                    Modifier
                                        .size(StepCell, LaneHeight)
                                        .padding(1.dp)
                                        .background(
                                            // Inactive cells are the lane's own
                                            // colour heavily dimmed; active ones
                                            // scale with velocity, so dynamics
                                            // read straight off the grid.
                                            if (step.active)
                                                laneColor.dimmed(0.50f + 0.38f * step.velocity.coerceIn(0f, 1f))
                                            else laneColor.dimmed(0.24f)
                                        )
                                        // Right-click opens the note editor, as
                                        // upstream does; touch has no right
                                        // button, so double-tap and long-press
                                        // open it too.
                                        .pointerInput(lane.note, index) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    if (event.type == PointerEventType.Press &&
                                                        event.buttons.isSecondaryPressed
                                                    ) {
                                                        detail = lane.note to index
                                                        event.changes.forEach { it.consume() }
                                                    }
                                                }
                                            }
                                        }
                                        .pointerInput(lane.note, index, defaultVelocity, defaultGate) {
                                            detectTapGestures(
                                                onTap = {
                                                    edit {
                                                        it.toggle(lane.note, index, defaultVelocity, defaultGate)
                                                    }
                                                },
                                                onDoubleTap = { detail = lane.note to index },
                                                onLongPress = { detail = lane.note to index }
                                            )
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Velocity / automation ────────────────────────────────────────────
        HorizontalDivider(Modifier.padding(top = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Velocity / automation", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Each active note is shown separately; editing will be added in-place later.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        VelocityMeter(pattern)

        Row(
            Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Changes apply immediately", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { reload() }) { Text("Reload") }
            if (status.isNotEmpty())
                Text(status, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
        }
    }

    detail?.let { (note, index) ->
        NoteAttributeDialog(
            pattern = pattern,
            note = note,
            step = index,
            onDismiss = { detail = null },
            onEdit = { transform -> edit(transform) }
        )
    }
}

/**
 * Per-step note editor: velocity, and the MIDI 2.0 per-note attribute the clip
 * round-trips. Gate is deliberately absent — upstream takes it from Default Gate
 * when a step is activated and only reports it in the tooltip.
 */
@Composable
private fun NoteAttributeDialog(
    pattern: Pattern,
    note: Int,
    step: Int,
    onDismiss: () -> Unit,
    onEdit: ((Pattern) -> Pattern) -> Unit
) {
    val lane = pattern.lanes.getOrNull(pattern.laneIndex(note))
    val cell = lane?.steps?.getOrNull(step)
    if (lane == null || cell == null) {
        onDismiss()
        return
    }
    val label = StepSequencerModel.noteLabel(note, pattern.noteSet == NoteSet.GmDrums)
    var attributeTypeMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$label, step ${step + 1}") },
        text = {
            if (!cell.active) {
                Text("Activate this step to edit its note.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column {
                    Text("Velocity", style = MaterialTheme.typography.labelSmall)
                    TypeableSlider(
                        value = cell.velocity,
                        valueRange = 0f..1f,
                        format = { fixed(it.toDouble(), 3) },
                        parse = { it.toFloatOrNull()?.coerceIn(0f, 1f) },
                        onValueChange = { v ->
                            onEdit { it.withStep(note, step) { s -> s.copy(velocity = v) } }
                        }
                    )

                    Text("Note attribute type", style = MaterialTheme.typography.labelSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Held as text rather than bound straight to the value:
                        // a field that re-derives itself from the committed
                        // number cannot be cleared, and fights every keystroke
                        // on the way to a two-digit entry.
                        var typedType by remember(note, step) {
                            mutableStateOf(cell.attributeType.toString())
                        }
                        OutlinedTextField(
                            value = typedType,
                            onValueChange = { text ->
                                typedType = text.filter { ch -> ch.isDigit() }.take(3)
                                typedType.toIntOrNull()?.let { t ->
                                    val clamped = t.coerceIn(0, 127)
                                    onEdit { it.withStep(note, step) { s -> s.copy(attributeType = clamped) } }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.width(140.dp)
                        )
                        Box {
                            TextButton(onClick = { attributeTypeMenu = true }) { Text("▾") }
                            DropdownMenu(
                                expanded = attributeTypeMenu,
                                onDismissRequest = { attributeTypeMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Pitch 7.9 (3)") },
                                    onClick = {
                                        attributeTypeMenu = false
                                        typedType = "3"
                                        onEdit { it.withStep(note, step) { s -> s.copy(attributeType = 3) } }
                                    }
                                )
                            }
                        }
                    }

                    Text("Note attribute value", style = MaterialTheme.typography.labelSmall)
                    TypeableSlider(
                        value = cell.attributeValue.toFloat(),
                        valueRange = 0f..65535f,
                        format = { it.toInt().toString() },
                        parse = { it.toIntOrNull()?.coerceIn(0, 65535)?.toFloat() },
                        onValueChange = { v ->
                            onEdit { it.withStep(note, step) { s -> s.copy(attributeValue = v.toInt()) } }
                        }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/**
 * A slider a double-click turns into a text field, which is how uapmd-app lets
 * an exact value be typed into one.
 *
 * The field opens focused with its text selected, Enter commits, and Escape or
 * clicking away cancels — the same bargain upstream's inline editor makes, so a
 * mistyped value never reaches the clip just because focus moved.
 */
@Composable
private fun TypeableSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    parse: (String) -> Float?,
    onValueChange: (Float) -> Unit
) {
    var typing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }

    fun commit() {
        parse(text.text)?.let(onValueChange)
        typing = false
    }

    if (typing) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { if (typing && !it.isFocused) typing = false }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> { commit(); true }
                        Key.Escape -> { typing = false; true }
                        else -> false
                    }
                }
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value.coerceIn(valueRange),
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.weight(1f).pointerInput(value) {
                    detectTapGestures(
                        onDoubleTap = {
                            // Opens with everything selected, so typing replaces
                            // rather than appends.
                            val shown = format(value)
                            text = TextFieldValue(shown, TextRange(0, shown.length))
                            typing = true
                        }
                    )
                }
            )
            Text(format(value), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/**
 * One bar per active note per step, side by side, height proportional to
 * velocity and coloured by note — uapmd-app's velocity/automation panel.
 */
@Composable
private fun VelocityMeter(pattern: Pattern) {
    val c = editorPalette
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    val stepWidthPx = with(density) { StepCell.toPx() }
    val labelWidthPx = with(density) { LaneLabelWidth.toPx() }
    val contentWidth = LaneLabelWidth + StepCell * pattern.patternSteps

    Box(Modifier.fillMaxWidth().height(VelocityPanelHeight).horizontalScroll(hScroll)) {
        Canvas(Modifier.width(contentWidth).height(VelocityPanelHeight)) {
            val bottom = size.height
            drawLine(c.gridLine, Offset(labelWidthPx, bottom), Offset(size.width, bottom), 1f)
            (0 until pattern.patternSteps).forEach { stepIndex ->
                val x = labelWidthPx + stepIndex * stepWidthPx
                drawLine(c.tableRowDivider, Offset(x, 0f), Offset(x, bottom), 1f)
                val active = pattern.lanes.filter { it.steps.getOrNull(stepIndex)?.active == true }
                if (active.isEmpty()) return@forEach
                val barWidth = (stepWidthPx - 2f) / active.size
                active.forEachIndexed { i, lane ->
                    val barHeight = lane.steps[stepIndex].velocity.coerceIn(0f, 1f) * bottom
                    drawRect(
                        color = noteColor(lane.note),
                        topLeft = Offset(x + 1f + i * barWidth, bottom - barHeight),
                        size = Size(maxOf(1f, barWidth - 1f), barHeight)
                    )
                }
            }
            val end = labelWidthPx + pattern.patternSteps * stepWidthPx
            drawLine(c.tableRowDivider, Offset(end, 0f), Offset(end, bottom), 1f)
        }
    }
}

/** The eight lane colours uapmd-app cycles through by note number. */
private val LaneColors = listOf(
    Color(0.30f, 0.66f, 0.87f), Color(0.37f, 0.75f, 0.62f),
    Color(0.76f, 0.63f, 0.31f), Color(0.78f, 0.45f, 0.53f),
    Color(0.58f, 0.52f, 0.84f), Color(0.36f, 0.70f, 0.72f),
    Color(0.72f, 0.58f, 0.70f), Color(0.63f, 0.70f, 0.38f)
)

private fun noteColor(note: Int) = LaneColors[note.mod(LaneColors.size)]

/** Scales a colour towards black, keeping its alpha, as uapmd-app's dimmed() does. */
private fun Color.dimmed(factor: Float) = Color(red * factor, green * factor, blue * factor, alpha)
