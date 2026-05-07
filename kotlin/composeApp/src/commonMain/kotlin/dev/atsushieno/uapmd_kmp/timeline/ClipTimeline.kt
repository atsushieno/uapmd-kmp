package dev.atsushieno.uapmd_kmp.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// ---------------------------------------------------------------------------
// Public data model
// ---------------------------------------------------------------------------

data class TimelineClip(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val label: String = "",
    val previewData: ClipPreviewData? = null
)

data class TimelineTrack(
    val index: Int,
    val name: String,
    val clips: List<TimelineClip>
)

// ---------------------------------------------------------------------------
// Internal state
// ---------------------------------------------------------------------------

private enum class DragTarget { None, Clip, Scrollbar }

private data class DragState(
    val target: DragTarget = DragTarget.None,
    val trackIndex: Int = -1,
    val clipId: Int = -1,
    val clipOriginalStartMs: Long = 0L,
    val clipDurationMs: Long = 0L,
    val dragStartX: Float = 0f,
    // for scrollbar
    val scrollbarStartFrame: Long = 0L,
    val scrollbarGrabX: Float = 0f
)

private class TimelineState(
    zoom: Float = 0.1f,      // px per ms
    startMs: Long = 0L
) {
    var zoom by mutableStateOf(zoom)
    var startMs by mutableStateOf(startMs)
    var drag by mutableStateOf(DragState())
    var selectedClipId by mutableStateOf(-1)
    var hoveredTrackIndex by mutableStateOf(-1)

    // double-click tracking (no recomposition needed)
    var lastTapMark: TimeMark? = null
    var lastTapClipId: Int = -1

    // layout constants (set once per draw pass from density)
    var legendWidth: Float = 180f
    var headerHeight: Float = 28f
    var sectionHeight: Float = 60f
    var scrollbarHeight: Float = 14f

    fun msToX(ms: Long): Float = legendWidth + (ms - startMs) * zoom
    fun xToMs(x: Float): Long = ((x - legendWidth) / zoom + startMs).roundToLong()

    fun visibleDurationMs(canvasWidth: Float): Long =
        ((canvasWidth - legendWidth) / zoom).roundToLong().coerceAtLeast(1L)

    fun clampStartMs(value: Long, totalMs: Long, canvasWidth: Float): Long =
        value.coerceIn(0L, max(0L, totalMs - visibleDurationMs(canvasWidth)))
}

// ---------------------------------------------------------------------------
// Lane assignment (greedy, same logic as C++ rebuildUnifiedTimeline)
// ---------------------------------------------------------------------------

private data class LaidOutClip(
    val clip: TimelineClip,
    val lane: Int,
    val laneCount: Int
)

private data class TrackAt(val track: TimelineTrack, val laid: List<LaidOutClip>, val topY: Float)

private fun assignLanes(clips: List<TimelineClip>): List<LaidOutClip> {
    if (clips.isEmpty()) return emptyList()
    val sorted = clips.sortedBy { it.startMs }
    val laneEnds = mutableListOf<Long>()
    val laneMap = mutableMapOf<Int, Int>()
    for (clip in sorted) {
        var lane = laneEnds.indexOfFirst { it <= clip.startMs }
        if (lane < 0) { lane = laneEnds.size; laneEnds.add(clip.endMs) }
        else laneEnds[lane] = clip.endMs
        laneMap[clip.id] = lane
    }
    val laneCount = max(1, laneEnds.size)
    return clips.map { LaidOutClip(it, laneMap[it.id] ?: 0, laneCount) }
}

// ---------------------------------------------------------------------------
// Colours
// ---------------------------------------------------------------------------

