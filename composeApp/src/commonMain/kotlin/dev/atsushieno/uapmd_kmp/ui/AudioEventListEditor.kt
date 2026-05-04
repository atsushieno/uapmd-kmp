package dev.atsushieno.uapmd_kmp.ui

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
import kotlin.math.round

import kotlin.math.abs
import kotlin.math.pow

private fun Double.toDecimalString(places: Int = 4): String {
    val factor = 10.0.pow(places).toLong()
    val rounded = round(this * factor).toLong()
    val whole = rounded / factor
    val frac = abs(rounded % factor)
    return "$whole.${frac.toString().padStart(places, '0')}"
}

enum class WarpReferenceType { Manual, ClipStart, ClipEnd, ClipMarker, MasterMarker }

data class MarkerRow(
    val id: String,
    val name: String,
    val clipPositionSeconds: Double
)

data class WarpPointRow(
    val id: String,
    val clipPositionOffset: Double,
    val speedRatio: Double,
    val referenceType: WarpReferenceType,
    val referenceLabel: String = ""
)

@Composable
fun AudioEventListEditor(
    clipName: String,
    markers: List<MarkerRow>,
    warpPoints: List<WarpPointRow>,
    onAddMarker: () -> Unit = {},
    onRemoveMarker: (id: String) -> Unit = {},
    onMarkerChanged: (id: String, name: String, positionSeconds: Double) -> Unit = { _, _, _ -> },
    onAddWarp: () -> Unit = {},
    onRemoveWarp: (id: String) -> Unit = {},
    onWarpChanged: (id: String, offsetSeconds: Double, speedRatio: Double, refType: WarpReferenceType) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Events — $clipName", style = MaterialTheme.typography.titleMedium)

        // ── Markers ──────────────────────────────────────────────────────────
        var markersExpanded by remember { mutableStateOf(true) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { markersExpanded = !markersExpanded }) {
                Text(if (markersExpanded) "▾ Markers (${markers.size})" else "▸ Markers (${markers.size})")
            }
            if (markersExpanded) {
                IconButton(onClick = onAddMarker, modifier = Modifier.size(32.dp)) { Text("+") }
            }
        }
        if (markersExpanded) {
            // header
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Position (s)", modifier = Modifier.width(100.dp), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Name", modifier = Modifier.weight(1f), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(36.dp))
            }
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                itemsIndexed(markers, key = { _, m -> m.id }) { _, marker ->
                    MarkerEditorRow(
                        marker = marker,
                        onChanged = { name, pos -> onMarkerChanged(marker.id, name, pos) },
                        onRemove = { onRemoveMarker(marker.id) }
                    )
                }
            }
        }

        // ── Warp Points ───────────────────────────────────────────────────────
        var warpsExpanded by remember { mutableStateOf(true) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { warpsExpanded = !warpsExpanded }) {
                Text(if (warpsExpanded) "▾ Warp Points (${warpPoints.size})" else "▸ Warp Points (${warpPoints.size})")
            }
            if (warpsExpanded) {
                IconButton(onClick = onAddWarp, modifier = Modifier.size(32.dp)) { Text("+") }
            }
        }
        if (warpsExpanded) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Offset (s)", modifier = Modifier.width(90.dp), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Speed", modifier = Modifier.width(70.dp), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Reference", modifier = Modifier.weight(1f), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(36.dp))
            }
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                itemsIndexed(warpPoints, key = { _, w -> w.id }) { _, warp ->
                    WarpEditorRow(
                        warp = warp,
                        onChanged = { offset, speed, ref -> onWarpChanged(warp.id, offset, speed, ref) },
                        onRemove = { onRemoveWarp(warp.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkerEditorRow(
    marker: MarkerRow,
    onChanged: (name: String, positionSeconds: Double) -> Unit,
    onRemove: () -> Unit
) {
    var posText by remember(marker.id) { mutableStateOf(marker.clipPositionSeconds.toDecimalString(4)) }
    var nameText by remember(marker.id) { mutableStateOf(marker.name) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = posText,
            onValueChange = { v -> posText = v; v.toDoubleOrNull()?.let { pos -> onChanged(nameText, pos) } },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            modifier = Modifier.width(100.dp)
        )
        OutlinedTextField(
            value = nameText,
            onValueChange = { v -> nameText = v; posText.toDoubleOrNull()?.let { pos -> onChanged(v, pos) } },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Text("✕", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun WarpEditorRow(
    warp: WarpPointRow,
    onChanged: (offsetSeconds: Double, speedRatio: Double, refType: WarpReferenceType) -> Unit,
    onRemove: () -> Unit
) {
    var offsetText by remember(warp.id) { mutableStateOf(warp.clipPositionOffset.toDecimalString(4)) }
    var speedText by remember(warp.id) { mutableStateOf(warp.speedRatio.toDecimalString(3)) }
    var refType by remember(warp.id) { mutableStateOf(warp.referenceType) }
    var refExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = offsetText,
            onValueChange = { v -> offsetText = v; v.toDoubleOrNull()?.let { off -> onChanged(off, speedText.toDoubleOrNull() ?: warp.speedRatio, refType) } },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            modifier = Modifier.width(90.dp)
        )
        OutlinedTextField(
            value = speedText,
            onValueChange = { v -> speedText = v; v.toDoubleOrNull()?.let { spd -> onChanged(offsetText.toDoubleOrNull() ?: warp.clipPositionOffset, spd, refType) } },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            modifier = Modifier.width(70.dp)
        )
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(onClick = { refExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(refType.name, fontSize = 10.sp)
            }
            DropdownMenu(expanded = refExpanded, onDismissRequest = { refExpanded = false }) {
                WarpReferenceType.entries.forEach { t ->
                    DropdownMenuItem(text = { Text(t.name) }, onClick = {
                        refType = t
                        refExpanded = false
                        onChanged(offsetText.toDoubleOrNull() ?: warp.clipPositionOffset,
                            speedText.toDoubleOrNull() ?: warp.speedRatio, t)
                    })
                }
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Text("✕", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}
