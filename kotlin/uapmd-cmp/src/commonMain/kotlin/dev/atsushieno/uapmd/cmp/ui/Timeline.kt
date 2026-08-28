package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.unit.DpOffset
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
private val RangeFill = Color(0x552F6FA8)

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
private val FrozenColor = Color(0xFF7FD4F0)

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
    val scope = rememberCoroutineScope()

    // uapmd-app's main timeline lanes ARE the sequence editor's unified timeline
    // (`TimelineEditor.cpp:1016` renderUnifiedTimeline; the per-track render at
    // :1572 is only a vtable stub), so its lane context menus belong here.
    var addMenuOpen by remember { mutableStateOf(false) }
    var rangeMenuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(DpOffset.Zero) }
    var clickedSeconds by remember { mutableStateOf(0.0) }
    var rangeAnchorSeconds by remember { mutableStateOf<Double?>(null) }
    var rangeCurrentSeconds by remember { mutableStateOf(0.0) }
    var rangeStart by remember { mutableStateOf(0.0) }
    var rangeEnd by remember { mutableStateOf(0.0) }

    fun clipAt(seconds: Double): ClipData? = clips.firstOrNull { c ->
        val start = c.positionSamples / sampleRate
        seconds >= start && seconds <= start + c.durationSamples / sampleRate
    }

    Box(
        Modifier.height(trackHeight).width(laneWidth)
            .background(LaneBackground)
            .pointerInput(clips, pixelsPerSecond) {
                detectTapGestures(onDoubleTap = { offset ->
                    val seconds = (offset.x / pixelsPerSecond).toDouble().coerceAtLeast(0.0)
                    clickedSeconds = seconds
                    menuAnchor = with(density) { DpOffset(offset.x.toDp(), 0.dp) }
                    // A double-click on a clip is that clip's menu, which the
                    // clip label already opens; empty space offers the adds.
                    if (clipAt(seconds) == null) addMenuOpen = true
                })
            }
            // Direct manipulation: drag a clip along the lane to move it. The
            // commit goes through setClipAnchor, so it lands in history as one step.
            .pointerInput(clips, pixelsPerSecond) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val seconds = (offset.x / pixelsPerSecond).toDouble()
                        draggingClipId = clipAt(seconds)?.clipId
                        dragSeconds = 0.0
                        // Only empty space starts a range selection; a drag that
                        // began on a clip is that clip's move gesture.
                        if (draggingClipId == null) {
                            rangeAnchorSeconds = seconds.coerceAtLeast(0.0)
                            rangeCurrentSeconds = seconds.coerceAtLeast(0.0)
                        }
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
                        rangeAnchorSeconds?.let { anchor ->
                            val a = minOf(anchor, rangeCurrentSeconds)
                            val b = maxOf(anchor, rangeCurrentSeconds)
                            // uapmd-app needs a few pixels of travel before a drag
                            // counts as a range rather than a stray click.
                            if ((b - a) * pixelsPerSecond >= 4.0) {
                                rangeStart = a
                                rangeEnd = b
                                menuAnchor = with(density) { DpOffset((a * pixelsPerSecond).toFloat().toDp(), 0.dp) }
                                rangeMenuOpen = true
                            }
                        }
                        rangeAnchorSeconds = null
                        draggingClipId = null
                        dragSeconds = 0.0
                    },
                    onDragCancel = {
                        draggingClipId = null; dragSeconds = 0.0; rangeAnchorSeconds = null
                    }
                ) { change, delta ->
                    change.consume()
                    if (draggingClipId != null) dragSeconds += delta.x / pixelsPerSecond
                    else if (rangeAnchorSeconds != null)
                        rangeCurrentSeconds = (change.position.x / pixelsPerSecond).toDouble().coerceAtLeast(0.0)
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
            rangeAnchorSeconds?.let { anchor ->
                val a = minOf(anchor, rangeCurrentSeconds).toFloat() * pixelsPerSecond
                val b = maxOf(anchor, rangeCurrentSeconds).toFloat() * pixelsPerSecond
                drawRect(RangeFill, Offset(a, 0f), Size(b - a, size.height))
            }
            val px = (host.playheadSeconds * pixelsPerSecond).toFloat()
            drawLine(Playhead, Offset(px, 0f), Offset(px, size.height), 2f)
        }

        LaneAddMenu(
            host = host, trackIndex = trackIndex, expanded = addMenuOpen, anchor = menuAnchor,
            seconds = clickedSeconds, sampleRate = sampleRate, scope = scope,
            onDismiss = { addMenuOpen = false }
        )
        LaneRangeMenu(
            host = host, trackIndex = trackIndex, expanded = rangeMenuOpen, anchor = menuAnchor,
            startSeconds = rangeStart, endSeconds = rangeEnd, sampleRate = sampleRate,
            onDismiss = { rangeMenuOpen = false }
        )

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

