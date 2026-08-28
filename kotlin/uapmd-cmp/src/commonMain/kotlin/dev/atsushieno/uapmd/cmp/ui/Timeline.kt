package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.ClipData
import dev.atsushieno.uapmd.ClipType
import dev.atsushieno.uapmd.cmp.UapmdHost
import dev.atsushieno.uapmd.cmp.pickMediaFileToOpen
import kotlinx.coroutines.launch

/** kotlin.text has no common String.format, so round and splice manually. */
private fun fixed(value: Double, decimals: Int): String {
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    val scaled = kotlin.math.round(value * factor).toLong()
    val whole = scaled / factor.toLong()
    val frac = (scaled % factor.toLong()).let { if (it < 0) -it else it }
    return if (decimals == 0) "$whole" else "$whole.${frac.toString().padStart(decimals, '0')}"
}

/**
 * Seconds ⇄ quarter-note beats, as uapmd-app's View toggle offers.
 *
 * uapmd-app uses `uapmd::TempoMap`, a piecewise-constant map built from the
 * master track's tempo points. Those points come from
 * `AppModel::buildMasterTrackSnapshot()`, which the C API does not expose, so
 * this converts at the project tempo — exact for projects without tempo
 * changes, and the axis is labelled so the assumption is visible.
 */
private enum class TimeUnit { Seconds, Beats }

/**
 * uapmd-app's legend uses icon buttons sized to one glyph plus frame padding.
 * Text-sized buttons overflow the legend and push Solo off the edge.
 */
private val TightPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)
private val IconButtonSize = 30.dp

/**
 * Legend width adapts to the window: a fixed 260dp eats two thirds of a phone
 * screen, leaving no room for the lanes it is supposed to label.
 */
private val LegendWidthWide = 260.dp
private val LegendWidthNarrow = 150.dp
private const val NarrowWidthThreshold = 620
/**
 * Row height. Narrow screens wrap the legend controls onto a third line, so the
 * row has to grow — and the lanes must use the *same* height or the legend and
 * lane columns drift apart as you scroll.
 */
private val TrackHeightWide = 84.dp
private val TrackHeightNarrow = 92.dp
private val RulerHeight = 22.dp

private val AudioClip = Color(0xFF4A3D75)
private val MidiClip = Color(0xFF3D5A75)
private val ClipBorder = Color(0xFF9A8FC7)
private val NoteColor = Color(0xFFBFD8F0)
private val Playhead = Color(0xFFE8C547)
private val LaneBackground = Color(0xFF1E1E24)
private val MasterLaneBackground = Color(0xFF26262F)

/** Mirrors UAPMD_MASTER_TRACK_INDEX / ProjectAddressBook.MASTER_TRACK_INDEX. */
private const val MasterTrackIndex = Int.MIN_VALUE

/** Gain slider range in dB; the bottom of the range is treated as silence. */
private const val MinGainDb = -60f
private const val MaxGainDb = 6f

private fun linearToDb(linear: Double): Double =
    if (linear <= 0.0) MinGainDb.toDouble()
    else (20.0 * kotlin.math.log10(linear)).coerceIn(MinGainDb.toDouble(), MaxGainDb.toDouble())

private fun dbToLinear(db: Double): Double =
    if (db <= MinGainDb) 0.0 else kotlin.math.exp(db / 20.0 * kotlin.math.ln(10.0))
private val MutedColor = Color(0xFFB32828)
private val SoloColor = Color(0xFFD1850F)

