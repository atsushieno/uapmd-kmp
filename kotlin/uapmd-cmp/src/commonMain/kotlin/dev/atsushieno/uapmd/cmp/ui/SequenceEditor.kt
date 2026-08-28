package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.ClipData
import dev.atsushieno.uapmd.ClipType
import dev.atsushieno.uapmd.cmp.UapmdHost
import dev.atsushieno.uapmd.cmp.pickMediaFileToOpen
import kotlinx.coroutines.launch

/*
 * uapmd-app's Sequence Editor (`SequenceEditor.cpp`), reached from a track
 * legend's Clips ▸ "Edit Clips…". It is where the per-lane context actions live
 * — the main TimelineEditor deliberately has none.
 *
 * Three menus, with uapmd-app's triggers (`SequenceEditor.cpp:533-610`):
 *   - double-click a clip        -> clip actions, plus the "…Here" adds
 *   - double-click empty lane    -> add-clip actions at the clicked position
 *   - drag across empty lane     -> range selection; on release, adds sized to it
 *
 * One deliberate omission: uapmd-app's add menu repeats "Edit Clips…", which
 * opens this very window. Inside it that item is a no-op, so it is not shown
 * here; it stays on the timeline legend's Clips popup where it is reachable.
 */

private val LaneBg = Color(0xFF1E1E24)
private val MasterLaneBg = Color(0xFF26262F)
private val AudioClipColor = Color(0xFF4A3D75)
private val MidiClipColor = Color(0xFF3D5A75)
private val ClipBorderColor = Color(0xFF9A8FC7)
private val PlayheadColor = Color(0xFFE8C547)
private val RangeFill = Color(0x552F6FA8)
private val RangeBorder = Color(0xFF7FB2E5)
private val DisabledClipAlpha = 0.35f

private val LaneHeight = 56.dp
private val LabelWidth = 120.dp

/** uapmd's `kMasterTrackIndex` is INT32_MIN; Timeline.kt uses the same constant. */
private const val MasterTrackIndex = Int.MIN_VALUE

