package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.cmp.UapmdHost
import dev.atsushieno.uapmd.cmp.defaultStemOutputDirectory
import dev.atsushieno.uapmd.cmp.pickAudioFileToOpen
import dev.atsushieno.uapmd.cmp.pickStemModelFileToOpen
import kotlinx.coroutines.launch

/**
 * Split audio import, as uapmd-app added it in `AudioImportWindow`.
 *
 * A separator is contributed by an addin (Demucs and BS-Roformer ship built
 * in), so the backend list is whatever is enabled right now — and the window
 * says so rather than offering a dead button when nothing is. Both shipped
 * separators want a model file; one that does not reports no extensions and the
 * model row disappears.
 *
 * The run itself is long and blocking, so it lives on [UapmdHost]; this is only
 * its dialog.
 */
@Composable
fun AudioImportWindow(host: UapmdHost, onClose: () -> Unit) {
    val separators = remember(host.addinRevision) { host.stemSeparators() }
    var separatorIndex by remember(separators) { mutableStateOf(0) }
    var audioPath by remember { mutableStateOf("") }
    var modelPath by remember { mutableStateOf("") }
    var separatorMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val status = host.stemImportStatus

    // The window closes itself once a finished import has been applied, the way
    // uapmd-app's processCompletedResult() does.
    LaunchedEffect(status.completed, status.success) {
        if (status.completed && status.success) {
            host.resetStemImportStatus()
            onClose()
        }
    }

    Column(Modifier.fillMaxWidth().padding(12.dp).verticalScroll(rememberScrollState())) {
        if (separators.isEmpty()) {
            Text("No stem separation backend is available.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Separators are contributed by addins — enable one in the Addins window " +
                    "(Demucs and BS-Roformer ship built in).",
                style = MaterialTheme.typography.labelSmall
            )
            return@Column
        }

        val separator = separators[separatorIndex.coerceIn(0, separators.lastIndex)]

        Text("Backend", style = MaterialTheme.typography.labelSmall)
        Box {
            Button(onClick = { separatorMenu = true }, enabled = !status.running) { Text(separator.name) }
            DropdownMenu(expanded = separatorMenu, onDismissRequest = { separatorMenu = false }) {
                separators.forEachIndexed { index, info ->
                    DropdownMenuItem(
                        text = { Text(info.name) },
                        onClick = { separatorIndex = index; separatorMenu = false }
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = audioPath,
                onValueChange = { audioPath = it },
                label = { Text("Audio file") },
                singleLine = true,
                enabled = !status.running,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { scope.launch { pickAudioFileToOpen()?.let { audioPath = it } } },
                enabled = !status.running
            ) { Text("Browse…") }
        }

        if (separator.requiresModelFile) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = modelPath,
                    onValueChange = { modelPath = it },
                    label = { Text(separator.modelFileLabel.ifEmpty { "Model file" }) },
                    singleLine = true,
                    enabled = !status.running,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            pickStemModelFileToOpen(separator.modelFileExtensions)?.let { modelPath = it }
                        }
                    },
                    enabled = !status.running
                ) { Text("Browse…") }
            }
            Text(
                "Accepts ${separator.modelFileExtensions.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 10.dp))

        if (status.running) {
            LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
            Text(status.message, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        } else if (status.completed) {
            Text(
                status.message,
                style = MaterialTheme.typography.labelSmall,
                color = if (status.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    host.importSplitAudioTracks(
                        separatorId = separator.id,
                        audioFile = audioPath,
                        outputDirectory = defaultStemOutputDirectory(audioPath),
                        modelPath = modelPath.ifEmpty { null }
                    )
                },
                // A separator that wants a model file cannot start without one:
                // it would fail deep inside the worker instead of here.
                enabled = !status.running && audioPath.isNotEmpty() &&
                    (!separator.requiresModelFile || modelPath.isNotEmpty())
            ) { Text("Import") }

            if (status.running) {
                Button(onClick = { host.cancelStemImport() }) { Text("Cancel") }
            } else {
                TextButton(onClick = { host.resetStemImportStatus(); onClose() }) { Text("Close") }
            }
        }
    }
}
