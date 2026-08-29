package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
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
import kotlin.math.roundToInt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.ClipData
import dev.atsushieno.uapmd.FreezePolicy
import dev.atsushieno.uapmd.FreezeRuntimeState
import dev.atsushieno.uapmd.ClipType
import dev.atsushieno.uapmd.cmp.TempoMap
import dev.atsushieno.uapmd.cmp.UapmdHost
import dev.atsushieno.uapmd.cmp.pickAudioFileToOpen
import dev.atsushieno.uapmd.cmp.pickMidiFileToOpen
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.abs

/** kotlin.text has no common String.format, so round and splice manually. */
internal fun fixed(value: Double, decimals: Int): String {
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
/*
 * uapmd-app computes the legend width from the row-1 buttons rather than fixing
 * it (TimelineEditor.cpp:634):
 *     pad + clips + gap + graph + gap + slider(iconW * 1.5) + gap + mute + gap + solo + pad
 * The same sum here, plus the track label and the editable dB readout, which
 * ImGui draws inside the slider and Compose cannot. Pinning this at 150dp is
 * what pushed Freeze / Add Plugin / the More menu out of the visible area.
 */
private val LegendPad = 4.dp
private val LegendGap = 3.dp
private val TrackLabelWidth = 26.dp
private val GainReadoutWidth = 40.dp
private val GainSliderWidth = IconButtonSize * 1.5f

/**
 * The master track's slider is the project's total volume, and its row carries
 * no Mute or Solo, so it reclaims their width rather than staying as narrow as a
 * track's. Same legend width as every other row.
 */
private val MasterVolumeSliderWidth = GainSliderWidth + IconButtonSize * 2 + LegendGap * 2
private val LegendWidth =
    LegendPad * 2 + TrackLabelWidth + IconButtonSize * 4 + GainSliderWidth +
        GainReadoutWidth + LegendGap * 6
private const val NarrowWidthThreshold = 620
/**
 * Row height. Narrow screens wrap the legend controls onto a third line, so the
 * row has to grow — and the lanes must use the *same* height or the legend and
 * lane columns drift apart as you scroll.
 */
private val TrackHeightWide = 84.dp
private val TrackHeightNarrow = 92.dp
private val RulerHeight = 22.dp
private val NavigatorHeight = 26.dp


/** How close to a clip's right edge a drag counts as a resize. */
private const val ResizeGripPx = 6f

/*
 * Zoom limits and law. uapmd-app clamps the timeline scale to
 * [kMinSafeTimelineScale, kMaxTimelineScale] and zooms exponentially —
 * `scale * 2^(wheel * kZoomWheelSensitivity)` (TimelineNavigator.cpp:147) — so a
 * step is a constant *ratio* rather than a constant number of pixels. Our unit is
 * pixels-per-second rather than its scale factor, but the law is the same one, and
 * the bounds are the zoom slider's so the slider and the navigator cannot disagree.
 */
private const val MinPixelsPerSecond = 8f
private const val MaxPixelsPerSecond = 240f
private const val ZoomWheelSensitivity = 0.2f

/**
 * Per-pixel sensitivity for the drag equivalent of the wheel: a touch screen has
 * no wheel, so a vertical drag over the navigator takes its place. ~69px per
 * doubling, i.e. one short swipe covers a useful range without overshooting.
 */
private const val ZoomDragSensitivity = 0.0145f

/** uapmd-app's `std::pow(2.0f, delta * sensitivity)`, with the caller scaling delta. */
private fun zoomFactor(steps: Float): Float = 2f.pow(steps)

/** Which axis a navigator drag committed to; see the drag handler for why. */
private enum class DragAxis { Undecided, Scroll, Zoom }

/** Mirrors UAPMD_MASTER_TRACK_INDEX / ProjectAddressBook.MASTER_TRACK_INDEX. */
internal const val MasterTrackIndex = Int.MIN_VALUE

/** Gain slider range in dB; the bottom of the range is treated as silence. */
private const val MinGainDb = -60f
private const val MaxGainDb = 6f

private fun linearToDb(linear: Double): Double =
    if (linear <= 0.0) MinGainDb.toDouble()
    else (20.0 * kotlin.math.log10(linear)).coerceIn(MinGainDb.toDouble(), MaxGainDb.toDouble())

private fun dbToLinear(db: Double): Double =
    if (db <= MinGainDb) 0.0 else kotlin.math.exp(db / 20.0 * kotlin.math.ln(10.0))

/**
 * The navigator's position control — a whole-song overview with the visible
 * window drawn on it, matching uapmd-app's `renderTimelineNavigator`. Clicking
 * or dragging scrolls the lanes: uapmd-app moves the view with
 * `Timeline::SetStartTimestamp` (`TimelineNavigator.cpp:141`), which for a
 * scrolling Compose column is the horizontal scroll offset.
 *
 * Zooming lives here too. uapmd-app zooms with the wheel over this bar
 * (`TimelineNavigator.cpp:145-151`); a touch screen has no wheel, so a *vertical*
 * drag does the same job, up to zoom in. Both go through [zoomBy] and so share
 * uapmd-app's exponential law and the zoom slider's bounds.
 *
 * Which axis a drag drives follows uapmd-app's own rule: it scrolls only when the
 * press began inside the visible-window rectangle (`draggingRegion`, :129-133), so
 * a drag starting on the bar's empty part zooms without yanking the view sideways.
 */
@Composable
private fun NavigatorBar(
    host: UapmdHost,
    contentSeconds: Double,
    pixelsPerSecond: Float,
    onZoom: (Float) -> Unit,
    hScroll: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier
) {
    val c = editorPalette
    val sampleRate = (host.model.sampleRate.takeIf { it > 0 } ?: 48000).toDouble()
    val scope = rememberCoroutineScope()
    val lanes = remember(host.masterClips, host.trackClips) {
        listOf(host.masterClips) + host.trackClips
    }

    fun scrollTo(fraction: Float) {
        val max = hScroll.maxValue
        if (max <= 0) return
        // Centre the click, as dragging the navigator's window does.
        val viewport = (hScroll.viewportSize).coerceAtLeast(1)
        val target = (fraction * (max + viewport) - viewport / 2f).coerceIn(0f, max.toFloat())
        scope.launch { hScroll.scrollTo(target.toInt()) }
    }

    /** The fraction of the bar the visible window currently covers: [x0, x1]. */
    fun windowFraction(): ClosedFloatingPointRange<Float> {
        val total = (hScroll.maxValue + hScroll.viewportSize).toFloat()
        if (total <= 0f || hScroll.viewportSize <= 0) return 0f..1f
        val x0 = hScroll.value / total
        return x0..(x0 + hScroll.viewportSize / total)
    }

    // Keyed on Unit, deliberately. Keying a pointerInput on `pixelsPerSecond`
    // restarts the gesture detector the instant a drag changes the zoom, cancelling
    // the very gesture doing the zooming — so a drag could never travel further than
    // one frame's worth before it was torn down. The handlers below read the scroll
    // state live and take the callback through `rememberUpdatedState`, so they never
    // need re-keying.
    val zoom by rememberUpdatedState(onZoom)

    Box(
        modifier.height(NavigatorHeight).background(c.navigatorBackground)
            .pointerInput(Unit) {
                detectTapGestures { offset -> scrollTo(offset.x / size.width.toFloat()) }
            }
            .pointerInput(Unit) {
                // Whether this gesture *may* scroll is decided once, at the press,
                // from where it landed — exactly as uapmd-app latches `draggingRegion`.
                var startedInWindow = false
                var axis = DragAxis.Undecided
                var travelled = Offset.Zero
                detectDragGestures(
                    onDragStart = { offset ->
                        startedInWindow = (offset.x / size.width.toFloat()) in windowFraction()
                        axis = DragAxis.Undecided
                        travelled = Offset.Zero
                    },
                    onDragEnd = { axis = DragAxis.Undecided },
                    onDragCancel = { axis = DragAxis.Undecided }
                ) { change, dragAmount ->
                    change.consume()
                    travelled += dragAmount
                    if (axis == DragAxis.Undecided) {
                        // A drag meant to zoom always carries a pixel or two of x,
                        // and the scroll branch turns any x at all into a jump of the
                        // whole visible region — it centres on the finger. Below the
                        // slop the two intentions are indistinguishable, so commit to
                        // one axis once the gesture has moved far enough to say which,
                        // and never switch mid-gesture.
                        val dx = abs(travelled.x)
                        val dy = abs(travelled.y)
                        if (max(dx, dy) < viewConfiguration.touchSlop) return@detectDragGestures
                        axis = if (dy > dx) DragAxis.Zoom else DragAxis.Scroll
                    }
                    when (axis) {
                        // Up is in, matching a wheel pushed forward.
                        DragAxis.Zoom ->
                            if (dragAmount.y != 0f)
                                zoom(zoomFactor(-dragAmount.y * ZoomDragSensitivity))
                        DragAxis.Scroll ->
                            if (startedInWindow) scrollTo(change.position.x / size.width.toFloat())
                        DragAxis.Undecided -> Unit
                    }
                }
            }
            .pointerInput(Unit) {
                // Desktop keeps the wheel uapmd-app uses. scrollDelta is positive
                // downwards, where ImGui's MouseWheel is positive upwards.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val dy = event.changes.fold(0f) { acc, c -> acc + c.scrollDelta.y }
                        if (dy == 0f) continue
                        zoom(zoomFactor(-dy * ZoomWheelSensitivity))
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (contentSeconds <= 0.0) return@Canvas
            val laneHeight = size.height / lanes.size.coerceAtLeast(1)
            lanes.forEachIndexed { row, clips ->
                clips.forEach { clip ->
                    val x = (clip.positionSamples / sampleRate / contentSeconds).toFloat() * size.width
                    val w = ((clip.durationSamples / sampleRate / contentSeconds).toFloat() * size.width)
                        .coerceAtLeast(1f)
                    drawRect(
                        if (clip.clipType == ClipType.Midi) c.midiClip else c.audioClip,
                        Offset(x, row * laneHeight + 1f),
                        Size(w, (laneHeight - 2f).coerceAtLeast(1f))
                    )
                }
            }
            // The window currently visible in the lanes.
            val total = (hScroll.maxValue + hScroll.viewportSize).toFloat()
            if (total > 0f && hScroll.viewportSize > 0) {
                val x = hScroll.value / total * size.width
                val w = (hScroll.viewportSize / total * size.width).coerceAtLeast(2f)
                drawRect(c.navigatorWindow, Offset(x, 0f), Size(w, size.height))
                drawRect(
                    c.clipBorder, Offset(x, 0f), Size(w, size.height),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1f)
                )
            }
            val px = (host.playheadSeconds / contentSeconds).toFloat() * size.width
            drawLine(c.playhead, Offset(px, 0f), Offset(px, size.height), 1.5f)
        }
    }
}

