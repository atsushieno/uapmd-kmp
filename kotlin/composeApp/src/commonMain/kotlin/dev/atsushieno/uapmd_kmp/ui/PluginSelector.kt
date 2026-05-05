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
    useRemoteScanner: Boolean = false,
    onScanRequested: () -> Unit = {},
    onRemoteScannerToggled: (Boolean) -> Unit = {},
    onClearBlocklist: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Plugin Scanner", style = MaterialTheme.typography.titleMedium)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onScanRequested, enabled = !isScanning) {
                Text(if (isScanning) "Scanning…" else "Scan Plugins")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useRemoteScanner, onCheckedChange = onRemoteScannerToggled)
                Text("Remote scanner", fontSize = 12.sp)
            }
        }

        if (isScanning)
            LinearProgressIndicator(progress = { scanProgress }, modifier = Modifier.fillMaxWidth())

        if (scanReport.isNotBlank()) {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide scan report" else "Show scan report")
            }
            if (expanded) {
                OutlinedTextField(
                    value = scanReport,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (blocklist.isNotEmpty()) {
            var showBlocklist by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showBlocklist = !showBlocklist }) {
                    Text("Blocklist (${blocklist.size})")
                }
                TextButton(onClick = onClearBlocklist) { Text("Clear") }
            }
            if (showBlocklist) {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                    items(blocklist, key = { it.id }) { entry ->
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("${entry.format}: ${entry.pluginId}", fontSize = 12.sp)
                            Text(entry.reason, fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}
