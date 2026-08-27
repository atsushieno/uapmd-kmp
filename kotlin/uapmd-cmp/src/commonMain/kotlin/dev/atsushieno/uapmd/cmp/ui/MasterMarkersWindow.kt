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
import dev.atsushieno.uapmd.ClipMarkerData
import dev.atsushieno.uapmd.cmp.UapmdHost

/**
 * Project-wide markers, which the engine owns alongside the master track.
 *
 * Edits go through `ProjectCommands.setMasterTrackMarkers()` so they are
 * undoable — the engine's own setter applies them directly and bypasses history.
 */
@Composable
fun MasterMarkersWindow(host: UapmdHost) {
    var revision by remember { mutableStateOf(0) }
    var newName by remember { mutableStateOf("") }
    var newOffset by remember { mutableStateOf("0.0") }
    var status by remember { mutableStateOf<String?>(null) }

    val engine = host.model.sequencer.engine
    val markers = remember(revision) { engine.masterTrackMarkers }

    fun apply(next: List<ClipMarkerData>) {
        status = if (engine.timeline.commands.setMasterTrackMarkers(next)) null
        else "The engine rejected the marker change."
        revision++
        host.refresh()
    }

    Column(Modifier.fillMaxWidth()) {
        Text("${markers.size} project marker(s)", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        Row(Modifier.fillMaxWidth()) {
            Text("Name", Modifier.width(150.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("Offset (s)", Modifier.width(96.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("Id", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(markers) { marker ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(marker.name.ifEmpty { "(unnamed)" }, Modifier.width(150.dp),
                        style = MaterialTheme.typography.bodySmall)
                    Text("${marker.clipPositionOffset}", Modifier.width(96.dp),
                        style = MaterialTheme.typography.bodySmall)
                    Text(marker.markerId, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { apply(markers.filter { it.markerId != marker.markerId }) }) { Text("×") }
                }
            }
        }

        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(newName, { newName = it }, label = { Text("Name") },
                singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(newOffset, { newOffset = it }, label = { Text("Offset (s)") },
                singleLine = true, modifier = Modifier.width(110.dp))
            Button(onClick = {
                val offset = newOffset.toDoubleOrNull()
                if (offset == null) {
                    status = "Offset must be a number."
                } else {
                    val id = "marker-${markers.size + 1}-${offset.toString().replace('.', '_')}"
                    apply(markers + ClipMarkerData(markerId = id, clipPositionOffset = offset, name = newName))
                    newName = ""
                }
            }) { Text("Add") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
