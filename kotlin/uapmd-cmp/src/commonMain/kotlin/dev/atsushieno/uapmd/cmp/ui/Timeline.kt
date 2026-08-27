package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.ClipData
import dev.atsushieno.uapmd.ClipType
import dev.atsushieno.uapmd.cmp.UapmdHost

/** kotlin.text has no common String.format, so round and splice manually. */
private fun fixed(value: Double, decimals: Int): String {
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    val scaled = kotlin.math.round(value * factor).toLong()
    val whole = scaled / factor.toLong()
    val frac = (scaled % factor.toLong()).let { if (it < 0) -it else it }
    return if (decimals == 0) "$whole" else "$whole.${frac.toString().padStart(decimals, '0')}"
}

private val TightPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)

private val LegendWidth = 260.dp
private val TrackHeight = 84.dp
private val RulerHeight = 22.dp

private val AudioClip = Color(0xFF4A3D75)
private val MidiClip = Color(0xFF3D5A75)
private val ClipBorder = Color(0xFF9A8FC7)
private val NoteColor = Color(0xFFBFD8F0)
private val Playhead = Color(0xFFE8C547)

/**
 * The main content: a track legend on the left and a time-ruled lane per track
 * on the right, as in uapmd-app.
 *
 * Absolute-time (seconds) view only for now. The beats/ticks view uapmd-app
 * toggles to needs `TempoMap`, which the C API does not expose yet
 * (docs/uapmd-binding-missing-api.md §3).
 */
