package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.ScanMode
import dev.atsushieno.uapmd.cmp.UapmdHost
import dev.atsushieno.uapmd.cmp.pickMediaFileToOpen
import dev.atsushieno.uapmd.cmp.pickProjectFileToOpen
import dev.atsushieno.uapmd.cmp.pickProjectFileToSave
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

private val EngineOn = Color(0xFF3F9455)
private val EngineOff = Color(0xFF944536)
private val RecordActive = Color(0xFFE03333)

/**
 * uapmd-app 0.5.6's toolbar: one row, with Device Settings / Script / MCP /
 * Addins and undo-redo folded into a "Command" popup. The two-row layout in the
 * users-guide screenshots is v0.5 and no longer current.
 */
@Composable
fun Toolbar(
    host: UapmdHost,
    onToggleAddins: () -> Unit,
    onToggleExporter: () -> Unit,
    onToggleMarkers: () -> Unit,
    onToggleDeviceSettings: () -> Unit,
    onTogglePlugins: () -> Unit,
    modifier: Modifier = Modifier
) {
    var commandOpen by remember { mutableStateOf(false) }
    var projectOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = { host.toggleAudioEngine() },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (host.isAudioEngineEnabled) EngineOn else EngineOff
            )
        ) {
            Text(if (host.isAudioEngineEnabled) "Audio Engine: On" else "Audio Engine: Off")
        }

        // ── Command popup ────────────────────────────────────────────────────
        Box {
            Button(onClick = { commandOpen = true }) { Text("Command") }
            DropdownMenu(expanded = commandOpen, onDismissRequest = { commandOpen = false }) {
                val h = host.history
                DropdownMenuItem(
                    text = { Text(if (h.undoDescription.isEmpty()) "Undo" else "Undo ${h.undoDescription}") },
                    enabled = h.canUndo && !h.busy,
                    onClick = { host.undo(); commandOpen = false }
                )
                DropdownMenuItem(
                    text = { Text(if (h.redoDescription.isEmpty()) "Redo" else "Redo ${h.redoDescription}") },
                    enabled = h.canRedo && !h.busy,
                    onClick = { host.redo(); commandOpen = false }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Show Device Settings") },
                    onClick = { commandOpen = false; onToggleDeviceSettings() }
                )
                DropdownMenuItem(
                    text = { Text("Show Addins") },
                    onClick = { commandOpen = false; onToggleAddins() }
                )
                DropdownMenuItem(
                    text = { Text("Show Project Markers") },
                    onClick = { commandOpen = false; onToggleMarkers() }
                )
                if (h.busy) {
                    DropdownMenuItem(
                        text = { Text("History operation in progress…", style = MaterialTheme.typography.bodySmall) },
                        enabled = false,
                        onClick = {}
                    )
                }
            }
        }

        // ── Transport ────────────────────────────────────────────────────────
        Button(
            onClick = { host.playOrStop() },
            enabled = host.isAudioEngineEnabled
        ) { Text(if (host.isPlaying) "■" else "▶") }

        Button(
            onClick = { /* recording needs MidiRecorder in the C API - see docs/uapmd-binding-missing-api.md */ },
            enabled = false,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (host.isRecording) RecordActive else MaterialTheme.colorScheme.primary
            )
        ) { Text("●") }

        Button(
            onClick = { host.pauseOrResume() },
            enabled = host.isAudioEngineEnabled && host.isPlaying
        ) { Text(if (host.isPaused) "▶" else "❚❚") }

        Spacer(Modifier.width(8.dp))

        Button(onClick = onTogglePlugins) { Text("Plugins") }

        Button(
            onClick = { if (host.isScanning) host.cancelScan() else host.scanPlugins(mode = ScanMode.InProcess) }
        ) { Text(if (host.isScanning) "Cancel Scan" else "Scan") }

        // ── Import / Project popups ──────────────────────────────────────────
        Box {
            Button(onClick = { importOpen = true }) { Text("Import") }
            DropdownMenu(expanded = importOpen, onDismissRequest = { importOpen = false }) {
                DropdownMenuItem(text = { Text("Add MIDI Clip from File… (track 0)") }, onClick = {
                    importOpen = false
                    scope.launch { pickMediaFileToOpen()?.let { host.importMidiClip(0, it) } }
                })
                DropdownMenuItem(text = { Text("Add Audio Clip from File… (track 0)") }, onClick = {
                    importOpen = false
                    scope.launch { pickMediaFileToOpen()?.let { host.importAudioClip(0, it) } }
                })
                // Demucs source separation is app-model work not yet bound.
                DropdownMenuItem(text = { Text("Import Split Audio Tracks (Demucs)") }, enabled = false, onClick = {})
            }
        }

        Box {
            Button(onClick = { projectOpen = true }) { Text("Project") }
            DropdownMenu(expanded = projectOpen, onDismissRequest = { projectOpen = false }) {
                DropdownMenuItem(text = { Text("Load Project") }, onClick = {
                    projectOpen = false
                    scope.launch { pickProjectFileToOpen()?.let { host.loadProject(it) } }
                })
                DropdownMenuItem(text = { Text("Save Project") }, onClick = {
                    projectOpen = false
                    scope.launch { pickProjectFileToSave()?.let { host.saveProject(it) } }
                })
                DropdownMenuItem(
                    text = { Text("Render To File") },
                    onClick = { projectOpen = false; onToggleExporter() }
                )
            }
        }
    }
}

@Composable
private fun Box(content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.Box { content() }
