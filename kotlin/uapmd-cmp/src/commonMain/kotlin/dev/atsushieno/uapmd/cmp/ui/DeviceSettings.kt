package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.AudioDeviceInfo
import dev.atsushieno.uapmd.AudioIoDirection
import dev.atsushieno.uapmd.cmp.UapmdHost

private val SampleRates = listOf(44100, 48000, 88200, 96000)
private val BufferSizes = listOf(128, 256, 512, 1024, 2048)

/**
 * uapmd-app's Device Settings. Audio in/out, sample rate, buffer size and the
 * auto-buffer-size switch AppModel owns.
 *
 * The platform MIDI in/out routing section is absent: it needs the MIDI port
 * list, which the C API does not expose.
 */
@Composable
fun DeviceSettings(host: UapmdHost) {
    val devices = remember { host.audioDevices() }
    var inputId by remember { mutableStateOf(devices.firstOrNull { it.isInput }?.id ?: -1) }
    var outputId by remember { mutableStateOf(devices.firstOrNull { !it.isInput }?.id ?: -1) }
    var sampleRate by remember { mutableStateOf(host.model.sampleRate.takeIf { it > 0 } ?: 48000) }
    var bufferSize by remember { mutableStateOf(1024) }
    var autoBuffer by remember { mutableStateOf(host.model.autoBufferSizeEnabled) }
    var status by remember { mutableStateOf<String?>(null) }
    var inMenu by remember { mutableStateOf(false) }
    var outMenu by remember { mutableStateOf(false) }
    var srMenu by remember { mutableStateOf(false) }
    var bsMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        DevicePicker("Input", devices.filter { it.isInput }, inputId, inMenu,
            { inMenu = it }, { inputId = it })
        DevicePicker("Output", devices.filter { !it.isInput }, outputId, outMenu,
            { outMenu = it }, { outputId = it })

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sample rate", Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.layout.Box {
                Button(onClick = { srMenu = true }) { Text("$sampleRate") }
                DropdownMenu(expanded = srMenu, onDismissRequest = { srMenu = false }) {
                    SampleRates.forEach { r ->
                        DropdownMenuItem(text = { Text("$r") }, onClick = { sampleRate = r; srMenu = false })
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Buffer size", Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.layout.Box {
                Button(onClick = { bsMenu = true }, enabled = !autoBuffer) { Text("$bufferSize") }
                DropdownMenu(expanded = bsMenu, onDismissRequest = { bsMenu = false }) {
                    BufferSizes.forEach { b ->
                        DropdownMenuItem(text = { Text("$b") }, onClick = { bufferSize = b; bsMenu = false })
                    }
                }
            }
            Checkbox(checked = autoBuffer, onCheckedChange = {
                autoBuffer = it
                host.model.autoBufferSizeEnabled = it
            })
            Text("Auto", style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        Button(onClick = {
            status = host.applyDeviceSettings(inputId, outputId, sampleRate, bufferSize)
        }) { Text("Apply") }

        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Text(
            "Platform MIDI routing needs the MIDI port list, which the C API does not expose.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun DevicePicker(
    label: String,
    devices: List<UapmdHost.UiAudioDevice>,
    selectedId: Int,
    expanded: Boolean,
    setExpanded: (Boolean) -> Unit,
    onSelect: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
        androidx.compose.foundation.layout.Box {
            Button(onClick = { setExpanded(true) }) {
                Text(devices.firstOrNull { it.id == selectedId }?.name ?: "(none)")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { setExpanded(false) }) {
                devices.forEach { d ->
                    DropdownMenuItem(text = { Text(d.name) }, onClick = { onSelect(d.id); setExpanded(false) })
                }
            }
        }
    }
}
