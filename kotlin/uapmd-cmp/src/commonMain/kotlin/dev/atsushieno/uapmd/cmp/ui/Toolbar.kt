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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import dev.atsushieno.uapmd.AddinCommandInfo
import dev.atsushieno.uapmd.AudioWorkerFault
import dev.atsushieno.uapmd.cmp.UapmdHost
import dev.atsushieno.uapmd.cmp.pickMidiFileToOpen
import dev.atsushieno.uapmd.cmp.pickProjectFileToOpen
import dev.atsushieno.uapmd.cmp.saveProjectToPlatform
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

private val EngineOn = Color(0xFF3F9455)
private val EngineOff = Color(0xFF944536)
private val RecordActive = Color(0xFFE03333)

/**
 * uapmd-app's toolbar is two rows (`MainWindow::render`, the `MainToolbar`
 * child, `90.0f * uiScale_` tall): row 1 is engine / System / Project / UI
 * scale / theme, and there is no `SameLine()` after the theme toggle, so row 2
 * starts with Plugins, then the transport and the In/Out meters. Device
 * Settings, Addins, Scripting and MCP live in the System popup along with
 * undo/redo; file I/O and imports live in Project. Both rows are FlowRows so
 * they wrap rather than clip on a phone.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Toolbar(
    host: UapmdHost,
    onToggleAddins: () -> Unit,
    onToggleExporter: () -> Unit,
    onToggleDeviceSettings: () -> Unit,
    onTogglePlugins: () -> Unit,
    onToggleAudioImport: () -> Unit,
    onToggleScript: () -> Unit,
    onToggleMcp: () -> Unit,
    onNewProject: () -> Unit,
    uiScale: Float = 1f,
    onUiScaleChange: (Float) -> Unit = {},
    darkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    isDeviceSettingsOpen: Boolean = false,
    isAddinsOpen: Boolean = false,
    isScriptOpen: Boolean = false,
    isMcpOpen: Boolean = false,
    modifier: Modifier = Modifier
) {
    var systemOpen by remember { mutableStateOf(false) }
    var projectOpen by remember { mutableStateOf(false) }
    var scaleMenu by remember { mutableStateOf(false) }
    var recordStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxWidth().padding(6.dp)) {
        // Row 1: engine, System and Project menus, UI scale, theme.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val fault = host.audioWorkerFault
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
                tooltip = {
                    PlainTooltip {
                        Text(
                            when (fault) {
                                AudioWorkerFault.DeadlineExceeded ->
                                    "Audio workers missed their deadline. Use fewer workers or a larger buffer in Settings, then click to restart."
                                AudioWorkerFault.PluginFailure ->
                                    "A plugin failed during parallel processing. Select Serial in Settings, then click to restart."
                                AudioWorkerFault.None ->
                                    if (host.isAudioEngineEnabled) "Click to turn off the audio engine"
                                    else "Click to turn on the audio engine"
                            }
                        )
                    }
                },
                state = rememberTooltipState()
            ) {
                Button(
                    onClick = { host.toggleAudioEngine() },
                    contentPadding = Compact,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (host.isAudioEngineEnabled) EngineOn else EngineOff
                    )
                ) {
                    Text(
                        when {
                            fault != AudioWorkerFault.None -> "Audio Engine: Restart"
                            host.isAudioEngineEnabled -> "Audio Engine: On"
                            else -> "Audio Engine: Off"
                        }
                    )
                }
            }

            Box {
                Button(onClick = { systemOpen = true }, contentPadding = Compact) { Text("System") }
                DropdownMenu(expanded = systemOpen, onDismissRequest = { systemOpen = false }) {
                    val h = host.history
                    DropdownMenuItem(
                        text = { Text(if (h.undoDescription.isEmpty()) "Undo" else "Undo ${h.undoDescription}") },
                        enabled = h.canUndo && !h.busy,
                        onClick = { host.undo(); systemOpen = false }
                    )
                    DropdownMenuItem(
                        text = { Text(if (h.redoDescription.isEmpty()) "Redo" else "Redo ${h.redoDescription}") },
                        enabled = h.canRedo && !h.busy,
                        onClick = { host.redo(); systemOpen = false }
                    )
                    if (h.busy) {
                        DropdownMenuItem(
                            text = { Text("History operation in progress…", style = MaterialTheme.typography.bodySmall) },
                            enabled = false, onClick = {}
                        )
                    }
                    HorizontalDivider()
                    // Toggles for the four panels, with a check mark on the open
                    // ones, as uapmd-app's contextActionMenuItem(label, selected).
                    CheckedMenuItem("Device Settings", isDeviceSettingsOpen) { systemOpen = false; onToggleDeviceSettings() }
                    CheckedMenuItem("UAPMD Addins", isAddinsOpen) { systemOpen = false; onToggleAddins() }
                    CheckedMenuItem("Scripting", isScriptOpen) { systemOpen = false; onToggleScript() }
                    CheckedMenuItem("MCP Settings", isMcpOpen, enabled = host.mcpSupported) {
                        systemOpen = false; onToggleMcp()
                    }

                    // Application commands contributed by addins (Virtual MIDI
                    // Devices, Augene2 Integration), after a separator, in the
                    // order the registry reports (renderCommandMenuItems).
                    // Re-read while the menu is open: a running command keeps
                    // its progress in its own title.
                    val commandTick = rememberTicker(systemOpen)
                    val addinCommands = remember(host.addinRevision, systemOpen, commandTick) {
                        host.addinCommands()
                    }
                    AddinCommandItems(addinCommands) { id -> systemOpen = false; host.invokeAddinCommand(id) }
                }
            }

            Box {
                Button(onClick = { projectOpen = true }, contentPadding = Compact) { Text("Project") }
                DropdownMenu(expanded = projectOpen, onDismissRequest = { projectOpen = false }) {
                    DropdownMenuItem(text = { Text("New Project") }, onClick = {
                        projectOpen = false
                        onNewProject()
                    })
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
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Import MIDI Tracks (SMF)") }, onClick = {
                        projectOpen = false
                        scope.launch { pickMidiFileToOpen()?.let { host.importMidiTracks(it) } }
                    })
                    // The backend is whichever separator addin is enabled, so the
                    // item only exists while at least one is, as in uapmd-app.
                    val separators = remember(host.addinRevision, projectOpen) { host.stemSeparators() }
                    if (separators.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Import Split Audio Tracks") },
                            onClick = { projectOpen = false; onToggleAudioImport() }
                        )
                    }
                    // Project-wide commands contributed by addins: MIR analysis
                    // and transcription of all audio clips.
                    val commandTick = rememberTicker(projectOpen)
                    val projectCommands = remember(host.addinRevision, projectOpen, commandTick) {
                        host.projectCommands()
                    }
                    AddinCommandItems(projectCommands) { id -> projectOpen = false; host.invokeProjectCommand(id) }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("UI: ", style = MaterialTheme.typography.labelMedium)
                Box {
                    Button(onClick = { scaleMenu = true }, contentPadding = Compact) { Text("×$uiScale") }
                    DropdownMenu(expanded = scaleMenu, onDismissRequest = { scaleMenu = false }) {
                        listOf(0.5f, 0.8f, 1f, 1.2f, 1.5f, 2f, 4f).forEach { v ->
                            DropdownMenuItem(text = { Text("×$v") }, onClick = { onUiScaleChange(v); scaleMenu = false })
                        }
                    }
                }
            }
            Button(onClick = onToggleTheme, contentPadding = Compact) { ThemeIcon(LocalContentColor.current, darkTheme) }
        }

        // Row 2: Plugins, the transport, and the level meters.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            // No Scan button here: uapmd-app scans from inside the Plugin
            // Selector (PluginSelector.cpp:116), not the toolbar.
            Button(onClick = onTogglePlugins, contentPadding = Compact) { Text("Plugins") }

            // Record first, then Play/Stop, as uapmd-app orders them: the
            // destructive button is not the one under the thumb.
            Button(
                onClick = { recordStatus = host.toggleRecording() },
                enabled = host.isAudioEngineEnabled,
                contentPadding = Compact,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (host.isRecording) RecordActive else MaterialTheme.colorScheme.primary
                )
            ) { RecordIcon(LocalContentColor.current) }
            Button(onClick = { host.playOrStop() }, enabled = host.isAudioEngineEnabled, contentPadding = Compact) {
                if (host.isPlaying) StopIcon(LocalContentColor.current) else PlayIcon(LocalContentColor.current)
            }
            Button(
                onClick = { host.pauseOrResume() },
                enabled = host.isAudioEngineEnabled && host.isPlaying,
                contentPadding = Compact
            ) { if (host.isPaused) PlayIcon(LocalContentColor.current) else PauseIcon(LocalContentColor.current) }

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

/** A panel toggle; the check mark says the panel is open. */
@Composable
private fun CheckedMenuItem(label: String, checked: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        enabled = enabled,
        trailingIcon = { if (checked) CheckIcon(LocalContentColor.current) },
        onClick = onClick
    )
}

/** Addin commands after a separator, greyed out when disabled (renderCommandMenuItems). */
@Composable
private fun AddinCommandItems(commands: List<AddinCommandInfo>, onInvoke: (String) -> Unit) {
    if (commands.isEmpty()) return
    HorizontalDivider()
    commands.forEach { command ->
        DropdownMenuItem(
            text = { Text(command.title) },
            enabled = command.enabled,
            onClick = { onInvoke(command.id) }
        )
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

/** A check mark for toggled menu items; drawn, for the same reason as the rest. */
@Composable
private fun CheckIcon(tint: Color) = Canvas(Modifier.size(TransportIconSize)) {
    drawPath(Path().apply {
        moveTo(size.width * 0.12f, size.height * 0.55f)
        lineTo(size.width * 0.4f, size.height * 0.82f)
        lineTo(size.width * 0.9f, size.height * 0.2f)
    }, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(size.minDimension * 0.14f))
}
