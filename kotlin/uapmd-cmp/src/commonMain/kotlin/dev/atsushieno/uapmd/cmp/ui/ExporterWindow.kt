package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.cmp.UapmdHost
import dev.atsushieno.uapmd.cmp.deliverSavedFile
import dev.atsushieno.uapmd.cmp.pickProjectFileToSave
import kotlinx.coroutines.launch

/**
 * uapmd-app's Render To File.
 *
 * Uses the already-bound `SequencerEngine.renderOffline()` rather than
 * AppModel's `start_render` family — same result, no new binding, and the
 * progress/cancel callbacks come through directly.
 */
@Composable
fun ExporterWindow(host: UapmdHost) {
    var outputPath by remember { mutableStateOf("") }
    var startSeconds by remember { mutableStateOf("0") }
    var endSeconds by remember { mutableStateOf("") }
    var tailSeconds by remember { mutableStateOf("2") }
    var silenceStop by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var deliverWhenDone by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = outputPath, onValueChange = { outputPath = it },
                label = { Text("Output file") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                scope.launch { pickProjectFileToSave("render.wav")?.let { outputPath = it } }
            }) { Text("…") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = startSeconds, onValueChange = { startSeconds = it },
                label = { Text("Start (s)") }, singleLine = true, modifier = Modifier.width(110.dp)
            )
            OutlinedTextField(
                value = endSeconds, onValueChange = { endSeconds = it },
                label = { Text("End (s, blank = content)") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = tailSeconds, onValueChange = { tailSeconds = it },
                label = { Text("Tail (s)") }, singleLine = true, modifier = Modifier.width(100.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = silenceStop, onCheckedChange = { silenceStop = it })
            Text("Stop on silence", style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    deliverWhenDone = outputPath
                    host.startRender(
                        outputPath = outputPath,
                        startSeconds = startSeconds.toDoubleOrNull() ?: 0.0,
                        endSeconds = endSeconds.toDoubleOrNull(),
                        tailSeconds = tailSeconds.toDoubleOrNull() ?: 0.0,
                        enableSilenceStop = silenceStop
                    )
                },
                enabled = outputPath.isNotBlank() && !host.isRendering
            ) { Text(if (host.isRendering) "Rendering…" else "Render") }

            Button(onClick = { host.cancelRender() }, enabled = host.isRendering) { Text("Cancel") }
        }

        // A render finishes on its own schedule, so the file is delivered when the
        // engine stops rendering rather than when the button was pressed.
        val rendering = host.isRendering
        LaunchedEffect(rendering) {
            if (!rendering && deliverWhenDone != null) {
                deliverSavedFile(deliverWhenDone!!)
                deliverWhenDone = null
            }
        }

        if (host.isRendering) {
            LinearProgressIndicator(
                progress = { host.renderProgress.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            )
        }
        host.renderStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