/**
 * The lane's "add a clip here" menu — uapmd-app's `TimelineAddClipContext`
 * (`SequenceEditor.cpp:700`), opened by a double-click on empty lane space. Every
 * entry lands the clip at the clicked position; the master track takes MIDI only.
 */
@Composable
private fun LaneAddMenu(
    host: UapmdHost,
    trackIndex: Int,
    expanded: Boolean,
    anchor: DpOffset,
    seconds: Double,
    sampleRate: Double,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit
) {
    val isMaster = trackIndex == MasterTrackIndex
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = anchor) {
        DropdownMenuItem(text = { Text("Add an Empty MIDI2 Clip") }, onClick = {
            onDismiss()
            host.addEmptyMidiClip(trackIndex, (seconds * sampleRate).toLong())
        })
        if (!isMaster) {
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Add Empty Audio Clip") }, onClick = {
                onDismiss()
                host.addEmptyAudioClip(trackIndex, seconds)
            })
            DropdownMenuItem(text = { Text("Create Audio Clip From File…") }, onClick = {
                onDismiss()
                scope.launch { pickMediaFileToOpen()?.let { host.importAudioClip(trackIndex, it, seconds) } }
            })
        }
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Add a MIDI Clip from File…") }, onClick = {
            onDismiss()
            scope.launch { pickMediaFileToOpen()?.let { host.importMidiClip(trackIndex, it, seconds) } }
        })
        if (!isMaster) {
            DropdownMenuItem(text = { Text("Add MIDI2 Clip from File…") }, onClick = {
                onDismiss()
                scope.launch { pickMediaFileToOpen()?.let { host.importMidiClip(trackIndex, it, seconds) } }
            })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Clear All") }, onClick = {
                onDismiss()
                host.clearClipsFromTrack(trackIndex)
            })
        }
    }
}

/**
 * uapmd-app's `TimelineRangeAddContext` (`SequenceEditor.cpp:743`): after a
 * drag across empty lane space, add a clip sized to that range. Regular tracks
 * only, as there.
 */
