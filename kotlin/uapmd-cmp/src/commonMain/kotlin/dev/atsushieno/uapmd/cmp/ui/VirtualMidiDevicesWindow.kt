package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.cmp.TrackInstance
import dev.atsushieno.uapmd.cmp.UapmdHost

/**
 * uapmd-app's Virtual MIDI Devices window (`MainWindow::renderVirtualMidiDevicesWindow`
 * over `TrackList`): the automatic-creation switch, Enable All / Disable All, and
 * one row per plug-in instance, grouped by track, with the virtual UMP device it
 * is exposed as. The Virtual MIDI Devices addin's command opens it.
 */
@Composable
fun VirtualMidiDevicesWindow(host: UapmdHost, windows: FloatingWindowManager) {
    // Edits keyed by instance, like uapmd-app's umpDeviceNameBuffers_; until
    // edited, a row shows the model's label (or the default name).
    val deviceNames = remember { mutableStateMapOf<Int, String>() }
    val tick = rememberTicker(true)
    val instances: List<Pair<Int, TrackInstance>> = remember(host.trackInstances, host.masterInstances) {
        host.trackInstances.flatMapIndexed { track, list -> list.map { track to it } } +
            host.masterInstances.map { -1 to it }
    }
    val rows = remember(instances, tick, host.addinRevision) {
        instances.mapNotNull { (_, instance) -> host.virtualMidiDeviceRow(instance.instanceId) }
    }
    fun nameOf(row: UapmdHost.VirtualMidiDeviceRow) = deviceNames[row.instanceId] ?: row.defaultDeviceName

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = host.autoCreateVirtualMidiDevices,
                onCheckedChange = { host.autoCreateVirtualMidiDevices = it }
            )
            Text("Create virtual MIDI 2.0 devices automatically", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Create devices for subsequently instantiated plugins. Use Enable or Disable below for existing instances.",
            style = MaterialTheme.typography.labelSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            Button(onClick = {
                rows.filter { !it.instantiating && it.supported && !it.running }
                    .forEach { host.enableVirtualMidiDevice(it.instanceId, nameOf(it)) }
            }) { Text("Enable All") }
            Button(onClick = {
                rows.filter { !it.instantiating && it.running }
                    .forEach { host.disableVirtualMidiDevice(it.instanceId) }
            }) { Text("Disable All") }
        }

        // The header stays put while the rows scroll (TableSetupScrollFreeze).
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            HeaderText("Track", Modifier.width(60.dp))
            HeaderText("Plugin", Modifier.weight(1f))
            HeaderText("Format", Modifier.width(60.dp))
            HeaderText("UMP Device", Modifier.weight(1.4f))
            HeaderText("Details", Modifier.width(72.dp))
        }
        HorizontalDivider()

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(rows, key = { it.instanceId }) { row ->
                val firstOfTrack = rows.firstOrNull { it.trackIndex == row.trackIndex }?.instanceId == row.instanceId
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when {
                            !firstOfTrack -> ""
                            row.trackIndex >= 0 -> "Track ${row.trackIndex + 1}"
                            else -> "-"
                        },
                        Modifier.width(60.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(row.pluginName, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(row.pluginFormat, Modifier.width(60.dp), style = MaterialTheme.typography.bodySmall)
                    Row(
                        Modifier.weight(1.4f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!row.supported && !row.running) {
                            Text(
                                "Virtual MIDI 2.0 unavailable on this platform",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        } else {
                            OutlinedTextField(
                                value = nameOf(row),
                                onValueChange = { deviceNames[row.instanceId] = it },
                                singleLine = true,
                                // The name is what the endpoint was registered
                                // under; it cannot change while it is running.
                                enabled = !row.running && !row.instantiating,
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                enabled = !row.instantiating,
                                onClick = {
                                    if (row.running) host.disableVirtualMidiDevice(row.instanceId)
                                    else host.enableVirtualMidiDevice(row.instanceId, nameOf(row))
                                },
                                contentPadding = RowButtonPadding,
                                modifier = Modifier.width(96.dp)
                            ) { Text(if (row.running) "Disable" else "Enable") }
                        }
                    }
                    Button(
                        onClick = {
                            val instance = instances.firstOrNull { it.second.instanceId == row.instanceId }?.second
                                ?: return@Button
                            val key = detailsWindowKey(row.instanceId)
                            if (windows.isOpen(key)) windows.close(key)
                            else windows.open(
                                key,
                                "${instance.displayName} (${instance.formatName}) - Details",
                                DpSize(460.dp, 420.dp)
                            ) { InstanceDetails(host, instance) }
                        },
                        contentPadding = RowButtonPadding,
                        modifier = Modifier.width(72.dp)
                    ) { Text("Show") }
                }
                if (row.statusMessage.isNotEmpty() && !row.running) {
                    Text(
                        row.statusMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 60.dp)
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun HeaderText(label: String, modifier: Modifier) =
    Text(label, modifier, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)

/** Fixed-width row buttons: the default 24dp side padding wraps their labels. */
private val RowButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