/**
 * The main content: a track legend on the left and a time-ruled lane per track
 * on the right, as in uapmd-app.
 *
 * Absolute-time (seconds) view only for now. The beats/ticks view uapmd-app
 * toggles to needs `TempoMap`, which the C API does not expose yet
 * (docs/uapmd-binding-missing-api.md §3).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Timeline(
    host: UapmdHost,
    windows: FloatingWindowManager,
    modifier: Modifier = Modifier
) {
    var pixelsPerSecond by remember { mutableStateOf(40f) }
    var timeUnit by remember { mutableStateOf(TimeUnit.Seconds) }
    val tempo = host.timeline?.tempo ?: 120.0
    val beatsPerSecond = tempo / 60.0
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

    BoxWithConstraints(modifier.fillMaxSize()) {
    val isNarrow = maxWidth.value < NarrowWidthThreshold
    val legendWidth = if (isNarrow) LegendWidthNarrow else LegendWidthWide
    val trackHeight = if (isNarrow) TrackHeightNarrow else TrackHeightWide

    Column(Modifier.fillMaxSize()) {
        // ── Navigator row ────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = {
                timeUnit = if (timeUnit == TimeUnit.Seconds) TimeUnit.Beats else TimeUnit.Seconds
            }) { Text(if (timeUnit == TimeUnit.Seconds) "View: Seconds" else "View: Beats") }
            Text("Zoom", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = pixelsPerSecond,
                onValueChange = { pixelsPerSecond = it },
                valueRange = 8f..240f,
                modifier = Modifier.width(220.dp)
            )
            Text(
                if (timeUnit == TimeUnit.Seconds)
                    "${fixed(contentSeconds, 1)}s · playhead ${fixed(host.playheadSeconds, 2)}s"
                else
                    "${fixed(contentSeconds * beatsPerSecond, 1)} beats · playhead " +
                        "${fixed(host.playheadSeconds * beatsPerSecond, 2)} · ${fixed(tempo, 1)} BPM " +
                        "${host.timeline?.timeSignatureNumerator ?: 4}/${host.timeline?.timeSignatureDenominator ?: 4}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        HorizontalDivider()

        Row(Modifier.fillMaxSize()) {
            // ── Legend column ────────────────────────────────────────────────
            Column(Modifier.width(legendWidth).verticalScroll(vScroll)) {
                Box(Modifier.height(RulerHeight).fillMaxWidth()) {
                    Text("Header", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall)
                }
                MasterTrackLegend(host, windows, trackHeight)
                HorizontalDivider()
                host.trackInstances.indices.forEach { trackIndex ->
                    TrackLegend(host, windows, trackIndex, isNarrow, trackHeight)
                    HorizontalDivider()
                }
            }
            HorizontalDivider(Modifier.width(1.dp).fillMaxSize())

            // ── Lanes ────────────────────────────────────────────────────────
            Column(Modifier.fillMaxSize().horizontalScroll(hScroll).verticalScroll(vScroll)) {
                val laneWidth = (contentSeconds * pixelsPerSecond).dp
                Ruler(contentSeconds, pixelsPerSecond, laneWidth, timeUnit, beatsPerSecond,
                    host.timeline?.timeSignatureNumerator ?: 4)
                MasterTrackLane(host, pixelsPerSecond, laneWidth, trackHeight)
                HorizontalDivider()
                host.trackClips.indices.forEach { trackIndex ->
                    TrackLane(host, windows, trackIndex, pixelsPerSecond, laneWidth, trackHeight)
                    HorizontalDivider()
                }
            }
        }
    }
}
}

@Composable
private fun Ruler(
    contentSeconds: Double,
    pixelsPerSecond: Float,
    laneWidth: Dp,
    timeUnit: TimeUnit,
    beatsPerSecond: Double,
    beatsPerBar: Int
) {
    Canvas(Modifier.height(RulerHeight).width(laneWidth)) {
        if (timeUnit == TimeUnit.Seconds) {
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
        } else {
            // Bar lines are brighter than beat lines, as in a DAW ruler.
            val pixelsPerBeat = (pixelsPerSecond / beatsPerSecond).toFloat()
            val bars = beatsPerBar.coerceAtLeast(1)
            val totalBeats = (contentSeconds * beatsPerSecond).toInt()
            for (beat in 0..totalBeats) {
                val x = beat * pixelsPerBeat
                val isBar = beat % bars == 0
                drawLine(
                    if (isBar) Color.LightGray else Color.Gray,
                    Offset(x, if (isBar) 0f else size.height * 0.4f),
                    Offset(x, size.height),
                    if (isBar) 1.5f else 1f
                )
            }
        }
    }
}

@Composable
private fun TrackLane(
    host: UapmdHost,
    windows: FloatingWindowManager,
    trackIndex: Int,
    pixelsPerSecond: Float,
    laneWidth: Dp,
    trackHeight: Dp
) {
    val clips = host.trackClips.getOrNull(trackIndex).orEmpty()
    val sampleRate = (host.model.sampleRate.takeIf { it > 0 } ?: 48000).toDouble()
    var draggingClipId by remember { mutableStateOf<Int?>(null) }
    var dragSeconds by remember { mutableStateOf(0.0) }
    val density = LocalDensity.current

    Box(
        Modifier.height(trackHeight).width(laneWidth)
            .background(LaneBackground)
            // Direct manipulation: drag a clip along the lane to move it. The
            // commit goes through setClipAnchor, so it lands in history as one step.
            .pointerInput(clips, pixelsPerSecond) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val seconds = offset.x / pixelsPerSecond
                        draggingClipId = clips.firstOrNull { c ->
                            val start = c.positionSamples / sampleRate
                            val end = start + c.durationSamples / sampleRate
                            seconds >= start && seconds <= end
                        }?.clipId
                        dragSeconds = 0.0
                    },
                    onDragEnd = {
                        val id = draggingClipId
                        if (id != null && dragSeconds != 0.0) {
                            val clip = clips.firstOrNull { it.clipId == id }
                            if (clip != null) {
                                val target = (clip.positionSamples / sampleRate + dragSeconds).coerceAtLeast(0.0)
                                host.moveClip(trackIndex, id, target)
                            }
                        }
                        draggingClipId = null
                        dragSeconds = 0.0
                    },
                    onDragCancel = { draggingClipId = null; dragSeconds = 0.0 }
                ) { change, delta ->
                    if (draggingClipId != null) {
                        change.consume()
                        dragSeconds += delta.x / pixelsPerSecond
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            clips.forEach { clip ->
                val shift = if (clip.clipId == draggingClipId) dragSeconds else 0.0
                val x = ((clip.positionSamples / sampleRate + shift) * pixelsPerSecond).toFloat()
                val w = (clip.durationSamples / sampleRate * pixelsPerSecond).toFloat().coerceAtLeast(2f)
                val isMidi = clip.clipType == ClipType.Midi
                val base = if (isMidi) MidiClip else AudioClip
                drawRect(
                    color = if (clip.muted) base.copy(alpha = 0.35f) else base,
                    topLeft = Offset(x, 4f),
                    size = Size(w, size.height - 8f)
                )
                drawRect(
                    color = if (clip.clipId == draggingClipId) Playhead else ClipBorder,
                    topLeft = Offset(x, 4f),
                    size = Size(w, size.height - 8f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(if (clip.clipId == draggingClipId) 2f else 1f)
                )
                if (isMidi) drawMidiNotes(host, trackIndex, clip, x, w, pixelsPerSecond)
            }
            val px = (host.playheadSeconds * pixelsPerSecond).toFloat()
            drawLine(Playhead, Offset(px, 0f), Offset(px, size.height), 2f)
        }

        // A clip's label opens its context menu, so every editor stays reachable
        // from one place - piano roll, raw events, properties, removal.
        clips.forEach { clip ->
            val shift = if (clip.clipId == draggingClipId) dragSeconds else 0.0
            val x = with(density) {
                ((clip.positionSamples / sampleRate + shift) * pixelsPerSecond).toFloat().toDp()
            }
            var menuOpen by remember(clip.clipId) { mutableStateOf(false) }
            val isMidi = clip.clipType == ClipType.Midi
            Box(Modifier.padding(start = x + 3.dp, top = 5.dp)) {
                Text(
                    clip.name.ifEmpty { if (isMidi) "MIDI clip" else "audio clip" },
                    Modifier.clickable {
                        if (isMidi) host.selectedMidiClip = trackIndex to clip.clipId
                        menuOpen = true
                    },
                    style = MaterialTheme.typography.labelSmall
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (isMidi) {
                        DropdownMenuItem(text = { Text("Piano Roll") }, onClick = {
                            menuOpen = false
                            windows.open(
                                "pianoroll:$trackIndex:${clip.clipId}",
                                "${clip.name.ifEmpty { "MIDI clip" }} - Piano Roll",
                                DpSize(640.dp, 420.dp)
                            ) { PianoRollEditor(host, trackIndex, clip.clipId) }
                        })
                        DropdownMenuItem(text = { Text("Edit Events (UMP)") }, onClick = {
                            menuOpen = false
                            windows.open(
                                "dump:$trackIndex:${clip.clipId}",
                                "${clip.name.ifEmpty { "MIDI clip" }} - Events",
                                DpSize(520.dp, 400.dp)
                            ) { MidiDumpWindow(host, trackIndex, clip.clipId) }
                        })
                        HorizontalDivider()
                    } else {
                        DropdownMenuItem(text = { Text("Markers & Warps") }, onClick = {
                            menuOpen = false
                            windows.open(
                                "events:$trackIndex:${clip.clipId}",
                                "${clip.name.ifEmpty { "audio clip" }} - Markers & Warps",
                                DpSize(560.dp, 420.dp)
                            ) { AudioEventListEditor(host, trackIndex, clip.clipId) }
                        })
                        HorizontalDivider()
                    }
                    DropdownMenuItem(text = { Text("Clip Properties…") }, onClick = {
                        menuOpen = false
                        windows.open(
                            "clipprops:$trackIndex:${clip.clipId}",
                            "${clip.name.ifEmpty { "clip" }} - Properties",
                            DpSize(520.dp, 320.dp)
                        ) { ClipProperties(host, trackIndex, clip.clipId) }
                    })
                    DropdownMenuItem(text = { Text(if (clip.muted) "Unmute Clip" else "Mute Clip") }, onClick = {
                        menuOpen = false
                        host.setClipMuted(trackIndex, clip.clipId, !clip.muted)
                    })
                    DropdownMenuItem(text = { Text("Remove Clip") }, onClick = {
                        menuOpen = false
                        listOf("pianoroll", "dump", "events", "clipprops").forEach {
                            windows.close("$it:$trackIndex:${clip.clipId}")
                        }
                        host.removeClip(trackIndex, clip.clipId)
                    })
                }
            }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackLegend(
    host: UapmdHost,
    windows: FloatingWindowManager,
    trackIndex: Int,
    isNarrow: Boolean = false,
    trackHeight: Dp = TrackHeightWide
) {
    val instances = host.trackInstances.getOrNull(trackIndex).orEmpty()
    val engineTrack = remember(trackIndex, host.trackCount) {
        runCatching { host.model.sequencer.engine.getTrack(trackIndex.toUInt()) }.getOrNull()
    }
    var pluginMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var clipsMenu by remember { mutableStateOf(false) }
    // uapmd-app treats Ctrl/Cmd-click on Solo as additive.
    var additiveSolo by remember { mutableStateOf(false) }
    var emptyAudioNotice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun openSelectorForTrack() {
        host.targetPluginDestination(trackIndex)
        windows.open("plugins", "Plugin Selector", DpSize(560.dp, 430.dp)) { PluginSelector(host) }
    }

    Column(Modifier.height(trackHeight).fillMaxWidth().padding(4.dp)) {
        // Rows wrap so a phone-width legend keeps every control reachable
        // instead of pushing Solo off the edge.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("T$trackIndex", style = MaterialTheme.typography.labelMedium)

            // Clips popup, as uapmd-app's first legend button.
            Box {
                Button(
                    onClick = { clipsMenu = true },
                    contentPadding = TightPadding,
                    modifier = Modifier.size(IconButtonSize)
                ) { Text("▤", style = MaterialTheme.typography.labelSmall) }
                DropdownMenu(expanded = clipsMenu, onDismissRequest = { clipsMenu = false }) {
                    DropdownMenuItem(text = { Text("Add an Empty MIDI2 Clip") }, onClick = {
                        clipsMenu = false
                        host.addEmptyMidiClip(trackIndex)
                    })
                    DropdownMenuItem(text = { Text("Add a MIDI Clip from File…") }, onClick = {
                        clipsMenu = false
                        scope.launch { pickMediaFileToOpen()?.let { host.importMidiClip(trackIndex, it) } }
                    })
                    DropdownMenuItem(text = { Text("Add MIDI2 Clip from File…") }, onClick = {
                        clipsMenu = false
                        // .midi2 goes through the same importer; the engine
                        // picks the reader from the file itself.
                        scope.launch { pickMediaFileToOpen()?.let { host.importMidiClip(trackIndex, it) } }
                    })
                    DropdownMenuItem(text = { Text("Add Empty Audio Clip") }, onClick = {
                        clipsMenu = false
                        // uapmd-app creates an empty audio clip the user then
                        // points at a file; the same two steps, explicitly.
                        emptyAudioNotice = "Create the clip from a file, then use Clip Properties ▸ Change file."
                    })
                    DropdownMenuItem(text = { Text("Create Audio Clip From File…") }, onClick = {
                        clipsMenu = false
                        scope.launch { pickMediaFileToOpen()?.let { host.importAudioClip(trackIndex, it) } }
                    })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Clear All") }, onClick = {
                        clipsMenu = false
                        host.clearClipsFromTrack(trackIndex)
                    })
                }
            }

            engineTrack?.takeIf { !isNarrow }?.let { t ->
                // Read the value from the track, write it through ProjectCommands.
                // uapmd-app's slider is in dB and wraps the drag in an undo
                // gesture, so a drag is one history entry rather than dozens.
                var gainDb by remember(trackIndex) { mutableStateOf(linearToDb(t.gain).toFloat()) }
                Slider(
                    value = gainDb,
                    onValueChange = { gainDb = it; host.setTrackGain(trackIndex, dbToLinear(gainDb.toDouble())) },
                    onValueChangeFinished = { host.endTrackGainGesture() },
                    valueRange = MinGainDb..MaxGainDb,
                    modifier = Modifier.width(if (isNarrow) 44.dp else 60.dp).onFocusChanged { }
                )
                // uapmd-app shows no inline value: the slider label is empty,
                // or "Mute" at the bottom of the range.
                if (gainDb <= MinGainDb) {
                    Text("Mute", style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = { host.setTrackMuted(trackIndex, !t.muted) },
                    contentPadding = TightPadding,
                    modifier = Modifier.size(IconButtonSize),
                    colors = if (t.muted) ButtonDefaults.buttonColors(containerColor = MutedColor)
                    else ButtonDefaults.buttonColors()
                ) { Text("M", style = MaterialTheme.typography.labelSmall) }
                Button(
                    onClick = { host.setTrackSolo(trackIndex, !t.solo, additive = additiveSolo) },
                    contentPadding = TightPadding,
                    modifier = Modifier.size(IconButtonSize),
                    colors = if (t.solo) ButtonDefaults.buttonColors(containerColor = SoloColor)
                    else ButtonDefaults.buttonColors()
                ) { Text("S", style = MaterialTheme.typography.labelSmall) }
            }
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

            Button(
                onClick = {
                    windows.open("graph:$trackIndex", "Track $trackIndex Graph", DpSize(620.dp, 440.dp)) {
                        TrackGraphEditor(host, trackIndex)
                    }
                },
                contentPadding = TightPadding,
                modifier = Modifier.size(IconButtonSize)
            ) { Text("⛓", style = MaterialTheme.typography.labelSmall) }

            Box {
                Button(
                    onClick = { moreMenu = true },
                    contentPadding = TightPadding,
                    modifier = Modifier.size(IconButtonSize)
                ) { Text("⋮", style = MaterialTheme.typography.labelSmall) }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    engineTrack?.let { t ->
                        DropdownMenuItem(
                            text = { Text(if (t.bypassed) "Enable Track Processing" else "Bypass Track Processing") },
                            onClick = { moreMenu = false; host.setTrackBypassed(trackIndex, !t.bypassed) }
                        )
                    }
                    engineTrack?.takeIf { isNarrow }?.let { t ->
                        // On a phone the mixer controls live here rather than as
                        // top-level buttons, which do not fit a 412dp legend.
                        DropdownMenuItem(
                            text = { Text(if (t.muted) "Unmute Track" else "Mute Track") },
                            onClick = { moreMenu = false; host.setTrackMuted(trackIndex, !t.muted) }
                        )
                        DropdownMenuItem(
                            text = { Text(if (t.solo) "Clear Solo" else "Solo Track") },
                            onClick = { moreMenu = false; host.setTrackSolo(trackIndex, !t.solo) }
                        )
                        DropdownMenuItem(
                            text = { Text("Gain: ${if (t.gain <= 0.0) "-∞" else fixed(linearToDb(t.gain), 1)}dB") },
                            enabled = false, onClick = {}
                        )
                        HorizontalDivider()
                    }
                    engineTrack?.let { t ->
                        DropdownMenuItem(
                            text = { Text(if (t.frozen) "Unfreeze Track" else "Freeze Track") },
                            onClick = { moreMenu = false; host.setTrackFreezePolicyEnabled(trackIndex, !t.frozen) }
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

/**
 * The master track. uapmd-app renders it above the regular tracks with a
 * reduced set of actions: no delete, no mute/solo, and "Add Master Plugin"
 * instead of "Add Plugin".
 */
