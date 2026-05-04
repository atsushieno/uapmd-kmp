package dev.atsushieno.uapmd_kmp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MidiEventEntry(
    val index: Int,
    val tickPosition: Long,
    val deltaTicks: Long,
    val words: List<UInt>   // raw UMP words (1-4)
)

@Composable
fun MidiDumpWindow(
    events: List<MidiEventEntry>,
    selectedIndex: Int = -1,
    onSelect: (Int) -> Unit = {},
    onWordEdited: (eventIndex: Int, wordIndex: Int, value: UInt) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("#", modifier = Modifier.width(40.dp), fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Tick", modifier = Modifier.width(70.dp), fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Delta", modifier = Modifier.width(60.dp), fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("UMP Words", modifier = Modifier.weight(1f), fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(events, key = { _, e -> e.index }) { _, event ->
                val isSelected = event.index == selectedIndex
                Surface(
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(event.index) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(event.index.toString(), modifier = Modifier.width(40.dp),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text(event.tickPosition.toString(), modifier = Modifier.width(70.dp),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text(event.deltaTicks.toString(), modifier = Modifier.width(60.dp),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            event.words.joinToString(" ") { it.toString(16).padStart(8, '0').uppercase() },
                            modifier = Modifier.weight(1f),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}
