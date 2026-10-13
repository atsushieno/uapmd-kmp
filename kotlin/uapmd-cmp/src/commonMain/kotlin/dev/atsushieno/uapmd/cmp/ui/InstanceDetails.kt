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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.ParameterMetadata
import dev.atsushieno.uapmd.ParameterMetadataSnapshot
import dev.atsushieno.uapmd.addParameterMetadataChangedListener
import dev.atsushieno.uapmd.PresetMetadata
import dev.atsushieno.uapmd.cmp.TrackInstance
import androidx.compose.runtime.rememberCoroutineScope
import dev.atsushieno.uapmd.cmp.UapmdHost
import dev.atsushieno.uapmd.cmp.pickProjectFileToOpen
import dev.atsushieno.uapmd.cmp.deliverSavedFile
import dev.atsushieno.uapmd.cmp.pickProjectFileToSave
import dev.atsushieno.uapmd.PluginNode
import dev.atsushieno.uapmd.SequencerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

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
    var revision by remember(inst.instanceId) { mutableStateOf(0) }
    var metadata by remember(inst.instanceId) { mutableStateOf<ParameterMetadataSnapshot?>(null) }
    var groupMenu by remember { mutableStateOf(false) }
    var stateStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val parameters = metadata?.parameters.orEmpty()
    // Values the plug-in (or anything else) changed since the list was last read,
    // keyed by parameter index. Reset together with the list.
    val liveValues = remember(inst.instanceId, metadata?.revision, revision) { mutableStateMapOf<Int, Double>() }
    val mailbox = remember(parameters) {
        val maxIndex = parameters.maxOfOrNull { it.index.toInt() } ?: -1
        ParameterValueMailbox((maxIndex + 1).coerceIn(0, ParameterValueMailbox.MAX_CAPACITY))
    }
    val mailboxRef = remember(inst.instanceId) { MailboxRef(mailbox) }
    SideEffect { mailboxRef.current = mailbox }

    // uapmd-app listens to the node's parameterUpdateEvent and
    // parameterMetadataRefreshEvent (InstanceDetails.cpp): the former moves single
    // values, the latter re-reads the whole list, which is how a plug-in that
    // reshapes its parameters (e.g. on preset/program change) gets reflected.
    DisposableEffect(inst.instanceId) {
        val node = findPluginNode(engine, inst.instanceId)
        // May run on the audio thread: only post to the lock-free mailbox.
        val updateListenerId = node?.addParameterUpdateListener { index, value ->
            mailboxRef.current.post(index, value)
        } ?: 0L
        val metadataListener = engine.addParameterMetadataChangedListener(
            inst.instanceId, dispatch = { task -> scope.launch { task() } }
        ) { snapshot ->
            metadata = snapshot
            revision++
        }
        onDispose {
            metadataListener.close()
            // Look the node up again: if the instance is gone its node (and the
            // listeners with it) is already destroyed.
            findPluginNode(engine, inst.instanceId)?.let {
                if (updateListenerId != 0L) it.removeParameterUpdateListener(updateListenerId)
            }
        }
    }
    LaunchedEffect(mailbox, liveValues) {
        while (true) {
            mailbox.drain { index, value -> liveValues[index] = value }
            delay(VALUE_DRAIN_INTERVAL_MS)
        }
    }
    val presets = remember(inst.instanceId) {
        (0 until instance.presetCount.toInt()).mapNotNull { instance.getPresetMetadata(it.toUInt()) }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // One button, as uapmd-app has it (InstanceDetails.cpp:356): the
            // label follows the UI's visibility and the two actions are the
            // same control, so there is never a "Hide" to press for a UI that
            // was never shown.
            val uiVisible = host.isPluginUiVisible(inst.instanceId)
            Button(
                onClick = {
                    if (uiVisible) host.hidePluginUi(inst.instanceId)
                    else host.showPluginUi(inst.instanceId)
                },
                enabled = instance.hasUiSupport
            ) { Text(if (uiVisible) "Hide UI" else "Show UI") }
            Button(onClick = { host.removeInstance(inst.instanceId) }) { Text("Delete") }
        }

        // uapmd-app keeps plug-in state save/load next to the UI buttons.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                scope.launch {
                    pickProjectFileToSave("plugin-state.bin")?.let { path ->
                        stateStatus = host.savePluginState(inst.instanceId, path)
                        deliverSavedFile(path)
                    }
                }
            }) { Text("Save State") }
            Button(onClick = {
                scope.launch {
                    pickProjectFileToOpen()?.let { path ->
                        stateStatus = host.loadPluginState(inst.instanceId, path)
                        revision++
                    }
                }
            }) { Text("Load State") }

            Text("UMP Group", style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.layout.Box {
                Button(onClick = { groupMenu = true }) {
                    Text("${host.model.getInstanceGroup(inst.instanceId)}")
                }
                DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                    (0..15).forEach { g ->
                        DropdownMenuItem(text = { Text("$g") }, onClick = {
                            host.setInstanceGroup(inst.instanceId, g)
                            groupMenu = false
                        })
                    }
                }
            }
        }
        stateStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Text(
            "${inst.formatName} · instance ${inst.instanceId} · UMP group " +
                "${host.model.getInstanceGroup(inst.instanceId)} · ${parameters.size} parameters",
            style = MaterialTheme.typography.bodySmall
        )

        host.pluginUiStatusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

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
            items(shown, key = { it.index.toInt() }) { meta -> ParameterRow(host, inst.instanceId, instance, meta, revision, liveValues) { revision++ } }
        }
    }
}

@Composable
private fun ParameterRow(
    host: UapmdHost,
    instanceId: Int,
    instance: dev.atsushieno.uapmd.PluginInstance,
    meta: ParameterMetadata,
    revision: Int,
    liveValues: SnapshotStateMap<Int, Double>,
    onReset: () -> Unit
) {
    val index = meta.index.toInt()
    // Keyed on revision so a metadata refresh / preset load re-reads the value;
    // changes in between arrive through liveValues.
    val initialValue = remember(instanceId, index, revision) { instance.getParameterValue(index) }
    val value = (liveValues[index] ?: initialValue).toFloat()
    val range = (meta.maxPlainValue - meta.minPlainValue).toFloat()

    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(meta.name, Modifier.width(130.dp), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value,
            onValueChange = {
                liveValues[index] = it.toDouble()
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
            liveValues[index] = meta.defaultPlainValue
            host.model.sequencer.engine.timeline.commands
                .setPluginParameterValue(instanceId, index, meta.defaultPlainValue)
            onReset()
        }) { Text("Reset") }
    }
}

private const val VALUE_DRAIN_INTERVAL_MS = 33L

private class MailboxRef(initial: ParameterValueMailbox) {
    @Volatile var current: ParameterValueMailbox = initial
}

private fun findPluginNode(engine: SequencerEngine, instanceId: Int): PluginNode? {
    for (i in 0u until engine.trackCount)
        engine.getTrack(i).graph.getPluginNode(instanceId)?.let { return it }
    return engine.masterTrack.graph.getPluginNode(instanceId)
}

private fun formatValue(v: Float): String {
    val scaled = (v * 1000f).toInt() / 1000f
    return scaled.toString()
}
