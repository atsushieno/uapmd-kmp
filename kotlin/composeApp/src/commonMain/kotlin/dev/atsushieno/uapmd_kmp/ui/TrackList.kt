package dev.atsushieno.uapmd_kmp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class TrackInstanceEntry(
    val trackIndex: Int,
    val trackName: String,
    val instanceId: Int,
    val pluginName: String,
    val pluginFormat: String = "",
    val deviceName: String,
    val deviceEnabled: Boolean = false,
    val deviceInstantiating: Boolean = false,
    val enabled: Boolean
)

@Composable
fun TrackList(
    entries: List<TrackInstanceEntry>,
    onEnabledChanged: (instanceId: Int, enabled: Boolean) -> Unit = { _, _ -> },
    onDetailsRequested: (instanceId: Int) -> Unit = {},
    onRemoveInstance: (instanceId: Int) -> Unit = {},
    onDeviceNameChanged: (instanceId: Int, name: String) -> Unit = { _, _ -> },
    onEnableDevice: (instanceId: Int) -> Unit = {},
    onDisableDevice: (instanceId: Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Column headers
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Track",  modifier = Modifier.width(60.dp),  fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Plugin", modifier = Modifier.weight(1f),    fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Fmt",    modifier = Modifier.width(36.dp),  fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // align with Switch + Details + ×
            Spacer(Modifier.width(40.dp + 52.dp + 28.dp))
        }
        HorizontalDivider()

        val byTrack = entries.groupBy { it.trackIndex }
        val trackIndices = byTrack.keys.sorted()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            for (ti in trackIndices) {
                val trackEntries = byTrack[ti] ?: continue
                itemsIndexed(trackEntries, key = { _, e -> e.instanceId }) { _, entry ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        // ── Main row: track / plugin / format / toggle / details / remove ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                entry.trackName,
                                modifier = Modifier.width(60.dp),
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                entry.pluginName,
                                modifier = Modifier.weight(1f),
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                entry.pluginFormat,
                                modifier = Modifier.width(36.dp),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Switch(
                                checked  = entry.enabled,
                                onCheckedChange = { onEnabledChanged(entry.instanceId, it) },
                                modifier = Modifier.width(40.dp)
                            )
                            TextButton(
                                onClick  = { onDetailsRequested(entry.instanceId) },
                                modifier = Modifier.width(52.dp),
                                enabled  = entry.instanceId >= 0,
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                            ) {
                                Text("Details", fontSize = 10.sp)
                            }
                            if (entry.instanceId >= 0) {
                                TextButton(
                                    onClick = { onRemoveInstance(entry.instanceId) },
                                    modifier = Modifier.width(28.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("×", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                                }
                            } else {
                                Spacer(Modifier.width(28.dp))
                            }
                        }

                        // ── Device row (only for real plugin instances, A-5 / A-6 / A-7) ──
                        if (entry.instanceId >= 0) {
                            val deviceControlsEnabled = !entry.deviceInstantiating
                            var localDeviceName by remember(entry.instanceId) {
                                mutableStateOf(entry.deviceName)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 66.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = localDeviceName,
                                    onValueChange = {
                                        localDeviceName = it
                                        onDeviceNameChanged(entry.instanceId, it)
                                    },
                                    enabled     = deviceControlsEnabled && !entry.deviceEnabled,
                                    singleLine  = true,
                                    placeholder = { Text("Device name", fontSize = 10.sp) },
                                    modifier    = Modifier.weight(1f).height(36.dp),
                                    textStyle   = MaterialTheme.typography.bodySmall
                                )
                                OutlinedButton(
                                    onClick = {
                                        if (entry.deviceEnabled) onDisableDevice(entry.instanceId)
                                        else onEnableDevice(entry.instanceId)
                                    },
                                    enabled = deviceControlsEnabled,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.width(64.dp).height(36.dp)
                                ) {
                                    Text(
                                        when {
                                            entry.deviceInstantiating -> "…"
                                            entry.deviceEnabled       -> "Disable"
                                            else                      -> "Enable"
                                        },
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}
