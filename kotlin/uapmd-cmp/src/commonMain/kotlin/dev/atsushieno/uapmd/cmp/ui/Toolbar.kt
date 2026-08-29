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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.ScanMode
import dev.atsushieno.uapmd.cmp.UapmdHost
import dev.atsushieno.uapmd.cmp.pickAudioFileToOpen
import dev.atsushieno.uapmd.cmp.pickMidiFileToOpen
import dev.atsushieno.uapmd.cmp.pickProjectFileToOpen
import dev.atsushieno.uapmd.cmp.saveProjectToPlatform
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

private val EngineOn = Color(0xFF3F9455)
private val EngineOff = Color(0xFF944536)
private val RecordActive = Color(0xFFE03333)

/**
 * uapmd-app 0.5.6's toolbar is two rows: there is no `SameLine()` after the theme
 * toggle (`MainWindow.cpp:576-581`), so `Plugins` starts a second line, and the
 * toolbar child is `90.0f * uiScale_` tall. Row 1 is engine / Command / transport
 * / scale / theme; row 2 is Plugins / Import / Project / In+Out meters. Device
 * Settings, Script, MCP and Addins live inside the "Command" popup, along with
 * undo/redo. Both rows are FlowRows so they wrap rather than clip on a phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Toolbar(
    host: UapmdHost,
    onToggleAddins: () -> Unit,
    onToggleExporter: () -> Unit,
    onToggleDeviceSettings: () -> Unit,
    onTogglePlugins: () -> Unit,
    uiScale: Float = 1f,
    onUiScaleChange: (Float) -> Unit = {},
    darkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    isDeviceSettingsOpen: Boolean = false,
    isAddinsOpen: Boolean = false,
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
                    // Script and MCP Settings live here in uapmd-app; both need
                    // C API that does not exist yet (UapmdJSRuntime, McpServer).
                    DropdownMenuItem(text = { Text("Show Script") }, enabled = false, onClick = {})
                    DropdownMenuItem(text = { Text("Show MCP Settings") }, enabled = false, onClick = {})
                }
            }

            Button(onClick = { host.playOrStop() }, enabled = host.isAudioEngineEnabled, contentPadding = Compact) {
                if (host.isPlaying) StopIcon(LocalContentColor.current) else PlayIcon(LocalContentColor.current)
            }
            Button(
                onClick = { recordStatus = host.toggleRecording() },
                enabled = host.isAudioEngineEnabled,
                contentPadding = Compact,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (host.isRecording) RecordActive else MaterialTheme.colorScheme.primary
                )
            ) { RecordIcon(LocalContentColor.current) }
            Button(
                onClick = { host.pauseOrResume() },
                enabled = host.isAudioEngineEnabled && host.isPlaying,
                contentPadding = Compact
            ) { if (host.isPaused) PlayIcon(LocalContentColor.current) else PauseIcon(LocalContentColor.current) }

            Box {
                Button(onClick = { scaleMenu = true }, contentPadding = Compact) { Text("×$uiScale") }
                DropdownMenu(expanded = scaleMenu, onDismissRequest = { scaleMenu = false }) {
                    listOf(0.5f, 0.8f, 1f, 1.2f, 1.5f, 2f, 4f).forEach { v ->
                        DropdownMenuItem(text = { Text("×$v") }, onClick = { onUiScaleChange(v); scaleMenu = false })
                    }
                }
            }
            Button(onClick = onToggleTheme, contentPadding = Compact) { ThemeIcon(LocalContentColor.current, darkTheme) }
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
                        scope.launch { pickMidiFileToOpen()?.let { host.importMidiClip(0, it) } }
                    })
                    DropdownMenuItem(text = { Text("Add Audio Clip from File… (track 0)") }, onClick = {
                        importOpen = false
                        scope.launch { pickAudioFileToOpen()?.let { host.importAudioClip(0, it) } }
                    })
                    DropdownMenuItem(text = { Text("Import MIDI Tracks (SMF)") }, onClick = {
                        importOpen = false
                        scope.launch { pickMidiFileToOpen()?.let { host.importMidiTracks(it) } }
                    })
                    // Still needs a C entry point for the Demucs separation path.
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
                        scope.launch { saveProjectToPlatform(host, "project.uapmdz") }
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

/*
 * Transport icons.
 *
 * Drawn rather than typed, for the same reason the track-legend icons are: we
 * ship no icon font, and the Unicode substitutes that were here (U+25B6 play,
 * U+25A0 stop, U+25CF record, U+275A pause, U+25D0/D1 theme) have no glyph in
 * the font Skiko falls back to on the web, so the whole transport row rendered
 * as tofu boxes in the browser. Paths cost nothing and look the same on all
 * five targets.
 */

private val TransportIconSize = 13.dp

@Composable
private fun PlayIcon(tint: Color) = Canvas(Modifier.size(TransportIconSize)) {
    drawPath(Path().apply {
        moveTo(size.width * 0.16f, 0f)
        lineTo(size.width * 0.94f, size.height / 2f)
        lineTo(size.width * 0.16f, size.height)
        close()
    }, tint)
}

@Composable
private fun StopIcon(tint: Color) = Canvas(Modifier.size(TransportIconSize)) {
    val inset = size.minDimension * 0.1f
    drawRect(tint, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2))
}

@Composable
private fun RecordIcon(tint: Color) = Canvas(Modifier.size(TransportIconSize)) {
    drawCircle(tint, size.minDimension * 0.42f, Offset(size.width / 2f, size.height / 2f))
}

@Composable
private fun PauseIcon(tint: Color) = Canvas(Modifier.size(TransportIconSize)) {
    val barWidth = size.width * 0.28f
    drawRect(tint, Offset(size.width * 0.1f, 0f), Size(barWidth, size.height))
    drawRect(tint, Offset(size.width * 0.62f, 0f), Size(barWidth, size.height))
}

/** The half-filled circle the theme toggle used: outline plus a filled half. */
@Composable
private fun ThemeIcon(tint: Color, dark: Boolean) = Canvas(Modifier.size(TransportIconSize)) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val r = size.minDimension * 0.45f
    drawCircle(tint, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.4f))
    val left = if (dark) c.x - r else c.x
    clipRect(left, c.y - r, left + r, c.y + r) { drawCircle(tint, r, c) }
}
