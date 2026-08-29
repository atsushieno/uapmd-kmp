package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.LinearProgressIndicator
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
import dev.atsushieno.uapmd.cmp.platformNeedsAudioEngineForScan
import dev.atsushieno.uapmd.cmp.platformSupportsRemoteScanner
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas

private enum class SortColumn { Format, Name, Vendor, Id }

/**
 * uapmd-app's Plugin Selector: scan controls, a filterable and sortable catalog
 * table, a destination selector and the device-name/API fields used when the
 * instance becomes a virtual MIDI 2.0 device.
 */
@Composable
fun PluginSelector(host: UapmdHost) {
    var filter by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf(SortColumn.Name) }
    var ascending by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<CatalogEntry?>(null) }
    // uapmd-app's defaults (`PluginSelector.hpp:39-42`): force a rescan, and scan in
    // a separate process wherever that exists. The remote scanner is not a nicety —
    // an in-process scan runs every plug-in's entry code inside the app, so a single
    // bad plug-in takes the app down partway through the scan.
    var forceRescan by remember { mutableStateOf(true) }
    var remoteScanner by remember { mutableStateOf(platformSupportsRemoteScanner) }
    var remoteTimeoutSeconds by remember { mutableStateOf("20") }

    var deviceName by remember { mutableStateOf("") }
    var apiName by remember { mutableStateOf("default") }
    var destinationOpen by remember { mutableStateOf(false) }

    // The destination lives on the host so opening the selector from a track's
    // Add Plugin button pre-targets that track, as uapmd-app does.
    val destinationTrack = host.pluginDestinationTrack

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

    val blockedByAudioEngine = platformNeedsAudioEngineForScan && !host.isAudioEngineEnabled

    Column(Modifier.fillMaxWidth()) {
        // ── Scan controls ────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                enabled = host.isScanning || !blockedByAudioEngine,
                onClick = {
                if (host.isScanning) host.cancelScan()
                else host.scanPlugins(
                    forceRescan = forceRescan,
                    mode = if (remoteScanner) ScanMode.Remote else ScanMode.InProcess,
                    remoteTimeoutSeconds = remoteTimeoutSeconds.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 20.0
                )
            }) { Text(if (host.isScanning) "Cancel" else "Scan Plugins") }


            // Settings are fixed for the duration of a scan, as uapmd-app disables
            // them while one runs.
            Checkbox(
                checked = forceRescan,
                onCheckedChange = { forceRescan = it },
                enabled = !host.isScanning
            )
            Text("Force Rescan", style = MaterialTheme.typography.bodySmall)

            if (platformSupportsRemoteScanner) {
                Checkbox(
                    checked = remoteScanner,
                    onCheckedChange = { remoteScanner = it },
                    enabled = !host.isScanning
                )
                Text("Remote scanner", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = remoteTimeoutSeconds,
                    onValueChange = { remoteTimeoutSeconds = it },
                    label = { Text("Timeout (s)") },
                    singleLine = true,
                    enabled = remoteScanner && !host.isScanning,
                    modifier = Modifier.width(110.dp)
                )
            }
        }
        if (blockedByAudioEngine) {
            Text(
                "Turn the audio engine on to scan: plug-ins are fetched and inspected " +
                    "by the audio worklet, which does not exist until then.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        // Scan progress, as uapmd-app's selector reports it (PluginSelector.cpp:163-177).
        // A slow scan walks every bundle on the machine, so a bare "Scanning…" is
        // indistinguishable from a scan that has stalled.
        val progress = host.scanProgress
        Text(
            when {
                !host.isScanning && !progress.running ->
                    "${entries.size} of ${host.catalog.size} plugins"
                progress.totalBundles > 0u ->
                    "Scanning… ${progress.processedBundles} / ${progress.totalBundles} bundles"
                progress.processedBundles > 0u ->
                    "Scanning… ${progress.processedBundles} bundle(s) processed"
                else -> "Scanning…"
            },
            style = MaterialTheme.typography.bodySmall
        )
        if (progress.running && progress.totalBundles > 0u) {
            LinearProgressIndicator(
                progress = {
                    (progress.processedBundles.toFloat() / progress.totalBundles.toFloat())
                        .coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (progress.currentBundle.isNotEmpty()) {
            Text(
                "Current bundle: ${progress.currentBundle}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
        host.scanError?.let {
            Text(
                "Last scanning error: $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

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
                        onClick = { host.targetPluginDestination(-1); destinationOpen = false }
                    )
                    repeat(host.trackCount) { i ->
                        DropdownMenuItem(
                            text = { Text("Track $i") },
                            onClick = { host.targetPluginDestination(i); destinationOpen = false }
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

        HorizontalDivider()
        BlockedBundles(host)
        Text(
            "Missing plugins? They may appear after a manual scan.",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * uapmd-app's blocked-bundle list (`PluginSelector.cpp:201-245`): a collapsed
 * header counting the entries, a Clear button, and one row per blocked bundle
 * with an Unblock action.
 *
 * uapmd-app also shows the time each bundle was blocked. `BlocklistEntry` in
 * uapmd carries a `timestamp`, but `uapmd_blocklist_entry_t` has no field for it,
 * so that column is absent here rather than faked.
 */
@Composable
private fun BlockedBundles(host: UapmdHost) {
    var expanded by remember { mutableStateOf(false) }
    // Re-read when the section is opened and after a scan, not every recomposition.
    LaunchedEffect(expanded, host.isScanning) {
        if (expanded && !host.isScanning) host.refreshBlocklist()
    }
    val entries = host.blocklist

    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DisclosureIcon(expanded, MaterialTheme.colorScheme.onSurface)
        Text(
            "${entries.size} Blocked bundle${if (entries.size == 1) "" else "s"}",
            style = MaterialTheme.typography.labelMedium
        )
    }
    if (!expanded) return

    if (entries.isEmpty()) {
        Text("No blocked plugin bundles.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Button(onClick = { host.clearPluginBlocklist() }) { Text("Clear blocklist") }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("Format", Modifier.width(70.dp), style = MaterialTheme.typography.labelSmall)
        Text("Bundle", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        Text("Reason", Modifier.weight(1.4f), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(88.dp))
    }
    HorizontalDivider()
    entries.forEach { entry ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(entry.format, Modifier.width(70.dp), style = MaterialTheme.typography.bodySmall)
            Text(entry.pluginId, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(entry.reason, Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { host.unblockPlugin(entry.id) },
                modifier = Modifier.width(88.dp)
            ) { Text("Unblock", style = MaterialTheme.typography.labelSmall) }
        }
        HorizontalDivider()
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

/**
 * Disclosure triangle. Drawn rather than typed: U+25BE/U+25B8 have no glyph in
 * the web build's fallback font and rendered as tofu there.
 */
@Composable
private fun DisclosureIcon(expanded: Boolean, tint: Color) =
    Canvas(Modifier.size(9.dp)) {
        drawPath(Path().apply {
            if (expanded) {
                moveTo(0f, size.height * 0.2f)
                lineTo(size.width, size.height * 0.2f)
                lineTo(size.width / 2f, size.height * 0.85f)
            } else {
                moveTo(size.width * 0.2f, 0f)
                lineTo(size.width * 0.85f, size.height / 2f)
                lineTo(size.width * 0.2f, size.height)
            }
            close()
        }, tint)
    }
