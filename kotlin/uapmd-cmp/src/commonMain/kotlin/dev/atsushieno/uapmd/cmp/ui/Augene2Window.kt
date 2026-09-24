package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.Augene2Integration
import dev.atsushieno.uapmd.ProjectAddressBook

/**
 * The Augene2 Integration panel (`uapmd-augene2`'s `Integration::render`),
 * drawn over the addin's public model instead of ImGui: import MML inputs and
 * includes, compile them into clips, and see where each MML track landed.
 *
 * Every request completes asynchronously in the panel registry's update, which
 * the host runs on every poll; the ticker re-reads the model meanwhile.
 */
@Composable
fun Augene2Window(integration: Augene2Integration) {
    val tick = rememberTicker(true)
    val busy = remember(tick) { integration.busy }
    val compiling = remember(tick) { integration.compiling }
    val sources = remember(tick) { integration.sources }
    val mappings = remember(tick) { integration.trackMappings }
    val status = remember(tick) { integration.status }
    val diagnostics = remember(tick) { integration.diagnostics }
    var folder by remember { mutableStateOf(integration.resourceFolder) }
    var mappingExpanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(enabled = !busy, onClick = { integration.importSources(compile = true) }) { Text("Import MMLs...") }
            Button(enabled = !busy, onClick = { integration.importSources(compile = false) }) { Text("Import included MML...") }
            Button(enabled = !busy, onClick = { integration.compile() }) { Text("Compile") }
        }
        // Saved preferences are kept, but synchronization stays inactive until
        // uapmd has an event-based filesystem watcher, so this is always off.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = false, onCheckedChange = null, enabled = false)
            Text("Enable Auto Synchronization", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Automatic synchronization is currently unavailable. Use Compile to refresh linked files.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = folder,
            onValueChange = {
                folder = it
                integration.resourceFolder = it
            },
            label = { Text("Resource folder (optional)") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
        HorizontalDivider()

        sources.forEach { source ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    "${if (source.compile) "Input" else "Include"}  ${source.path}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    if (source.externalPath.isNotEmpty()) "Linked: ${source.externalPath}" else "Bundled copy",
                    style = MaterialTheme.typography.labelSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { integration.relinkSource(source.path) }) { Text("Relink...") }
                    OutlinedButton(enabled = !busy, onClick = { integration.removeSource(source.path) }) { Text("Remove") }
                }
            }
        }
        HorizontalDivider()

        if (compiling)
            Text("Checking / compiling sources...", style = MaterialTheme.typography.bodySmall)
        if (status.isNotEmpty())
            Text(status, style = MaterialTheme.typography.bodySmall)
        diagnostics.forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }

        if (mappings.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clickable { mappingExpanded = !mappingExpanded }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DisclosureIcon(mappingExpanded, MaterialTheme.colorScheme.onSurface)
                Text("Track mapping", style = MaterialTheme.typography.labelMedium)
            }
            if (mappingExpanded) mappings.forEach { mapping ->
                val destination = when {
                    mapping.trackIndex == ProjectAddressBook.MASTER_TRACK_INDEX -> "Master track"
                    mapping.trackIndex >= 0 -> "Track ${mapping.trackIndex + 1}"
                    else -> "Track unavailable"
                }
                Text("MML ${mapping.key} -> $destination", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