@Composable
fun SequenceEditor(host: UapmdHost, windows: FloatingWindowManager) {
    var pixelsPerSecond by remember { mutableStateOf(40f) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    val sampleRate = (host.model.sampleRate.takeIf { it > 0 } ?: 48000).toDouble()
    val lanes = buildList {
        add(MasterTrackIndex to host.masterClips)
        for (t in 0 until host.trackCount) add(t to host.trackClips.getOrNull(t).orEmpty())
    }

    // Wide enough for the content plus a minute of empty room to drop clips into.
    val contentSeconds = lanes.flatMap { it.second }
        .maxOfOrNull { (it.positionSamples + it.durationSamples) / sampleRate } ?: 0.0
    val laneSeconds = (contentSeconds + 60.0).coerceAtLeast(60.0)
    val laneWidth = with(LocalDensity.current) { (laneSeconds * pixelsPerSecond).toFloat().toDp() }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Zoom", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = pixelsPerSecond,
                onValueChange = { pixelsPerSecond = it },
                valueRange = 8f..400f,
                modifier = Modifier.width(160.dp).padding(horizontal = 8.dp)
            )
            Text("${laneSeconds.toInt()}s", style = MaterialTheme.typography.labelSmall)
        }
        HorizontalDivider()
        Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
            Column {
                lanes.forEach { (trackIndex, _) ->
                    Box(
                        Modifier.height(LaneHeight).width(LabelWidth).padding(4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            if (trackIndex == MasterTrackIndex) "Master" else "T$trackIndex",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            Column(Modifier.horizontalScroll(hScroll)) {
                lanes.forEach { (trackIndex, clips) ->
                    SequenceLane(
                        host = host,
                        windows = windows,
                        trackIndex = trackIndex,
                        clips = clips,
                        pixelsPerSecond = pixelsPerSecond,
                        laneWidth = laneWidth,
                        sampleRate = sampleRate
                    )
                }
            }
        }
    }
}

@Composable
private fun SequenceLane(
    host: UapmdHost,
    windows: FloatingWindowManager,
    trackIndex: Int,
    clips: List<ClipData>,
    pixelsPerSecond: Float,
    laneWidth: Dp,
    sampleRate: Double
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val isMaster = trackIndex == MasterTrackIndex

    // Which menu is showing, and where it was summoned from. uapmd-app records
    // the clicked position so "Add … Here" lands under the pointer even when the
    // click was on top of an existing clip.
    var clipMenuFor by remember { mutableStateOf<Int?>(null) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var rangeMenuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(DpOffset.Zero) }
    var clickedSeconds by remember { mutableStateOf(0.0) }

    // Range-selection drag, the equivalent of TimelineRangeSelection.hpp: it only
    // starts on empty lane space, and stays on this lane once started.
    var rangeAnchorSeconds by remember { mutableStateOf<Double?>(null) }
    var rangeCurrentSeconds by remember { mutableStateOf(0.0) }
    var rangeStart by remember { mutableStateOf(0.0) }
    var rangeEnd by remember { mutableStateOf(0.0) }

    fun clipAt(seconds: Double): ClipData? = clips.firstOrNull { c ->
        val start = c.positionSamples / sampleRate
        seconds >= start && seconds <= start + c.durationSamples / sampleRate
    }

    Box(
        Modifier.height(LaneHeight).width(laneWidth)
            .background(if (isMaster) MasterLaneBg else LaneBg)
            .pointerInput(clips, pixelsPerSecond) {
                detectTapGestures(onDoubleTap = { offset ->
                    val seconds = (offset.x / pixelsPerSecond).toDouble().coerceAtLeast(0.0)
                    clickedSeconds = seconds
                    menuAnchor = with(density) { DpOffset(offset.x.toDp(), offset.y.toDp()) }
                    val hit = clipAt(seconds)
                    if (hit != null) {
                        if (hit.clipType == ClipType.Midi)
                            host.selectedMidiClip = trackIndex to hit.clipId
                        clipMenuFor = hit.clipId
                    } else {
                        addMenuOpen = true
                    }
                })
            }
            .pointerInput(clips, pixelsPerSecond) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val seconds = (offset.x / pixelsPerSecond).toDouble().coerceAtLeast(0.0)
                        // A drag that starts on a clip is the timeline's own move
                        // gesture, not a range selection.
                        if (clipAt(seconds) == null) {
                            rangeAnchorSeconds = seconds
                            rangeCurrentSeconds = seconds
                        }
                    },
                    onDragEnd = {
                        val anchor = rangeAnchorSeconds
                        if (anchor != null) {
                            val a = minOf(anchor, rangeCurrentSeconds)
                            val b = maxOf(anchor, rangeCurrentSeconds)
                            // uapmd-app requires a few pixels of travel before a
                            // drag counts as a range rather than a stray click.
                            if ((b - a) * pixelsPerSecond >= 4.0) {
                                rangeStart = a
                                rangeEnd = b
                                menuAnchor = with(density) {
                                    DpOffset((a * pixelsPerSecond).toFloat().toDp(), 0.dp)
                                }
                                if (!isMaster) rangeMenuOpen = true
                            }
                        }
                        rangeAnchorSeconds = null
                    },
                    onDragCancel = { rangeAnchorSeconds = null }
                ) { change, _ ->
                    if (rangeAnchorSeconds != null) {
                        change.consume()
                        rangeCurrentSeconds =
                            (change.position.x / pixelsPerSecond).toDouble().coerceAtLeast(0.0)
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            clips.forEach { clip ->
                val x = (clip.positionSamples / sampleRate * pixelsPerSecond).toFloat()
                val w = (clip.durationSamples / sampleRate * pixelsPerSecond)
                    .toFloat().coerceAtLeast(2f)
                val base = if (clip.clipType == ClipType.Midi) MidiClipColor else AudioClipColor
                drawRect(
                    color = if (clip.muted) base.copy(alpha = DisabledClipAlpha) else base,
                    topLeft = Offset(x, 4f),
                    size = Size(w, size.height - 8f)
                )
                drawRect(
                    color = ClipBorderColor,
                    topLeft = Offset(x, 4f),
                    size = Size(w, size.height - 8f),
                    style = Stroke(1f)
                )
            }
            val anchor = rangeAnchorSeconds
            if (anchor != null) {
                val a = minOf(anchor, rangeCurrentSeconds).toFloat() * pixelsPerSecond
                val b = maxOf(anchor, rangeCurrentSeconds).toFloat() * pixelsPerSecond
                drawRect(RangeFill, Offset(a, 0f), Size(b - a, size.height))
                drawRect(RangeBorder, Offset(a, 0f), Size(b - a, size.height), style = Stroke(1f))
            }
            val px = (host.playheadSeconds * pixelsPerSecond).toFloat()
            drawLine(PlayheadColor, Offset(px, 0f), Offset(px, size.height), 2f)
        }

        clips.forEach { clip ->
            val x = with(density) {
                (clip.positionSamples / sampleRate * pixelsPerSecond).toFloat().toDp()
            }
            Text(
                clip.name.ifEmpty { if (clip.clipType == ClipType.Midi) "MIDI clip" else "audio clip" },
                Modifier.padding(start = x + 3.dp, top = 5.dp),
                style = MaterialTheme.typography.labelSmall
            )
        }

        // ── Clip actions (double-click on a clip) ───────────────────────────
        val menuClip = clipMenuFor?.let { id -> clips.firstOrNull { it.clipId == id } }
        DropdownMenu(
            expanded = clipMenuFor != null,
            onDismissRequest = { clipMenuFor = null },
            offset = menuAnchor
        ) {
            if (menuClip == null) {
                DropdownMenuItem(text = { Text("Clip not available.") }, enabled = false, onClick = {})
            } else {
                val isMidi = menuClip.clipType == ClipType.Midi
                val name = menuClip.name.ifEmpty { if (isMidi) "MIDI clip" else "audio clip" }
                DropdownMenuItem(
                    text = { Text("Show Dump List") },
                    enabled = isMidi,
                    onClick = {
                        clipMenuFor = null
                        windows.open(
                            "dump:$trackIndex:${menuClip.clipId}", "$name - Events",
                            DpSize(520.dp, 400.dp)
                        ) { MidiDumpWindow(host, trackIndex, menuClip.clipId) }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Edit Audio Events") },
                    enabled = !isMidi && !isMaster,
                    onClick = {
                        clipMenuFor = null
                        windows.open(
                            "events:$trackIndex:${menuClip.clipId}", "$name - Markers & Warps",
                            DpSize(560.dp, 420.dp)
                        ) { AudioEventListEditor(host, trackIndex, menuClip.clipId) }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Open Piano Roll") },
                    enabled = isMidi,
                    onClick = {
                        clipMenuFor = null
                        windows.open(
                            "pianoroll:$trackIndex:${menuClip.clipId}", "$name - Piano Roll",
                            DpSize(640.dp, 420.dp)
                        ) { PianoRollEditor(host, trackIndex, menuClip.clipId) }
                    }
                )
                DropdownMenuItem(text = { Text("Delete") }, onClick = {
                    clipMenuFor = null
                    listOf("pianoroll", "dump", "events", "clipprops").forEach {
                        windows.close("$it:$trackIndex:${menuClip.clipId}")
                    }
                    host.removeClip(trackIndex, menuClip.clipId)
                })
                val enabled = host.isClipEnabled(trackIndex, menuClip.clipId)
                DropdownMenuItem(
                    text = { Text(if (enabled) "Disable Clip" else "Enable Clip") },
                    onClick = {
                        clipMenuFor = null
                        host.setClipEnabled(trackIndex, menuClip.clipId, !enabled)
                    }
                )
                if (!isMaster) {
                    HorizontalDivider()
                    AddHereItems(host, trackIndex, clickedSeconds, scope) { clipMenuFor = null }
                }
            }
        }

        // ── Add actions (double-click on empty lane) ────────────────────────
        DropdownMenu(
            expanded = addMenuOpen,
            onDismissRequest = { addMenuOpen = false },
            offset = menuAnchor
        ) {
            DropdownMenuItem(text = { Text("Add an Empty MIDI2 Clip") }, onClick = {
                addMenuOpen = false
                host.addEmptyMidiClip(trackIndex, (clickedSeconds * sampleRate).toLong())
            })
            if (!isMaster) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Add Empty Audio Clip") }, onClick = {
                    addMenuOpen = false
                    host.addEmptyAudioClip(trackIndex, clickedSeconds)
                })
                DropdownMenuItem(text = { Text("Create Audio Clip From File…") }, onClick = {
                    addMenuOpen = false
                    scope.launch {
                        pickMediaFileToOpen()?.let { host.importAudioClip(trackIndex, it, clickedSeconds) }
                    }
                })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Add a MIDI Clip from File…") }, onClick = {
                addMenuOpen = false
                scope.launch {
                    pickMediaFileToOpen()?.let { host.importMidiClip(trackIndex, it, clickedSeconds) }
                }
            })
            if (!isMaster) {
                DropdownMenuItem(text = { Text("Add MIDI2 Clip from File…") }, onClick = {
                    addMenuOpen = false
                    scope.launch {
                        pickMediaFileToOpen()?.let { host.importMidiClip(trackIndex, it, clickedSeconds) }
                    }
                })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Clear All") }, onClick = {
                    addMenuOpen = false
                    host.clearClipsFromTrack(trackIndex)
                })
            }
        }

        // ── Range actions (drag across empty lane) ──────────────────────────
        DropdownMenu(
            expanded = rangeMenuOpen,
            onDismissRequest = { rangeMenuOpen = false },
            offset = menuAnchor
        ) {
            DropdownMenuItem(text = { Text("Add New MIDI Clip") }, onClick = {
                rangeMenuOpen = false
                val r = host.addEmptyMidiClip(trackIndex, (rangeStart * sampleRate).toLong())
                // uapmd-app sizes the new clip to the dragged range.
                if (r.success)
                    host.resizeClip(trackIndex, r.clipId, ((rangeEnd - rangeStart) * sampleRate).toLong())
            })
            DropdownMenuItem(text = { Text("Add Empty Audio Clip") }, onClick = {
                rangeMenuOpen = false
                host.addEmptyAudioClip(trackIndex, rangeStart, rangeEnd)
            })
        }
    }
}

