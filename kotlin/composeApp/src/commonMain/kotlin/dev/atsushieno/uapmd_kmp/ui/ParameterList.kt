package dev.atsushieno.uapmd_kmp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ParameterEntry(
    val index: Int,
    val name: String,
    val value: Float,          // normalized 0..1
    val displayValue: String,
    val isAutomatable: Boolean,
    val isDiscrete: Boolean,
    val namedValues: List<Pair<Float, String>> = emptyList()
)

@Composable
fun ParameterList(
    parameters: List<ParameterEntry>,
    filter: String = "",
    onFilterChanged: (String) -> Unit = {},
    onValueChanged: (index: Int, value: Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = filter,
            onValueChange = onFilterChanged,
            label = { Text("Filter parameters") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            val visible = if (filter.isBlank()) parameters
                          else parameters.filter { it.name.contains(filter, ignoreCase = true) }
            items(visible, key = { it.index }) { param ->
                ParameterRow(param, onValueChanged)
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun ParameterRow(param: ParameterEntry, onValueChanged: (Int, Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.width(140.dp)) {
            Text(param.name, fontSize = 12.sp, maxLines = 1)
            Text(param.displayValue, fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (param.namedValues.isNotEmpty()) {
            // Discrete named-value selector
            var expanded by remember { mutableStateOf(false) }
            val current = param.namedValues.minByOrNull { kotlin.math.abs(it.first - param.value) }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(current?.second ?: param.displayValue, fontSize = 11.sp)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    param.namedValues.forEach { (v, name) ->
                        DropdownMenuItem(text = { Text(name) },
                            onClick = { onValueChanged(param.index, v); expanded = false })
                    }
                }
            }
        } else {
            // Continuous slider
            Slider(
                value = param.value,
                onValueChange = { onValueChanged(param.index, it) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
