package dev.atsushieno.uapmd_kmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

@Composable
fun InstanceDetailsPanel(
    info: InstanceInfo,
    initialOffset: Offset = Offset(40f, 40f),
    onClose: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit = {},
    onGroupChanged: (Int) -> Unit = {},
    onPresetSelected: (PresetEntry) -> Unit = {},
    onParameterChanged: (index: Int, value: Float) -> Unit = { _, _ -> },
    onNoteOn: (note: Int) -> Unit = {},
    onNoteOff: (note: Int) -> Unit = {},
    onShowUi: () -> Unit = {},
) {
    var offset by remember(info.instanceId) { mutableStateOf(initialOffset) }
    var filterText by remember(info.instanceId) { mutableStateOf("") }

    Box(
        modifier = Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .width(420.dp)
            .zIndex(10f)
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
        ) {
            Column {
                // ── Title / drag bar ──────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .pointerInput(Unit) {
                            detectDragGestures { _, dragAmount ->
                                offset = Offset(offset.x + dragAmount.x, offset.y + dragAmount.y)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        info.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onClose,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("✕", fontSize = 12.sp)
                    }
                }

                // ── Scrollable controls ───────────────────────────────────
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Enable + Show UI
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = info.isEnabled, onCheckedChange = onEnabledChanged)
                        Text("Enabled", fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = onShowUi,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text("Show UI", fontSize = 11.sp) }
                    }

                    // Group selector
                    if (info.groupCount > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Group:", fontSize = 12.sp)
                            (0 until info.groupCount).forEach { g ->
                                FilterChip(
                                    selected = info.groupIndex == g,
                                    onClick = { onGroupChanged(g) },
                                    label = { Text("$g") }
                                )
                            }
                        }
                    }

                    // Presets
                    if (info.presets.isNotEmpty()) {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth()) {
                                Text("Presets", modifier = Modifier.weight(1f))
                            }
                            DropdownMenu(expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier = Modifier.fillMaxWidth()) {
                                info.presets.forEach { preset ->
                                    DropdownMenuItem(
                                        text = { Text("${preset.bank}:${preset.index}  ${preset.name}") },
                                        onClick = { onPresetSelected(preset); expanded = false }
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider()
                    Text("Parameters", style = MaterialTheme.typography.labelLarge)

                    OutlinedTextField(
                        value = filterText,
                        onValueChange = { filterText = it },
                        label = { Text("Filter") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    val visible = if (filterText.isBlank()) info.parameters
                                  else info.parameters.filter {
                                      it.name.contains(filterText, ignoreCase = true)
                                  }
                    visible.forEach { param ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.width(120.dp)) {
                                Text(param.name, fontSize = 12.sp, maxLines = 1)
                                Text(param.displayValue, fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (param.namedValues.isNotEmpty()) {
                                var exp by remember { mutableStateOf(false) }
                                val cur = param.namedValues
                                    .minByOrNull { kotlin.math.abs(it.first - param.value) }
                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedButton(onClick = { exp = true },
                                        modifier = Modifier.fillMaxWidth()) {
                                        Text(cur?.second ?: param.displayValue, fontSize = 11.sp)
                                    }
                                    DropdownMenu(expanded = exp,
                                        onDismissRequest = { exp = false }) {
                                        param.namedValues.forEach { (v, name) ->
                                            DropdownMenuItem(text = { Text(name) },
                                                onClick = { onParameterChanged(param.index, v); exp = false })
                                        }
                                    }
                                }
                            } else {
                                Slider(
                                    value = param.value,
                                    onValueChange = { onParameterChanged(param.index, it) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }

                // ── MIDI keyboard (always visible, outside scroll) ─────────
                HorizontalDivider()
                MidiKeyboard(
                    onNoteOn  = onNoteOn,
                    onNoteOff = onNoteOff,
                    modifier  = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
        }
    }
}
