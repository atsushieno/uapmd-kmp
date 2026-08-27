package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.AudioWarpPointData
import dev.atsushieno.uapmd.ClipMarkerData
import dev.atsushieno.uapmd.cmp.UapmdHost

/**
 * uapmd-app's Audio Event List: a clip's markers and warp points.
 *
 * Reads through `getClipAudioEvents()` and writes through `setClipAudioEvents()`
 * so both halves come from the same place; the undoable per-field commands
 * (`setClipMarkers` / `setClipAudioWarps`) remain available for finer edits.
 */
@Composable
fun AudioEventListEditor(host: UapmdHost, trackIndex: Int, clipId: Int) {
    var revision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var newOffset by remember { mutableStateOf("0.0") }
    var newName by remember { mutableStateOf("") }
    var newSpeed by remember { mutableStateOf("1.0") }

    val events = remember(trackIndex, clipId, revision) {
        host.model.getClipAudioEvents(trackIndex, clipId)
    }

    fun apply(markers: List<ClipMarkerData>, warps: List<AudioWarpPointData>) {
        val r = host.model.setClipAudioEvents(trackIndex, clipId, markers, warps)
        status = r.error
        revision++
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "track $trackIndex clip $clipId · ${events.markers.size} marker(s), ${events.warps.size} warp(s)" +
                (events.error?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        Text("Markers", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(events.markers) { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name.ifEmpty { "(unnamed)" }, Modifier.width(130.dp), style = MaterialTheme.typography.bodySmall)
                    Text("${m.clipPositionOffset}s", Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
                    Text(m.markerId, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        apply(events.markers.filter { it.markerId != m.markerId }, events.warps)
                    }) { Text("×") }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(newName, { newName = it }, label = { Text("Marker name") },
                singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(newOffset, { newOffset = it }, label = { Text("Offset (s)") },
                singleLine = true, modifier = Modifier.width(104.dp))
            Button(onClick = {
                val off = newOffset.toDoubleOrNull()
                if (off == null) status = "Offset must be a number."
                else {
                    apply(
                        events.markers + ClipMarkerData(
                            markerId = "m${events.markers.size + 1}-$off",
                            clipPositionOffset = off,
                            name = newName
                        ),
                        events.warps
                    )
                    newName = ""
                }
            }) { Text("Add") }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Warp points", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(events.warps) { w ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${w.clipPositionOffset}s", Modifier.width(90.dp), style = MaterialTheme.typography.bodySmall)
                    Text("×${w.speedRatio}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { apply(events.markers, events.warps - w) }) { Text("×") }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(newOffset, { newOffset = it }, label = { Text("Offset (s)") },
                singleLine = true, modifier = Modifier.width(104.dp))
            OutlinedTextField(newSpeed, { newSpeed = it }, label = { Text("Speed") },
                singleLine = true, modifier = Modifier.width(96.dp))
            Button(onClick = {
                val off = newOffset.toDoubleOrNull()
                val speed = newSpeed.toDoubleOrNull()
                if (off == null || speed == null) status = "Offset and speed must be numbers."
                else apply(events.markers, events.warps + AudioWarpPointData(off, speed))
            }) { Text("Add warp") }
        }

        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
