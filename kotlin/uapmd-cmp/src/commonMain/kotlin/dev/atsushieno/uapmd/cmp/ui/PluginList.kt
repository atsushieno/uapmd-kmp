package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.CatalogEntry

/** uapmd-app's `PluginList::GroupMode`. */
enum class PluginGroupMode(val label: String) {
    None("None"), Format("Plugin Format"), Developer("Developer"), Bundle("Bundle")
}

private enum class PluginColumn(val title: String) { Format("Format"), Name("Name"), Vendor("Vendor"), Id("ID"), Bundle("Bundle") }

/** The columns each grouping shows: the grouped attribute moves into the group row. */
private fun columnsFor(mode: PluginGroupMode): List<PluginColumn> = when (mode) {
    PluginGroupMode.None -> listOf(PluginColumn.Format, PluginColumn.Name, PluginColumn.Vendor, PluginColumn.Bundle, PluginColumn.Id)
    PluginGroupMode.Format -> listOf(PluginColumn.Name, PluginColumn.Vendor, PluginColumn.Bundle, PluginColumn.Id)
    PluginGroupMode.Developer -> listOf(PluginColumn.Name, PluginColumn.Format, PluginColumn.Bundle, PluginColumn.Id)
    PluginGroupMode.Bundle -> listOf(PluginColumn.Name, PluginColumn.Format, PluginColumn.Vendor, PluginColumn.Id)
}

/** Format, name and vendor are what a user scans for, so they are drawn a size up. */
private fun PluginColumn.isPrimary() = this == PluginColumn.Format || this == PluginColumn.Name || this == PluginColumn.Vendor

private fun Modifier.columnWidth(column: PluginColumn, scope: androidx.compose.foundation.layout.RowScope): Modifier =
    with(scope) {
        when (column) {
            PluginColumn.Format -> this@columnWidth.width(72.dp)
            PluginColumn.Name -> this@columnWidth.weight(1.2f)
            PluginColumn.Vendor -> this@columnWidth.weight(0.9f)
            PluginColumn.Bundle -> this@columnWidth.weight(0.9f)
            PluginColumn.Id -> this@columnWidth.weight(1f)
        }
    }

/** `std::filesystem::path{bundle}.filename()`, falling back to the whole path. */
private fun bundleLabel(bundle: String): String =
    bundle.substringAfterLast('/').substringAfterLast('\\').ifEmpty { bundle }

/** Case-insensitive for ASCII letters, as uapmd-app's compareCI. */
private fun compareCI(a: String, b: String): Int = a.lowercase().compareTo(b.lowercase())

private class PluginGroup(val key: String, val label: String, val tooltip: String, val placeholder: Boolean) {
    val members = mutableListOf<CatalogEntry>()
}

private sealed interface PluginListRow {
    data class Group(val group: PluginGroup, val expanded: Boolean) : PluginListRow
    data class Plugin(val entry: CatalogEntry, val indent: Boolean) : PluginListRow
}

