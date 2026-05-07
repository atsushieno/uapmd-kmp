package dev.atsushieno.uapmd_kmp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class BlocklistEntry(
    val id: String,
    val format: String,
    val pluginId: String,
    val reason: String
)

@Composable
fun PluginSelector(
    isScanning: Boolean,
    scanProgress: Float = 0f,
    scanReport: String = "",
    blocklist: List<BlocklistEntry> = emptyList(),
    plugins: List<PluginEntry> = emptyList(),
    trackEntries: List<TrackInstanceEntry> = emptyList(),
    initialTargetTrackIndex: Int = -1,
    useRemoteScanner: Boolean = false,
    onScanRequested: () -> Unit = {},
    onRemoteScannerToggled: (Boolean) -> Unit = {},
    onClearBlocklist: () -> Unit = {},
    onInstantiatePlugin: (format: String, pluginId: String, trackIndex: Int) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    var selectedPlugin    by remember { mutableStateOf<PluginEntry?>(null) }
    var targetTrackIndex  by remember { mutableStateOf(initialTargetTrackIndex) }
    var sortColumn        by remember { mutableStateOf(PluginSortColumn.Name) }
    var sortAsc           by remember { mutableStateOf(true) }
    var showBlocklist     by remember { mutableStateOf(false) }
    var showScanReport    by remember { mutableStateOf(false) }
    var targetMenuExpanded by remember { mutableStateOf(false) }
    var newTrackDeviceName by remember { mutableStateOf("") }
    var newTrackApi        by remember { mutableStateOf("") }

    // Sync when preselected track changes (e.g. user clicks "Add Plugin" on a different track)
    LaunchedEffect(initialTargetTrackIndex) {
        targetTrackIndex = initialTargetTrackIndex
    }

    val trackIndices = trackEntries.map { it.trackIndex }.distinct().sorted()

    Column(modifier = modifier) {

        // ── Scan controls ──────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = onScanRequested,
                enabled = !isScanning,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(if (isScanning) "Scanning…" else "Scan Plugins", fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = useRemoteScanner,
                    onCheckedChange = onRemoteScannerToggled,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Remote scanner", fontSize = 11.sp)
            }
        }

        if (isScanning) {
            LinearProgressIndicator(
                progress = { scanProgress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        if (scanReport.isNotBlank()) {
            TextButton(
                onClick = { showScanReport = !showScanReport },
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Text(if (showScanReport) "Hide scan report" else "Show scan report", fontSize = 11.sp)
            }
            if (showScanReport) {
                OutlinedTextField(
                    value = scanReport,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .padding(horizontal = 8.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (blocklist.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = { showBlocklist = !showBlocklist }) {
                    Text("Blocklist (${blocklist.size})", fontSize = 11.sp)
                }
                TextButton(onClick = onClearBlocklist) { Text("Clear", fontSize = 11.sp) }
            }
            if (showBlocklist) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .padding(horizontal = 8.dp)
                ) {
                    items(blocklist, key = { it.id }) { entry ->
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("${entry.format}: ${entry.pluginId}", fontSize = 12.sp)
                            Text(
                                entry.reason, fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
        }

        HorizontalDivider()

        // ── Plugin list ────────────────────────────────────────────────────
        PluginList(
            plugins       = plugins,
            selectedId    = selectedPlugin?.pluginId,
            onSelect      = { selectedPlugin = it },
            sortColumn    = sortColumn,
            sortAsc       = sortAsc,
            onSortChanged = { col, asc -> sortColumn = col; sortAsc = asc },
            modifier      = Modifier.weight(1f).fillMaxWidth()
        )

        HorizontalDivider()

        // ── Instantiate controls ───────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Target track dropdown
            val targetLabel = when {
                targetTrackIndex < 0 -> "New Track"
                else -> trackEntries.firstOrNull { it.trackIndex == targetTrackIndex }?.trackName
                    ?: "Track ${targetTrackIndex + 1}"
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Destination:", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(
                        onClick = { targetMenuExpanded = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(targetLabel, fontSize = 11.sp)
                    }
                    DropdownMenu(
                        expanded = targetMenuExpanded,
                        onDismissRequest = { targetMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("New Track") },
                            onClick = { targetTrackIndex = -1; targetMenuExpanded = false }
                        )
                        if (trackIndices.isNotEmpty()) HorizontalDivider()
                        trackIndices.forEach { ti ->
                            val name = trackEntries.firstOrNull { it.trackIndex == ti }?.trackName
                                ?: "Track ${ti + 1}"
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = { targetTrackIndex = ti; targetMenuExpanded = false }
                            )
                        }
                    }
                }
            }

            // Device Name + API inputs when creating a new track (P-7)
            if (targetTrackIndex < 0) {
                OutlinedTextField(
                    value = newTrackDeviceName,
                    onValueChange = { newTrackDeviceName = it },
                    label = { Text("Device Name", fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = newTrackApi,
                    onValueChange = { newTrackApi = it },
                    label = { Text("API", fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }

            // Instantiate button
            Button(
                onClick = {
                    val p = selectedPlugin ?: return@Button
                    onInstantiatePlugin(p.format, p.pluginId, targetTrackIndex)
                },
                enabled = selectedPlugin != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    selectedPlugin?.let { "Instantiate: ${it.name}" } ?: "Instantiate Plugin",
                    fontSize = 12.sp
                )
            }
        }
    }
}
