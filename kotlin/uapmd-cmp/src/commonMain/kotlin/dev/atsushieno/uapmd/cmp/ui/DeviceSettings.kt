package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import dev.atsushieno.uapmd.AudioDeviceManager
import dev.atsushieno.uapmd.cmp.UapmdHost
import dev.atsushieno.uapmd.cmp.platformHardwareConcurrency
import dev.atsushieno.uapmd.cmp.platformSupportsAudioWorkerSettings

private val SampleRates = listOf(44100, 48000, 88200, 96000)
private val BufferSizes = listOf(128, 256, 512, 1024, 2048)

/** `AudioDeviceSettings`'s "System Default": the platform picks the device. */
private const val SystemDefault = -1

/**
 * uapmd-app's Device Settings. Audio in/out, sample rate, buffer size and the
 * auto-buffer-size switch AppModel owns, then - on desktop - the audio worker
 * controls.
 *
 * Input offers "None" as well (`AudioIODeviceManager::kNoDeviceIndex`), which
 * leaves the input closed instead of falling back to the default device.
 *
 * The platform MIDI in/out routing section is absent: it needs the MIDI port
 * list, which the C API does not expose.
 */
@Composable
fun DeviceSettings(host: UapmdHost) {
    val devices = remember { host.audioDevices() }
    var inputIndex by remember { mutableStateOf(SystemDefault) }
    var outputIndex by remember { mutableStateOf(SystemDefault) }
    var sampleRate by remember { mutableStateOf(host.model.sampleRate.takeIf { it > 0 } ?: 48000) }
    var bufferSize by remember { mutableStateOf(1024) }
    var autoBuffer by remember { mutableStateOf(host.model.autoBufferSizeEnabled) }
    var status by remember { mutableStateOf<String?>(null) }
    var inMenu by remember { mutableStateOf(false) }
    var outMenu by remember { mutableStateOf(false) }
    var srMenu by remember { mutableStateOf(false) }
    var bsMenu by remember { mutableStateOf(false) }

    // Scrolls: with the worker controls it outgrows a small floating window.
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        DevicePicker("Input Device", devices.filter { it.isInput }, inputIndex, allowNone = true, inMenu,
            { inMenu = it }, { inputIndex = it })
        DevicePicker("Output Device", devices.filter { !it.isInput }, outputIndex, allowNone = false, outMenu,
            { outMenu = it }, { outputIndex = it })

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
            status = host.applyDeviceSettings(inputIndex, outputIndex, sampleRate, bufferSize)
        }) { Text("Apply") }

        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (platformSupportsAudioWorkerSettings) AudioWorkerSettings(host)

        Text(
            "Platform MIDI routing needs the MIDI port list, which the C API does not expose.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/**
 * uapmd-app's Audio Workers combo and deadline switch. The choices are Serial,
 * 1, 2, 4, then every multiple of 4 up to the machine's CPU count - capped at 32,
 * the capacity of the worker pool's diagnostics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioWorkerSettings(host: UapmdHost) {
    var workerCount by remember { mutableStateOf(host.audioWorkerCount) }
    var stopOnDeadline by remember { mutableStateOf(host.stopAudioEngineOnDeadline) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val choices = remember {
        val cpus = platformHardwareConcurrency.takeIf { it > 0 } ?: 4
        val maximum = minOf(cpus, 32).toUInt()
        buildList {
            add(0u)
            listOf(1u, 2u, 4u).filter { it <= maximum }.forEach { add(it) }
            var n = 8u
            while (n <= maximum) { add(n); n += 4u }
        }
    }
    fun label(count: UInt) = if (count == 0u) "Serial" else "$count"

    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Audio Workers", Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
            tooltip = {
                PlainTooltip {
                    Text(
                        "Process independent tracks concurrently. The default uses one quarter of the " +
                            "machine's CPU concurrency, up to four workers. Choices include 1, 2, 4, then " +
                            "every multiple of 4 up to the machine's CPU count (maximum 32). Incompatible " +
                            "graph providers or extensions retain serial processing."
                    )
                }
            },
            state = rememberTooltipState()
        ) {
            androidx.compose.foundation.layout.Box {
                Button(onClick = { menu = true }) { Text(label(workerCount)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    choices.forEach { count ->
                        DropdownMenuItem(text = { Text(label(count)) }, onClick = {
                            menu = false
                            error = host.configureAudioWorkers(count)
                            workerCount = host.audioWorkerCount
                        })
                    }
                }
            }
        }
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(
                    "Off by default for this session. Late audio blocks are silenced until workers " +
                        "finish, then processing resumes automatically. Enable to require a manual " +
                        "restart after a worker deadline overrun. Overruns are always logged."
                )
            }
        },
        state = rememberTooltipState()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = stopOnDeadline, onCheckedChange = {
                host.stopAudioEngineOnDeadline = it
                stopOnDeadline = host.stopAudioEngineOnDeadline
            })
            Text("Stop audio engine on deadline overrun", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DevicePicker(
    label: String,
    devices: List<UapmdHost.UiAudioDevice>,
    selectedIndex: Int,
    allowNone: Boolean,
    expanded: Boolean,
    setExpanded: (Boolean) -> Unit,
    onSelect: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
        androidx.compose.foundation.layout.Box {
            Button(onClick = { setExpanded(true) }) {
                Text(
                    when (selectedIndex) {
                        SystemDefault -> "System Default"
                        AudioDeviceManager.NO_DEVICE_INDEX -> "None"
                        else -> devices.firstOrNull { it.index == selectedIndex }?.name ?: "System Default"
                    }
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { setExpanded(false) }) {
                DropdownMenuItem(text = { Text("System Default") }, onClick = { onSelect(SystemDefault); setExpanded(false) })
                if (allowNone)
                    DropdownMenuItem(text = { Text("None") }, onClick = {
                        onSelect(AudioDeviceManager.NO_DEVICE_INDEX); setExpanded(false)
                    })
                devices.forEach { d ->
                    DropdownMenuItem(text = { Text(d.name) }, onClick = { onSelect(d.index); setExpanded(false) })
                }
            }
        }
    }
}
