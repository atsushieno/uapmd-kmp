package dev.atsushieno.uapmd.cmp.nodegraph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.min
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// ---------------------------------------------------------------------------
// Public data model
// ---------------------------------------------------------------------------

enum class BusType { Audio, Event }

data class GraphPin(
    val id: Int,
    val label: String,
    val isInput: Boolean,
    val busType: BusType = BusType.Audio
)

data class GraphNode(
    val id: Int,
    val label: String,
    val inputs: List<GraphPin>,
    val outputs: List<GraphPin>
)

data class GraphLink(
    val id: Int,
    val sourcePinId: Int,
    val targetPinId: Int
)

// ---------------------------------------------------------------------------
// Layout constants (world units)
// ---------------------------------------------------------------------------

/*
 * A node is as wide as its content needs, never a fixed width. Labels are measured
 * in sp, so they scale with the viewer's density while world units do not: a fixed
 * width that looks right at 1x has the input and output labels of a row printed on
 * top of each other at 2.6x, which is what a phone-density render produced.
 */
private const val NODE_W_MIN = 180f
private const val LABEL_GAP = 16f
private const val COLUMN_GAP = 80f
private const val ROW_GAP = 30f
private const val TITLE_H = 30f
private const val PIN_ROW_H = 24f
private const val PIN_R = 5f
private const val CORNER_R = 6f
private const val PAD = 8f
private const val LINK_WIDTH = 2f
private const val GRID_STEP = 40f

private fun nodeHeight(node: GraphNode) =
    TITLE_H + PAD + maxOf(node.inputs.size, node.outputs.size) * PIN_ROW_H + PAD

private val TitleStyle = TextStyle(fontSize = 11.sp)
private val PinStyle = TextStyle(fontSize = 9.sp)

/** The width [node] needs for its title and for the widest input/output label pair. */
private fun measureNodeWidth(node: GraphNode, measurer: TextMeasurer): Float {
    val title = measurer.measure(node.label, TitleStyle).size.width + PAD * 2
    val rows = maxOf(node.inputs.size, node.outputs.size)
    var widest = 0f
    for (i in 0 until rows) {
        val left = node.inputs.getOrNull(i)
            ?.let { measurer.measure(it.label, PinStyle).size.width + PIN_R + 4f } ?: 0f
        val right = node.outputs.getOrNull(i)
            ?.let { measurer.measure(it.label, PinStyle).size.width + PIN_R + 4f } ?: 0f
        widest = maxOf(widest, left + right + LABEL_GAP)
    }
    return maxOf(NODE_W_MIN, title.toFloat(), widest)
}

private fun inputPinWorldPos(nodePos: Offset, index: Int) =
    Offset(nodePos.x, nodePos.y + TITLE_H + PAD + index * PIN_ROW_H + PIN_ROW_H / 2)

private fun outputPinWorldPos(nodePos: Offset, index: Int, width: Float) =
    Offset(nodePos.x + width, nodePos.y + TITLE_H + PAD + index * PIN_ROW_H + PIN_ROW_H / 2)

// ---------------------------------------------------------------------------
// Colours
// ---------------------------------------------------------------------------

/**
 * The graph editor paints on a Canvas, so its colours cannot come from Material's
 * component defaults. Dark values are the previous constants unchanged; the light
 * set keeps the same roles so the editor follows the toolbar's theme toggle
 * instead of staying black behind a light chrome.
 */
private data class NgPalette(
    val background: Color, val grid: Color, val nodeBg: Color,
    val nodeBorderNormal: Color, val nodeBorderSelected: Color,
    val titleDefault: Color, val titleEndpoint: Color,
    val titleText: Color, val bodyText: Color, val separator: Color,
    val pinAudio: Color, val pinEvent: Color, val pinOutline: Color,
    val linkAudio: Color, val linkEvent: Color, val linkDrag: Color,
)

