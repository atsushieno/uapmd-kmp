package dev.atsushieno.uapmd_kmp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class TrackInstanceEntry(
    val trackIndex: Int,
    val trackName: String,
    val instanceId: Int,
    val pluginName: String,
    val deviceName: String,
    val enabled: Boolean
)

@Composable
fun TrackList(
    entries: List<TrackInstanceEntry>,
    onEnabledChanged: (instanceId: Int, enabled: Boolean) -> Unit = { _, _ -> },
    onDetailsRequested: (instanceId: Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Track", modifier = Modifier.width(80.dp), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Plugin", modifier = Modifier.weight(1f), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Device", modifier = Modifier.weight(1f), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(48.dp + 64.dp))
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(entries, key = { _, e -> e.instanceId }) { _, entry ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(entry.trackName, modifier = Modifier.width(80.dp), fontSize = 12.sp)
                    Text(entry.pluginName, modifier = Modifier.weight(1f), fontSize = 12.sp)
                    Text(entry.deviceName, modifier = Modifier.weight(1f), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = { onEnabledChanged(entry.instanceId, it) },
                        modifier = Modifier.width(48.dp)
                    )
                    TextButton(onClick = { onDetailsRequested(entry.instanceId) },
                        modifier = Modifier.width(64.dp)) {
                        Text("Details", fontSize = 11.sp)
                    }
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}