@Composable
fun Timeline(
    host: UapmdHost,
    windows: FloatingWindowManager,
    modifier: Modifier = Modifier
) {
    var pixelsPerSecond by remember { mutableStateOf(40f) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    // Show at least a minute, or the content plus a margin.
    val contentSeconds = remember(host.trackClips, host.model.sampleRate) {
        val sr = host.model.sampleRate.takeIf { it > 0 } ?: 48000
        val last = host.trackClips.flatten().maxOfOrNull {
            (it.positionSamples + it.durationSamples).toDouble() / sr
        } ?: 0.0
        maxOf(60.0, last + 10.0)
    }

    Column(modifier.fillMaxSize()) {
        // ── Navigator row ────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // uapmd-app toggles Seconds/Beats here; Beats needs TempoMap.
            Button(onClick = {}, enabled = false) { Text("View: Seconds") }
            Text("Zoom", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = pixelsPerSecond,
                onValueChange = { pixelsPerSecond = it },
                valueRange = 8f..240f,
                modifier = Modifier.width(220.dp)
            )
            Text(
                "${fixed(contentSeconds, 1)}s · playhead ${fixed(host.playheadSeconds, 2)}s",
                style = MaterialTheme.typography.bodySmall
            )
        }
        HorizontalDivider()

        Row(Modifier.fillMaxSize()) {
            // ── Legend column ────────────────────────────────────────────────
            Column(Modifier.width(LegendWidth).verticalScroll(vScroll)) {
                Box(Modifier.height(RulerHeight).fillMaxWidth()) {
                    Text("Header", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall)
                }
                host.trackInstances.indices.forEach { trackIndex ->
                    TrackLegend(host, windows, trackIndex)
                    HorizontalDivider()
                }
            }
            HorizontalDivider(Modifier.width(1.dp).fillMaxSize())

            // ── Lanes ────────────────────────────────────────────────────────
            Column(Modifier.fillMaxSize().horizontalScroll(hScroll).verticalScroll(vScroll)) {
                val laneWidth = (contentSeconds * pixelsPerSecond).dp
                Ruler(contentSeconds, pixelsPerSecond, laneWidth)
                host.trackClips.indices.forEach { trackIndex ->
                    TrackLane(host, windows, trackIndex, pixelsPerSecond, laneWidth)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Ruler(contentSeconds: Double, pixelsPerSecond: Float, laneWidth: Dp) {
    Canvas(Modifier.height(RulerHeight).width(laneWidth)) {
        val step = when {
            pixelsPerSecond >= 120f -> 1
            pixelsPerSecond >= 40f -> 5
            pixelsPerSecond >= 16f -> 10
            else -> 30
        }
        var t = 0
        while (t <= contentSeconds) {
            val x = t * pixelsPerSecond
            drawLine(Color.Gray, Offset(x, 0f), Offset(x, size.height), 1f)
            t += step
        }
    }
}

@Composable
private fun TrackLane(
    host: UapmdHost,
    windows: FloatingWindowManager,
    trackIndex: Int,
    pixelsPerSecond: Float,
    laneWidth: Dp
) {
    val clips = host.trackClips.getOrNull(trackIndex).orEmpty()
    val sampleRate = (host.model.sampleRate.takeIf { it > 0 } ?: 48000).toDouble()

    Box(Modifier.height(TrackHeight).width(laneWidth)) {
        Canvas(Modifier.fillMaxSize()) {
            clips.forEach { clip ->
                val x = (clip.positionSamples / sampleRate * pixelsPerSecond).toFloat()
                val w = (clip.durationSamples / sampleRate * pixelsPerSecond).toFloat().coerceAtLeast(2f)
                val isMidi = clip.clipType == ClipType.Midi
                drawRect(
                    color = if (isMidi) MidiClip else AudioClip,
                    topLeft = Offset(x, 4f),
                    size = Size(w, size.height - 8f)
                )
                drawRect(
                    color = ClipBorder,
                    topLeft = Offset(x, 4f),
                    size = Size(w, size.height - 8f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1f)
                )
                if (isMidi) drawMidiNotes(host, trackIndex, clip, x, w, pixelsPerSecond)
            }
            // Playhead over the lane content.
            val px = (host.playheadSeconds * pixelsPerSecond).toFloat()
            drawLine(Playhead, Offset(px, 0f), Offset(px, size.height), 2f)
        }
        // Clip labels double as the handle for opening a clip editor, the way
        // uapmd-app reaches its dump / event-list windows.
        clips.forEach { clip ->
            val x = (clip.positionSamples / sampleRate * pixelsPerSecond).dp
            val isMidi = clip.clipType == ClipType.Midi
            Text(
                clip.name.ifEmpty { if (isMidi) "MIDI clip" else "audio clip" },
                Modifier
                    .padding(start = x + 3.dp, top = 5.dp)
                    .clickable(enabled = isMidi) {
                        windows.open(
                            "dump:$trackIndex:${clip.clipId}",
                            "${clip.name.ifEmpty { "MIDI clip" }} - Events",
                            DpSize(520.dp, 400.dp)
                        ) { MidiDumpWindow(host, trackIndex, clip.clipId) }
                    },
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMidiNotes(
    host: UapmdHost,
    trackIndex: Int,
    clip: ClipData,
    clipX: Float,
    clipW: Float,
    pixelsPerSecond: Float
) {
    val notes = host.midiNotes(trackIndex, clip.clipId)
    if (notes.isEmpty()) return
    val lo = notes.minOf { it.note }
    val hi = notes.maxOf { it.note }
    val span = (hi - lo).coerceAtLeast(1)
    val top = 6f
    val usable = size.height - 12f
    notes.forEach { n ->
        val nx = clipX + (n.startSeconds * pixelsPerSecond).toFloat()
        val nw = (n.durationSeconds * pixelsPerSecond).toFloat().coerceAtLeast(1.5f)
        if (nx > clipX + clipW) return@forEach
        val ny = top + usable * (1f - (n.note - lo).toFloat() / span) * 0.85f
        drawRect(NoteColor, Offset(nx, ny), Size(nw, 2.5f))
    }
}

@Composable
private fun TrackLegend(host: UapmdHost, windows: FloatingWindowManager, trackIndex: Int) {
    val instances = host.trackInstances.getOrNull(trackIndex).orEmpty()
    val engineTrack = remember(trackIndex, host.trackCount) {
        runCatching { host.model.sequencer.engine.getTrack(trackIndex.toUInt()) }.getOrNull()
    }
    var pluginMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }

    fun openSelectorForTrack() {
        windows.open("plugins", "Plugin Selector", DpSize(560.dp, 430.dp)) { PluginSelector(host) }
    }

    Column(Modifier.height(TrackHeight).fillMaxWidth().padding(4.dp)) {
        // Row 1: track identity and mixer controls.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Track $trackIndex", style = MaterialTheme.typography.labelMedium)
            // Gain / M / S need trackGain()/muted()/solo() getters the C API lacks;
            // setters alone cannot drive a correct control.
            Button(onClick = {}, enabled = false, contentPadding = TightPadding) { Text("M") }
            Button(onClick = {}, enabled = false, contentPadding = TightPadding) { Text("S") }
            engineTrack?.let { t ->
                Button(
                    onClick = { host.setTrackBypassed(trackIndex, !t.bypassed) },
                    contentPadding = TightPadding
                ) { Text(if (t.bypassed) "Byp" else "On") }
            }
        }

        // Row 2: the plugin context button, exactly as uapmd-app labels it -
        // the first instance's name, or "Add Plugin" when the track is empty.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box {
                Button(
                    onClick = { if (instances.isEmpty()) openSelectorForTrack() else pluginMenu = true },
                    contentPadding = TightPadding
                ) {
                    Text(
                        instances.firstOrNull()?.let { "⋮ ${it.displayName}" } ?: "Add Plugin",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                DropdownMenu(expanded = pluginMenu, onDismissRequest = { pluginMenu = false }) {
                    instances.forEachIndexed { i, instance ->
                        val detailsKey = "details:${instance.instanceId}"
                        val detailsOpen = windows.isOpen(detailsKey)
                        DropdownMenuItem(
                            text = { Text("${if (detailsOpen) "Hide" else "Show"} ${instance.displayName} Details") },
                            onClick = {
                                pluginMenu = false
                                if (detailsOpen) windows.close(detailsKey)
                                else windows.open(
                                    detailsKey,
                                    "${instance.displayName} (${instance.formatName}) - Details",
                                    DpSize(460.dp, 420.dp)
                                ) { InstanceDetails(host, instance) }
                            }
                        )
                        val uiVisible = host.isPluginUiVisible(instance.instanceId)
                        DropdownMenuItem(
                            text = { Text("${if (uiVisible) "Hide" else "Show"} ${instance.displayName} GUI") },
                            onClick = {
                                pluginMenu = false
                                if (uiVisible) host.closePluginUi(instance.instanceId)
                                else host.showPluginUi(instance.instanceId)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete ${instance.displayName} (at [${i + 1}])") },
                            onClick = {
                                pluginMenu = false
                                windows.close("details:${instance.instanceId}")
                                host.removeInstance(instance.instanceId)
                            }
                        )
                        HorizontalDivider()
                    }
                    DropdownMenuItem(
                        text = { Text("Add Plugin") },
                        onClick = { pluginMenu = false; openSelectorForTrack() }
                    )
                }
            }

            Box {
                Button(onClick = { moreMenu = true }, contentPadding = TightPadding) { Text("⋮") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    engineTrack?.let { t ->
                        DropdownMenuItem(
                            text = { Text(if (t.bypassed) "Enable Track Processing" else "Bypass Track Processing") },
                            onClick = { moreMenu = false; host.setTrackBypassed(trackIndex, !t.bypassed) }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Delete Track") },
                        onClick = { moreMenu = false; host.removeTrack(trackIndex) }
                    )
                }
            }
        }
    }
}