/**
 * The "…Here" adds uapmd-app appends to the clip menu, so a clip sitting under
 * the pointer does not block adding another one at that spot.
 */
@Composable
private fun AddHereItems(
    host: UapmdHost,
    trackIndex: Int,
    seconds: Double,
    scope: kotlinx.coroutines.CoroutineScope,
    dismiss: () -> Unit
) {
    val sampleRate = (host.model.sampleRate.takeIf { it > 0 } ?: 48000).toDouble()
    DropdownMenuItem(text = { Text("Add an Empty MIDI2 Clip Here") }, onClick = {
        dismiss()
        host.addEmptyMidiClip(trackIndex, (seconds * sampleRate).toLong())
    })
    DropdownMenuItem(text = { Text("Add Empty Audio Clip Here") }, onClick = {
        dismiss()
        host.addEmptyAudioClip(trackIndex, seconds)
    })
    DropdownMenuItem(text = { Text("Create Audio Clip From File Here…") }, onClick = {
        dismiss()
        scope.launch { pickMediaFileToOpen()?.let { host.importAudioClip(trackIndex, it, seconds) } }
    })
    DropdownMenuItem(text = { Text("Import SMF Here…") }, onClick = {
        dismiss()
        scope.launch { pickMediaFileToOpen()?.let { host.importMidiClip(trackIndex, it, seconds) } }
    })
}
