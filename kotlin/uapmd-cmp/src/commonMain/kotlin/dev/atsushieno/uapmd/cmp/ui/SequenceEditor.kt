package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.AnchorOrigin
import dev.atsushieno.uapmd.ClipData
import dev.atsushieno.uapmd.TimeReference
import dev.atsushieno.uapmd.TimeReferenceType
import dev.atsushieno.uapmd.TrackProperties
import dev.atsushieno.uapmd.cmp.UapmdHost
import dev.atsushieno.uapmd.cmp.pickForClip
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * uapmd-app's Track Details window, opened per track by Clips ▸ "Track Details…"
 * (`SequenceEditor::showWindow(trackIndex)`): the track's properties (color,
 * organizational path and order, `SequenceEditor::renderTrackProperties`) above
 * its clip table.
 *
 * The clip part is a TABLE, not a second timeline — the main timeline's lanes are already
 * this class's unified view (`TimelineEditor.cpp:1016`), so the window adds the
 * columns a lane cannot show. `SequenceEditor::renderClipRow` (:936) is the
 * reference: Anchor | Origin | Position | Name | File (+Change) | Delete.
 *
 * Anchor and Origin are the point of the window: a clip can be anchored to its
 * track or to another clip, measured from that anchor's start or end, with the
 * Position column holding the offset. All three go through one
 * `ProjectCommands.setClipAnchor`, so an edit is a single undo step.
 */


private val AnchorWidth = 150.dp
private val OriginWidth = 90.dp
private val PositionWidth = 110.dp
private val NameWidth = 160.dp
private val FileWidth = 220.dp
private val DeleteWidth = 90.dp

private val CompactPadding =
    androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)

/** uapmd-app grew the window from 350 to 480 high to fit the properties above the table. */
internal val TrackDetailsWindowSize = DpSize(840.dp, 480.dp)

/**
 * A text cell that commits when it loses focus, matching the
 * deactivate-after-edit behaviour of uapmd-app's ImGui inputs.
 */
@Composable
private fun CommitField(
    value: String,
    width: Dp,
    onCommit: (String) -> Unit
) {
    var text by remember(value) { mutableStateOf(value) }
    var wasFocused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(fontSize = MaterialTheme.typography.labelSmall.fontSize),
        modifier = Modifier.width(width).onFocusChanged { state ->
            if (wasFocused && !state.isFocused && text != value) onCommit(text)
            wasFocused = state.isFocused
        }
    )
}

@Composable
fun SequenceEditor(host: UapmdHost, windows: FloatingWindowManager, trackIndex: Int) {
    val c = editorPalette
    val clips = if (trackIndex == MasterTrackIndex) host.masterClips
    else host.trackClips.getOrNull(trackIndex).orEmpty()
    val sampleRate = (host.model.sampleRate.takeIf { it > 0 } ?: 48000).toDouble()
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    var status by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        TrackPropertiesSection(host, trackIndex)
        SeparatorText("Clips")
        Column(Modifier.weight(1f).horizontalScroll(hScroll)) {
            Row(Modifier.background(c.tableHeaderBackground).padding(vertical = 4.dp)) {
                HeaderCell("Anchor", AnchorWidth)
                HeaderCell("Origin", OriginWidth)
                HeaderCell("Position", PositionWidth)
                HeaderCell("Name", NameWidth)
                HeaderCell("File", FileWidth)
                HeaderCell("", DeleteWidth)
            }
            Column(Modifier.verticalScroll(vScroll)) {
                if (clips.isEmpty()) {
                    Text(
                        "No clips on this track.",
                        Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                clips.forEach { clip ->
                    ClipRow(host, windows, trackIndex, clip, clips, sampleRate) { status = it }
                    HorizontalDivider(color = c.tableRowDivider)
                }
            }
        }
        status?.let {
            Spacer4()
            Text(it, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Spacer4() = Box(Modifier.padding(2.dp)) {}

/** ImGui's SeparatorText: a label with a rule running on from it. */
@Composable
private fun SeparatorText(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        HorizontalDivider(Modifier.weight(1f).padding(start = 8.dp))
    }
}

/**
 * `SequenceEditor::renderTrackProperties`. Every change is one
 * `ProjectCommands.setTrackProperties` call, so it is a single undo step, and
 * properties the engine rejects (a non-finite order) leave the track unchanged.
 */
@Composable
private fun TrackPropertiesSection(host: UapmdHost, trackIndex: Int) {
    val properties = host.trackProperties(trackIndex)
    fun apply(updated: TrackProperties) {
        host.setTrackProperties(trackIndex, updated)
    }

    SeparatorText("Track Properties")

    // ── Color: a swatch that opens the picker, and a way back to the theme's
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            var pickerOpen by remember { mutableStateOf(false) }
            // An unset color shows the theme's header color, as uapmd-app's does.
            val swatch = properties.composeColor() ?: MaterialTheme.colorScheme.primaryContainer
            Box(
                Modifier.size(24.dp)
                    .background(swatch)
                    .border(1.dp, MaterialTheme.colorScheme.outline)
                    .clickable { pickerOpen = true }
            )
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                TrackColorPicker(
                    initial = swatch,
                    onApply = { pickerOpen = false; apply(properties.copy(color = it)) },
                    onCancel = { pickerOpen = false }
                )
            }
        }
        Text("Color", style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { apply(properties.copy(color = null)) },
            enabled = properties.color != null,
            contentPadding = CompactPadding
        ) { Text("Reset to Default", style = MaterialTheme.typography.labelSmall) }
    }
    Spacer4()

    // ── Path: an organizational string, edited in a modal as uapmd-app does
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var editing by remember { mutableStateOf(false) }
        Button(onClick = { editing = true }, contentPadding = CompactPadding) {
            Text("Edit Path…", style = MaterialTheme.typography.labelSmall)
        }
        Text(properties.path.ifEmpty { "(no path)" }, style = MaterialTheme.typography.bodySmall)
        if (editing)
            TrackPathDialog(
                initial = properties.path,
                onApply = { editing = false; apply(properties.copy(path = it)) },
                onDismiss = { editing = false }
            )
    }
    Spacer4()

    // ── Order: the master track stays first, regardless of its metadata
    if (trackIndex == MasterTrackIndex)
        return
    // The slider spans this track's path group with a step of room either
    // side, so that it can move past the first and last of its peers.
    val peers = host.trackPropertiesList + host.masterTrackProperties
    val range = remember(properties, peers) {
        var min = minOf(0.0, properties.order)
        var max = maxOf(1.0, properties.order)
        peers.filter { it.path == properties.path }.forEach {
            min = minOf(min, it.order)
            max = maxOf(max, it.order)
        }
        // Compose's slider is a Float one; keep the bounds representable.
        ((min - 1.0).coerceAtLeast(-OrderSliderLimit))..((max + 1.0).coerceAtMost(OrderSliderLimit))
    }
    // Only a drag in progress holds a value of its own; it is committed on release.
    var dragValue by remember(trackIndex) { mutableStateOf<Double?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Slider(
            value = (dragValue ?: properties.order).toFloat(),
            onValueChange = { dragValue = it.toDouble() },
            onValueChangeFinished = {
                dragValue?.let { if (it.isFinite()) apply(properties.copy(order = it)) }
                dragValue = null
            },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            modifier = Modifier.weight(1f)
        )
        CommitField(formatOrder(dragValue ?: properties.order), 110.dp) { entered ->
            entered.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { apply(properties.copy(order = it)) }
        }
        Text("Order", style = MaterialTheme.typography.bodySmall)
    }
}

