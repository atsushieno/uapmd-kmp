package dev.atsushieno.uapmd_kmp.ui

import androidx.compose.foundation.background
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

enum class PluginSortColumn { Format, Name, Vendor, Id }

@Composable
fun PluginList(
    plugins: List<PluginEntry>,
    selectedId: String? = null,
    onSelect: (PluginEntry) -> Unit = {},
    sortColumn: PluginSortColumn = PluginSortColumn.Name,
    sortAsc: Boolean = true,
    onSortChanged: (column: PluginSortColumn, asc: Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var filter by remember { mutableStateOf("") }

    val displayed = remember(plugins, filter, sortColumn, sortAsc) {
        val f = filter.trim().lowercase()
        val filtered = if (f.isBlank()) plugins
        else plugins.filter {
            it.name.contains(f, ignoreCase = true) ||
            it.vendor.contains(f, ignoreCase = true) ||
            it.format.contains(f, ignoreCase = true) ||
            it.pluginId.contains(f, ignoreCase = true)
        }
        val compareCI = Comparator<String> { a, b -> a.lowercase().compareTo(b.lowercase()) }
        val sorted = filtered.sortedWith(compareCI.let { ci ->
            val primary: Comparator<PluginEntry> = when (sortColumn) {
                PluginSortColumn.Format -> Comparator { a, b -> ci.compare(a.format, b.format) }
                PluginSortColumn.Name   -> Comparator { a, b -> ci.compare(a.name, b.name) }
                PluginSortColumn.Vendor -> Comparator { a, b -> ci.compare(a.vendor, b.vendor) }
                PluginSortColumn.Id     -> Comparator { a, b -> ci.compare(a.pluginId, b.pluginId) }
            }
            // tiebreakers for deterministic order
            primary
                .then(Comparator { a, b -> ci.compare(a.name, b.name) })
                .then(Comparator { a, b -> ci.compare(a.vendor, b.vendor) })
                .then(Comparator { a, b -> ci.compare(a.format, b.format) })
        })
        if (sortAsc) sorted else sorted.reversed()
    }

    fun onHeader(col: PluginSortColumn) {
        if (sortColumn == col) onSortChanged(col, !sortAsc) else onSortChanged(col, true)
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
        )

        // Column headers
        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                @Composable
                fun Header(label: String, col: PluginSortColumn, width: Modifier) {
                    val indicator = if (sortColumn == col) if (sortAsc) " ▲" else " ▼" else ""
                    Text(
                        text = label + indicator,
                        fontSize = 11.sp,
                        color = if (sortColumn == col) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = width.clickable { onHeader(col) }.padding(vertical = 4.dp)
                    )
                }
                Header("Format", PluginSortColumn.Format, Modifier.width(64.dp))
                Header("Name",   PluginSortColumn.Name,   Modifier.weight(2f))
                Header("Vendor", PluginSortColumn.Vendor, Modifier.weight(2f))
                Header("ID",     PluginSortColumn.Id,     Modifier.weight(3f))
            }
        }
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(displayed, key = { "${it.format}/${it.pluginId}" }) { plugin ->
                val isSelected = plugin.pluginId == selectedId && plugin.format == plugin.format
                Surface(
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(plugin) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(plugin.format,   modifier = Modifier.width(64.dp),  fontSize = 12.sp)
                        Text(plugin.name,     modifier = Modifier.weight(2f),    fontSize = 12.sp)
                        Text(plugin.vendor,   modifier = Modifier.weight(2f),    fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(plugin.pluginId, modifier = Modifier.weight(3f),    fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1)
                    }
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}
