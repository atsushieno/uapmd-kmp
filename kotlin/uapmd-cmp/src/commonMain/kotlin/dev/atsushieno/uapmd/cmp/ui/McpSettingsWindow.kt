package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.McpState
import dev.atsushieno.uapmd.cmp.UapmdHost

/**
 * MCP transport settings, as uapmd-app's MainWindow offers them: server mode on
 * a local port, or an outbound client to a relay.
 *
 * Server mode is offered only where the build has the embedded HTTP server —
 * wasm, iOS and Android have client mode only, which is why the choice is asked
 * of the binding rather than assumed.
 */
/**
 * uapmd's MCP port, as `main_common.cpp` and `MainWindow.hpp` both default it —
 * a client configured for uapmd-app finds uapmd-cmp on the same port.
 */
const val DefaultMcpPort = 37373

/** uapmd rejects a privileged port and anything past the 16-bit range. */
private const val MinMcpPort = 1024
private const val MaxMcpPort = 65535

@Composable
fun McpSettingsWindow(host: UapmdHost) {
    var serverMode by remember { mutableStateOf(host.mcpHasHttpServer) }
    var port by remember { mutableStateOf(DefaultMcpPort.toString()) }
    var relayUrl by remember { mutableStateOf("ws://127.0.0.1:8765/mcp") }
    var autoReconnect by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxWidth().padding(10.dp)) {
        if (!host.mcpSupported) {
            Text("This build has no MCP support.", color = MaterialTheme.colorScheme.error)
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = serverMode,
                onClick = { serverMode = true },
                enabled = host.mcpHasHttpServer && !host.mcpRunning
            )
            Text("Server (localhost)", style = MaterialTheme.typography.bodySmall)
            RadioButton(
                selected = !serverMode,
                onClick = { serverMode = false },
                enabled = !host.mcpRunning
            )
            Text("Client (relay)", style = MaterialTheme.typography.bodySmall)
        }
        if (!host.mcpHasHttpServer)
            Text(
                "This build has client mode only; the embedded HTTP server is desktop-only.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

        if (serverMode) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("Port") },
                supportingText = { Text("$MinMcpPort-$MaxMcpPort; uapmd uses $DefaultMcpPort") },
                singleLine = true,
                enabled = !host.mcpRunning,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            OutlinedTextField(
                value = relayUrl,
                onValueChange = { relayUrl = it },
                label = { Text("Relay URL") },
                singleLine = true,
                enabled = !host.mcpRunning,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = autoReconnect,
                    onCheckedChange = { autoReconnect = it },
                    enabled = !host.mcpRunning
                )
                Text("Auto-reconnect", style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (host.mcpRunning) host.stopMcp()
                else if (serverMode)
                    host.startMcpServer(
                        (port.toIntOrNull() ?: DefaultMcpPort).coerceIn(MinMcpPort, MaxMcpPort)
                    )
                else host.startMcpClient(relayUrl, autoReconnect)
            }) { Text(if (host.mcpRunning) "Disconnect" else "Connect") }

            Text(
                host.mcpStatusMessage.ifEmpty { if (host.mcpRunning) "Starting…" else "Not connected" },
                style = MaterialTheme.typography.labelSmall,
                color = if (host.mcpState == McpState.Error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
