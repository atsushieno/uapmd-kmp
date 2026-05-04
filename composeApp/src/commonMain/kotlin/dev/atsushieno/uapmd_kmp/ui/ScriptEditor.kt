package dev.atsushieno.uapmd_kmp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ScriptEditor(
    script: String,
    onScriptChanged: (String) -> Unit,
    errorMessage: String = "",
    outputMessage: String = "",
    isRunning: Boolean = false,
    onRun: () -> Unit = {},
    onClear: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRun, enabled = !isRunning) {
                Text(if (isRunning) "Running…" else "Run")
            }
            OutlinedButton(onClick = onClear) { Text("Clear") }
        }

        OutlinedTextField(
            value = script,
            onValueChange = onScriptChanged,
            modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            label = { Text("Script") }
        )

        if (errorMessage.isNotBlank()) {
            Text(
                errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
        if (outputMessage.isNotBlank()) {
            OutlinedTextField(
                value = outputMessage,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().height(100.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                label = { Text("Output") }
            )
        }
    }
}
