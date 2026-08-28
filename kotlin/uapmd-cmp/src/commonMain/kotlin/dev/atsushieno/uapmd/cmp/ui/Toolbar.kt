package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Toolbar(
    host: UapmdHost,
    onToggleAddins: () -> Unit,
    onToggleExporter: () -> Unit,
    onToggleMarkers: () -> Unit,
    onToggleDeviceSettings: () -> Unit,
    onTogglePlugins: () -> Unit,
    uiScale: Float = 1f,
    onUiScaleChange: (Float) -> Unit = {},
    darkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    isDeviceSettingsOpen: Boolean = false,
    isAddinsOpen: Boolean = false,
    isMarkersOpen: Boolean = false,
    modifier: Modifier = Modifier
) {
    var commandOpen by remember { mutableStateOf(false) }
    var projectOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var scaleMenu by remember { mutableStateOf(false) }
    var recordStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxWidth().padding(6.dp)) {
        // Row 1: engine, command menu, transport, scale, theme.
        // uapmd-app breaks the line here (no SameLine after the theme toggle),
        // and its toolbar child is 90pt tall - two rows, not one.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = { host.toggleAudioEngine() },
                contentPadding = Compact,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (host.isAudioEngineEnabled) EngineOn else EngineOff
                )
            ) { Text(if (host.isAudioEngineEnabled) "Audio Engine: On" else "Audio Engine: Off") }

            Box {
                Button(onClick = { commandOpen = true }, contentPadding = Compact) { Text("Command") }
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
                    if (h.busy) {
                        DropdownMenuItem(
                            text = { Text("History operation in progress…", style = MaterialTheme.typography.bodySmall) },
                            enabled = false, onClick = {}
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (isDeviceSettingsOpen) "Hide Device Settings" else "Show Device Settings") },
                        onClick = { commandOpen = false; onToggleDeviceSettings() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isAddinsOpen) "Hide Addins" else "Show Addins") },
                        onClick = { commandOpen = false; onToggleAddins() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isMarkersOpen) "Hide Project Markers" else "Show Project Markers") },
                        onClick = { commandOpen = false; onToggleMarkers() }
                    )
                    // Script and MCP Settings live here in uapmd-app; both need
                    // C API that does not exist yet (UapmdJSRuntime, McpServer).
                    DropdownMenuItem(text = { Text("Show Script") }, enabled = false, onClick = {})
                    DropdownMenuItem(text = { Text("Show MCP Settings") }, enabled = false, onClick = {})
                }
            }

            Button(onClick = { host.playOrStop() }, enabled = host.isAudioEngineEnabled, contentPadding = Compact) {
                Text(if (host.isPlaying) "■" else "▶")
            }
            Button(
                onClick = { recordStatus = host.toggleRecording() },
                enabled = host.isAudioEngineEnabled,
                contentPadding = Compact,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (host.isRecording) RecordActive else MaterialTheme.colorScheme.primary
                )
            ) { Text("●") }
            Button(
                onClick = { host.pauseOrResume() },
                enabled = host.isAudioEngineEnabled && host.isPlaying,
                contentPadding = Compact
            ) { Text(if (host.isPaused) "▶" else "❚❚") }

            Box {
                Button(onClick = { scaleMenu = true }, contentPadding = Compact) { Text("×$uiScale") }
                DropdownMenu(expanded = scaleMenu, onDismissRequest = { scaleMenu = false }) {
                    listOf(0.5f, 0.8f, 1f, 1.2f, 1.5f, 2f, 4f).forEach { v ->
                        DropdownMenuItem(text = { Text("×$v") }, onClick = { onUiScaleChange(v); scaleMenu = false })
                    }
                }
            }
            Button(onClick = onToggleTheme, contentPadding = Compact) { Text(if (darkTheme) "◐" else "◑") }
        }

        // Row 2: content actions and the level meters.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            // No Scan button here: uapmd-app scans from inside the Plugin
            // Selector (PluginSelector.cpp:116), not the toolbar.
            Button(onClick = onTogglePlugins, contentPadding = Compact) { Text("Plugins") }

            Box {
                Button(onClick = { importOpen = true }, contentPadding = Compact) { Text("Import") }
                DropdownMenu(expanded = importOpen, onDismissRequest = { importOpen = false }) {
                    DropdownMenuItem(text = { Text("Add MIDI Clip from File… (track 0)") }, onClick = {
                        importOpen = false
                        scope.launch { pickMediaFileToOpen()?.let { host.importMidiClip(0, it) } }
                    })
                    DropdownMenuItem(text = { Text("Add Audio Clip from File… (track 0)") }, onClick = {
                        importOpen = false
                        scope.launch { pickMediaFileToOpen()?.let { host.importAudioClip(0, it) } }
                    })
                    // Both need C API that does not exist: importMidiTracksFromFile
                    // for the SMF split, and the Demucs import path.
                    DropdownMenuItem(text = { Text("Import MIDI Tracks (SMF)") }, enabled = false, onClick = {})
                    DropdownMenuItem(text = { Text("Import Split Audio Tracks (Demucs)") }, enabled = false, onClick = {})
                }
            }

            Box {
                Button(onClick = { projectOpen = true }, contentPadding = Compact) { Text("Project") }
                DropdownMenu(expanded = projectOpen, onDismissRequest = { projectOpen = false }) {
                    DropdownMenuItem(text = { Text("Load Project") }, onClick = {
                        projectOpen = false
                        scope.launch { pickProjectFileToOpen()?.let { host.loadProject(it) } }
                    })
                    DropdownMenuItem(text = { Text("Save Project") }, onClick = {
                        projectOpen = false
                        scope.launch { pickProjectFileToSave()?.let { host.saveProject(it) } }
                    })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Render To File") }, onClick = {
                        projectOpen = false; onToggleExporter()
                    })
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("In ", style = MaterialTheme.typography.labelSmall)
                SpectrumAnalyzer(host.inputSpectrum)
                Text(" Out ", style = MaterialTheme.typography.labelSmall)
                SpectrumAnalyzer(host.outputSpectrum)
            }
        }

        recordStatus?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        host.lastProjectResult?.error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Tight padding keeps two rows of controls usable on a phone screen. */
private val Compact = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
