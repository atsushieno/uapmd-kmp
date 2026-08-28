package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.AnchorOrigin
import dev.atsushieno.uapmd.ClipData
import dev.atsushieno.uapmd.TimeReference
import dev.atsushieno.uapmd.TimeReferenceType
import dev.atsushieno.uapmd.cmp.UapmdHost
import dev.atsushieno.uapmd.cmp.pickMediaFileToOpen
import kotlinx.coroutines.launch

/*
 * uapmd-app's Sequence Editor window, opened per track by Clips ▸ "Edit Clips…"
 * (`SequenceEditor::showWindow(trackIndex)`).
 *
 * It is a TABLE, not a second timeline — the main timeline's lanes are already
 * this class's unified view (`TimelineEditor.cpp:1016`), so the window adds the
 * columns a lane cannot show. `SequenceEditor::renderClipRow` (:936) is the
 * reference: Anchor | Origin | Position | Name | File (+Change) | Delete.
 *
 * Anchor and Origin are the point of the window: a clip can be anchored to its
 * track or to another clip, measured from that anchor's start or end, with the
 * Position column holding the offset. All three go through one
 * `ProjectCommands.setClipAnchor`, so an edit is a single undo step.
 */

private val HeaderBg = Color(0xFF2A2A33)
private val RowDivider = Color(0xFF3A3A44)

private val AnchorWidth = 150.dp
private val OriginWidth = 90.dp
private val PositionWidth = 110.dp
private val NameWidth = 160.dp
private val FileWidth = 220.dp
private val DeleteWidth = 90.dp

private val CompactPadding =
    androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)

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
    val clips = if (trackIndex == MasterTrackIndex) host.masterClips
    else host.trackClips.getOrNull(trackIndex).orEmpty()
    val sampleRate = (host.model.sampleRate.takeIf { it > 0 } ?: 48000).toDouble()
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    var status by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text(
            if (trackIndex == MasterTrackIndex) "Master track clips" else "Track $trackIndex clips",
            style = MaterialTheme.typography.labelMedium
        )
        Spacer4()
        Column(Modifier.horizontalScroll(hScroll)) {
            Row(Modifier.background(HeaderBg).padding(vertical = 4.dp)) {
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
                    HorizontalDivider(color = RowDivider)
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
                        pickMediaFileToOpen()?.let {
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