private val NgDark = NgPalette(
    background = Color(0xFF1A1A1A), grid = Color(0xFF282828), nodeBg = Color(0xFF2C2C2C),
    nodeBorderNormal = Color(0xFF505050), nodeBorderSelected = Color(0xFF88AADD),
    titleDefault = Color(0xFF3A4A3A), titleEndpoint = Color(0xFF3A3A5A),
    titleText = Color(0xFFEEEEEE), bodyText = Color(0xFFBBBBBB), separator = Color(0xFF444444),
    pinAudio = Color(0xFF44AA88), pinEvent = Color(0xFFAA9944), pinOutline = Color(0xFF888888),
    linkAudio = Color(0xFF44AA88), linkEvent = Color(0xFFAA9944), linkDrag = Color(0xFFCCCCCC),
)

private val NgLight = NgPalette(
    background = Color(0xFFF2F2F5), grid = Color(0xFFDCDCE2), nodeBg = Color(0xFFFFFFFF),
    nodeBorderNormal = Color(0xFFB0B0B8), nodeBorderSelected = Color(0xFF3A6EA5),
    titleDefault = Color(0xFFD3E3D3), titleEndpoint = Color(0xFFD6D6EA),
    titleText = Color(0xFF1E1E1E), bodyText = Color(0xFF44444C), separator = Color(0xFFC9C9D0),
    pinAudio = Color(0xFF2E7D62), pinEvent = Color(0xFF8A6D1F), pinOutline = Color(0xFF6B6B73),
    linkAudio = Color(0xFF2E7D62), linkEvent = Color(0xFF8A6D1F), linkDrag = Color(0xFF55555C),
)

private val ngPalette: NgPalette
    @Composable @ReadOnlyComposable
    get() = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) NgLight else NgDark

// ---------------------------------------------------------------------------
// Internal state
// ---------------------------------------------------------------------------

private data class NodeDragState(
    val active: Boolean = false,
    val nodeId: Int = -1,
    val grabOffset: Offset = Offset.Zero
)

private data class LinkDragState(
    val active: Boolean = false,
    val sourcePinId: Int = -1,
    val sourceWorldPos: Offset = Offset.Zero,
    val currentWorldPos: Offset = Offset.Zero
)

private class NodeGraphState {
    var pan by mutableStateOf(Offset.Zero)
    var zoom by mutableStateOf(1f)
    var selectedNodeId by mutableStateOf(-1)
    var nodeDrag by mutableStateOf(NodeDragState())
    var linkDrag by mutableStateOf(LinkDragState())
    var lastTapMark: TimeMark? = null
    var lastTapLinkId: Int = -1

    fun screenToWorld(s: Offset) = Offset((s.x - pan.x) / zoom, (s.y - pan.y) / zoom)
    fun worldToScreen(w: Offset) = Offset(w.x * zoom + pan.x, w.y * zoom + pan.y)
}

// ---------------------------------------------------------------------------
// Main composable
// ---------------------------------------------------------------------------

