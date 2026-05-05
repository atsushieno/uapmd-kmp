package dev.atsushieno.uapmd_kmp.nodegraph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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

private const val NODE_W = 180f
private const val TITLE_H = 30f
private const val PIN_ROW_H = 24f
private const val PIN_R = 5f
private const val CORNER_R = 6f
private const val PAD = 8f
private const val LINK_WIDTH = 2f
private const val GRID_STEP = 40f

private fun nodeHeight(node: GraphNode) =
    TITLE_H + PAD + maxOf(node.inputs.size, node.outputs.size) * PIN_ROW_H + PAD

private fun inputPinWorldPos(nodePos: Offset, index: Int) =
    Offset(nodePos.x, nodePos.y + TITLE_H + PAD + index * PIN_ROW_H + PIN_ROW_H / 2)

private fun outputPinWorldPos(nodePos: Offset, index: Int) =
    Offset(nodePos.x + NODE_W, nodePos.y + TITLE_H + PAD + index * PIN_ROW_H + PIN_ROW_H / 2)

// ---------------------------------------------------------------------------
// Colours
// ---------------------------------------------------------------------------

private object NgColors {
    val background = Color(0xFF1A1A1A)
    val grid = Color(0xFF282828)
    val nodeBg = Color(0xFF2C2C2C)
    val nodeBorderNormal = Color(0xFF505050)
    val nodeBorderSelected = Color(0xFF88AADD)
    val titleDefault = Color(0xFF3A4A3A)
    val titleEndpoint = Color(0xFF3A3A5A)
    val titleText = Color(0xFFEEEEEE)
    val bodyText = Color(0xFFBBBBBB)
    val separator = Color(0xFF444444)
    val pinAudio = Color(0xFF44AA88)
    val pinEvent = Color(0xFFAA9944)
    val pinOutline = Color(0xFF888888)
    val linkAudio = Color(0xFF44AA88)
    val linkEvent = Color(0xFFAA9944)
    val linkDrag = Color(0xFFCCCCCC)
}

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
    val state = remember { NodeGraphState() }
    val textMeasurer = rememberTextMeasurer()

    val nodePositions = remember(nodes) {
        mutableStateMapOf<Int, Offset>().also { map ->
            nodes.forEachIndexed { i, node ->
                map[node.id] = initialNodePositions[node.id] ?: Offset(60f + i * 220f, 80f)
            }
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
                    handleGestures(state, nodes, links, nodePositions, pinLookup,
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
        drawRect(NgColors.background)
        drawGrid(state)

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
                    pinWorldPositions[pin.id] = outputPinWorldPos(pos, i)
                }
            }

            // Links (behind nodes)
            for (link in links) {
                val src = pinWorldPositions[link.sourcePinId] ?: continue
                val dst = pinWorldPositions[link.targetPinId] ?: continue
                val color = when (pinLookup[link.sourcePinId]?.second?.busType) {
                    BusType.Event -> NgColors.linkEvent
                    else -> NgColors.linkAudio
                }
                drawLink(src, dst, color, LINK_WIDTH)
            }

            // In-progress link drag
            val ld = state.linkDrag
            if (ld.active)
                drawLine(NgColors.linkDrag, ld.sourceWorldPos, ld.currentWorldPos,
                    LINK_WIDTH, cap = StrokeCap.Round)

            // Nodes
            for (node in nodes) {
                val pos = nodePositions[node.id] ?: continue
                drawNode(state, node, pos, pinWorldPositions, textMeasurer)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

private fun DrawScope.drawGrid(state: NodeGraphState) {
    val step = GRID_STEP * state.zoom
    val ox = ((state.pan.x % step) + step) % step
    val oy = ((state.pan.y % step) + step) % step
    var x = ox
    while (x < size.width) {
        var y = oy
        while (y < size.height) {
            drawCircle(NgColors.grid, 1.5f, Offset(x, y))
            y += step
        }
        x += step
    }
}

private fun DrawScope.drawNode(
    state: NodeGraphState,
    node: GraphNode,
    pos: Offset,
    pinWorldPositions: Map<Int, Offset>,
    measurer: TextMeasurer
) {
    val h = nodeHeight(node)
    val isEndpoint = node.inputs.isEmpty() || node.outputs.isEmpty()
    val isSelected = state.selectedNodeId == node.id

    // Node background
    drawRoundRect(
        NgColors.nodeBg,
        topLeft = pos, size = Size(NODE_W, h),
        cornerRadius = CornerRadius(CORNER_R)
    )

    // Title bar (clipped to top strip)
    clipRect(pos.x, pos.y, pos.x + NODE_W, pos.y + TITLE_H) {
        drawRoundRect(
            if (isEndpoint) NgColors.titleEndpoint else NgColors.titleDefault,
            topLeft = pos, size = Size(NODE_W, h),
            cornerRadius = CornerRadius(CORNER_R)
        )
    }

    // Separator
    drawLine(NgColors.separator, Offset(pos.x, pos.y + TITLE_H), Offset(pos.x + NODE_W, pos.y + TITLE_H), 1f)

    // Title text
    val titleLayout = measurer.measure(node.label, TextStyle(color = NgColors.titleText, fontSize = 11.sp))
    drawText(titleLayout, topLeft = Offset(pos.x + PAD, pos.y + (TITLE_H - titleLayout.size.height) / 2))

    // Node border
    drawRoundRect(
        if (isSelected) NgColors.nodeBorderSelected else NgColors.nodeBorderNormal,
        topLeft = pos, size = Size(NODE_W, h),
        cornerRadius = CornerRadius(CORNER_R),
        style = Stroke(width = if (isSelected) 2f else 1f)
    )

    // Input pins
    node.inputs.forEachIndexed { i, pin ->
        val pinPos = pinWorldPositions[pin.id] ?: inputPinWorldPos(pos, i)
        val pinColor = if (pin.busType == BusType.Event) NgColors.pinEvent else NgColors.pinAudio
        drawCircle(pinColor, PIN_R, pinPos)
        drawCircle(NgColors.pinOutline, PIN_R, pinPos, style = Stroke(1f))
        val layout = measurer.measure(pin.label, TextStyle(color = NgColors.bodyText, fontSize = 9.sp))
        drawText(layout, topLeft = Offset(pinPos.x + PIN_R + 4f, pinPos.y - layout.size.height / 2f))
    }

    // Output pins
    node.outputs.forEachIndexed { i, pin ->
        val pinPos = pinWorldPositions[pin.id] ?: outputPinWorldPos(pos, i)
        val pinColor = if (pin.busType == BusType.Event) NgColors.pinEvent else NgColors.pinAudio
        drawCircle(pinColor, PIN_R, pinPos)
        drawCircle(NgColors.pinOutline, PIN_R, pinPos, style = Stroke(1f))
        val layout = measurer.measure(pin.label, TextStyle(color = NgColors.bodyText, fontSize = 9.sp))
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
                outputPinWorldPos(nodePos, node.outputs.indexOf(pin))
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
                    outputPinWorldPos(nodePos, node.outputs.indexOf(pin))
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
            worldPos.x in pos.x..(pos.x + NODE_W) && worldPos.y in pos.y..(pos.y + nodeHeight(node))
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
        linkHitDist(link, worldPos, pinLookup, nodePositions)
    }?.takeIf { link -> linkHitDist(link, worldPos, pinLookup, nodePositions) < LINK_HIT_WORLD }

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
    nodePositions: Map<Int, Offset>
): Offset? {
    val (node, pin) = pinLookup[pinId] ?: return null
    val nodePos = nodePositions[node.id] ?: return null
    return if (pin.isInput)
        inputPinWorldPos(nodePos, node.inputs.indexOf(pin))
    else
        outputPinWorldPos(nodePos, node.outputs.indexOf(pin))
}

private fun linkHitDist(
    link: GraphLink,
    worldPos: Offset,
    pinLookup: Map<Int, Pair<GraphNode, GraphPin>>,
    nodePositions: Map<Int, Offset>
): Float {
    val src = pinWorldPosById(link.sourcePinId, pinLookup, nodePositions) ?: return Float.MAX_VALUE
    val dst = pinWorldPosById(link.targetPinId, pinLookup, nodePositions) ?: return Float.MAX_VALUE
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
