package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import dev.atsushieno.uapmd.cmp.UapmdHost

/**
 * The JavaScript console, as uapmd-app's ScriptEditor offers it: type a script,
 * run it, read the JSON result or the error.
 *
 * The runtime is AppModel's, reached through the binding — the `uapmd` global a
 * script talks to is the same API uapmd-app exposes, so a script written for one
 * runs in the other.
 */
private val Presets = listOf(
    "Tracks" to "JSON.stringify(uapmd.project.getTracks(), null, 2)",
    "Transport" to "JSON.stringify(uapmd.sequencer.transport.getState(), null, 2)",
    "Plugin catalog" to "JSON.stringify(uapmd.pluginCatalog.getEntries().slice(0, 10), null, 2)",
    "API surface" to "Object.keys(uapmd).join('\\n')"
)

@Composable
fun ScriptEditorWindow(host: UapmdHost) {
    var script by remember { mutableStateOf(Presets.first().second) }
    var presetMenu by remember { mutableStateOf(false) }
    val result = host.scriptResult

    Column(Modifier.fillMaxWidth().padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { host.runScript(script) }) { Text("Run") }
            Box {
                TextButton(onClick = { presetMenu = true }) { Text("Presets") }
                DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                    Presets.forEach { (name, code) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = { presetMenu = false; script = code }
                        )
                    }
                }
            }
            TextButton(onClick = { host.resetScriptRuntime() }) { Text("Reset runtime") }
        }

        OutlinedTextField(
            value = script,
            onValueChange = { script = it },
            label = { Text("Script") },
            modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 8.dp)
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        when {
            result == null ->
                Text("Not run yet.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            result.success ->
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Result", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(result.json ?: "undefined", style = MaterialTheme.typography.bodySmall)
                }
            else ->
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Error", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                    Text(result.error ?: "unknown error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
        }
    }
}