@Composable
fun NodeGraphEditor(
    nodes: List<GraphNode>,
    links: List<GraphLink>,
    initialNodePositions: Map<Int, Offset> = emptyMap(),
    modifier: Modifier = Modifier,
    onLinkCreated: (sourcePinId: Int, targetPinId: Int) -> Unit = { _, _ -> },
    onLinkDeleted: (linkId: Int) -> Unit = {}
) {
    val c = ngPalette
    val state = remember { NodeGraphState() }
    val textMeasurer = rememberTextMeasurer()

    val nodeWidths = remember(nodes, textMeasurer) {
        nodes.associate { it.id to measureNodeWidth(it, textMeasurer) }
    }

    val nodePositions = remember(nodes, nodeWidths) {
        mutableStateMapOf<Int, Offset>().also { map ->
            // Three columns, as uapmd-app arranges them (PluginGraphEditor.cpp:330,
            // :455): the graph's input on the left, its output on the right, and
            // everything else stacked between them. A single row would be tidier for
            // a plain chain, but it puts uninvolved nodes directly on the path of a
            // link that skips them, which reads as a connection that is not there.
            val sources = nodes.filter { it.inputs.isEmpty() }
            val sinks = nodes.filter { it.outputs.isEmpty() && it.inputs.isNotEmpty() }
            val middle = nodes - sources.toSet() - sinks.toSet()

            fun widthOf(node: GraphNode) = nodeWidths[node.id] ?: NODE_W_MIN
            val leftWidth = sources.maxOfOrNull { widthOf(it) } ?: 0f
            val middleWidth = middle.maxOfOrNull { widthOf(it) } ?: 0f
            val middleX = 60f + leftWidth + COLUMN_GAP
            val rightX = middleX + middleWidth + COLUMN_GAP

            fun place(column: List<GraphNode>, x: Float) {
                var y = 80f
                column.forEach { node ->
                    map[node.id] = initialNodePositions[node.id] ?: Offset(x, y)
                    y += nodeHeight(node) + ROW_GAP
                }
            }
            place(sources, 60f)
            place(middle, middleX)
            place(sinks, rightX)
        }
    }

    // Pin lookup: pinId → (node, pin) — recomputed only when nodes list changes
    val pinLookup = remember(nodes) {
        buildMap {
            for (node in nodes) {
                for (pin in node.inputs + node.outputs) put(pin.id, node to pin)
            }
        }
    }

    Canvas(
        modifier = modifier
            // gestures: drag, click, link creation
            .pointerInput(nodes, links) {
                val cw = size.width.toFloat()
                val ch = size.height.toFloat()
                awaitEachGesture {
                    handleGestures(state, nodes, links, nodePositions, nodeWidths, pinLookup,
                        cw, ch, onLinkCreated, onLinkDeleted)
                }
            }
            // scroll-wheel zoom + horizontal pan
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.type != PointerEventType.Scroll) continue
                        val delta = event.changes.firstOrNull()?.scrollDelta ?: continue
                        val cursor = event.changes.firstOrNull()?.position
                            ?: Offset(size.width / 2f, size.height / 2f)
                        if (delta.y != 0f) {
                            val worldAtCursor = state.screenToWorld(cursor)
                            val factor = if (delta.y < 0) 1.12f else 1f / 1.12f
                            state.zoom = (state.zoom * factor).coerceIn(0.15f, 4f)
                            state.pan = Offset(
                                cursor.x - worldAtCursor.x * state.zoom,
                                cursor.y - worldAtCursor.y * state.zoom
                            )
                        }
                        if (delta.x != 0f)
                            state.pan = state.pan - Offset(delta.x * 3f, 0f)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        // Background
        drawRect(c.background)
        drawGrid(state, c)

        // pin positions populated during node draw, used for link draw
        val pinWorldPositions = HashMap<Int, Offset>()

        withTransform({ translate(state.pan.x, state.pan.y); scale(state.zoom, state.zoom) }) {
            // First pass: populate pin positions
            for (node in nodes) {
                val pos = nodePositions[node.id] ?: continue
                node.inputs.forEachIndexed { i, pin ->
                    pinWorldPositions[pin.id] = inputPinWorldPos(pos, i)
                }
                node.outputs.forEachIndexed { i, pin ->
                    pinWorldPositions[pin.id] = outputPinWorldPos(pos, i, nodeWidths[node.id] ?: NODE_W_MIN)
                }
            }

            // Links (behind nodes)
            for (link in links) {
                val src = pinWorldPositions[link.sourcePinId] ?: continue
                val dst = pinWorldPositions[link.targetPinId] ?: continue
                val color = when (pinLookup[link.sourcePinId]?.second?.busType) {
                    BusType.Event -> c.linkEvent
                    else -> c.linkAudio
                }
                drawLink(src, dst, color, LINK_WIDTH)
            }

            // In-progress link drag
            val ld = state.linkDrag
            if (ld.active)
                drawLine(c.linkDrag, ld.sourceWorldPos, ld.currentWorldPos,
                    LINK_WIDTH, cap = StrokeCap.Round)

            // Nodes
            for (node in nodes) {
                val pos = nodePositions[node.id] ?: continue
                drawNode(state, node, pos, nodeWidths[node.id] ?: NODE_W_MIN,
                    pinWorldPositions, textMeasurer, c)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

private fun DrawScope.drawGrid(state: NodeGraphState, c: NgPalette) {
    val step = GRID_STEP * state.zoom
    val ox = ((state.pan.x % step) + step) % step
    val oy = ((state.pan.y % step) + step) % step
    var x = ox
    while (x < size.width) {
        var y = oy
        while (y < size.height) {
            drawCircle(c.grid, 1.5f, Offset(x, y))
            y += step
        }
        x += step
    }
}

private fun DrawScope.drawNode(
    state: NodeGraphState,
    node: GraphNode,
    pos: Offset,
    width: Float,
    pinWorldPositions: Map<Int, Offset>,
    measurer: TextMeasurer
,
    c: NgPalette) {
    val h = nodeHeight(node)
    val isEndpoint = node.inputs.isEmpty() || node.outputs.isEmpty()
    val isSelected = state.selectedNodeId == node.id

    // Node background
    drawRoundRect(
        c.nodeBg,
        topLeft = pos, size = Size(width, h),
        cornerRadius = CornerRadius(CORNER_R)
    )

    // Title bar (clipped to top strip)
    clipRect(pos.x, pos.y, pos.x + width, pos.y + TITLE_H) {
        drawRoundRect(
            if (isEndpoint) c.titleEndpoint else c.titleDefault,
            topLeft = pos, size = Size(width, h),
            cornerRadius = CornerRadius(CORNER_R)
        )
    }

    // Separator
    drawLine(c.separator, Offset(pos.x, pos.y + TITLE_H), Offset(pos.x + width, pos.y + TITLE_H), 1f)

    // Title text
    val titleLayout = measurer.measure(node.label, TitleStyle.copy(color = c.titleText))
    drawText(titleLayout, topLeft = Offset(pos.x + PAD, pos.y + (TITLE_H - titleLayout.size.height) / 2))

    // Node border
    drawRoundRect(
        if (isSelected) c.nodeBorderSelected else c.nodeBorderNormal,
        topLeft = pos, size = Size(width, h),
        cornerRadius = CornerRadius(CORNER_R),
        style = Stroke(width = if (isSelected) 2f else 1f)
    )

    // Input pins
    node.inputs.forEachIndexed { i, pin ->
        val pinPos = pinWorldPositions[pin.id] ?: inputPinWorldPos(pos, i)
        val pinColor = if (pin.busType == BusType.Event) c.pinEvent else c.pinAudio
        drawCircle(pinColor, PIN_R, pinPos)
        drawCircle(c.pinOutline, PIN_R, pinPos, style = Stroke(1f))
        val layout = measurer.measure(pin.label, PinStyle.copy(color = c.bodyText))
        drawText(layout, topLeft = Offset(pinPos.x + PIN_R + 4f, pinPos.y - layout.size.height / 2f))
    }

    // Output pins
    node.outputs.forEachIndexed { i, pin ->
        val pinPos = pinWorldPositions[pin.id] ?: outputPinWorldPos(pos, i, width)
        val pinColor = if (pin.busType == BusType.Event) c.pinEvent else c.pinAudio
        drawCircle(pinColor, PIN_R, pinPos)
        drawCircle(c.pinOutline, PIN_R, pinPos, style = Stroke(1f))
        val layout = measurer.measure(pin.label, PinStyle.copy(color = c.bodyText))
        drawText(layout, topLeft = Offset(pinPos.x - PIN_R - 4f - layout.size.width, pinPos.y - layout.size.height / 2f))
    }
}

private fun DrawScope.drawLink(src: Offset, dst: Offset, color: Color, width: Float) {
    val path = Path()
    val midX = (src.x + dst.x) / 2f
    val dy = dst.y - src.y

    if (dst.x >= src.x) {
        // Forward link: 3-segment orthogonal with rounded corners
        val r = min(10f, min(abs(midX - src.x), abs(dy) / 2f))
        if (r < 0.5f || abs(dy) < 0.5f) {
            path.moveTo(src.x, src.y)
            path.lineTo(midX, src.y)
            path.lineTo(midX, dst.y)
            path.lineTo(dst.x, dst.y)
        } else {
            val sign = if (dy > 0) 1f else -1f
            path.moveTo(src.x, src.y)
            path.lineTo(midX - r, src.y)
            path.quadraticTo(midX, src.y, midX, src.y + sign * r)
            path.lineTo(midX, dst.y - sign * r)
            path.quadraticTo(midX, dst.y, midX + r, dst.y)
            path.lineTo(dst.x, dst.y)
        }
    } else {
        // Backward link: 5-segment detour around nodes
        val slack = 24f
        val midY = (src.y + dst.y) / 2f
        val r = min(10f, min(slack, abs(dy) / 4f))
        val sign = if (dy > 0) 1f else -1f
        path.moveTo(src.x, src.y)
        path.lineTo(src.x + slack - r, src.y)
        path.quadraticTo(src.x + slack, src.y, src.x + slack, src.y + sign * r)
        path.lineTo(src.x + slack, midY - sign * r)
        path.quadraticTo(src.x + slack, midY, src.x + slack - r, midY)
        path.lineTo(dst.x - slack + r, midY)
        path.quadraticTo(dst.x - slack, midY, dst.x - slack, midY + sign * r)
        path.lineTo(dst.x - slack, dst.y - sign * r)
        path.quadraticTo(dst.x - slack, dst.y, dst.x - slack + r, dst.y)
        path.lineTo(dst.x, dst.y)
    }

    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
}

// ---------------------------------------------------------------------------
// Gesture handling
// ---------------------------------------------------------------------------

private suspend fun AwaitPointerEventScope.handleGestures(
    state: NodeGraphState,
    nodes: List<GraphNode>,
    links: List<GraphLink>,
    nodePositions: MutableMap<Int, Offset>,
    nodeWidths: Map<Int, Float>,
    pinLookup: Map<Int, Pair<GraphNode, GraphPin>>,
    canvasWidth: Float,
    canvasHeight: Float,
    onLinkCreated: (Int, Int) -> Unit,
    onLinkDeleted: (Int) -> Unit
) {
    val down = awaitFirstDown(requireUnconsumed = false)
    val screenPos = down.position
    val worldPos = state.screenToWorld(screenPos)

    // --- pin hit (highest priority) ---
    val PIN_HIT_PX = 12f
    val hitPin = pinLookup.entries
        .mapNotNull { (pinId, pair) ->
            val node = pair.first
            val pin = pair.second
            val nodePos = nodePositions[node.id] ?: return@mapNotNull null
            val worldPinPos = if (pin.isInput)
                inputPinWorldPos(nodePos, node.inputs.indexOf(pin))
            else
                outputPinWorldPos(nodePos, node.outputs.indexOf(pin), nodeWidths[node.id] ?: NODE_W_MIN)
            val screenDist = (state.worldToScreen(worldPinPos) - screenPos).getDistance()
            if (screenDist < PIN_HIT_PX) Triple(pinId, pin, worldPinPos) else null
        }
        .minByOrNull { (_, _, wp) -> (state.worldToScreen(wp) - screenPos).getDistance() }

    if (hitPin != null) {
        val (sourcePinId, _, sourcePinWorld) = hitPin
        state.linkDrag = LinkDragState(true, sourcePinId, sourcePinWorld, worldPos)

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) break
            state.linkDrag = state.linkDrag.copy(currentWorldPos = state.screenToWorld(change.position))
            change.consume()
        }

        val releaseWorld = state.linkDrag.currentWorldPos
        val SNAP_WORLD = 20f / state.zoom
        val targetPin = pinLookup.entries
            .mapNotNull { (pinId, pair) ->
                if (pinId == sourcePinId) return@mapNotNull null
                val node = pair.first
                val pin = pair.second
                val nodePos = nodePositions[node.id] ?: return@mapNotNull null
                val worldPinPos = if (pin.isInput)
                    inputPinWorldPos(nodePos, node.inputs.indexOf(pin))
                else
                    outputPinWorldPos(nodePos, node.outputs.indexOf(pin), nodeWidths[node.id] ?: NODE_W_MIN)
                val dist = (worldPinPos - releaseWorld).getDistance()
                if (dist < SNAP_WORLD) Triple(pinId, pin, dist) else null
            }
            .minByOrNull { (_, _, d) -> d }

        if (targetPin != null) {
            val (targetPinId, targetPinData, _) = targetPin
            val srcPin = pinLookup[sourcePinId]?.second
            if (srcPin != null && srcPin.busType == targetPinData.busType
                && srcPin.isInput != targetPinData.isInput
            ) {
                val (outPinId, inPinId) =
                    if (!srcPin.isInput) sourcePinId to targetPinId
                    else targetPinId to sourcePinId
                onLinkCreated(outPinId, inPinId)
            }
        }

        state.linkDrag = LinkDragState()
        return
    }

    // --- node hit ---
    val hitNodeId = nodes
        .firstOrNull { node ->
            val pos = nodePositions[node.id] ?: return@firstOrNull false
            worldPos.x in pos.x..(pos.x + (nodeWidths[node.id] ?: NODE_W_MIN)) &&
                worldPos.y in pos.y..(pos.y + nodeHeight(node))
        }?.id

    if (hitNodeId != null) {
        state.selectedNodeId = hitNodeId
        val nodePos = nodePositions[hitNodeId]!!
        val grabOffset = worldPos - nodePos
        var moved = false

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) break
            if (moved || (change.position - screenPos).getDistance() > 4f) {
                moved = true
                nodePositions[hitNodeId] = state.screenToWorld(change.position) - grabOffset
                change.consume()
            }
        }
        return
    }

    // --- link double-tap or background pan ---
    val LINK_HIT_WORLD = 8f / state.zoom
    val hitLink = links.minByOrNull { link ->
        linkHitDist(link, worldPos, pinLookup, nodePositions, nodeWidths)
    }?.takeIf { link ->
        linkHitDist(link, worldPos, pinLookup, nodePositions, nodeWidths) < LINK_HIT_WORLD
    }

    if (hitLink != null) {
        val now = TimeSource.Monotonic.markNow()
        val isDouble = state.lastTapMark?.let {
            it.elapsedNow().inWholeMilliseconds < 350L && state.lastTapLinkId == hitLink.id
        } ?: false
        state.lastTapMark = now
        state.lastTapLinkId = hitLink.id
        if (isDouble) {
            state.lastTapMark = null
            onLinkDeleted(hitLink.id)
        }
        return
    }

    // --- background: deselect + pan ---
    state.selectedNodeId = -1
    var lastScreen = screenPos
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: break
        if (!change.pressed) break
        state.pan += change.position - lastScreen
        lastScreen = change.position
        change.consume()
    }
}