@Composable
private fun MasterTrackLegend(host: UapmdHost, windows: FloatingWindowManager, trackHeight: Dp) {
    var pluginMenu by remember { mutableStateOf(false) }
    val instances = host.masterInstances

    Column(Modifier.height(trackHeight).fillMaxWidth().padding(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Master", style = MaterialTheme.typography.labelMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box {
                Button(
                    onClick = {
                        if (instances.isEmpty()) {
                            host.targetPluginDestination(MasterTrackIndex)
                            windows.open("plugins", "Plugin Selector", DpSize(560.dp, 430.dp)) { PluginSelector(host) }
                        } else pluginMenu = true
                    },
                    contentPadding = TightPadding
                ) {
                    Text(
                        instances.firstOrNull()?.let { "⋮ ${it.displayName}" } ?: "Add Master Plugin",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                DropdownMenu(expanded = pluginMenu, onDismissRequest = { pluginMenu = false }) {
                    instances.forEachIndexed { i, instance ->
                        val key = "details:${instance.instanceId}"
                        DropdownMenuItem(
                            text = { Text("${if (windows.isOpen(key)) "Hide" else "Show"} ${instance.displayName} Details") },
                            onClick = {
                                pluginMenu = false
                                if (windows.isOpen(key)) windows.close(key)
                                else windows.open(key, "${instance.displayName} - Details", DpSize(460.dp, 420.dp)) {
                                    InstanceDetails(host, instance)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete ${instance.displayName} (at [${i + 1}])") },
                            onClick = { pluginMenu = false; host.removeInstance(instance.instanceId) }
                        )
                        HorizontalDivider()
                    }
                    DropdownMenuItem(text = { Text("Add Master Plugin") }, onClick = {
                        pluginMenu = false
                        host.targetPluginDestination(MasterTrackIndex)
                        windows.open("plugins", "Plugin Selector", DpSize(560.dp, 430.dp)) { PluginSelector(host) }
                    })
                }
            }
        }
    }
}

@Composable
private fun MasterTrackLane(host: UapmdHost, pixelsPerSecond: Float, laneWidth: Dp, trackHeight: Dp) {
    val sampleRate = (host.model.sampleRate.takeIf { it > 0 } ?: 48000).toDouble()
    Box(Modifier.height(trackHeight).width(laneWidth).background(MasterLaneBackground)) {
        Canvas(Modifier.fillMaxSize()) {
            host.masterClips.forEach { clip ->
                val x = (clip.positionSamples / sampleRate * pixelsPerSecond).toFloat()
                val w = (clip.durationSamples / sampleRate * pixelsPerSecond).toFloat().coerceAtLeast(2f)
                drawRect(MidiClip, Offset(x, 4f), Size(w, size.height - 8f))
                drawRect(ClipBorder, Offset(x, 4f), Size(w, size.height - 8f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
            }
            val px = (host.playheadSeconds * pixelsPerSecond).toFloat()
            drawLine(Playhead, Offset(px, 0f), Offset(px, size.height), 2f)
        }
    }
}