@Composable
private fun LaneRangeMenu(
    host: UapmdHost,
    trackIndex: Int,
    expanded: Boolean,
    anchor: DpOffset,
    startSeconds: Double,
    endSeconds: Double,
    sampleRate: Double,
    onDismiss: () -> Unit
) {
    if (trackIndex == MasterTrackIndex) return
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = anchor) {
        DropdownMenuItem(text = { Text("Add New MIDI Clip") }, onClick = {
            onDismiss()
            val r = host.addEmptyMidiClip(trackIndex, (startSeconds * sampleRate).toLong())
            if (r.success)
                host.resizeClip(trackIndex, r.clipId, ((endSeconds - startSeconds) * sampleRate).toLong())
        })
        DropdownMenuItem(text = { Text("Add Empty Audio Clip") }, onClick = {
            onDismiss()
            host.addEmptyAudioClip(trackIndex, startSeconds, endSeconds)
        })
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

/**
 * The legend gain slider.
 *
 * uapmd-app draws no value on the slider itself — its format string is `""`, or
 * `"Mute"` at the bottom of the range (`TimelineEditor.cpp:1255`) — and puts the
 * dB in the hover tooltip, so the tooltip is the only place the number appears.
 * The slider is `iconButtonWidth * 1.5f` wide there, i.e. as narrow as this one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GainSlider(
    gainDb: Float,
    isNarrow: Boolean = false,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(if (gainDb <= MinGainDb) "Mute" else "${fixed(gainDb.toDouble(), 1)} dB")
            }
        },
        state = rememberTooltipState()
    ) {
        Slider(
            value = gainDb,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = MinGainDb..MaxGainDb,
            modifier = Modifier.width(if (isNarrow) 44.dp else 60.dp)
        )
    }
}

/*
 * Legend button icons.
 *
 * uapmd-app labels these with Font Awesome glyphs plus a hover tooltip
 * (`renderIconButtonWithTooltip`; `FontIcons.hpp` picks clipboard-list,
 * diagram-project and ellipsis-vertical). We ship no icon font, and bare Unicode
 * substitutes were the wrong answer: `⛓` renders as tofu on macOS and `▤` reads
 * as a smudge at this size. Compose Multiplatform 1.10 has no material-icons
 * artifact either, so the three shapes are drawn here - they cost nothing,
 * render identically on all five targets, and each carries uapmd-app's tooltip.
 */

private val LegendIconSize = 16.dp

/** clipboard-list: a stack of bars. */
@Composable
private fun ClipsIcon(tint: Color) = Canvas(Modifier.size(LegendIconSize)) {
    val barHeight = size.height / 7f
    listOf(0f, 3f, 6f).forEach { slot ->
        drawRect(tint, Offset(0f, slot * barHeight), Size(size.width, barHeight))
    }
}

/** diagram-project: two nodes feeding a third. */
@Composable
private fun GraphIcon(tint: Color) = Canvas(Modifier.size(LegendIconSize)) {
    val r = size.minDimension * 0.16f
    val upper = Offset(r, r)
    val lower = Offset(r, size.height - r)
    val out = Offset(size.width - r, size.height / 2f)
    drawLine(tint, upper, out, 1.5f)
    drawLine(tint, lower, out, 1.5f)
    listOf(upper, lower, out).forEach { drawCircle(tint, r, it) }
}

/** snowflake: track freeze. */
@Composable
private fun FreezeIcon(tint: Color) = Canvas(Modifier.size(LegendIconSize)) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val r = size.minDimension * 0.45f
    // Three crossing spokes at 60 degrees, the usual snowflake shorthand.
    listOf(0.0, 1.0471975, 2.0943951).forEach { a ->
        val dx = (r * kotlin.math.cos(a)).toFloat()
        val dy = (r * kotlin.math.sin(a)).toFloat()
        drawLine(tint, Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), 1.5f)
    }
}