// ---------------------------------------------------------------------------
// Hit-test helpers
// ---------------------------------------------------------------------------

private fun pinWorldPosById(
    pinId: Int,
    pinLookup: Map<Int, Pair<GraphNode, GraphPin>>,
    nodePositions: Map<Int, Offset>,
    nodeWidths: Map<Int, Float>
): Offset? {
    val (node, pin) = pinLookup[pinId] ?: return null
    val nodePos = nodePositions[node.id] ?: return null
    return if (pin.isInput)
        inputPinWorldPos(nodePos, node.inputs.indexOf(pin))
    else
        outputPinWorldPos(nodePos, node.outputs.indexOf(pin), nodeWidths[node.id] ?: NODE_W_MIN)
}

private fun linkHitDist(
    link: GraphLink,
    worldPos: Offset,
    pinLookup: Map<Int, Pair<GraphNode, GraphPin>>,
    nodePositions: Map<Int, Offset>,
    nodeWidths: Map<Int, Float>
): Float {
    val src = pinWorldPosById(link.sourcePinId, pinLookup, nodePositions, nodeWidths)
        ?: return Float.MAX_VALUE
    val dst = pinWorldPosById(link.targetPinId, pinLookup, nodePositions, nodeWidths)
        ?: return Float.MAX_VALUE
    val midX = (src.x + dst.x) / 2f
    return minOf(
        segDist(worldPos, src, Offset(midX, src.y)),
        segDist(worldPos, Offset(midX, src.y), Offset(midX, dst.y)),
        segDist(worldPos, Offset(midX, dst.y), dst)
    )
}

private fun segDist(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val lenSq = ab.x * ab.x + ab.y * ab.y
    if (lenSq < 0.0001f) return (p - a).getDistance()
    val t = ((p.x - a.x) * ab.x + (p.y - a.y) * ab.y).div(lenSq).coerceIn(0f, 1f)
    return (p - Offset(a.x + ab.x * t, a.y + ab.y * t)).getDistance()
}
