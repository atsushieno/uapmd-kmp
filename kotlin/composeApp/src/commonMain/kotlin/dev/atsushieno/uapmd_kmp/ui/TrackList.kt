package dev.atsushieno.uapmd_kmp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    catalogEntries: List<PluginEntry> = emptyList(),
    onEnabledChanged: (instanceId: Int, enabled: Boolean) -> Unit = { _, _ -> },
    onDetailsRequested: (instanceId: Int) -> Unit = {},
    onRemoveInstance: (instanceId: Int) -> Unit = {},
    onAddTrack: () -> Unit = {},
    onAddPluginToTrack: (trackIndex: Int, format: String, pluginId: String) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    // Track index for which the plugin-picker popup is open; -1 = none
    var addPluginForTrack by remember { mutableStateOf(-1) }
    var pluginPickerFilter by remember { mutableStateOf("") }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Track", modifier = Modifier.width(80.dp), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Plugin", modifier = Modifier.weight(1f), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(0.01f))
            TextButton(onClick = onAddTrack, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("+ Track", fontSize = 11.sp)
            }
            Spacer(Modifier.width(64.dp + 32.dp))
        }
        HorizontalDivider()

        // Group entries by trackIndex so we can show one "Add Plugin" button per track
        val byTrack = entries.groupBy { it.trackIndex }
        val trackIndices = byTrack.keys.sorted()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            for (ti in trackIndices) {
                val trackEntries = byTrack[ti] ?: continue
                itemsIndexed(trackEntries, key = { _, e -> e.instanceId }) { _, entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(entry.trackName, modifier = Modifier.width(80.dp), fontSize = 12.sp)
                        Text(entry.pluginName, modifier = Modifier.weight(1f), fontSize = 12.sp)
                        Switch(
                            checked = entry.enabled,
                            onCheckedChange = { onEnabledChanged(entry.instanceId, it) },
                            modifier = Modifier.width(48.dp)
                        )
                        TextButton(onClick = { onDetailsRequested(entry.instanceId) },
                            modifier = Modifier.width(64.dp)) {
                            Text("Details", fontSize = 11.sp)
                        }
                        if (entry.instanceId >= 0) {
                            TextButton(
                                onClick = { onRemoveInstance(entry.instanceId) },
                                modifier = Modifier.width(32.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("×", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            Spacer(Modifier.width(32.dp))
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }

                // "Add Plugin" button at the bottom of each track's section
                item(key = "add_plugin_$ti") {
                    Box {
                        TextButton(
                            onClick = { addPluginForTrack = ti; pluginPickerFilter = "" },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 80.dp, end = 8.dp, top = 0.dp, bottom = 2.dp)
                        ) {
                            Text("⋮ Add Plugin", fontSize = 11.sp)
                        }

                        DropdownMenu(
                            expanded = addPluginForTrack == ti,
                            onDismissRequest = { addPluginForTrack = -1 }
                        ) {
                            // Filter box
                            OutlinedTextField(
                                value = pluginPickerFilter,
                                onValueChange = { pluginPickerFilter = it },
                                placeholder = { Text("Filter…", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .height(44.dp)
                            )
                            val filtered = if (pluginPickerFilter.isBlank()) catalogEntries
                                else catalogEntries.filter {
                                    it.name.contains(pluginPickerFilter, ignoreCase = true) ||
                                    it.vendor.contains(pluginPickerFilter, ignoreCase = true)
                                }
                            if (filtered.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No plugins found", fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    onClick = {}
                                )
                            } else {
                                filtered.take(30).forEach { plugin ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(plugin.name, fontSize = 12.sp)
                                                Text("${plugin.format}  ${plugin.vendor}",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        },
                                        onClick = {
                                            addPluginForTrack = -1
                                            onAddPluginToTrack(ti, plugin.format, plugin.pluginId)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
