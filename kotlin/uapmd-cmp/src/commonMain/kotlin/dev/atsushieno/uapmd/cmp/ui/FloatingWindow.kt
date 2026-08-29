package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * In-scene floating windows, the way uapmd-app has them.
 *
 * uapmd-app does not enable ImGui multi-viewport, so its Plugin Selector,
 * Instance Details, graph editors and so on are windows drawn inside the single
 * application window — draggable, resizable, stacked, and several open at once.
 * Compose Multiplatform has no equivalent (`Window` is desktop-only; `Dialog`
 * is modal and `Popup` is anchored), so this is that missing piece.
 * See docs/uapmd-cmp-plan.md §3.1.
 *
 * Windows are keyed, which is what lets multi-instance windows coexist:
 * `"details:$instanceId"`, `"graph:$trackIndex"`, `"dump:$trackIndex:$clipId"`.
 */
class FloatingWindowManager {
    internal val windows = mutableStateListOf<FloatingWindowEntry>()

    fun isOpen(key: String): Boolean = windows.any { it.key == key }

    /** Opening an already-open key brings it to the front instead of duplicating it. */
    fun open(
        key: String,
        title: String,
        initialSize: DpSize = DpSize(420.dp, 320.dp),
        initialPosition: DpOffset? = null,
        resizable: Boolean = true,
        onClose: () -> Unit = {},
        content: @Composable () -> Unit
    ) {
        val existing = windows.firstOrNull { it.key == key }
        if (existing != null) {
            bringToFront(key)
            return
        }
        // Cascade new windows so they do not land exactly on top of each other.
        val step = (windows.size % 8) * 24
        windows.add(
            FloatingWindowEntry(
                key = key,
                title = title,
                position = initialPosition ?: DpOffset((40 + step).dp, (40 + step).dp),
                size = initialSize,
                resizable = resizable,
                onClose = onClose,
                content = content
            )
        )
    }

    fun close(key: String) {
        val entry = windows.firstOrNull { it.key == key } ?: return
        windows.remove(entry)
        entry.onClose()
    }

    fun toggle(
        key: String,
        title: String,
        initialSize: DpSize = DpSize(420.dp, 320.dp),
        resizable: Boolean = true,
        onClose: () -> Unit = {},
        content: @Composable () -> Unit
    ) {
        if (isOpen(key)) close(key) else open(key, title, initialSize, null, resizable, onClose, content)
    }

    fun bringToFront(key: String) {
        val index = windows.indexOfFirst { it.key == key }
        if (index < 0 || index == windows.lastIndex) return
        val entry = windows.removeAt(index)
        windows.add(entry)
    }

    /** Drops windows whose key is no longer valid (e.g. the plugin instance is gone). */
    fun closeWhere(predicate: (String) -> Boolean) {
        windows.filter { predicate(it.key) }.forEach { close(it.key) }
    }
}

class FloatingWindowEntry internal constructor(
    val key: String,
    val title: String,
    position: DpOffset,
    size: DpSize,
    val resizable: Boolean,
    internal val onClose: () -> Unit,
    internal val content: @Composable () -> Unit
) {
    var position by mutableStateOf(position)
        internal set
    var size by mutableStateOf(size)
        internal set
}

@Composable
fun rememberFloatingWindowManager(): FloatingWindowManager = remember { FloatingWindowManager() }

private val MinWindowSize = DpSize(180.dp, 90.dp)
private val TitleBarHeight = 30.dp

/**
 * Draws every open window over [content]. Later entries render on top, which is
 * also the focus order the manager maintains.
 */
@Composable
fun FloatingWindowLayer(
    manager: FloatingWindowManager,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var hostSize by remember { mutableStateOf(DpSize.Zero) }
    val density = LocalDensity.current
    Box(modifier = modifier.fillMaxSize().onSizeChanged {
        hostSize = with(density) { DpSize(it.width.toDp(), it.height.toDp()) }
    }) {
        content()
        // Snapshot the list so closing a window during composition cannot break iteration.
        manager.windows.toList().forEach { entry ->
            key(entry.key) {
                FloatingWindowFrame(manager, entry, hostSize)
            }
        }
    }
}

@Composable
private fun FloatingWindowFrame(
    manager: FloatingWindowManager,
    entry: FloatingWindowEntry,
    hostSize: DpSize
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset { with(density) { IntOffset(entry.position.x.toPx().roundToInt(), entry.position.y.toPx().roundToInt()) } }
            .size(entry.size)
            .pointerInput(entry.key) {
                // Any press inside the window raises it, matching ImGui focus behaviour.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        manager.bringToFront(entry.key)
                        break
                    }
                }
            }
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                TitleBar(manager, entry, hostSize)
                Box(Modifier.fillMaxSize().padding(8.dp)) { entry.content() }
            }
        }
        if (entry.resizable) ResizeHandle(entry, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun TitleBar(
    manager: FloatingWindowManager,
    entry: FloatingWindowEntry,
    hostSize: DpSize
) {
    val density = LocalDensity.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .pointerInput(entry.key) {
                detectDragGestures(
                    onDragStart = { manager.bringToFront(entry.key) }
                ) { change, delta ->
                    change.consume()
                    val dx = with(density) { delta.x.toDp() }
                    val dy = with(density) { delta.y.toDp() }
                    // Keep at least the title bar reachable, so a window cannot be lost off-edge.
                    val maxX = (hostSize.width - 48.dp).coerceAtLeast(0.dp)
                    val maxY = (hostSize.height - TitleBarHeight).coerceAtLeast(0.dp)
                    entry.position = DpOffset(
                        (entry.position.x + dx).coerceIn(0.dp - entry.size.width + 48.dp, maxX),
                        (entry.position.y + dy).coerceIn(0.dp, maxY)
                    )
                }
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            entry.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f)
        )
        Text(
            "✕",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .padding(start = 8.dp)
                .pointerInput(entry.key) {
                    detectTapOrPress { manager.close(entry.key) }
                }
        )
    }
}

@Composable
private fun ResizeHandle(entry: FloatingWindowEntry, modifier: Modifier) {
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .size(16.dp)
            .pointerInput(entry.key) {
                detectDragGestures { change, delta ->
                    change.consume()
                    val dw = with(density) { delta.x.toDp() }
                    val dh = with(density) { delta.y.toDp() }
                    entry.size = DpSize(
                        (entry.size.width + dw).coerceAtLeast(MinWindowSize.width),
                        (entry.size.height + dh).coerceAtLeast(MinWindowSize.height)
                    )
                }
            }
    ) {
        Text(
            "◢",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapOrPress(onTap: () -> Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent()
            val event = currentEvent
            if (event.changes.any { it.pressed }) {
                event.changes.forEach { it.consume() }
                onTap()
                // Wait for release so one press fires once.
                do {
                    awaitPointerEvent()
                } while (currentEvent.changes.any { it.pressed })
            }
        }
    }
}
