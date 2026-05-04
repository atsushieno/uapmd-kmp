package dev.atsushieno.uapmd_kmp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class PresetEntry(val bank: Int, val index: Int, val name: String)

data class InstanceInfo(
    val instanceId: Int,
    val displayName: String,
    val isEnabled: Boolean,
    val groupIndex: Int,
    val groupCount: Int,
    val parameters: List<ParameterEntry>,
    val presets: List<PresetEntry>
)

@Composable
fun InstanceDetails(
    info: InstanceInfo,
    filterText: String = "",
    onFilterChanged: (String) -> Unit = {},
    onEnabledChanged: (Boolean) -> Unit = {},
    onGroupChanged: (Int) -> Unit = {},
    onPresetSelected: (PresetEntry) -> Unit = {},
    onParameterChanged: (index: Int, value: Float) -> Unit = { _, _ -> },
    onShowUi: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(info.displayName, style = MaterialTheme.typography.titleMedium)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = info.isEnabled, onCheckedChange = onEnabledChanged)
                Spacer(Modifier.width(4.dp))
                Text("Enabled", fontSize = 12.sp)
            }
            Button(onClick = onShowUi) { Text("Show UI", fontSize = 12.sp) }
        }

        // Group selector
        if (info.groupCount > 1) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Presets", modifier = Modifier.weight(1f))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
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

        // Inline ParameterList without its own scroll (we're already scrolled)
        OutlinedTextField(
            value = filterText,
            onValueChange = onFilterChanged,
            label = { Text("Filter") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        val visible = if (filterText.isBlank()) info.parameters
                      else info.parameters.filter { it.name.contains(filterText, ignoreCase = true) }
        visible.forEach { param ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.width(130.dp)) {
                    Text(param.name, fontSize = 12.sp, maxLines = 1)
                    Text(param.displayValue, fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (param.namedValues.isNotEmpty()) {
                    var exp by remember { mutableStateOf(false) }
                    val cur = param.namedValues.minByOrNull { kotlin.math.abs(it.first - param.value) }
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(onClick = { exp = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(cur?.second ?: param.displayValue, fontSize = 11.sp)
                        }
                        DropdownMenu(expanded = exp, onDismissRequest = { exp = false }) {
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
}