/** ellipsis-vertical. */
@Composable
private fun MoreIcon(tint: Color) = Canvas(Modifier.size(LegendIconSize)) {
    val r = size.minDimension * 0.11f
    listOf(0.18f, 0.5f, 0.82f).forEach {
        drawCircle(tint, r, Offset(size.width / 2f, size.height * it))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegendIconButton(
    tooltip: String,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState()
    ) {
        Button(
            onClick = onClick,
            contentPadding = TightPadding,
            modifier = Modifier.size(IconButtonSize)
        ) {
            icon(LocalContentColor.current)
        }
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
                LegendIconButton("Edit clips", onClick = { clipsMenu = true }) { ClipsIcon(it) }
                DropdownMenu(expanded = clipsMenu, onDismissRequest = { clipsMenu = false }) {
                    // First item in uapmd-app's Clips popup (TimelineEditor.cpp:1449);
                    // the per-lane context actions live in that window.
                    DropdownMenuItem(text = { Text("Edit Clips…") }, onClick = {
                        clipsMenu = false
                        windows.open("sequence", "Sequence Editor", DpSize(720.dp, 420.dp)) {
                            SequenceEditor(host, windows)
                        }
                    })
                    HorizontalDivider()
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

            LegendIconButton(
                "Show track graph",
                onClick = {
                    windows.open("graph:$trackIndex", "Track $trackIndex Graph", DpSize(620.dp, 440.dp)) {
                        TrackGraphEditor(host, trackIndex)
                    }
                }
            ) { GraphIcon(it) }

            engineTrack?.takeIf { !isNarrow }?.let { t ->
                // Read the value from the track, write it through ProjectCommands.
                // uapmd-app's slider is in dB and wraps the drag in an undo
                // gesture, so a drag is one history entry rather than dozens.
                var gainDb by remember(trackIndex) { mutableStateOf(linearToDb(t.gain).toFloat()) }
                GainSlider(
                    gainDb = gainDb,
                    isNarrow = isNarrow,
                    onChange = { gainDb = it; host.setTrackGain(trackIndex, dbToLinear(it.toDouble())) },
                    onFinished = { host.endTrackGainGesture() }
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
        }

        // Row 2, as uapmd-app orders it: Freeze switch, then the plugin context
        // button labelled with the first instance's name (or "Add Plugin" when
        // the track is empty), then the More menu.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            LegendIconButton(
                if (trackIndex in host.freezeRequested)
                    "Track freezing: On (click to unfreeze)"
                else
                    "Track freezing: Off (click to render and freeze)",
                onClick = {
                    host.setTrackFreezePolicyEnabled(trackIndex, trackIndex !in host.freezeRequested)
                }
            ) { FreezeIcon(if (trackIndex in host.freezeRequested) FrozenColor else it) }

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
                LegendIconButton("More track actions", onClick = { moreMenu = true }) { MoreIcon(it) }
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
    var clipsMenu by remember { mutableStateOf(false) }
    val instances = host.masterInstances
    // uapmd-app builds the master legend from the same code as a regular track
    // (`renderTrackLegendContent`, via `engine()->masterTrack()`): row 1 is
    // Clips + Graph + the gain slider — this is the project's total volume —
    // and only Mute/Solo, Freeze and the More menu are gated to regular tracks.
    val masterTrack = remember(host.trackCount) {
        runCatching { host.model.sequencer.engine.masterTrack }.getOrNull()
    }

    Column(Modifier.height(trackHeight).fillMaxWidth().padding(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Master", style = MaterialTheme.typography.labelMedium)

            Box {
                LegendIconButton("Edit clips", onClick = { clipsMenu = true }) { ClipsIcon(it) }
                DropdownMenu(expanded = clipsMenu, onDismissRequest = { clipsMenu = false }) {
                    DropdownMenuItem(text = { Text("Edit Clips…") }, onClick = {
                        clipsMenu = false
                        windows.open("sequence", "Sequence Editor", DpSize(720.dp, 420.dp)) {
                            SequenceEditor(host, windows)
                        }
                    })
                    HorizontalDivider()
                    // The master track takes MIDI clips only.
                    DropdownMenuItem(text = { Text("Add an Empty MIDI2 Clip") }, onClick = {
                        clipsMenu = false
                        host.addEmptyMidiClip(MasterTrackIndex)
                    })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Markers") }, onClick = {
                        clipsMenu = false
                        windows.open("markers", "Markers", DpSize(520.dp, 340.dp)) {
                            MasterMarkersWindow(host)
                        }
                    })
                }
            }

            LegendIconButton(
                "Show track graph",
                onClick = {
                    windows.open("graph:master", "Master Track Graph", DpSize(620.dp, 440.dp)) {
                        TrackGraphEditor(host, MasterTrackIndex)
                    }
                }
            ) { GraphIcon(it) }

            masterTrack?.let { t ->
                var gainDb by remember { mutableStateOf(linearToDb(t.gain).toFloat()) }
                GainSlider(
                    gainDb = gainDb,
                    onChange = {
                        gainDb = it
                        host.setTrackGain(MasterTrackIndex, dbToLinear(it.toDouble()))
                    },
                    onFinished = { host.endTrackGainGesture() }
                )
            }
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
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var addMenuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(DpOffset.Zero) }
    var clickedSeconds by remember { mutableStateOf(0.0) }
    Box(
        Modifier.height(trackHeight).width(laneWidth).background(MasterLaneBackground)
            .pointerInput(host.masterClips, pixelsPerSecond) {
                detectTapGestures(onDoubleTap = { offset ->
                    val seconds = (offset.x / pixelsPerSecond).toDouble().coerceAtLeast(0.0)
                    val onClip = host.masterClips.any { c ->
                        val start = c.positionSamples / sampleRate
                        seconds >= start && seconds <= start + c.durationSamples / sampleRate
                    }
                    if (!onClip) {
                        clickedSeconds = seconds
                        menuAnchor = with(density) { DpOffset(offset.x.toDp(), 0.dp) }
                        addMenuOpen = true
                    }
                })
            }
    ) {
        LaneAddMenu(
            host = host, trackIndex = MasterTrackIndex, expanded = addMenuOpen, anchor = menuAnchor,
            seconds = clickedSeconds, sampleRate = sampleRate, scope = scope,
            onDismiss = { addMenuOpen = false }
        )
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