private object TimelineColors {
    val header = Color(0xFF3D3837)
    val headerText = Color(0xFFC4C4C4)
    val sectionBg = Color(0xFF283232)
    val sectionBgHovered = Color(0x18201008)
    val legendBg = Color(0xFF222222)
    val legendText = Color(0xFFCCCCCC)
    val clipBg = Color(0xFF4488AA)
    val clipBgSelected = Color(0xFF66AACC)
    val clipText = Color(0xFFFFFFFF)
    val clipBorder = Color(0xFF88CCEE)
    val clipDrag = Color(0x99224466)
    val scrollbarBg = Color(0xFF222222)
    val scrollbarTrack = Color(0xFF101010)
    val scrollbarThumb = Color(0xFF505050)
    val scrollbarThumbHovered = Color(0xFF707070)
}

// ---------------------------------------------------------------------------
// Main composable
// ---------------------------------------------------------------------------

@Composable
fun ClipTimeline(
    tracks: List<TimelineTrack>,
    playheadMs: Long = -1L,
    masterTrackIndex: Int = 0,
    modifier: Modifier = Modifier,
    onClipMoved: (trackIndex: Int, clipId: Int, newStartMs: Long) -> Unit = { _, _, _ -> },
    onClipDoubleClicked: (trackIndex: Int, clipId: Int) -> Unit = { _, _ -> },
    onEmptyDoubleClicked: (trackIndex: Int, positionMs: Long) -> Unit = { _, _ -> },
    onSavePluginState: (trackIndex: Int) -> Unit = {},
    onOpenGraph: (trackIndex: Int) -> Unit = {},
    onToggleMonitor: (trackIndex: Int) -> Unit = {},
    onRemoveTrack: (trackIndex: Int) -> Unit = {},
    onAddPlugin: (trackIndex: Int) -> Unit = {}
) {
    val state = remember { TimelineState() }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    with(density) {
        state.legendWidth = 160.dp.toPx()
        state.headerHeight = 28.dp.toPx()
        state.sectionHeight = 64.dp.toPx()
        state.scrollbarHeight = 14.dp.toPx()
    }

    val totalMs by remember(tracks) {
        derivedStateOf { tracks.maxOfOrNull { t -> t.clips.maxOfOrNull { it.endMs } ?: 0L } ?: 0L }
    }
    val laidOutTracks by remember(tracks) {
        derivedStateOf { tracks.map { t -> t to assignLanes(t.clips) } }
    }

    val totalHeight: Dp = with(density) {
        (state.headerHeight + laidOutTracks.sumOf { (_, lanes) ->
            val laneCount = lanes.maxOfOrNull { it.laneCount } ?: 1
            (state.sectionHeight * laneCount).toDouble()
        }.toFloat() + state.scrollbarHeight).toDp()
    }

    val headerHeightDp  = with(density) { state.headerHeight.toDp() }
    val sectionHeightDp = with(density) { state.sectionHeight.toDp() }
    val legendWidthDp   = with(density) { state.legendWidth.toDp() }

    Box(modifier = modifier.height(totalHeight)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(totalHeight)
                // drag, selection, double-click, header pan
                .pointerInput(tracks, totalMs) {
                    val canvasWidth = size.width.toFloat()
                    val canvasHeight = size.height.toFloat()
                    awaitEachGesture {
                        handleTimelineGestures(state, laidOutTracks, totalMs,
                            canvasWidth, canvasHeight,
                            onClipMoved, onClipDoubleClicked, onEmptyDoubleClicked)
                    }
                }
                // scroll-wheel zoom (vertical) and horizontal pan (trackpad swipe)
                .pointerInput(totalMs) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (event.type == PointerEventType.Scroll) {
                                val delta = event.changes.firstOrNull()?.scrollDelta ?: continue
                                val cursorX = event.changes.firstOrNull()?.position?.x
                                    ?: (size.width / 2f)
                                if (delta.y != 0f) {
                                    // zoom around cursor position
                                    val msAtCursor = state.xToMs(cursorX)
                                    val factor = if (delta.y < 0) 1.15f else 1f / 1.15f
                                    state.zoom = (state.zoom * factor).coerceIn(0.01f, 5f)
                                    state.startMs = state.clampStartMs(
                                        msAtCursor - ((cursorX - state.legendWidth) / state.zoom).roundToLong(),
                                        totalMs, size.width.toFloat()
                                    )
                                }
                                if (delta.x != 0f) {
                                    // horizontal trackpad swipe → pan
                                    val msPerPx = 1f / state.zoom
                                    state.startMs = state.clampStartMs(
                                        state.startMs + (delta.x * msPerPx * 8f).roundToLong(),
                                        totalMs, size.width.toFloat()
                                    )
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
                // hover track highlighting (no press required)
                .pointerInput(tracks) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Move ||
                                event.type == PointerEventType.Enter
                            ) {
                                val pos = event.changes.firstOrNull()?.position ?: continue
                                state.hoveredTrackIndex =
                                    trackAt(state, laidOutTracks, pos.y)?.track?.index ?: -1
                            } else if (event.type == PointerEventType.Exit) {
                                state.hoveredTrackIndex = -1
                            }
                        }
                    }
                }
        ) {
            state.startMs = state.clampStartMs(state.startMs, totalMs, size.width)

            drawHeader(state, totalMs, textMeasurer)
            drawSections(state, laidOutTracks, textMeasurer)
            if (playheadMs >= 0L) drawPlayhead(state, playheadMs)
            drawScrollbar(state, totalMs)
            if (state.drag.target == DragTarget.Clip) drawDragGhost(state, laidOutTracks)
        }

        // ── Interactive legend overlay ──────────────────────────────────────
        // Compose buttons laid out on top of the canvas-drawn legend column.
        Column(
            modifier = Modifier
                .width(legendWidthDp)
                .padding(top = headerHeightDp)
        ) {
            laidOutTracks.forEach { (track, laid) ->
                val laneCount = (laid.maxOfOrNull { it.laneCount } ?: 1).coerceAtLeast(1)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(sectionHeightDp * laneCount),
                    contentAlignment = Alignment.BottomStart
                ) {
                    TrackLegendButtons(
                        isMaster      = track.index == masterTrackIndex,
                        onSaveState   = { onSavePluginState(track.index) },
                        onOpenGraph   = { onOpenGraph(track.index) },
                        onMonitor     = { onToggleMonitor(track.index) },
                        onRemove      = { onRemoveTrack(track.index) },
                        onAddPlugin   = { onAddPlugin(track.index) }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Legend overlay — interactive Compose buttons
// ---------------------------------------------------------------------------

@Composable
private fun TrackLegendButtons(
    isMaster: Boolean,
    onSaveState: () -> Unit,
    onOpenGraph: () -> Unit,
    onMonitor: () -> Unit,
    onRemove: () -> Unit,
    onAddPlugin: () -> Unit
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)) {
            // Icon buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendIconButton("💾", onSaveState)
                LegendIconButton("⊛",  onOpenGraph)
                LegendIconButton("👁", onMonitor)
                if (!isMaster) {
                    LegendIconButton("✕", onRemove, isDestructive = true)
                }
                Spacer(Modifier.weight(1f))
            }
            // Add Plugin button
            TextButton(
                onClick = onAddPlugin,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier.height(22.dp).fillMaxWidth()
            ) {
                Text("+ Plugin", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun LegendIconButton(label: String, onClick: () -> Unit, isDestructive: Boolean = false) {
    val contentColor = if (isDestructive)
        MaterialTheme.colorScheme.error
    else
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(22.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor)
    ) {
        Text(label, fontSize = 11.sp)
    }
}

// ---------------------------------------------------------------------------
// Drawing — header / ruler
// ---------------------------------------------------------------------------

private fun DrawScope.drawHeader(state: TimelineState, totalMs: Long, measurer: TextMeasurer) {
    drawRect(
        color = TimelineColors.header,
        topLeft = Offset.Zero,
        size = Size(size.width, state.headerHeight)
    )
    // legend area within header
    drawRect(
        color = TimelineColors.legendBg,
        topLeft = Offset.Zero,
        size = Size(state.legendWidth, state.headerHeight)
    )

    val visMs = state.visibleDurationMs(size.width)
    // pick a tick interval that keeps ticks at least 60px apart
    val minTickMs = (60f / state.zoom).roundToInt().coerceAtLeast(1)
    val tickInterval = listOf(100, 250, 500, 1000, 2000, 5000, 10000, 30000, 60000)
        .firstOrNull { it >= minTickMs } ?: 60000

    val firstTick = (state.startMs / tickInterval) * tickInterval
    var t = firstTick
    clipRect(left = state.legendWidth, top = 0f, right = size.width, bottom = state.headerHeight) {
        while (t <= state.startMs + visMs) {
            val x = state.msToX(t)
            drawLine(TimelineColors.headerText, Offset(x, state.headerHeight * 0.5f), Offset(x, state.headerHeight), 1f)
            val label = formatMs(t)
            val layout = measurer.measure(label, TextStyle(color = TimelineColors.headerText, fontSize = 9.sp))
            drawText(layout, topLeft = Offset(x + 2f, 2f))
            t += tickInterval
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    return if (m > 0) "${m}m${s % 60}s" else "${s}s${(ms % 1000 / 100)}".trimEnd('0').trimEnd('.')
}

// ---------------------------------------------------------------------------
// Drawing — sections + clips
// ---------------------------------------------------------------------------

private fun DrawScope.drawSections(
    state: TimelineState,
    laidOutTracks: List<Pair<TimelineTrack, List<LaidOutClip>>>,
    measurer: TextMeasurer
) {
    var y = state.headerHeight
    for ((track, laid) in laidOutTracks) {
        val laneCount = (laid.maxOfOrNull { it.laneCount } ?: 1).coerceAtLeast(1)
        val trackH = state.sectionHeight * laneCount

        // section background
        val isHovered = state.hoveredTrackIndex == track.index
        drawRect(
            color = if (isHovered) TimelineColors.sectionBg.copy(alpha = 0.9f) else TimelineColors.sectionBg,
            topLeft = Offset(0f, y),
            size = Size(size.width, trackH)
        )
        // legend
        drawRect(
            color = TimelineColors.legendBg,
            topLeft = Offset(0f, y),
            size = Size(state.legendWidth, trackH)
        )
        val nameLayout = measurer.measure(track.name, TextStyle(color = TimelineColors.legendText, fontSize = 11.sp))
        drawText(nameLayout, topLeft = Offset(6f, y + 6f))

        // clips
        clipRect(left = state.legendWidth, top = y, right = size.width, bottom = y + trackH) {
            for (laidClip in laid) {
                val clip = laidClip.clip
                if (state.drag.target == DragTarget.Clip && state.drag.clipId == clip.id) continue
                val laneH = state.sectionHeight
                val clipY = y + laidClip.lane * laneH
                drawClip(state, clip, clipY, laneH, measurer)
            }
        }

        // separator line
        drawLine(Color(0x33FFFFFF), Offset(0f, y + trackH), Offset(size.width, y + trackH), 1f)
        y += trackH
    }
}

private const val CLIP_LABEL_H = 16f

private fun DrawScope.drawClip(
    state: TimelineState,
    clip: TimelineClip,
    clipY: Float,
    laneH: Float,
    measurer: TextMeasurer
) {
    val x1 = state.msToX(clip.startMs).coerceAtLeast(state.legendWidth)
    val x2 = state.msToX(clip.endMs).coerceAtMost(size.width)
    if (x2 <= x1) return

    val isSelected = state.selectedClipId == clip.id
    val bg = if (isSelected) TimelineColors.clipBgSelected else TimelineColors.clipBg
    val rect = Rect(x1, clipY + 2f, x2, clipY + laneH - 2f)

    drawRoundRect(bg, rect.topLeft, rect.size, androidx.compose.ui.geometry.CornerRadius(4f))

    // Preview content (rendered before border so border draws on top)
    val previewRect = Rect(rect.left + 2f, rect.top + CLIP_LABEL_H, rect.right - 2f, rect.bottom - 2f)
    if (previewRect.width > 4f && previewRect.height > 4f) {
        when (val p = clip.previewData) {
            is ClipPreviewData.Audio -> drawWaveformPreview(p, previewRect, measurer)
            is ClipPreviewData.Midi -> drawMidiPreview(p, previewRect)
            is ClipPreviewData.MasterMeta -> drawMasterMetaPreview(p, previewRect, measurer)
            is ClipPreviewData.Loading -> drawPreviewPlaceholder("Loading…", previewRect, measurer)
            is ClipPreviewData.Error -> drawPreviewPlaceholder(p.message, previewRect, measurer)
            null -> Unit
        }
    }

    drawRoundRect(
        color = if (isSelected) TimelineColors.clipBorder else TimelineColors.clipBorder.copy(alpha = 0.5f),
        topLeft = rect.topLeft, size = rect.size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
    )
    if (rect.width > 20f) {
        val typeLabel = when (clip.previewData) {
            is ClipPreviewData.Midi -> "MIDI"
            is ClipPreviewData.Audio -> "Audio"
            is ClipPreviewData.MasterMeta -> "Meta"
            else -> null
        }
        clipRect(left = rect.left + 2f, top = rect.top, right = rect.right - 2f, bottom = rect.top + CLIP_LABEL_H) {
            val layout = measurer.measure(clip.label, TextStyle(color = TimelineColors.clipText, fontSize = 10.sp))
            drawText(layout, topLeft = Offset(rect.left + 4f, rect.top + 2f))
            if (typeLabel != null) {
                val tl = measurer.measure(typeLabel, TextStyle(color = TimelineColors.clipText.copy(alpha = 0.6f), fontSize = 8.sp))
                drawText(tl, topLeft = Offset(rect.right - tl.size.width - 4f, rect.top + 3f))
            }
        }
    }
}

private fun DrawScope.drawPreviewPlaceholder(text: String, rect: Rect, measurer: TextMeasurer) {
    val layout = measurer.measure(text, TextStyle(color = TimelineColors.clipText.copy(alpha = 0.5f), fontSize = 8.sp))
    val x = rect.left + (rect.width - layout.size.width) / 2
    val y = rect.top + (rect.height - layout.size.height) / 2
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        drawText(layout, topLeft = Offset(x, y))
    }
}

private fun DrawScope.drawWaveformPreview(
    data: ClipPreviewData.Audio,
    rect: Rect,
    measurer: TextMeasurer
) {
    if (data.waveform.isEmpty()) return
    val centerY = rect.center.y
    val halfH = rect.height / 2f
    val lineColor = Color(0xB270C8FF.toInt())
    val safeDuration = data.durationSeconds.coerceAtLeast(0.001)

    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        val count = data.waveform.size
        data.waveform.forEachIndexed { i, pt ->
            val t = if (count > 1) i.toFloat() / (count - 1) else 0f
            val x = rect.left + t * rect.width
            val y1 = centerY - pt.maxValue.coerceIn(-1f, 1f) * halfH
            val y2 = centerY - pt.minValue.coerceIn(-1f, 1f) * halfH
            drawLine(lineColor, Offset(x, y1), Offset(x, y2.coerceAtLeast(y1 + 1f)), 1.2f)
        }

        val markerColor = Color(0xDCFFDE59.toInt())
        for (marker in data.markers) {
            val x = rect.left + (marker.clipPositionSeconds / safeDuration).toFloat().coerceIn(0f, 1f) * rect.width
            drawLine(markerColor, Offset(x, rect.top), Offset(x, rect.bottom), 1.5f)
            if (marker.name.isNotEmpty()) {
                val ml = measurer.measure(marker.name, TextStyle(color = markerColor, fontSize = 7.sp))
                drawText(ml, topLeft = Offset(x + 2f, rect.top + 1f))
            }
        }
    }
}

private fun DrawScope.drawMidiPreview(data: ClipPreviewData.Midi, rect: Rect) {
    if (data.notes.isEmpty()) return
    val noteRange = (data.maxNote - data.minNote + 1).coerceAtLeast(1)
    val safeDuration = data.durationSeconds.coerceAtLeast(0.01)
    val laneH = rect.height / noteRange

    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        for (note in data.notes) {
            val x1 = rect.left + (note.startSeconds / safeDuration).toFloat().coerceIn(0f, 1f) * rect.width
            val x2 = (rect.left + ((note.startSeconds + note.durationSeconds) / safeDuration).toFloat().coerceIn(0f, 1f) * rect.width)
                .coerceAtLeast(x1 + 1.5f)
            val notePos = (data.maxNote - note.note).toFloat() / noteRange
            val y1 = rect.top + notePos * rect.height
            val y2 = (y1 + laneH.coerceAtLeast(4f) * 0.85f).coerceAtMost(rect.bottom)
            val v = note.velocity
            val noteColor = Color(0.2f + 0.5f * v, 0.4f + 0.3f * v, 0.9f - 0.4f * v, 0.9f)
            drawRoundRect(noteColor, Offset(x1, y1), Size(x2 - x1, y2 - y1), androidx.compose.ui.geometry.CornerRadius(2f))
        }
    }
}

private fun DrawScope.drawMasterMetaPreview(
    data: ClipPreviewData.MasterMeta,
    rect: Rect,
    measurer: TextMeasurer
) {
    val safeDuration = data.durationSeconds.coerceAtLeast(0.001)
    val validTempos = data.tempoPoints.filter { it.bpm > 0 }
    val minBpm = validTempos.minOfOrNull { it.bpm } ?: 40.0
    val maxBpm = validTempos.maxOfOrNull { it.bpm } ?: 200.0
    val bpmRange = (maxBpm - minBpm).coerceAtLeast(1.0)

    fun toX(sec: Double) = rect.left + (sec / safeDuration).toFloat().coerceIn(0f, 1f) * rect.width
    fun toY(bpm: Double) = rect.bottom - ((bpm - minBpm) / bpmRange).toFloat().coerceIn(0f, 1f) * rect.height

    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        val gridColor = Color(0xA0787890.toInt())
        for (i in 0..3) {
            val y = rect.bottom - i / 3f * rect.height
            drawLine(gridColor, Offset(rect.left, y), Offset(rect.right, y), 0.5f)
        }

        val tempoColor = Color(0xFF70CAFF.toInt())
        validTempos.forEachIndexed { i, pt ->
            val x = toX(pt.timeSeconds)
            val y = toY(pt.bpm)
            val endX = if (i + 1 < validTempos.size) toX(validTempos[i + 1].timeSeconds) else rect.right
            if (endX > x) drawLine(tempoColor, Offset(x, y), Offset(endX, y), 2f)
            drawCircle(tempoColor, 2.5f, Offset(x, y))
            if (i + 1 < validTempos.size) {
                val ny = toY(validTempos[i + 1].bpm)
                drawLine(tempoColor, Offset(endX, y), Offset(endX, ny), 2f)
            }
        }

        val sigColor = Color(0xC8D68FFF.toInt())
        for (sig in data.timeSignatures) {
            val x = toX(sig.timeSeconds)
            drawLine(sigColor, Offset(x, rect.top), Offset(x, rect.bottom), 1f)
            val tl = measurer.measure("${sig.numerator}/${sig.denominator}", TextStyle(color = sigColor, fontSize = 7.sp))
            drawText(tl, topLeft = Offset(x + 2f, rect.top + 1f))
        }
    }
}

// ---------------------------------------------------------------------------
// Drawing — drag ghost
// ---------------------------------------------------------------------------

private fun DrawScope.drawDragGhost(
    state: TimelineState,
    laidOutTracks: List<Pair<TimelineTrack, List<LaidOutClip>>>
) {
    val d = state.drag
    if (d.target != DragTarget.Clip) return
    // clipOriginalStartMs is mutated during drag to hold the live position
    val x1 = state.msToX(d.clipOriginalStartMs).coerceAtLeast(state.legendWidth)
    val x2 = (x1 + d.clipDurationMs * state.zoom).coerceAtMost(size.width)

    var y = state.headerHeight
    for ((track, laid) in laidOutTracks) {
        val laneCount = (laid.maxOfOrNull { it.laneCount } ?: 1).coerceAtLeast(1)
        val trackH = state.sectionHeight * laneCount
        if (track.index == d.trackIndex) {
            drawRect(TimelineColors.clipDrag, Offset(x1, y + 2f), Size(x2 - x1, state.sectionHeight - 4f))
            break
        }
        y += trackH
    }
}

// ---------------------------------------------------------------------------
// Drawing — playhead
// ---------------------------------------------------------------------------

private fun DrawScope.drawPlayhead(state: TimelineState, playheadMs: Long) {
    val x = state.msToX(playheadMs)
    if (x < state.legendWidth || x > size.width) return
    val bottom = size.height - state.scrollbarHeight
    drawLine(Color(0xFF2A2AFF.toInt()), Offset(x, state.headerHeight), Offset(x, bottom), 2f)
}

// ---------------------------------------------------------------------------
// Drawing — scrollbar
// ---------------------------------------------------------------------------

private fun DrawScope.drawScrollbar(state: TimelineState, totalMs: Long) {
    val y = size.height - state.scrollbarHeight
    val trackW = size.width - state.legendWidth

    drawRect(TimelineColors.scrollbarBg, Offset(state.legendWidth, y), Size(trackW, state.scrollbarHeight))
    drawRect(TimelineColors.scrollbarTrack, Offset(state.legendWidth, y + 1f), Size(trackW, state.scrollbarHeight - 2f))

    val visMs = state.visibleDurationMs(size.width).toFloat()
    val totalMsF = totalMs.toFloat().coerceAtLeast(visMs)
    val thumbRatio = (visMs / totalMsF).coerceIn(0.05f, 1f)
    val thumbW = trackW * thumbRatio
    val thumbX = state.legendWidth + (state.startMs.toFloat() / totalMsF) * trackW

    val thumbHovered = state.drag.target == DragTarget.Scrollbar
    drawRoundRect(
        color = if (thumbHovered) TimelineColors.scrollbarThumbHovered else TimelineColors.scrollbarThumb,
        topLeft = Offset(thumbX, y + 2f),
        size = Size(thumbW, state.scrollbarHeight - 4f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)
    )
}

// ---------------------------------------------------------------------------
// Gesture handling
// ---------------------------------------------------------------------------

private suspend fun AwaitPointerEventScope.handleTimelineGestures(
    state: TimelineState,
    laidOutTracks: List<Pair<TimelineTrack, List<LaidOutClip>>>,
    totalMs: Long,
    canvasWidth: Float,
    canvasHeight: Float,
    onClipMoved: (Int, Int, Long) -> Unit,
    onClipDoubleClicked: (Int, Int) -> Unit,
    onEmptyDoubleClicked: (Int, Long) -> Unit
) {
    val down = awaitFirstDown(requireUnconsumed = false)
    val pos = down.position

    // -- scrollbar hit --
    val sbY = canvasHeight - state.scrollbarHeight
    if (pos.y >= sbY) {
        val trackW = canvasWidth - state.legendWidth
        val visMs = state.visibleDurationMs(canvasWidth).toFloat()
        val totalMsF = totalMs.toFloat().coerceAtLeast(visMs)
        state.drag = DragState(
            target = DragTarget.Scrollbar,
            scrollbarStartFrame = state.startMs,
            scrollbarGrabX = pos.x
        )
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) break
            val dx = change.position.x - state.drag.scrollbarGrabX
            val msPerPx = totalMsF / trackW
            state.startMs = state.clampStartMs(
                (state.drag.scrollbarStartFrame + (dx * msPerPx).toLong()),
                totalMs, canvasWidth
            )
            change.consume()
        }
        state.drag = DragState()
        return
    }

    // -- resolve track + clip under pointer --
    val hitTrack = trackAt(state, laidOutTracks, pos.y)
    val hitClip = hitTrack?.let { clipAt(state, it.laid, pos, it.topY) }

    state.hoveredTrackIndex = hitTrack?.track?.index ?: -1

    if (hitClip != null) {
        state.selectedClipId = hitClip.clip.id
        state.drag = DragState(
            target = DragTarget.Clip,
            trackIndex = hitTrack.track.index,
            clipId = hitClip.clip.id,
            clipOriginalStartMs = hitClip.clip.startMs,
            clipDurationMs = hitClip.clip.endMs - hitClip.clip.startMs,
            dragStartX = pos.x
        )
    }

    var moved = false
    var lastX = pos.x

    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: break
        if (!change.pressed) break
        val dx = change.position.x - lastX
        lastX = change.position.x

        if (state.drag.target == DragTarget.Clip &&
            (moved || abs(change.position.x - state.drag.dragStartX) > 4f)
        ) {
            moved = true
            val totalDx = change.position.x - state.drag.dragStartX
            val newStartMs = (state.drag.clipOriginalStartMs + (totalDx / state.zoom).roundToLong())
                .coerceAtLeast(0L)
            state.drag = state.drag.copy(clipOriginalStartMs = newStartMs, dragStartX = change.position.x)
            change.consume()
        } else if (state.drag.target == DragTarget.None && pos.y < state.headerHeight) {
            state.startMs = state.clampStartMs(
                (state.startMs - (dx / state.zoom).roundToLong()),
                totalMs, canvasWidth
            )
            change.consume()
        }
    }

    if (state.drag.target == DragTarget.Clip && hitClip != null && moved) {
        onClipMoved(state.drag.trackIndex, state.drag.clipId, state.drag.clipOriginalStartMs)
    } else if (!moved) {
        // single or double tap
        val tapClipId = hitClip?.clip?.id ?: -1
        val now = TimeSource.Monotonic.markNow()
        val isDoubleTap = state.lastTapMark?.let { mark ->
            mark.elapsedNow().inWholeMilliseconds < 350L && state.lastTapClipId == tapClipId
        } ?: false
        state.lastTapMark = now
        state.lastTapClipId = tapClipId

        if (isDoubleTap) {
            state.lastTapMark = null  // consume so triple-click doesn't re-fire
            val trackIndex = hitTrack?.track?.index ?: -1
            if (hitClip != null) {
                onClipDoubleClicked(trackIndex, hitClip.clip.id)
            } else if (trackIndex >= 0 && pos.x > state.legendWidth) {
                onEmptyDoubleClicked(trackIndex, state.xToMs(pos.x))
            }
        }
    }

    state.drag = DragState()
}

// ---------------------------------------------------------------------------
// Hit testing helpers
// ---------------------------------------------------------------------------

private fun trackAt(
    state: TimelineState,
    laidOutTracks: List<Pair<TimelineTrack, List<LaidOutClip>>>,
    y: Float
): TrackAt? {
    var cur = state.headerHeight
    for ((track, laid) in laidOutTracks) {
        val laneCount = (laid.maxOfOrNull { it.laneCount } ?: 1).coerceAtLeast(1)
        val trackH = state.sectionHeight * laneCount
        if (y in cur..(cur + trackH)) return TrackAt(track, laid, cur)
        cur += trackH
    }
    return null
}

private fun clipAt(
    state: TimelineState,
    laid: List<LaidOutClip>,
    pos: Offset,
    trackTopY: Float
): LaidOutClip? {
    for (laidClip in laid) {
        val x1 = state.msToX(laidClip.clip.startMs)
        val x2 = state.msToX(laidClip.clip.endMs)
        val laneY1 = trackTopY + laidClip.lane * state.sectionHeight
        val laneY2 = laneY1 + state.sectionHeight
        if (pos.x in x1..x2 && pos.y in laneY1..laneY2) return laidClip
    }
    return null
}
