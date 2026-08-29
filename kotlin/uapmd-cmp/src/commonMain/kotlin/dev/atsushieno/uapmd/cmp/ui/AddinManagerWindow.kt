package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import dev.atsushieno.uapmd.AddinManager
import dev.atsushieno.uapmd.AddinState
import dev.atsushieno.uapmd.cmp.UapmdHost

/**
 * uapmd-app's Addin Manager. ARA is one such addin.
 *
 * `AddinManager.supportsDynamicLoading` is false where the platform cannot load
 * shared libraries (wasm, iOS); only built-in addins appear there.
 */
@Composable
fun AddinManagerWindow(host: UapmdHost) {
    var revision by remember { mutableStateOf(0) }
    val manager = host.addins
    val addins = remember(revision, manager) { manager?.addins.orEmpty() }

    Column(Modifier.fillMaxWidth()) {
        if (manager == null) {
            Text("Addin manager unavailable.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Text(
            "dynamic loading: ${AddinManager.supportsDynamicLoading} · ${addins.size} addin(s)",
            style = MaterialTheme.typography.bodySmall
        )
        manager.lastError.takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(
            "directories: ${manager.directories.joinToString().ifEmpty { "(none)" }}",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        Row(Modifier.fillMaxWidth()) {
            Text("Name", Modifier.width(150.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("State", Modifier.width(90.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("Built-in", Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()

        LazyColumn(Modifier.fillMaxWidth()) {
            items(addins) { addin ->
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(addin.name.ifEmpty { addin.addinId }, Modifier.width(150.dp),
                            style = MaterialTheme.typography.bodySmall)
                        Text(addin.state.name, Modifier.width(90.dp), style = MaterialTheme.typography.bodySmall)
                        Text(if (addin.builtIn) "yes" else "no", Modifier.width(64.dp),
                            style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = addin.state == AddinState.Active,
                            onCheckedChange = { enabled ->
                                manager.setEnabled(addin.packageId, addin.addinId, enabled)
                                revision++
                            }
                        )
                    }
                    if (addin.message.isNotEmpty()) {
                        Text(addin.message, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    Text("${addin.packageId} · ${addin.path}", style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
            }
        }
    }
}
