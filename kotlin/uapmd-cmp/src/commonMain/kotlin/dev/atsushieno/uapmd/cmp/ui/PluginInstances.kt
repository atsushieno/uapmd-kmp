package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.cmp.UapmdHost

/**
 * uapmd-app's Audio Graph Editor / "Plugin Instances" window: every instance
 * across all tracks, with its details toggle, removal, and the UMP device name
 * used when the instance is published as a virtual MIDI 2.0 device.
 */
@Composable
fun PluginInstances(host: UapmdHost, windows: FloatingWindowManager) {
    val deviceNames = remember { mutableStateMapOf<Int, String>() }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text("Track", Modifier.width(52.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("Plugin", Modifier.width(150.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("Fmt", Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("Grp", Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()

        LazyColumn(Modifier.fillMaxWidth()) {
            host.trackInstances.forEachIndexed { trackIndex, instances ->
                items(instances.size) { i ->
                    val inst = instances[i]
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$trackIndex", Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall)
                            Text(inst.displayName, Modifier.width(150.dp), style = MaterialTheme.typography.bodySmall)
                            Text(inst.formatName, Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${host.model.getInstanceGroup(inst.instanceId)}",
                                Modifier.width(40.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Button(onClick = {
                                val key = "details:${inst.instanceId}"
                                if (windows.isOpen(key)) windows.close(key)
                                else windows.open(
                                    key,
                                    "${inst.displayName} (${inst.formatName}) - Details",
                                    DpSize(460.dp, 420.dp)
                                ) { InstanceDetails(host, inst) }
                            }) { Text("Details") }

                            OutlinedTextField(
                                value = deviceNames[inst.instanceId] ?: "",
                                onValueChange = { deviceNames[inst.instanceId] = it },
                                label = { Text("UMP device") },
                                singleLine = true,
                                modifier = Modifier.width(180.dp)
                            )
                            Button(onClick = {
                                host.model.enableUmpDevice(inst.instanceId, deviceNames[inst.instanceId] ?: "")
                            }) { Text("Enable") }
                            Button(onClick = { host.model.disableUmpDevice(inst.instanceId) }) { Text("Disable") }
                            Button(onClick = {
                                windows.close("details:${inst.instanceId}")
                                host.removeInstance(inst.instanceId)
                            }) { Text("×") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
