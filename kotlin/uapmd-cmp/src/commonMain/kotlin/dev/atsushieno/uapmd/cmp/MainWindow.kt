package dev.atsushieno.uapmd.cmp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.cmp.ui.FloatingWindowLayer
import dev.atsushieno.uapmd.cmp.ui.rememberFloatingWindowManager

/**
 * Root of the uapmd-cmp UI.
 *
 * Phase 0/1 in progress: the AppModel bootstrap and the floating window manager
 * are real; the toolbar and timeline are still placeholders
 * (docs/uapmd-cmp-plan.md).
 */
@Composable
fun MainWindow() {
    val host = rememberUapmdHost()
    val windows = rememberFloatingWindowManager()

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            FloatingWindowLayer(windows) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("uapmd-cmp", style = MaterialTheme.typography.headlineMedium)

                    Button(
                        onClick = { host.toggleAudioEngine() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (host.isAudioEngineEnabled) Color(0xFF3F9455) else Color(0xFF944536)
                        )
                    ) {
                        Text(if (host.isAudioEngineEnabled) "Audio Engine: On" else "Audio Engine: Off")
                    }

                    Text(
                        "sample rate ${host.model.sampleRate} · tracks ${host.model.trackCount}",
                        style = MaterialTheme.typography.bodySmall
                    )

                    // Window-manager exercise: several concurrent, independently
                    // keyed windows, which is what Instance Details needs.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            windows.toggle("plugins", "Plugin Selector", DpSize(460.dp, 300.dp)) {
                                Text("Plugin list goes here (Phase 3).")
                            }
                        }) { Text("Plugins") }

                        repeat(3) { i ->
                            Button(onClick = {
                                windows.open("details:$i", "Instance $i - Details", DpSize(320.dp, 200.dp)) {
                                    Text("Details for instance $i (Phase 4).")
                                }
                            }) { Text("Details $i") }
                        }
                    }
                }
            }
        }
    }
}
