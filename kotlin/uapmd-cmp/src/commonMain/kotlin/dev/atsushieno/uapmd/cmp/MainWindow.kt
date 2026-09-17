package dev.atsushieno.uapmd.cmp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.cmp.ui.FloatingWindowLayer
import dev.atsushieno.uapmd.cmp.ui.closeDetailsWindowsExcept
import dev.atsushieno.uapmd.cmp.ui.AddinManagerWindow
import dev.atsushieno.uapmd.cmp.ui.McpSettingsWindow
import dev.atsushieno.uapmd.cmp.ui.ScriptEditorWindow
import dev.atsushieno.uapmd.cmp.ui.AudioImportWindow
import dev.atsushieno.uapmd.cmp.ui.DeviceSettings
import dev.atsushieno.uapmd.cmp.ui.ExporterWindow
import dev.atsushieno.uapmd.cmp.ui.InstanceDetails
import dev.atsushieno.uapmd.cmp.ui.MasterMarkersWindow
import dev.atsushieno.uapmd.cmp.ui.MixerMonitor
import dev.atsushieno.uapmd.cmp.ui.PluginInstances
import dev.atsushieno.uapmd.cmp.ui.PluginSelector
import dev.atsushieno.uapmd.cmp.ui.Timeline
import dev.atsushieno.uapmd.cmp.ui.Toolbar
import dev.atsushieno.uapmd.cmp.ui.rememberFloatingWindowManager

/**
 * Root of the uapmd-cmp UI. Phase 1: toolbar, bottom bar and the floating
 * window manager are real; the timeline is still a placeholder track list.
 */
