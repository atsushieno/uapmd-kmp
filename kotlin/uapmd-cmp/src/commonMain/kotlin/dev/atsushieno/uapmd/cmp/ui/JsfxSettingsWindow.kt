package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.JsfxDuplicateListing
import dev.atsushieno.uapmd.cmp.JsfxResources
import dev.atsushieno.uapmd.cmp.platformIsAndroid

private val duplicateListingLabels = listOf(
    JsfxDuplicateListing.PreferLatestVersion to "Prefer latest version",
    JsfxDuplicateListing.HideExactDuplicates to "Hide exact copies only",
    JsfxDuplicateListing.ShowAll to "Show every script"
)

/**
 * uapmd-app's `JsfxResourcesWindow` ("JSFX Effects", opened by System ▸ JSFX
 * Settings): copy effects in, choose where effects are looked for, and how
 * duplicate scripts are listed. The behaviour lives in [JsfxResources].
 */
@Composable
fun JsfxSettingsWindow(resources: JsfxResources) {
    val provider = resources.provider
    val foldersArePlaces = remember(provider) { provider?.folderPathsAreUsable == true }
    val contentDir = remember { resources.contentDirectory }
    val canCopy = provider != null && contentDir.isNotEmpty()
    val idle = !resources.pickInFlight && !resources.busy

    Column(Modifier.fillMaxWidth().padding(10.dp).verticalScroll(rememberScrollState())) {
        SeparatorText("Copy effects in")

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = resources.destination,
                onValueChange = { resources.updateDestination(it) },
                label = { Text("Copy into") },
                placeholder = { Text("a name for this set of effects") },
                singleLine = true,
                modifier = Modifier.width(320.dp)
            )
            HelpMarker(
                "A folder of your own to keep these files in. Leave it empty\n" +
                    "to copy them in as they are."
            )
        }

        Button(
            enabled = idle && canCopy,
            onClick = { resources.beginFileImport() },
            modifier = Modifier.padding(vertical = 4.dp)
        ) { Text("Copy in files or a .zip...") }

        if (contentDir.isEmpty())
            Text("There is nowhere writable to copy effects to on this platform.", style = MaterialTheme.typography.bodySmall)
        else {
            Text("Copies are kept in:", style = MaterialTheme.typography.bodySmall)
            Text(contentDir, style = MaterialTheme.typography.bodySmall)
        }

        SeparatorText("Where effects are looked for")

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // On the platforms where a folder is a grant rather than a place, its
            // content is copied here and re-copied whenever it changes. Calling that
            // "adding a folder to search" would describe something the platform
            // cannot do.
            Button(enabled = idle && provider != null, onClick = { resources.beginFolderPick() }) {
                Text(if (foldersArePlaces) "Add a folder to search..." else "Import JSFX from a folder...")
            }
            HelpMarker(
                if (foldersArePlaces)
                    "Choose a folder of effects. It stays where it is and is read\n" +
                        "where it sits."
                else
                    "Choose a folder of effects. This platform hands over a folder's\n" +
                        "files rather than the folder itself, so the folder is remembered\n" +
                        "and re-read: effects you add to it later still turn up." +
                        (if (platformIsAndroid)
                            "\n\nAndroid refuses to hand over Download, Android/data, or the\n" +
                                "whole of internal storage. Keep effects in a folder of their own,\n" +
                                "such as a top-level JSFX folder."
                        else "")
            )
            if (resources.registeredFolders.isNotEmpty())
                OutlinedButton(enabled = idle, onClick = { resources.syncRegisteredFolders() }) {
                    Text("Re-read registered folders")
                }
        }

        resources.registeredFolders.forEach { folder ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("•  ${folder.name}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "(registered)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { resources.removeRegisteredFolder(folder.token) }) { Text("Remove") }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = resources.useDefaults, onCheckedChange = { resources.changeUseDefaults(it) })
            Text("Also look in this platform's usual locations", style = MaterialTheme.typography.bodySmall)
        }

        if (resources.useDefaults) {
            val defaults = remember { resources.defaultSearchPaths }
            if (defaults.isEmpty())
                Text(
                    "This platform has no conventional JSFX location.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            else
                defaults.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall) }
        }

        resources.searchPaths.forEachIndexed { i, path ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("•  $path", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { resources.removeSearchPath(i) }) { Text("Remove") }
            }
        }

        SeparatorText("Plugin list")

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            var menu by remember { mutableStateOf(false) }
            Box {
                Button(onClick = { menu = true }) {
                    Text(duplicateListingLabels.first { it.first == resources.duplicateListing }.second)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    duplicateListingLabels.forEach { (value, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            menu = false
                            if (value != resources.duplicateListing)
                                resources.changeDuplicateListing(value)
                        })
                    }
                }
            }
            Text("Duplicate scripts", style = MaterialTheme.typography.bodySmall)
            HelpMarker(
                "Prefer latest version collapses paths that differ only by a\n" +
                    "semantic-version directory. Hidden paths remain available\n" +
                    "to projects."
            )
        }

        if (resources.status.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(
                resources.status,
                style = MaterialTheme.typography.bodySmall,
                color = if (resources.statusIsError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** ImGui's `SeparatorText`: a label, then a rule to the right edge. */
@Composable
private fun SeparatorText(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        HorizontalDivider(Modifier.weight(1f))
    }
}

/** The `(?)` marker uapmd-app puts beside a control, with its tooltip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpMarker(text: String) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState()
    ) {
        Text("(?)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
