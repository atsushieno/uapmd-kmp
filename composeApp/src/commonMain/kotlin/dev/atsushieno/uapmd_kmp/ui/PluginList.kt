package dev.atsushieno.uapmd_kmp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class PluginEntry(
    val format: String,
    val name: String,
    val vendor: String,
    val pluginId: String
)

@Composable
fun PluginList(
    plugins: List<PluginEntry>,
    selectedId: String? = null,
    onSelect: (PluginEntry) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var filter by remember { mutableStateOf("") }
    val filtered = remember(plugins, filter) {
        if (filter.isBlank()) plugins
        else plugins.filter {
            it.name.contains(filter, ignoreCase = true) ||
            it.vendor.contains(filter, ignoreCase = true) ||
            it.format.contains(filter, ignoreCase = true)
        }
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Filter") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        )
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Format", modifier = Modifier.width(60.dp), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Name", modifier = Modifier.weight(1f), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Vendor", modifier = Modifier.weight(1f), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.pluginId }) { plugin ->
                val isSelected = plugin.pluginId == selectedId
                Surface(
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(plugin) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(plugin.format, modifier = Modifier.width(60.dp), fontSize = 12.sp)
                        Text(plugin.name, modifier = Modifier.weight(1f), fontSize = 12.sp)
                        Text(plugin.vendor, modifier = Modifier.weight(1f), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}
