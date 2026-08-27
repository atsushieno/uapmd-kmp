package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.CatalogEntry
import dev.atsushieno.uapmd.PluginInstanceConfig
import dev.atsushieno.uapmd.ScanMode
import dev.atsushieno.uapmd.cmp.UapmdHost

private enum class SortColumn { Format, Name, Vendor, Id }

/**
 * uapmd-app's Plugin Selector: scan controls, a filterable and sortable catalog
 * table, a destination selector and the device-name/API fields used when the
 * instance becomes a virtual MIDI 2.0 device.
 *
 * Not present yet, and deliberately not faked: the blocked-bundle list. Reading
 * it needs AppModel's own PluginScanTool, which the C API does not expose
 * (`uapmd_app_clear_plugin_blocklist` exists, but nothing enumerates it).
 * See docs/uapmd-binding-missing-api.md.
 */
@Composable
fun PluginSelector(host: UapmdHost) {
    var filter by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf(SortColumn.Name) }
    var ascending by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<CatalogEntry?>(null) }
    var forceRescan by remember { mutableStateOf(false) }
    var remoteScanner by remember { mutableStateOf(false) }
    var destinationTrack by remember { mutableStateOf(-1) }
    var deviceName by remember { mutableStateOf("") }
    var apiName by remember { mutableStateOf("default") }
    var destinationOpen by remember { mutableStateOf(false) }

    val entries = remember(host.catalog, filter, sortBy, ascending) {
        val f = filter.trim()
        val filtered =
            if (f.isEmpty()) host.catalog
            else host.catalog.filter {
                it.displayName.contains(f, true) || it.vendor.contains(f, true) ||
                    it.format.contains(f, true) || it.pluginId.contains(f, true)
            }
        val sorted = when (sortBy) {
            SortColumn.Format -> filtered.sortedBy { it.format }
            SortColumn.Name -> filtered.sortedBy { it.displayName.lowercase() }
            SortColumn.Vendor -> filtered.sortedBy { it.vendor.lowercase() }
            SortColumn.Id -> filtered.sortedBy { it.pluginId }
        }
        if (ascending) sorted else sorted.reversed()
    }

    Column(Modifier.fillMaxWidth()) {
        // ── Scan controls ────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                if (host.isScanning) host.cancelScan()
                else host.scanPlugins(forceRescan, if (remoteScanner) ScanMode.Remote else ScanMode.InProcess)
            }) { Text(if (host.isScanning) "Cancel" else "Scan Plugins") }

            Checkbox(checked = forceRescan, onCheckedChange = { forceRescan = it })
            Text("Force Rescan", style = MaterialTheme.typography.bodySmall)
            Checkbox(checked = remoteScanner, onCheckedChange = { remoteScanner = it })
            Text("Remote scanner", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (host.isScanning) "Scanning…" else "${entries.size} of ${host.catalog.size} plugins",
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )

        // ── Table ────────────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            HeaderCell("Format", SortColumn.Format, sortBy, ascending, Modifier.width(70.dp)) {
                if (sortBy == it) ascending = !ascending else { sortBy = it; ascending = true }
            }
            HeaderCell("Name", SortColumn.Name, sortBy, ascending, Modifier.weight(1f)) {
                if (sortBy == it) ascending = !ascending else { sortBy = it; ascending = true }
            }
            HeaderCell("Vendor", SortColumn.Vendor, sortBy, ascending, Modifier.weight(0.8f)) {
                if (sortBy == it) ascending = !ascending else { sortBy = it; ascending = true }
            }
        }
        HorizontalDivider()

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(entries) { entry ->
                val isSelected = selected?.pluginId == entry.pluginId && selected?.format == entry.format
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable { selected = entry }
                        .padding(vertical = 3.dp)
                ) {
                    Text(entry.format, Modifier.width(70.dp), style = MaterialTheme.typography.bodySmall)
                    Text(entry.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(entry.vendor, Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        HorizontalDivider()

        // ── Destination + instantiate ────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = { selected?.let { host.instantiate(it, destinationTrack, PluginInstanceConfig(apiName = apiName, deviceName = deviceName)) } },
                enabled = selected != null && !host.isInstantiating
            ) { Text(if (host.isInstantiating) "Instantiating…" else "Instantiate Plugin") }

            androidx.compose.foundation.layout.Box {
                Button(onClick = { destinationOpen = true }) {
                    Text(if (destinationTrack < 0) "New Track" else "Track $destinationTrack")
                }
                DropdownMenu(expanded = destinationOpen, onDismissRequest = { destinationOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("New Track (new UMP device)") },
                        onClick = { destinationTrack = -1; destinationOpen = false }
                    )
                    repeat(host.trackCount) { i ->
                        DropdownMenuItem(
                            text = { Text("Track $i") },
                            onClick = { destinationTrack = i; destinationOpen = false }
                        )
                    }
                }
            }
        }

        if (destinationTrack < 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = deviceName, onValueChange = { deviceName = it },
                    label = { Text("Device Name") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = apiName, onValueChange = { apiName = it },
                    label = { Text("API") }, singleLine = true, modifier = Modifier.width(120.dp)
                )
            }
        }

        host.lastInstantiation?.let { r ->
            Text(
                if (r.error != null) "Failed: ${r.error}" else "Created '${r.pluginName}' (id ${r.instanceId})",
                style = MaterialTheme.typography.bodySmall,
                color = if (r.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun HeaderCell(
    label: String,
    column: SortColumn,
    sortBy: SortColumn,
    ascending: Boolean,
    modifier: Modifier,
    onClick: (SortColumn) -> Unit
) {
    Text(
        text = if (sortBy == column) "$label ${if (ascending) "▲" else "▼"}" else label,
        modifier = modifier.clickable { onClick(column) },
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold
    )
}
