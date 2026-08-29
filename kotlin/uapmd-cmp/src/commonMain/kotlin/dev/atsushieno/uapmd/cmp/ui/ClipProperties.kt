package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.cmp.UapmdHost
import dev.atsushieno.uapmd.cmp.pickMediaFileToOpen
import kotlinx.coroutines.launch

/**
 * Per-clip properties, every field going through `ProjectCommands` so each edit
 * is one undoable step: name, position, length, gain, mute, enable, source file.
 *
 * uapmd-app 0.5.6 has no equivalent window — its GUI never calls `setClipGain` or
 * `setClipMuted`, and name/file editing lives in the Sequence Editor table. This is
 * deliberately ahead of it rather than a divergence to correct: the same features
 * are wanted in uapmd-app. Do not delete it in the name of parity.
 */
@Composable
fun ClipProperties(host: UapmdHost, trackIndex: Int, clipId: Int) {
    val clip = host.trackClips.getOrNull(trackIndex)?.firstOrNull { it.clipId == clipId }
    if (clip == null) {
        Text("Clip $clipId is gone.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val sampleRate = (host.model.sampleRate.takeIf { it > 0 } ?: 48000).toDouble()
    val scope = rememberCoroutineScope()

    var name by remember(clipId) { mutableStateOf(clip.name) }
    var position by remember(clipId) { mutableStateOf((clip.positionSamples / sampleRate).toString()) }
    var duration by remember(clipId) { mutableStateOf((clip.durationSamples / sampleRate).toString()) }
    var gain by remember(clipId) { mutableStateOf(clip.gain.toFloat()) }
    var status by remember { mutableStateOf<String?>(null) }

    fun report(ok: Boolean, what: String) { status = if (ok) null else "The engine rejected the $what change." }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "${clip.clipType} clip $clipId on track $trackIndex",
            style = MaterialTheme.typography.bodySmall
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") },
                singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { report(host.setClipName(trackIndex, clipId, name), "name") }) { Text("Set") }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(position, { position = it }, label = { Text("Position (s)") },
                singleLine = true, modifier = Modifier.width(130.dp))
            Button(onClick = {
                position.toDoubleOrNull()?.let { report(host.moveClip(trackIndex, clipId, it), "position") }
                    ?: run { status = "Position must be a number." }
            }) { Text("Move") }

            OutlinedTextField(duration, { duration = it }, label = { Text("Length (s)") },
                singleLine = true, modifier = Modifier.width(130.dp))
            Button(onClick = {
                duration.toDoubleOrNull()?.let {
                    report(host.resizeClip(trackIndex, clipId, (it * sampleRate).toLong()), "length")
                } ?: run { status = "Length must be a number." }
            }) { Text("Resize") }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Gain", Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = gain,
                onValueChange = { gain = it },
                onValueChangeFinished = { report(host.setClipGain(trackIndex, clipId, gain.toDouble()), "gain") },
                valueRange = 0f..2f,
                modifier = Modifier.weight(1f)
            )
            Text(fixed2(gain.toDouble()), Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = clip.muted, onCheckedChange = {
                report(host.setClipMuted(trackIndex, clipId, it), "mute")
            })
            Text("Muted", style = MaterialTheme.typography.bodySmall)

            val enabled = remember(clipId, host.trackClips) { host.isClipEnabled(trackIndex, clipId) }
            Checkbox(checked = enabled, onCheckedChange = {
                report(host.setClipEnabled(trackIndex, clipId, it), "enable")
            })
            Text("Enabled", style = MaterialTheme.typography.bodySmall)
        }

        if (clip.filepath.isNotEmpty() || clip.clipType.name == "Audio") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    clip.filepath.ifEmpty { "(no source file)" },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = {
                    scope.launch {
                        pickMediaFileToOpen()?.let { report(host.setClipFilepath(trackIndex, clipId, it), "file") }
                    }
                }) { Text("Change file…") }
            }
        }

        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

private fun fixed2(v: Double): String {
    val scaled = kotlin.math.round(v * 100).toLong()
    return "${scaled / 100}.${((if (scaled < 0) -scaled else scaled) % 100).toString().padStart(2, '0')}"
}