/**
 * The main content: a track legend on the left and a time-ruled lane per track
 * on the right, as in uapmd-app. The ruler shows either seconds or beats, the
 * latter placed through `host.tempoMap` so a tempo change moves the beat lines.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Timeline(
    host: UapmdHost,
    windows: FloatingWindowManager,
    modifier: Modifier = Modifier
) {
    var pixelsPerSecond by remember { mutableStateOf(40f) }
    // The second of the two things a zoom has to do: the lane is `contentSeconds *
    // pixelsPerSecond` wide, so changing the zoom alone leaves the scroll offset
    // pointing at a different moment in the song and the view slides sideways on
    // every step. Remember where the middle of the viewport was, in seconds, and put
    // it back once the lane has been remeasured at the new scale.
    var recentreSeconds by remember { mutableStateOf<Float?>(null) }
    var timeUnit by remember { mutableStateOf(TimeUnit.Seconds) }
    val tempo = host.timeline?.tempo ?: 120.0
    val beatsPerSecond = tempo / 60.0
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val density = LocalDensity.current.density

    /** Pixels per second as the scroll state counts them, rather than in dp. */
    fun scrollPxPerSecond(scale: Float) = scale * density

    fun zoomBy(factor: Float) {
        val previous = pixelsPerSecond
        val next = (previous * factor).coerceIn(MinPixelsPerSecond, MaxPixelsPerSecond)
        if (next == previous) return
        val viewport = hScroll.viewportSize
        if (viewport > 0)
            recentreSeconds = (hScroll.value + viewport / 2f) / scrollPxPerSecond(previous)
        pixelsPerSecond = next
    }

    // Show at least a minute, or the content plus a margin.
    val contentSeconds = remember(host.trackClips, host.model.sampleRate) {
        val sr = host.model.sampleRate.takeIf { it > 0 } ?: 48000
        val last = host.trackClips.flatten().maxOfOrNull {
            (it.positionSamples + it.durationSamples).toDouble() / sr
        } ?: 0.0
        maxOf(60.0, last + 10.0)
    }

    LaunchedEffect(pixelsPerSecond, hScroll.maxValue) {
        val centre = recentreSeconds ?: return@LaunchedEffect
        val viewport = hScroll.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        // This effect can run once before the lane has been remeasured, when
        // `maxValue` still describes the old scale; scrolling then would clamp
        // against the wrong extent and, because the value would have been consumed,
        // never be corrected. Wait for the extent that matches the new scale.
        val expectedContentPx = contentSeconds.toFloat() * scrollPxPerSecond(pixelsPerSecond)
        if (abs((hScroll.maxValue + viewport) - expectedContentPx) > 2f) return@LaunchedEffect
        val target = (centre * scrollPxPerSecond(pixelsPerSecond) - viewport / 2f)
            .coerceIn(0f, hScroll.maxValue.toFloat())
        recentreSeconds = null
        hScroll.scrollTo(target.roundToInt())
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
    val isNarrow = maxWidth.value < NarrowWidthThreshold
    val legendWidth = LegendWidth
    val trackHeight = if (isNarrow) TrackHeightNarrow else TrackHeightWide

    Column(Modifier.fillMaxSize()) {
        // ── Navigator row ────────────────────────────────────────────────────
        // A FlowRow, like the toolbar: at a phone width the controls alone
        // exceed the row, which squeezed the readout to one character per line.
        FlowRow(
            Modifier.fillMaxWidth().padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = {
                timeUnit = if (timeUnit == TimeUnit.Seconds) TimeUnit.Beats else TimeUnit.Seconds
            }) { Text(if (timeUnit == TimeUnit.Seconds) "View: Seconds" else "View: Beats") }
            Text("Zoom", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = pixelsPerSecond,
                onValueChange = { zoomBy(it / pixelsPerSecond) },
                valueRange = MinPixelsPerSecond..MaxPixelsPerSecond,
                modifier = Modifier.width(140.dp)
            )
            Text(
                if (timeUnit == TimeUnit.Seconds)
                    "${fixed(contentSeconds, 1)}s · playhead ${fixed(host.playheadSeconds, 2)}s"
                else
                    "${fixed(host.tempoMap.secondsToBeats(contentSeconds), 1)} beats · playhead " +
                        "${fixed(host.tempoMap.secondsToBeats(host.playheadSeconds), 2)} · " +
                        (if (host.tempoMap.hasTempoData) "tempo map" else "${fixed(tempo, 1)} BPM") +
                        " ${host.timeline?.timeSignatureNumerator ?: 4}/${host.timeline?.timeSignatureDenominator ?: 4}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        // uapmd-app draws the navigator across the lane area, starting at the
        // legend's right edge (TimelineEditor.cpp:990).
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            Spacer(Modifier.width(legendWidth))
            NavigatorBar(
                host, contentSeconds, pixelsPerSecond,
                onZoom = ::zoomBy,
                hScroll = hScroll,
                modifier = Modifier.weight(1f)
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
                Ruler(contentSeconds, pixelsPerSecond, laneWidth, timeUnit, host.tempoMap,
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
    tempoMap: TempoMap,
    fallbackBeatsPerBar: Int
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
            // Each beat's x comes from the tempo map, so a tempo change moves the
            // lines rather than the whole ruler sharing one spacing. Bars are
            // counted from the signature in force at each beat, so a meter change
            // restarts the bar count from that point.
            val totalBeats = tempoMap.secondsToBeats(contentSeconds).toInt()
            var beatInBar = 0
            var currentSignatureStart = 0.0
            for (beat in 0..totalBeats) {
                val beatD = beat.toDouble()
                val (numerator, _) = tempoMap.signatureAtBeat(beatD)
                val bars = if (tempoMap.signatures.isEmpty()) fallbackBeatsPerBar.coerceAtLeast(1)
                           else numerator.coerceAtLeast(1)
                val signatureStart =
                    tempoMap.signatures.lastOrNull { beatD >= it.startBeat }?.startBeat ?: 0.0
                if (signatureStart != currentSignatureStart) {
                    currentSignatureStart = signatureStart
                    beatInBar = 0
                }
                val isBar = beatInBar % bars == 0
                val x = (tempoMap.beatsToSeconds(beatD) * pixelsPerSecond).toFloat()
                drawLine(
                    if (isBar) Color.LightGray else Color.Gray,
                    Offset(x, if (isBar) 0f else size.height * 0.4f),
                    Offset(x, size.height),
                    if (isBar) 1.5f else 1f
                )
                beatInBar++
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
    val c = editorPalette
    val clips = host.trackClips.getOrNull(trackIndex).orEmpty()
    val sampleRate = (host.model.sampleRate.takeIf { it > 0 } ?: 48000).toDouble()
    var draggingClipId by remember { mutableStateOf<Int?>(null) }
    var resizingClipId by remember { mutableStateOf<Int?>(null) }
    var dragSeconds by remember { mutableStateOf(0.0) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // uapmd-app's main timeline lanes ARE the sequence editor's unified timeline
    // (`TimelineEditor.cpp:1016` renderUnifiedTimeline; the per-track render at
    // :1572 is only a vtable stub), so its lane context menus belong here.
    var addMenuOpen by remember { mutableStateOf(false) }
    var rangeMenuOpen by remember { mutableStateOf(false) }
    // uapmd-app latches which clip the context menu belongs to (`contextMenuClipId`,
    // SequenceEditor.cpp:623) rather than hanging a menu off each clip's label.
    var contextMenuClipId by remember { mutableStateOf<Int?>(null) }
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
            .background(c.laneBackground)
            .pointerInput(clips, pixelsPerSecond) {
                fun openMenuAt(offset: Offset) {
                    val seconds = (offset.x / pixelsPerSecond).toDouble().coerceAtLeast(0.0)
                    clickedSeconds = seconds
                    menuAnchor = with(density) { DpOffset(offset.x.toDp(), 0.dp) }
                    // On a clip it is that clip's menu, on empty lane the add menu —
                    // the two popups uapmd-app opens from the same right-click.
                    val hit = clipAt(seconds)
                    if (hit != null) contextMenuClipId = hit.clipId else addMenuOpen = true
                }
                detectTapGestures(
                    onDoubleTap = { openMenuAt(it) },
                    // Touch has no right button; a long press is its context click.
                    onLongPress = { openMenuAt(it) }
                )
            }
            .pointerInput(clips, pixelsPerSecond) {
                // detectTapGestures fires for any button, so the right-click uapmd-app
                // uses needs its own handler to avoid a left click opening the menu.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Press) continue
                        if (!event.buttons.isSecondaryPressed) continue
                        val position = event.changes.firstOrNull()?.position ?: continue
                        val seconds = (position.x / pixelsPerSecond).toDouble().coerceAtLeast(0.0)
                        clickedSeconds = seconds
                        menuAnchor = with(density) { DpOffset(position.x.toDp(), 0.dp) }
                        val hit = clipAt(seconds)
                        if (hit != null) contextMenuClipId = hit.clipId else addMenuOpen = true
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            // Direct manipulation: drag a clip along the lane to move it. The
            // commit goes through setClipAnchor, so it lands in history as one step.
            .pointerInput(clips, pixelsPerSecond) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val seconds = (offset.x / pixelsPerSecond).toDouble()
                        val hit = clipAt(seconds)
                        // Within the grip of a clip's right edge the drag resizes
                        // it instead of moving it.
                        val grip = ResizeGripPx / pixelsPerSecond
                        resizingClipId = hit?.takeIf { c ->
                            val end = (c.positionSamples + c.durationSamples) / sampleRate
                            seconds >= end - grip
                        }?.clipId
                        draggingClipId = if (resizingClipId == null) hit?.clipId else null
                        dragSeconds = 0.0
                        // Only empty space starts a range selection; a drag that
                        // began on a clip is that clip's move or resize gesture.
                        if (draggingClipId == null && resizingClipId == null) {
                            rangeAnchorSeconds = seconds.coerceAtLeast(0.0)
                            rangeCurrentSeconds = seconds.coerceAtLeast(0.0)
                        }
                    },
                    onDragEnd = {
                        val resizeId = resizingClipId
                        if (resizeId != null && dragSeconds != 0.0) {
                            val clip = clips.firstOrNull { it.clipId == resizeId }
                            if (clip != null) {
                                val samples = clip.durationSamples + (dragSeconds * sampleRate).toLong()
                                // A clip shorter than a single frame is not a clip.
                                host.resizeClip(trackIndex, resizeId, samples.coerceAtLeast(1L))
                            }
                        }
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
                        resizingClipId = null
                        dragSeconds = 0.0
                    },
                    onDragCancel = {
                        draggingClipId = null; resizingClipId = null
                        dragSeconds = 0.0; rangeAnchorSeconds = null
                    }
                ) { change, delta ->
                    change.consume()
                    if (draggingClipId != null || resizingClipId != null)
                        dragSeconds += delta.x / pixelsPerSecond
                    else if (rangeAnchorSeconds != null)
                        rangeCurrentSeconds = (change.position.x / pixelsPerSecond).toDouble().coerceAtLeast(0.0)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            clips.forEach { clip ->
                val shift = if (clip.clipId == draggingClipId) dragSeconds else 0.0
                val stretch = if (clip.clipId == resizingClipId) dragSeconds else 0.0
                val x = ((clip.positionSamples / sampleRate + shift) * pixelsPerSecond).toFloat()
                val w = ((clip.durationSamples / sampleRate + stretch) * pixelsPerSecond)
                    .toFloat().coerceAtLeast(2f)
                val isMidi = clip.clipType == ClipType.Midi
                val base = if (isMidi) c.midiClip else c.audioClip
                drawRect(
                    color = if (clip.muted) base.copy(alpha = 0.35f) else base,
                    topLeft = Offset(x, 4f),
                    size = Size(w, size.height - 8f)
                )
                drawRect(
                    color = if (clip.clipId == draggingClipId) c.playhead else c.clipBorder,
                    topLeft = Offset(x, 4f),
                    size = Size(w, size.height - 8f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(if (clip.clipId == draggingClipId) 2f else 1f)
                )
                if (isMidi) drawMidiNotes(host, trackIndex, clip, x, w, pixelsPerSecond, c.note)
            }
            rangeAnchorSeconds?.let { anchor ->
                val a = minOf(anchor, rangeCurrentSeconds).toFloat() * pixelsPerSecond
                val b = maxOf(anchor, rangeCurrentSeconds).toFloat() * pixelsPerSecond
                drawRect(c.rangeFill, Offset(a, 0f), Size(b - a, size.height))
            }
            val px = (host.playheadSeconds * pixelsPerSecond).toFloat()
            drawLine(c.playhead, Offset(px, 0f), Offset(px, size.height), 2f)
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

        clips.forEach { clip ->
            val shift = if (clip.clipId == draggingClipId) dragSeconds else 0.0
            val x = with(density) {
                ((clip.positionSamples / sampleRate + shift) * pixelsPerSecond).toFloat().toDp()
            }
            val isMidi = clip.clipType == ClipType.Midi
            // offset, not padding: padding requires a non-negative value and throws
            // otherwise, and `x` follows the clip's position, which can be negative —
            // a clip anchored before zero, or dragged relative to a marker. On the
            // desktop that exception surfaced as a Java error dialog and a window
            // that never drew again. offset takes negatives and simply places the
            // label off to the left, with the clip it belongs to.
            Box(Modifier.offset(x = x + 3.dp, y = 5.dp)) {
                Text(
                    clip.name.ifEmpty { if (isMidi) "MIDI clip" else "audio clip" },
                    Modifier.clickable {
                        if (isMidi) host.selectedMidiClip = trackIndex to clip.clipId
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        ClipContextMenu(
            host = host,
            windows = windows,
            trackIndex = trackIndex,
            clip = clips.firstOrNull { it.clipId == contextMenuClipId },
            addAtSeconds = clickedSeconds,
            anchor = menuAnchor,
            expanded = contextMenuClipId != null,
            onDismiss = { contextMenuClipId = null }
        )
    }
}

/**
 * A clip's context menu, following `SequenceEditor.cpp:622-698` item for item.
 *
 * Two things about it are uapmd-app's shape rather than the obvious Compose one.
 * Every action is always listed and merely *disabled* when it does not apply, so
 * the menu does not change height depending on what was clicked; and the "add a
 * clip here" actions repeat at the bottom, so a right-click on a clip can still add
 * one beside it. The master track has no add group, as it has no audio clips.
 */
@Composable
private fun ClipContextMenu(
    host: UapmdHost,
    windows: FloatingWindowManager,
    trackIndex: Int,
    clip: ClipData?,
    addAtSeconds: Double,
    anchor: DpOffset,
    expanded: Boolean,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val sampleRate = (host.model.sampleRate.takeIf { it > 0 } ?: 48000).toDouble()
    val isMaster = trackIndex == MasterTrackIndex
    val isMidi = clip?.clipType == ClipType.Midi
    val title = clip?.name?.ifEmpty { if (isMidi) "MIDI clip" else "audio clip" } ?: "clip"

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = anchor) {
        if (clip == null) {
            DropdownMenuItem(text = { Text("Clip not available.") }, enabled = false, onClick = {})
            return@DropdownMenu
        }
        val enabled = host.isClipEnabled(trackIndex, clip.clipId)

        DropdownMenuItem(
            text = { Text("Show Dump List") },
            enabled = isMidi,
            onClick = {
                onDismiss()
                windows.open(
                    "dump:$trackIndex:${clip.clipId}", "$title - Events", DpSize(520.dp, 400.dp)
                ) { MidiDumpWindow(host, trackIndex, clip.clipId) }
            }
        )
        DropdownMenuItem(
            text = { Text("Edit Audio Events") },
            enabled = !isMaster && !isMidi,
            onClick = {
                onDismiss()
                windows.open(
                    "events:$trackIndex:${clip.clipId}", "$title - Markers & Warps", DpSize(560.dp, 420.dp)
                ) { AudioEventListEditor(host, trackIndex, clip.clipId) }
            }
        )
        DropdownMenuItem(
            text = { Text("Open Piano Roll") },
            enabled = isMidi,
            onClick = {
                onDismiss()
                host.selectedMidiClip = trackIndex to clip.clipId
                windows.open(
                    "pianoroll:$trackIndex:${clip.clipId}", "$title - Piano Roll", DpSize(640.dp, 420.dp)
                ) { PianoRollEditor(host, trackIndex, clip.clipId) }
            }
        )
        // Ours, not uapmd-app's — see the note on ClipProperties itself.
        DropdownMenuItem(text = { Text("Clip Properties…") }, onClick = {
            onDismiss()
            windows.open(
                "clipprops:$trackIndex:${clip.clipId}", "$title - Properties", DpSize(520.dp, 320.dp)
            ) { ClipProperties(host, trackIndex, clip.clipId) }
        })
        DropdownMenuItem(text = { Text("Delete") }, onClick = {
            onDismiss()
            listOf("pianoroll", "dump", "events", "clipprops").forEach {
                windows.close("$it:$trackIndex:${clip.clipId}")
            }
            host.removeClip(trackIndex, clip.clipId)
        })
        DropdownMenuItem(text = { Text(if (enabled) "Disable Clip" else "Enable Clip") }, onClick = {
            onDismiss()
            host.setClipEnabled(trackIndex, clip.clipId, !enabled)
        })

        if (!isMaster) {
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Add an Empty MIDI2 Clip Here") }, onClick = {
                onDismiss()
                host.addEmptyMidiClip(trackIndex, (addAtSeconds * sampleRate).toLong())
            })
            DropdownMenuItem(text = { Text("Add Empty Audio Clip Here") }, onClick = {
                onDismiss()
                host.addEmptyAudioClip(trackIndex, addAtSeconds)
            })
            DropdownMenuItem(text = { Text("Create Audio Clip From File Here…") }, onClick = {
                onDismiss()
                scope.launch {
                    pickAudioFileToOpen()?.let { host.importAudioClip(trackIndex, it, addAtSeconds) }
                }
            })
            DropdownMenuItem(text = { Text("Import SMF Here…") }, onClick = {
                onDismiss()
                scope.launch {
                    pickMidiFileToOpen()?.let { host.importMidiClip(trackIndex, it, addAtSeconds) }
                }
            })
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
                scope.launch { pickAudioFileToOpen()?.let { host.importAudioClip(trackIndex, it, seconds) } }
            })
        }
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Add a MIDI Clip from File…") }, onClick = {
            onDismiss()
            scope.launch { pickMidiFileToOpen()?.let { host.importMidiClip(trackIndex, it, seconds) } }
        })
        if (!isMaster) {
            DropdownMenuItem(text = { Text("Add MIDI2 Clip from File…") }, onClick = {
                onDismiss()
                scope.launch { pickMidiFileToOpen()?.let { host.importMidiClip(trackIndex, it, seconds) } }
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
,
    noteColor: Color) {
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
        drawRect(noteColor, Offset(nx, ny), Size(nw, 2.5f))
    }
}

/**
 * The legend gain slider, plus an editable dB readout.
 *
 * uapmd-app draws no value on the slider — its format string is `""`, or `"Mute"`
 * at the bottom (`TimelineEditor.cpp:1255`) — and puts the dB in a tooltip. Its
 * slider is `iconButtonWidth * 1.5f`, as narrow as this one, but ImGui's
 * SliderFloat takes a ctrl+click to type an exact value. Compose's Slider has no
 * such affordance, and at 45dp a drag gives only a few dozen usable steps, so the
 * readout beside it is a text field: tap it and type the dB. That is the one
 * deliberate addition here, and it exists because the widget is weaker, not
 * because the layout should differ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GainSlider(
    gainDb: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    sliderWidth: Dp = GainSliderWidth
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
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
                modifier = Modifier.width(sliderWidth)
            )
        }
        var editing by remember { mutableStateOf<String?>(null) }
        val shown = if (gainDb <= MinGainDb) "Mute" else fixed(gainDb.toDouble(), 1)
        if (editing == null) {
            Text(
                shown,
                Modifier.width(GainReadoutWidth).clickable { editing = fixed(gainDb.toDouble(), 1) },
                style = MaterialTheme.typography.labelSmall
            )
        } else {
            BasicTextField(
                value = editing.orEmpty(),
                onValueChange = { editing = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelSmall.copy(color = LocalContentColor.current),
                cursorBrush = SolidColor(LocalContentColor.current),
                modifier = Modifier.width(GainReadoutWidth).onFocusChanged { state ->
                    if (!state.isFocused) {
                        editing?.toFloatOrNull()
                            ?.coerceIn(MinGainDb, MaxGainDb)
                            ?.let { onChange(it); onFinished() }
                        editing = null
                    }
                }
            )
        }
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

/**
 * snowflake: track freeze. Drawn as a vertical stem with barbed arms rather than
 * bare crossing spokes — at 16dp on a round button, spokes alone read as a
 * starburst rather than as a snowflake.
 */
@Composable
private fun FreezeIcon(tint: Color) = Canvas(Modifier.size(LegendIconSize)) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val r = size.minDimension * 0.42f
    val barb = r * 0.34f
    // Vertical stem plus the two diagonals at +/-60 degrees from it.
    listOf(-1.0471975, 0.0, 1.0471975).forEach { a ->
        val ux = kotlin.math.sin(a).toFloat()
        val uy = kotlin.math.cos(a).toFloat()
        val tip = Offset(c.x + ux * r, c.y + uy * r)
        val opp = Offset(c.x - ux * r, c.y - uy * r)
        drawLine(tint, opp, tip, 1.2f)
        // A V at each end is what separates a snowflake from an asterisk.
        listOf(tip, opp).forEach { end ->
            val toCentre = Offset(c.x - end.x, c.y - end.y)
            val len = kotlin.math.sqrt(toCentre.x * toCentre.x + toCentre.y * toCentre.y)
            if (len <= 0f) return@forEach
            val nx = toCentre.x / len
            val ny = toCentre.y / len
            val px = -ny * barb * 0.7f
            val py = nx * barb * 0.7f
            val base = Offset(end.x + nx * barb, end.y + ny * barb)
            drawLine(tint, end, Offset(base.x + px, base.y + py), 1.0f)
            drawLine(tint, end, Offset(base.x - px, base.y - py), 1.0f)
        }
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
    enabled: Boolean = true,
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
            enabled = enabled,
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
    val c = editorPalette
    val instances = host.trackInstances.getOrNull(trackIndex).orEmpty()
    var pluginMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var clipsMenu by remember { mutableStateOf(false) }
    // uapmd-app disables the graph button and the plugin popup while a freeze
    // render is in flight (TimelineEditor.cpp:1217-1240).
    val trackBusy = host.isTrackBusy(trackIndex)
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LegendGap), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("T$trackIndex", style = MaterialTheme.typography.labelMedium)

            // Clips popup, as uapmd-app's first legend button.
            Box {
                LegendIconButton("Edit clips", onClick = { clipsMenu = true }) { ClipsIcon(it) }
                DropdownMenu(expanded = clipsMenu, onDismissRequest = { clipsMenu = false }) {
                    // First item in uapmd-app's Clips popup (TimelineEditor.cpp:1449);
                    // the per-lane context actions live in that window.
                    DropdownMenuItem(text = { Text("Edit Clips…") }, onClick = {
                        clipsMenu = false
                        windows.open(
                            "sequence:$trackIndex",
                            "Track $trackIndex - Clips",
                            DpSize(840.dp, 360.dp)
                        ) { SequenceEditor(host, windows, trackIndex) }
                    })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Add an Empty MIDI2 Clip") }, onClick = {
                        clipsMenu = false
                        host.addEmptyMidiClip(trackIndex)
                    })
                    DropdownMenuItem(text = { Text("Add a MIDI Clip from File…") }, onClick = {
                        clipsMenu = false
                        scope.launch { pickMidiFileToOpen()?.let { host.importMidiClip(trackIndex, it) } }
                    })
                    DropdownMenuItem(text = { Text("Add MIDI2 Clip from File…") }, onClick = {
                        clipsMenu = false
                        // .midi2 goes through the same importer; the engine
                        // picks the reader from the file itself.
                        scope.launch { pickMidiFileToOpen()?.let { host.importMidiClip(trackIndex, it) } }
                    })
                    DropdownMenuItem(text = { Text("Add Empty Audio Clip") }, onClick = {
                        clipsMenu = false
                        // uapmd-app creates an empty audio clip the user then
                        // points at a file; the same two steps, explicitly.
                        emptyAudioNotice = "Create the clip from a file, then use Clip Properties ▸ Change file."
                    })
                    DropdownMenuItem(text = { Text("Create Audio Clip From File…") }, onClick = {
                        clipsMenu = false
                        scope.launch { pickAudioFileToOpen()?.let { host.importAudioClip(trackIndex, it) } }
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
                enabled = !trackBusy,
                onClick = {
                    windows.open("graph:$trackIndex", "Track $trackIndex Graph", DpSize(620.dp, 440.dp)) {
                        TrackGraphEditor(host, trackIndex)
                    }
                }
            ) { GraphIcon(it) }

            if (host.trackExists(trackIndex)) {
                // Read the value from the track, write it through ProjectCommands.
                // uapmd-app's slider is in dB and wraps the drag in an undo
                // gesture, so a drag is one history entry rather than dozens.
                // Keyed on the project revision so a load re-reads the new value.
                var gainDb by remember(trackIndex, host.projectRevision) {
                    mutableStateOf(linearToDb(host.trackGain(trackIndex)).toFloat())
                }
                GainSlider(
                    gainDb = gainDb,
                    onChange = { gainDb = it; host.setTrackGain(trackIndex, dbToLinear(it.toDouble())) },
                    onFinished = { host.endTrackGainGesture() }
                )
                val muted = host.trackMuted(trackIndex)
                val solo = host.trackSolo(trackIndex)
                Button(
                    onClick = { host.setTrackMuted(trackIndex, !muted) },
                    contentPadding = TightPadding,
                    modifier = Modifier.size(IconButtonSize),
                    colors = if (muted) ButtonDefaults.buttonColors(containerColor = c.muted)
                    else ButtonDefaults.buttonColors()
                ) { Text("M", style = MaterialTheme.typography.labelSmall) }
                Button(
                    onClick = { host.setTrackSolo(trackIndex, !solo, additive = additiveSolo) },
                    contentPadding = TightPadding,
                    modifier = Modifier.size(IconButtonSize),
                    colors = if (solo) ButtonDefaults.buttonColors(containerColor = c.solo)
                    else ButtonDefaults.buttonColors()
                ) { Text("S", style = MaterialTheme.typography.labelSmall) }
            }
        }

        // Row 2, as uapmd-app orders it: Freeze switch, then the plugin context
        // button labelled with the first instance's name (or "Add Plugin" when
        // the track is empty), then the More menu.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            val frozen = host.trackFreezePolicy(trackIndex) == FreezePolicy.On
            val freezeState = host.trackFreezeState(trackIndex)
            LegendIconButton(
                if (frozen) "Track freezing: On (click to unfreeze)"
                else "Track freezing: Off (click to render and freeze)",
                enabled = !trackBusy,
                onClick = { host.setTrackFreezePolicyEnabled(trackIndex, !frozen) }
            ) { tint ->
                FreezeIcon(
                    when (freezeState) {
                        FreezeRuntimeState.Rendering -> c.rendering
                        FreezeRuntimeState.Frozen -> c.frozen
                        FreezeRuntimeState.Error -> c.muted
                        FreezeRuntimeState.Live -> if (frozen) c.frozen else tint
                    }
                )
            }

            Box(Modifier.weight(1f)) {
                Button(
                    onClick = { if (instances.isEmpty()) openSelectorForTrack() else pluginMenu = true },
                    contentPadding = TightPadding,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        instances.firstOrNull()?.displayName ?: "Add Plugin",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
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
                    if (host.trackExists(trackIndex)) {
                        val bypassed = host.trackBypassed(trackIndex)
                        DropdownMenuItem(
                            text = { Text(if (bypassed) "Enable Track Processing" else "Bypass Track Processing") },
                            onClick = { moreMenu = false; host.setTrackBypassed(trackIndex, !bypassed) }
                        )
                    }
                    // uapmd-app's misc popup is exactly Bypass + Delete Track
                    // (TimelineEditor.cpp:1550-1564). Freeze is the row-2 button,
                    // not a menu entry.
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

    Column(Modifier.height(trackHeight).fillMaxWidth().padding(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Master", style = MaterialTheme.typography.labelMedium)

            Box {
                LegendIconButton("Edit clips", onClick = { clipsMenu = true }) { ClipsIcon(it) }
                DropdownMenu(expanded = clipsMenu, onDismissRequest = { clipsMenu = false }) {
                    DropdownMenuItem(text = { Text("Edit Clips…") }, onClick = {
                        clipsMenu = false
                        windows.open(
                            "sequence:master",
                            "Master Track - Clips",
                            DpSize(840.dp, 360.dp)
                        ) { SequenceEditor(host, windows, MasterTrackIndex) }
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

            if (host.trackExists(MasterTrackIndex)) {
                var gainDb by remember(host.projectRevision) {
                    mutableStateOf(linearToDb(host.trackGain(MasterTrackIndex)).toFloat())
                }
                GainSlider(
                    gainDb = gainDb,
                    onChange = {
                        gainDb = it
                        host.setTrackGain(MasterTrackIndex, dbToLinear(it.toDouble()))
                    },
                    onFinished = { host.endTrackGainGesture() },
                    sliderWidth = MasterVolumeSliderWidth
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
                        instances.firstOrNull()?.let { it.displayName } ?: "Add Master Plugin",
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
    val c = editorPalette
    val sampleRate = (host.model.sampleRate.takeIf { it > 0 } ?: 48000).toDouble()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var addMenuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(DpOffset.Zero) }
    var clickedSeconds by remember { mutableStateOf(0.0) }
    Box(
        Modifier.height(trackHeight).width(laneWidth).background(c.masterLaneBackground)
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
                drawRect(c.midiClip, Offset(x, 4f), Size(w, size.height - 8f))
                drawRect(c.clipBorder, Offset(x, 4f), Size(w, size.height - 8f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
            }
            val px = (host.playheadSeconds * pixelsPerSecond).toFloat()
            drawLine(c.playhead, Offset(px, 0f), Offset(px, size.height), 2f)
        }
    }
}
