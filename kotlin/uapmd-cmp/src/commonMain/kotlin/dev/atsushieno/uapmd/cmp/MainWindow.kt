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
import dev.atsushieno.uapmd.cmp.ui.InstanceDetails
import dev.atsushieno.uapmd.cmp.ui.PluginSelector
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
                        onTogglePlugins = {
                            windows.toggle("plugins", "Plugin Selector", DpSize(560.dp, 430.dp)) {
                                PluginSelector(host)
                            }
                        }
                    )
                    HorizontalDivider()
                    TrackList(host, windows, Modifier.weight(1f))
                    HorizontalDivider()
                    BottomBar(host, windows)
                }
            }
        }
    }
}

@Composable
private fun TrackList(
    host: UapmdHost,
    windows: dev.atsushieno.uapmd.cmp.ui.FloatingWindowManager,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().padding(8.dp)) {
        val tl = host.timeline
        Text(
            "tracks ${host.trackCount} · tempo ${tl?.tempo ?: "-"} · " +
                "${tl?.timeSignatureNumerator ?: "-"}/${tl?.timeSignatureDenominator ?: "-"} · " +
                "sr ${host.model.sampleRate}",
            style = MaterialTheme.typography.bodySmall
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            items(host.trackInstances.indices.toList()) { trackIndex ->
                val instances = host.trackInstances[trackIndex]
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Track $trackIndex", Modifier.weight(1f))
                        Button(onClick = { host.removeTrack(trackIndex) }) { Text("🗑") }
                    }
                    // uapmd-app labels this button with the first plugin, or
                    // "Add Plugin" when the track is empty.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            windows.toggle("plugins", "Plugin Selector", DpSize(560.dp, 430.dp)) {
                                PluginSelector(host)
                            }
                        }) { Text(instances.firstOrNull()?.displayName ?: "Add Plugin") }
                        instances.forEach { inst ->
                            Text("  ")
                            Button(onClick = {
                                windows.open(
                                    "details:${inst.instanceId}",
                                    "${inst.displayName} (${inst.formatName}) - Details",
                                    DpSize(460.dp, 420.dp)
                                ) { InstanceDetails(host, inst) }
                            }) { Text("${inst.displayName} ⋮") }
                        }
                    }
                }
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
            windows.toggle("mixer", "Mixer Monitor", DpSize(460.dp, 300.dp)) {
                Text("Mixer monitor (Phase 7).")
            }
        }) { Text("Mixer Monitor") }
        Text("  ")
        Button(onClick = {
            windows.toggle("instances", "Plugin Instances", DpSize(520.dp, 320.dp)) {
                Text("Plugin instances (Phase 3).")
            }
        }) { Text("Plugin Instances") }
    }
}
