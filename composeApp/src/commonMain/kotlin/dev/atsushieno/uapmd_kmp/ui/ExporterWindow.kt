package dev.atsushieno.uapmd_kmp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class ExportRange { EntireProject, LoopRegion, Custom }

data class ExportSettings(
    val outputPath: String = "",
    val range: ExportRange = ExportRange.EntireProject,
    val customStartSeconds: Double = 0.0,
    val customEndSeconds: Double = 60.0,
    val enableSilenceStop: Boolean = false,
    val silenceThresholdDb: Double = -60.0,
    val silenceDurationSeconds: Double = 3.0,
    val sampleRate: Int = 48000,
    val tailSeconds: Double = 2.0
)

@Composable
fun ExporterWindow(
    settings: ExportSettings,
    onSettingsChanged: (ExportSettings) -> Unit,
    progress: Float? = null,      // null = idle, 0..1 = rendering
    statusMessage: String = "",
    onStartExport: () -> Unit = {},
    onCancelExport: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Export Audio", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = settings.outputPath,
            onValueChange = { onSettingsChanged(settings.copy(outputPath = it)) },
            label = { Text("Output path") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Range", style = MaterialTheme.typography.labelMedium)
        ExportRange.entries.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.range == r, onClick = { onSettingsChanged(settings.copy(range = r)) })
                Text(r.name.replace(Regex("([A-Z])"), " $1").trim())
            }
        }

        if (settings.range == ExportRange.Custom) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = settings.customStartSeconds.toString(),
                    onValueChange = { it.toDoubleOrNull()?.let { v -> onSettingsChanged(settings.copy(customStartSeconds = v)) } },
                    label = { Text("Start (s)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = settings.customEndSeconds.toString(),
                    onValueChange = { it.toDoubleOrNull()?.let { v -> onSettingsChanged(settings.copy(customEndSeconds = v)) } },
                    label = { Text("End (s)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = settings.enableSilenceStop,
                onCheckedChange = { onSettingsChanged(settings.copy(enableSilenceStop = it)) })
            Text("Stop on silence")
        }

        if (settings.enableSilenceStop) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = settings.silenceThresholdDb.toString(),
                    onValueChange = { it.toDoubleOrNull()?.let { v -> onSettingsChanged(settings.copy(silenceThresholdDb = v)) } },
                    label = { Text("Threshold (dB)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = settings.silenceDurationSeconds.toString(),
                    onValueChange = { it.toDoubleOrNull()?.let { v -> onSettingsChanged(settings.copy(silenceDurationSeconds = v)) } },
                    label = { Text("Duration (s)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        HorizontalDivider()

        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            if (statusMessage.isNotEmpty()) Text(statusMessage, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onCancelExport) { Text("Cancel") }
        } else {
            Button(onClick = onStartExport, enabled = settings.outputPath.isNotBlank()) {
                Text("Start Export")
            }
            if (statusMessage.isNotEmpty()) Text(statusMessage, style = MaterialTheme.typography.bodySmall)
        }
    }
}
