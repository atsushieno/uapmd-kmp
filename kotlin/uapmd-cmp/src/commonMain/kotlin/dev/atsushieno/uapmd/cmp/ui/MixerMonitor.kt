package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.cmp.UapmdHost

/**
 * uapmd-app's Mixer Monitor: transport positions and the per-track latency
 * picture the engine computes.
 *
 * The monitoring-policy and infinite-tail-policy dropdowns from the original are
 * absent — those enums are not exposed by the C API.
 */
@Composable
fun MixerMonitor(host: UapmdHost) {
    val engine = host.model.sequencer.engine
    val sampleRate = (host.model.sampleRate.takeIf { it > 0 } ?: 48000).toDouble()

    Column(Modifier.fillMaxWidth()) {
        Text("Transport", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(
            "audible ${sec(engine.playbackPosition / sampleRate)} · " +
                "render ${sec(engine.renderPlaybackPosition / sampleRate)} · " +
                "playing ${engine.isPlaybackActive}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "master latency ${engine.masterTrackLatency} smp · " +
                "render lead ${engine.masterTrackRenderLead} smp",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        Row(Modifier.fillMaxWidth()) {
            HeaderText("Track", Modifier.width(56.dp))
            HeaderText("Plugins", Modifier.width(64.dp))
            HeaderText("Latency", Modifier.width(74.dp))
            HeaderText("Lead", Modifier.width(64.dp))
            HeaderText("Tail", Modifier.width(64.dp))
            HeaderText("Dirty", Modifier.width(52.dp))
        }
        HorizontalDivider()

        LazyColumn(Modifier.fillMaxWidth()) {
            items(host.trackInstances.indices.toList()) { i ->
                val track = runCatching { engine.getTrack(i.toUInt()) }.getOrNull()
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    CellText("$i", Modifier.width(56.dp))
                    CellText("${host.trackInstances[i].size}", Modifier.width(64.dp))
                    CellText("${engine.getTrackLatency(i)}", Modifier.width(74.dp))
                    CellText("${engine.getTrackRenderLead(i)}", Modifier.width(64.dp))
                    CellText(sec(track?.tailLengthInSeconds ?: 0.0), Modifier.width(64.dp))
                    CellText(if (engine.isTrackDirty(i)) "yes" else "-", Modifier.width(52.dp))
                }
            }
        }
    }
}

@Composable
private fun HeaderText(text: String, modifier: Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)

@Composable
private fun CellText(text: String, modifier: Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall)

private fun sec(v: Double): String {
    val scaled = kotlin.math.round(v * 100).toLong()
    return "${scaled / 100}.${(if (scaled < 0) -scaled else scaled) % 100}s"
}