/**
 * uapmd-app's `PluginList`: a table of the catalog, flat or grouped into a
 * tree by format, developer or bundle.
 *
 * Grouped, each group row folds its members, groups sort by label (A-Z or
 * Z-A, placeholder groups always last) and members by name. Flat, the column
 * headers sort (ascending, descending, then catalog order again). The search
 * matches name and vendor; while it is active every matching group starts
 * expanded, and collapsing one only lasts until the search changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginList(
    catalog: List<CatalogEntry>,
    selected: CatalogEntry?,
    onSelect: (CatalogEntry) -> Unit,
    modifier: Modifier = Modifier,
    initialGroupMode: PluginGroupMode = PluginGroupMode.None
) {
    var groupMode by remember { mutableStateOf(initialGroupMode) }
    var groupMenu by remember { mutableStateOf(false) }
    var groupDescending by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    // Expanded groups, kept per grouping so switching back restores them.
    val expandedGroups = remember { mutableStateMapOf<PluginGroupMode, Set<String>>() }
    var searchCollapsedGroups by remember { mutableStateOf(emptySet<String>()) }
    var sortColumn by remember { mutableStateOf<PluginColumn?>(null) }
    var sortAscending by remember { mutableStateOf(true) }

    val columns = columnsFor(groupMode)
    val searching = search.isNotEmpty()

    val filtered = remember(catalog, search) {
        val f = search.lowercase()
        if (f.isEmpty()) catalog
        else catalog.filter { it.displayName.lowercase().contains(f) || it.vendor.lowercase().contains(f) }
    }

    val groups: List<PluginGroup> = remember(filtered, groupMode, groupDescending) {
        if (groupMode == PluginGroupMode.None) return@remember emptyList()
        val byKey = linkedMapOf<String, PluginGroup>()
        filtered.forEach { p ->
            var key: String
            var label: String
            var tooltip = ""
            when (groupMode) {
                PluginGroupMode.Format -> { key = p.format; label = p.format }
                PluginGroupMode.Developer -> { val v = p.vendor.trim(); key = v.lowercase(); label = v }
                else -> {
                    key = p.bundlePath
                    label = bundleLabel(p.bundlePath)
                    if (label != p.bundlePath) tooltip = p.bundlePath
                }
            }
            val placeholder = key.isEmpty()
            if (placeholder) label = when (groupMode) {
                PluginGroupMode.Developer -> "(Unknown developer)"
                PluginGroupMode.Bundle -> "(No bundle)"
                else -> "(Unknown)"
            }
            byKey.getOrPut(key) { PluginGroup(key, label, tooltip, placeholder) }.members += p
        }
        val byName = Comparator<CatalogEntry> { a, b ->
            compareCI(a.displayName, b.displayName).let { if (groupDescending) -it else it }.takeIf { it != 0 }
                ?: compareCI(a.format, b.format).takeIf { it != 0 }
                ?: compareCI(a.pluginId, b.pluginId)
        }
        byKey.values.onEach { it.members.sortWith(byName) }
            .sortedWith { a, b ->
                if (a.placeholder != b.placeholder) (if (a.placeholder) 1 else -1)
                else compareCI(a.label, b.label).let { if (groupDescending) -it else it }
            }
    }

    fun isExpanded(key: String): Boolean =
        if (searching) key !in searchCollapsedGroups
        else key in expandedGroups[groupMode].orEmpty()

    fun setExpanded(key: String, expanded: Boolean) {
        if (searching) searchCollapsedGroups = if (expanded) searchCollapsedGroups - key else searchCollapsedGroups + key
        else {
            val current = expandedGroups[groupMode].orEmpty()
            expandedGroups[groupMode] = if (expanded) current + key else current - key
        }
    }

    val rows: List<PluginListRow> = if (groupMode == PluginGroupMode.None) {
        val sorted = sortColumn?.let { column ->
            val selector: (CatalogEntry) -> String = when (column) {
                PluginColumn.Format -> { e -> e.format }
                PluginColumn.Name -> { e -> e.displayName }
                PluginColumn.Vendor -> { e -> e.vendor }
                PluginColumn.Id -> { e -> e.pluginId }
                PluginColumn.Bundle -> { e -> bundleLabel(e.bundlePath) }
            }
            filtered.sortedWith { a, b ->
                compareCI(selector(a), selector(b)).let { if (sortAscending) it else -it }.takeIf { it != 0 }
                    // A deterministic tiebreak, as uapmd-app's sortFlat.
                    ?: compareCI(a.displayName, b.displayName).takeIf { it != 0 }
                    ?: compareCI(a.vendor, b.vendor).takeIf { it != 0 }
                    ?: compareCI(a.pluginId, b.pluginId).takeIf { it != 0 }
                    ?: compareCI(a.format, b.format)
            }
        } ?: filtered
        sorted.map { PluginListRow.Plugin(it, indent = false) }
    } else {
        groups.flatMap { group ->
            val expanded = isExpanded(group.key)
            listOf<PluginListRow>(PluginListRow.Group(group, expanded)) +
                if (expanded) group.members.map { PluginListRow.Plugin(it, indent = true) } else emptyList()
        }
    }

    Column(modifier) {
        // ── Toolbar ──────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Group by:", style = MaterialTheme.typography.bodySmall)
            Box {
                Button(onClick = { groupMenu = true }) { Text(groupMode.label) }
                DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                    PluginGroupMode.entries.forEach { mode ->
                        DropdownMenuItem(text = { Text(mode.label) }, onClick = {
                            groupMenu = false
                            if (mode != groupMode) {
                                groupMode = mode
                                searchCollapsedGroups = emptySet()
                            }
                        })
                    }
                }
            }
            if (groupMode != PluginGroupMode.None) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Toggle sort order by name") } },
                    state = rememberTooltipState()
                ) {
                    Button(onClick = { groupDescending = !groupDescending }) { Text(if (groupDescending) "Z-A" else "A-Z") }
                }
                Button(onClick = {
                    if (searching) searchCollapsedGroups = emptySet()
                    else expandedGroups[groupMode] = groups.mapTo(mutableSetOf()) { it.key }
                }) { Text("Expand All") }
                Button(onClick = {
                    if (searching) searchCollapsedGroups = groups.mapTo(mutableSetOf()) { it.key }
                    else expandedGroups[groupMode] = emptySet()
                }) { Text("Collapse All") }
            }
        }
        OutlinedTextField(
            value = search,
            onValueChange = {
                search = it
                searchCollapsedGroups = emptySet()
            },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )

        // ── Header: stays put while the rows scroll ─────────────────────────
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            columns.forEach { column ->
                val sortable = groupMode == PluginGroupMode.None
                val arrow = if (sortable && sortColumn == column) (if (sortAscending) " ▲" else " ▼") else ""
                Text(
                    column.title + arrow,
                    Modifier.columnWidth(column, this).then(
                        if (!sortable) Modifier else Modifier.clickable {
                            // Ascending, descending, then back to catalog order
                            // (ImGuiTableFlags_SortTristate).
                            when {
                                sortColumn != column -> { sortColumn = column; sortAscending = true }
                                sortAscending -> sortAscending = false
                                else -> sortColumn = null
                            }
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
        HorizontalDivider()

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            // No item keys: a catalog can list the same format and id twice
            // (one plug-in installed in two places), and LazyColumn rejects
            // duplicate keys outright.
            items(rows) { row ->
                when (row) {
                    is PluginListRow.Group -> GroupRow(row) { setExpanded(row.group.key, !row.expanded) }
                    is PluginListRow.Plugin -> PluginRow(
                        row.entry, columns, row.indent,
                        isSelected = selected?.pluginId == row.entry.pluginId && selected.format == row.entry.format,
                        onClick = { onSelect(row.entry) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupRow(row: PluginListRow.Group, onToggle: () -> Unit) {
    val content = @Composable {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DisclosureIcon(row.expanded, MaterialTheme.colorScheme.onSurface)
            Text(
                "${row.group.label} (${row.group.members.size})",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
    if (row.group.tooltip.isEmpty()) content()
    else TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(row.group.tooltip) } },
        state = rememberTooltipState()
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginRow(
    entry: CatalogEntry,
    columns: List<PluginColumn>,
    indent: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (indent) Spacer(Modifier.width(18.dp))
        columns.forEach { column ->
            val style: TextStyle =
                if (column.isPrimary()) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall
            val text = when (column) {
                PluginColumn.Format -> entry.format
                PluginColumn.Name -> entry.displayName
                PluginColumn.Vendor -> entry.vendor
                PluginColumn.Id -> entry.pluginId
                PluginColumn.Bundle -> bundleLabel(entry.bundlePath)
            }
            val cellModifier = Modifier.columnWidth(column, this).padding(end = 4.dp)
            if (column == PluginColumn.Bundle && text != entry.bundlePath) {
                // The full path on hover, as the bundle cell's tooltip.
                Box(cellModifier) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(entry.bundlePath) } },
                        state = rememberTooltipState()
                    ) { Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            } else {
                Text(text, cellModifier, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