@Composable
fun MainWindow() {
    val host = rememberUapmdHost()
    val windows = rememberFloatingWindowManager()
    var uiScale by remember { mutableStateOf(1f) }
    var darkTheme by remember { mutableStateOf(true) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    // "New Project" always replaces; asking first is the host's job, and only
    // worth doing when there is something to lose.
    var confirmNewProject by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        // uapmd-app's Scale combo rescales the whole UI; overriding the density
        // is the Compose equivalent.
        CompositionLocalProvider(
            LocalDensity provides Density(LocalDensity.current.density * uiScale, LocalDensity.current.fontScale)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .focusRequester(focus)
                    .focusable()
                    // Ctrl/Cmd+Z, Shift+Ctrl+Z and Ctrl+Y, as uapmd-app binds them.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val primary = event.isCtrlPressed || event.isMetaPressed
                        val h = host.history
                        when {
                            primary && event.isShiftPressed && event.key == Key.Z -> {
                                if (h.canRedo && !h.busy) host.redo(); true
                            }
                            primary && event.key == Key.Z -> {
                                if (h.canUndo && !h.busy) host.undo(); true
                            }
                            primary && event.key == Key.Y -> {
                                if (h.canRedo && !h.busy) host.redo(); true
                            }
                            // Clipboard over the clip selection. Paste lands on
                            // the track the selection came from, at the
                            // playhead, which is where uapmd-app puts it when
                            // the gesture carries no position of its own.
                            primary && event.key == Key.C -> {
                                if (host.selectedClips().isNotEmpty()) host.copySelectedClips()
                                true
                            }
                            primary && event.key == Key.X -> {
                                if (host.selectedClips().isNotEmpty()) host.deleteSelectedClips(cut = true)
                                true
                            }
                            primary && event.key == Key.V -> {
                                val track = host.selectedClips().firstOrNull()?.trackIndex ?: 0
                                if (host.canPasteOnto(track))
                                    host.pasteClips(track, host.playheadSeconds)
                                true
                            }
                            event.key == Key.Delete || event.key == Key.Backspace -> {
                                if (host.selectedClips().isNotEmpty()) { host.deleteSelectedClips(); true } else false
                            }
                            event.key == Key.Escape -> {
                                if (host.selectedClips().isNotEmpty()) { host.clearClipSelection(); true } else false
                            }
                            else -> false
                        }
                    }
            ) {
                // A deleted instance must take its Details window with it, whichever
                // path deleted it: the track menu, the master track menu, the
                // Plugin Instances window, or the window's own Delete button. Two
                // of those four used to close it by hand and two did not, so this
                // prunes against the live instance list instead — which also
                // catches the removals no button performed at all, such as a
                // project load replacing every instance at once.
                //
                // uapmd-app does the same thing from one place, in its
                // `instanceRemoved` handler (MainWindow.cpp).
                val liveInstanceIds = remember(host.trackInstances, host.masterInstances) {
                    (host.trackInstances.flatten() + host.masterInstances)
                        .mapTo(mutableSetOf()) { it.instanceId }
                }
                LaunchedEffect(liveInstanceIds) {
                    windows.closeDetailsWindowsExcept(liveInstanceIds)
                }

                FloatingWindowLayer(windows) {
                    Column(Modifier.fillMaxSize()) {
                        Toolbar(
                            host = host,
                            uiScale = uiScale,
                            onUiScaleChange = { uiScale = it },
                            darkTheme = darkTheme,
                            onToggleTheme = { darkTheme = !darkTheme },
                            isDeviceSettingsOpen = windows.isOpen("devices"),
                            isAddinsOpen = windows.isOpen("addins"),
                            onToggleExporter = {
                                windows.toggle("exporter", "Render To File", DpSize(560.dp, 280.dp)) {
                                    ExporterWindow(host)
                                }
                            },
                            onToggleScript = {
                                windows.toggle("script", "Script", DpSize(620.dp, 520.dp)) {
                                    ScriptEditorWindow(host)
                                }
                            },
                            onToggleMcp = {
                                windows.toggle("mcp", "MCP Settings", DpSize(460.dp, 320.dp)) {
                                    McpSettingsWindow(host)
                                }
                            },
                            onToggleAddins = {
                                windows.toggle("addins", "Addins", DpSize(500.dp, 340.dp)) {
                                    AddinManagerWindow(host)
                                }
                            },
                            onToggleDeviceSettings = {
                                windows.toggle("devices", "Device Settings", DpSize(420.dp, 320.dp)) {
                                    DeviceSettings(host)
                                }
                            },
                            onTogglePlugins = {
                                host.targetPluginDestination(-1)
                                windows.toggle("plugins", "Plugin Selector", DpSize(560.dp, 430.dp)) {
                                    PluginSelector(host) { windows.close("plugins") }
                                }
                            },
                            onToggleAudioImport = {
                                windows.toggle("audioImport", "Import Split Audio Tracks", DpSize(560.dp, 400.dp)) {
                                    AudioImportWindow(host) { windows.close("audioImport") }
                                }
                            },
                            onNewProject = {
                                if (host.history.dirty) confirmNewProject = true else host.newProject()
                            }
                        )
                        HorizontalDivider()
                        Timeline(host, windows, Modifier.weight(1f))
                        HorizontalDivider()
                        BottomBar(host, windows)
                    }
                    PlatformHostedPluginUiLayer(host, Modifier.fillMaxSize())
                }
            }
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    if (confirmNewProject) {
        AlertDialog(
            onDismissRequest = { confirmNewProject = false },
            title = { Text("New Project") },
            text = { Text("This project has unsaved changes. Starting a new project discards them.") },
            confirmButton = {
                TextButton(onClick = { confirmNewProject = false; host.newProject() }) { Text("Discard and Continue") }
            },
            dismissButton = { TextButton(onClick = { confirmNewProject = false }) { Text("Cancel") } }
        )
    }

    // uapmd-app asks before discarding an unsaved project on quit.
    if (showUnsavedDialog) {
        UnsavedProjectDialog(
            onSave = { showUnsavedDialog = false },
            onDiscard = { showUnsavedDialog = false },
            onCancel = { showUnsavedDialog = false }
        )
    }
}

@Composable
private fun UnsavedProjectDialog(onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Unsaved Project") },
        text = { Text("This project has unsaved changes.") },
        confirmButton = { TextButton(onClick = onSave) { Text("Save Project") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Discard") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun BottomBar(host: UapmdHost, windows: dev.atsushieno.uapmd.cmp.ui.FloatingWindowManager) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { host.addTrack() }) { Text("+") }
        Text("  ")
        Button(onClick = {
            windows.toggle("mixer", "Mixer Monitor", DpSize(480.dp, 340.dp)) { MixerMonitor(host) }
        }) { Text("Mixer Monitor") }
        Text("  ")
        Button(onClick = {
            windows.toggle("instances", "Plugin Instances", DpSize(520.dp, 320.dp)) { PluginInstances(host, windows) }
        }) { Text("Plugin Instances") }
    }
}
