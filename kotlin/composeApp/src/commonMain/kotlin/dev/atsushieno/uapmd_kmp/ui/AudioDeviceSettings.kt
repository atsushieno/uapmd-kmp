package dev.atsushieno.uapmd_kmp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class AudioDeviceInfo(
    val id: Int,
    val name: String,
    val isInput: Boolean
)

@Composable
fun AudioDeviceSettings(
    inputDevices: List<AudioDeviceInfo>,
    outputDevices: List<AudioDeviceInfo>,
    selectedInputId: Int?,
    selectedOutputId: Int?,
    sampleRates: List<Int> = listOf(44100, 48000, 88200, 96000),
    selectedSampleRate: Int = 48000,
    bufferSizes: List<Int> = listOf(128, 256, 512, 1024, 2048),
    selectedBufferSize: Int = 512,
    onInputSelected: (Int) -> Unit = {},
    onOutputSelected: (Int) -> Unit = {},
    onSampleRateSelected: (Int) -> Unit = {},
    onBufferSizeSelected: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Audio Device Settings", style = MaterialTheme.typography.titleMedium)

        DeviceDropdown(
            label = "Input Device",
            devices = inputDevices,
            selectedId = selectedInputId,
            onSelected = onInputSelected
        )
        DeviceDropdown(
            label = "Output Device",
            devices = outputDevices,
            selectedId = selectedOutputId,
            onSelected = onOutputSelected
        )

        IntDropdown(
            label = "Sample Rate",
            options = sampleRates,
            selected = selectedSampleRate,
            display = { "$it Hz" },
            onSelected = onSampleRateSelected
        )
        IntDropdown(
            label = "Buffer Size",
            options = bufferSizes,
            selected = selectedBufferSize,
            display = { "$it frames" },
            onSelected = onBufferSizeSelected
        )
    }
}

@Composable
private fun DeviceDropdown(
    label: String,
    devices: List<AudioDeviceInfo>,
    selectedId: Int?,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = devices.firstOrNull { it.id == selectedId }

    Column {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selected?.name ?: "— none —", modifier = Modifier.weight(1f))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = { Text(device.name) },
                        onClick = { onSelected(device.id); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> IntDropdown(
    label: String,
    options: List<T>,
    selected: T,
    display: (T) -> String,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(display(selected), modifier = Modifier.weight(1f))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(display(opt)) },
                        onClick = { onSelected(opt); expanded = false }
                    )
                }
            }
        }
    }
}
