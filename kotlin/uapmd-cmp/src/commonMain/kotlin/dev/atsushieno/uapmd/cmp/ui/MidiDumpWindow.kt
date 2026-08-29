package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.UmpEvent
import dev.atsushieno.uapmd.cmp.UapmdHost

/**
 * uapmd-app's MIDI dump editor: the raw UMP stream of one clip, with removal
 * and append. One window per (track, clip).
 */
@Composable
fun MidiDumpWindow(host: UapmdHost, trackIndex: Int, clipId: Int) {
    var revision by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    val result = remember(trackIndex, clipId, revision) {
        host.model.getMidiClipUmpEvents(trackIndex, clipId)
    }
    val events = remember(result, filter) {
        if (filter.isBlank()) result.events
        else result.events.filter { ev -> ev.words.any { it.toString(16).contains(filter, true) } }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "track $trackIndex clip $clipId · ${result.events.size} UMP events" +
                (result.error?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Filter (hex)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
        Row(Modifier.fillMaxWidth()) {
            Text("#", Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("Tick", Modifier.width(88.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("UMP", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(events) { index, event ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$index", Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
                    Text("${event.tick}", Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall)
                    Text(
                        event.words.joinToString(" ") { it.toString(16).padStart(8, '0') },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Button(onClick = {
                        // Index is into the unfiltered list the engine holds.
                        val realIndex = result.events.indexOf(event)
                        status = if (host.model.removeUmpEventFromClip(trackIndex, clipId, realIndex))
                            null else "Failed to remove event $realIndex."
                        host.invalidateMidiCache()
                        revision++
                    }) { Text("×") }
                }
            }
        }

        HorizontalDivider()
        AppendRow(host, trackIndex, clipId) { status = it; revision++ }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun AppendRow(host: UapmdHost, trackIndex: Int, clipId: Int, onDone: (String?) -> Unit) {
    var tick by remember { mutableStateOf("0") }
    var words by remember { mutableStateOf("") }

    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedTextField(
            value = tick, onValueChange = { tick = it },
            label = { Text("Tick") }, singleLine = true, modifier = Modifier.width(110.dp)
        )
        OutlinedTextField(
            value = words, onValueChange = { words = it },
            label = { Text("UMP words (hex, space separated)") }, singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Button(onClick = {
            val parsed = words.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                .mapNotNull { it.removePrefix("0x").toUIntOrNull(16) }
            val t = tick.toLongOrNull()
            when {
                t == null -> onDone("Tick must be an integer.")
                parsed.isEmpty() -> onDone("Enter at least one hex UMP word.")
                else -> {
                    val ok = host.model.addUmpEventToClip(trackIndex, clipId, t, parsed.toUIntArray())
                    host.invalidateMidiCache()
                    onDone(if (ok) null else "The engine rejected the event.")
                }
            }
        }) { Text("Add") }
    }
}