/** ImGui's slider bound limit, scaled down to what a Float slider can represent. */
private const val OrderSliderLimit = Float.MAX_VALUE / 4.0

/** Up to six significant digits, as uapmd-app's `%.6g` shows the order. */
private fun formatOrder(order: Double): String {
    if (!order.isFinite() || abs(order) >= 1e15) return order.toString()
    if (order == kotlin.math.floor(order)) return order.toLong().toString()
    val magnitude = kotlin.math.floor(kotlin.math.log10(abs(order))).toInt()
    val decimals = (5 - magnitude).coerceIn(0, 12)
    // fixed() loses the sign of values between -1 and 0, so format the magnitude.
    val digits = fixed(abs(order), decimals).let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }
    return if (order < 0) "-$digits" else digits
}

/**
 * The color popup: uapmd-app's `ColorPicker3` with its hex readout. A value is
 * only applied by Apply, so trying colors out does not litter the history.
 */
@Composable
private fun TrackColorPicker(initial: Color, onApply: (UInt) -> Unit, onCancel: () -> Unit) {
    var red by remember { mutableStateOf(initial.red) }
    var green by remember { mutableStateOf(initial.green) }
    var blue by remember { mutableStateOf(initial.blue) }
    fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f).roundToInt().toUInt()
    val rgb = (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    Column(Modifier.width(260.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Box(
            Modifier.fillMaxWidth().height(32.dp)
                .background(Color(red, green, blue))
                .border(1.dp, MaterialTheme.colorScheme.outline)
        )
        listOf("R" to red, "G" to green, "B" to blue).forEachIndexed { i, (label, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(16.dp), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = value,
                    onValueChange = { v -> when (i) { 0 -> red = v; 1 -> green = v; else -> blue = v } },
                    modifier = Modifier.weight(1f)
                )
                Text(channel(value).toString(), Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        CommitField("#" + rgb.toString(16).uppercase().padStart(6, '0'), 120.dp) { entered ->
            entered.removePrefix("#").takeIf { it.length == 6 }?.toUIntOrNull(16)?.let {
                red = ((it shr 16) and 0xFFu).toFloat() / 255f
                green = ((it shr 8) and 0xFFu).toFloat() / 255f
                blue = (it and 0xFFu).toFloat() / 255f
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onApply(rgb) }, contentPadding = CompactPadding) { Text("Apply") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun TrackPathDialog(initial: String, onApply: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track Path") },
        text = {
            Column {
                Text("Organizational path (for example, Orchestra/Strings)", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Case-sensitive; non-empty paths sort first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer4()
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onApply(text) }),
                    modifier = Modifier.width(350.dp).focusRequester(focus)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onApply(text) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun HeaderCell(label: String, width: Dp) {
    Text(
        label,
        Modifier.width(width).padding(horizontal = 4.dp),
        style = MaterialTheme.typography.labelSmall
    )
}

@Composable
private fun ClipRow(
    host: UapmdHost,
    windows: FloatingWindowManager,
    trackIndex: Int,
    clip: ClipData,
    siblings: List<ClipData>,
    sampleRate: Double,
    report: (String) -> Unit
) {
    val scope = rememberCoroutineScope()

    /** One command carries anchor, origin and offset together. */
    fun applyAnchor(referenceId: String, origin: AnchorOrigin, offsetSeconds: Double) {
        val type = when {
            referenceId.isEmpty() -> TimeReferenceType.ContainerStart
            origin == AnchorOrigin.End -> TimeReferenceType.ContainerEnd
            else -> TimeReferenceType.ContainerStart
        }
        val ok = host.setClipAnchor(trackIndex, clip.clipId, TimeReference(type, referenceId, offsetSeconds))
        report(if (ok) "Anchor updated." else "Could not update the anchor.")
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        // ── Anchor: the track, or another clip on it
        Box(Modifier.width(AnchorWidth).padding(horizontal = 4.dp)) {
            var open by remember { mutableStateOf(false) }
            val current = siblings.firstOrNull { it.referenceId == clip.anchorReferenceId }
            Button(onClick = { open = true }, contentPadding = CompactPadding) {
                Text(
                    if (clip.anchorReferenceId.isEmpty()) "Track"
                    else current?.name?.ifEmpty { current.referenceId } ?: clip.anchorReferenceId,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Track") }, onClick = {
                    open = false
                    applyAnchor("", clip.anchorOrigin, clip.anchorOffsetSamples / sampleRate)
                })
                siblings.filter { it.clipId != clip.clipId && it.referenceId.isNotEmpty() }.forEach { other ->
                    DropdownMenuItem(
                        text = { Text(other.name.ifEmpty { other.referenceId }) },
                        onClick = {
                            open = false
                            applyAnchor(other.referenceId, clip.anchorOrigin, clip.anchorOffsetSamples / sampleRate)
                        }
                    )
                }
            }
        }

        // ── Origin: measure the offset from the anchor's start or its end
        Box(Modifier.width(OriginWidth).padding(horizontal = 4.dp)) {
            var open by remember { mutableStateOf(false) }
            Button(
                onClick = { open = true },
                enabled = clip.anchorReferenceId.isNotEmpty(),
                contentPadding = CompactPadding
            ) { Text(clip.anchorOrigin.name, style = MaterialTheme.typography.labelSmall) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                AnchorOrigin.entries.forEach { origin ->
                    DropdownMenuItem(text = { Text(origin.name) }, onClick = {
                        open = false
                        applyAnchor(clip.anchorReferenceId, origin, clip.anchorOffsetSamples / sampleRate)
                    })
                }
            }
        }

        // ── Position: the offset from the anchor, in seconds
        Box(Modifier.width(PositionWidth).padding(horizontal = 4.dp)) {
            val shown = if (clip.anchorReferenceId.isEmpty())
                clip.positionSamples / sampleRate
            else
                clip.anchorOffsetSamples / sampleRate
            CommitField(fixed(shown, 3), PositionWidth - 8.dp) { entered ->
                entered.toDoubleOrNull()?.let { applyAnchor(clip.anchorReferenceId, clip.anchorOrigin, it) }
                    ?: report("\"$entered\" is not a number of seconds.")
            }
        }

        // ── Name
        Box(Modifier.width(NameWidth).padding(horizontal = 4.dp)) {
            CommitField(clip.name, NameWidth - 8.dp) { entered ->
                report(
                    if (host.setClipName(trackIndex, clip.clipId, entered)) "Renamed."
                    else "Could not rename the clip."
                )
            }
        }

        // ── File: Change button then the filename, as uapmd-app orders it
        Row(
            Modifier.width(FileWidth).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        host.pickForClip(clip.clipType)?.let {
                            report(
                                if (host.setClipFilepath(trackIndex, clip.clipId, it)) "File changed."
                                else "Could not change the file."
                            )
                        }
                    }
                },
                contentPadding = CompactPadding
            ) { Text("Change", style = MaterialTheme.typography.labelSmall) }
            Text(
                clip.filepath.substringAfterLast('/').ifEmpty { "(none)" },
                style = MaterialTheme.typography.labelSmall
            )
        }

        // ── Delete
        Box(Modifier.width(DeleteWidth).padding(horizontal = 4.dp)) {
            Button(
                onClick = {
                    listOf("pianoroll", "dump", "events", "clipprops").forEach {
                        windows.close("$it:$trackIndex:${clip.clipId}")
                    }
                    report(
                        if (host.removeClip(trackIndex, clip.clipId)) "Deleted."
                        else "Could not delete the clip."
                    )
                },
                contentPadding = CompactPadding
            ) { Text("Delete", style = MaterialTheme.typography.labelSmall) }
        }
    }
}
