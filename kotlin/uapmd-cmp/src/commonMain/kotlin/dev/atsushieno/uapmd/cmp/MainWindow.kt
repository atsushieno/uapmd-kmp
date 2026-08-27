package dev.atsushieno.uapmd.cmp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import dev.atsushieno.uapmd.cmp.ui.AddinManagerWindow
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

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            FloatingWindowLayer(windows) {
                Column(Modifier.fillMaxSize()) {
                    Toolbar(
                        host = host,
                        onToggleMarkers = {
                            windows.toggle("markers", "Project Markers", DpSize(520.dp, 340.dp)) {
                                MasterMarkersWindow(host)
                            }
                        },
                        onToggleExporter = {
                            windows.toggle("exporter", "Render To File", DpSize(560.dp, 280.dp)) {
                                ExporterWindow(host)
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
                            windows.toggle("plugins", "Plugin Selector", DpSize(560.dp, 430.dp)) {
                                PluginSelector(host)
                            }
                        }
                    )
                    HorizontalDivider()
                    Timeline(host, windows, Modifier.weight(1f))
                    HorizontalDivider()
                    BottomBar(host, windows)
                }
                // Platform-hosted plugin UIs (Android AAP) draw over everything.
                PlatformHostedPluginUiLayer(host, Modifier.fillMaxSize())
            }
        }
    }
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
