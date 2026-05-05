package dev.atsushieno.uapmd_kmp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AudioImportWindow(
    filePath: String,
    onFilePathChanged: (String) -> Unit,
    progress: Float? = null,
    statusMessage: String = "",
    onImport: () -> Unit = {},
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Import Audio", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = filePath,
            onValueChange = onFilePathChanged,
            label = { Text("Audio file path") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            if (statusMessage.isNotEmpty())
                Text(statusMessage, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport, enabled = filePath.isNotBlank()) { Text("Import") }
            }
            if (statusMessage.isNotEmpty())
                Text(statusMessage, style = MaterialTheme.typography.bodySmall)
        }
    }
}
