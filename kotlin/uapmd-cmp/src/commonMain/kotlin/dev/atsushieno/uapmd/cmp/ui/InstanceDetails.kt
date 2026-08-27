package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.ParameterMetadata
import dev.atsushieno.uapmd.PresetMetadata
import dev.atsushieno.uapmd.cmp.TrackInstance
import dev.atsushieno.uapmd.cmp.UapmdHost

/**
 * uapmd-app's per-instance Details window: pitch bend, channel pressure, a
 * playable keyboard, presets, UMP group and the parameter table.
 *
 * One window per instance — the manager keys them as `details:<instanceId>`, so
 * several stay open at once, as in the original.
 */
@Composable
fun InstanceDetails(host: UapmdHost, inst: TrackInstance) {
    val engine = host.model.sequencer.engine
    val instance = engine.getPluginInstance(inst.instanceId)
    if (instance == null) {
        Text("Instance ${inst.instanceId} is gone.", style = MaterialTheme.typography.bodySmall)
        return
    }

    var pitchBend by remember { mutableStateOf(0f) }
    var pressure by remember { mutableStateOf(0f) }
    var filter by remember { mutableStateOf("") }
    var presetsOpen by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf<PresetMetadata?>(null) }
    var revision by remember { mutableStateOf(0) }

    val parameters = remember(inst.instanceId, revision) {
        (0 until instance.parameterCount.toInt()).mapNotNull { instance.getParameterMetadata(it.toUInt()) }
    }
    val presets = remember(inst.instanceId) {
        (0 until instance.presetCount.toInt()).mapNotNull { instance.getPresetMetadata(it.toUInt()) }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { host.model.requestShowPluginUi(inst.instanceId) },
                enabled = instance.hasUiSupport
            ) { Text("Show UI") }
            Button(onClick = { host.model.hidePluginUi(inst.instanceId) }) { Text("Hide UI") }
            Button(onClick = { host.removeInstance(inst.instanceId) }) { Text("Delete") }
        }

        Text(
            "${inst.formatName} · instance ${inst.instanceId} · UMP group " +
                "${host.model.getInstanceGroup(inst.instanceId)} · ${parameters.size} parameters",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        // ── Expression ───────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pitchbend", Modifier.width(84.dp), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = pitchBend,
                onValueChange = { pitchBend = it; engine.sendPitchBend(inst.instanceId, it) },
                valueRange = -1f..1f,
                modifier = Modifier.weight(1f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pressure", Modifier.width(84.dp), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = pressure,
                onValueChange = { pressure = it; engine.sendChannelPressure(inst.instanceId, it) },
                modifier = Modifier.weight(1f)
            )
        }

        MidiKeyboard(
            onNoteOn = { engine.sendNoteOn(inst.instanceId, it) },
            onNoteOff = { engine.sendNoteOff(inst.instanceId, it) },
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // ── Presets ──────────────────────────────────────────────────────────
        if (presets.isNotEmpty()) {
            androidx.compose.foundation.layout.Box {
                Button(onClick = { presetsOpen = true }) {
                    Text(selectedPreset?.name ?: "Select preset… (${presets.size})")
                }
                DropdownMenu(expanded = presetsOpen, onDismissRequest = { presetsOpen = false }) {
                    presets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name) },
                            onClick = {
                                selectedPreset = preset
                                instance.loadPreset(preset.index.toInt())
                                revision++          // preset load moves every parameter
                                presetsOpen = false
                            }
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Filter Parameters") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )

        // ── Parameters ───────────────────────────────────────────────────────
        val shown = parameters.filter { filter.isBlank() || it.name.contains(filter, true) }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(shown) { meta -> ParameterRow(host, inst.instanceId, instance, meta) { revision++ } }
        }
    }
}

@Composable
private fun ParameterRow(
    host: UapmdHost,
    instanceId: Int,
    instance: dev.atsushieno.uapmd.PluginInstance,
    meta: ParameterMetadata,
    onReset: () -> Unit
) {
    val index = meta.index.toInt()
    var value by remember(instanceId, index) { mutableStateOf(instance.getParameterValue(index).toFloat()) }
    val range = (meta.maxPlainValue - meta.minPlainValue).toFloat()

    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(meta.name, Modifier.width(130.dp), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value,
            onValueChange = {
                value = it
                // Route through ProjectCommands so the edit is undoable, the way
                // uapmd-app does it, rather than poking the instance directly.
                host.model.sequencer.engine.timeline.commands
                    .setPluginParameterValue(instanceId, index, it.toDouble())
            },
            valueRange = if (range > 0f) meta.minPlainValue.toFloat()..meta.maxPlainValue.toFloat() else 0f..1f,
            modifier = Modifier.weight(1f)
        )
        Text(
            instance.getParameterValueString(index, value.toDouble()).ifEmpty { formatValue(value) },
            Modifier.width(76.dp),
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = {
            value = meta.defaultPlainValue.toFloat()
            host.model.sequencer.engine.timeline.commands
                .setPluginParameterValue(instanceId, index, meta.defaultPlainValue)
            onReset()
        }) { Text("Reset") }
    }
}

private fun formatValue(v: Float): String {
    val scaled = (v * 1000f).toInt() / 1000f
    return scaled.toString()
}
