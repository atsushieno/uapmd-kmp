package dev.atsushieno.uapmd.cmp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.*

/**
 * Draws a plugin that renders into a pixel buffer, and hands it back what the user does.
 *
 * A plugin like this has no view to embed: asking the platform for a window produces an
 * empty one, which is what a JSFX effect used to get. Everything here is in terms of
 * [FramebufferUi], so nothing in it knows what a JSFX is.
 *
 * Frames are polled rather than pushed. The plugin draws on its own thread whenever it
 * likes, and Compose has no render loop to hook, so each display frame asks whether the
 * plugin's frame serial has moved and copies only when it has.
 */
@Composable
fun FramebufferPluginEditor(
    ui: FramebufferUi,
    modifier: Modifier = Modifier
) {
    val size = remember(ui) { ui.preferredSize ?: FramebufferSize(640, 480) }
    // One surface, reused. Its contents change while its identity does not, so the state
    // holding it must invalidate on every assignment rather than on a change of value --
    // otherwise the editor would draw the first frame and never redraw.
    var frame by remember(ui) { mutableStateOf<ImageBitmap?>(null, neverEqualPolicy()) }

    // What the user is doing, accumulated by the pointer handlers and sent as a snapshot.
    val pointer = remember(ui) { PointerState() }
    var menu by remember(ui) { mutableStateOf<PendingMenu?>(null) }

    // The plugin is only told to draw while this composable is on screen, and the host is
    // uninstalled on the way out: its callbacks reach into this composition.
    DisposableEffect(ui) {
        // A starting point only; the real size arrives from the layout below, and the
        // plugin is told again every time the window changes it.
        ui.setSurfaceSize(size.width, size.height, 1.0)
        ui.setHost(object : FramebufferUiHost {
            override fun requestMenu(
                items: List<FramebufferMenuItem>,
                x: Int,
                y: Int,
                complete: (Int) -> Unit
            ) {
                // Arrives on the plugin's render thread. Compose state is only safe to
                // touch from the composition's own thread, so it is handed over rather
                // than written here.
                menu = PendingMenu(items, x, y, complete)
            }

            override fun setCursor(cursor: FramebufferCursor) {
                pointer.cursor = cursor
            }

            override fun droppedFile(index: Int): String? = null
        })
        ui.setDisplayed(true)
        onDispose {
            ui.setDisplayed(false)
            ui.setHost(null)
            // A menu still waiting when the editor closes would leave the plugin's
            // renderer blocked for ever.
            menu?.complete?.invoke(0)
            menu = null
        }
    }

    // Paced by the display, and done on the display's thread deliberately.
    //
    // The surface is a Skia bitmap whose pixels Compose reads when it draws, so writing it
    // from anywhere else races the renderer and tears the editor. Inside withFrameNanos the
    // write lands between frames instead. It costs about 250us for a 1080x400 editor --
    // under one percent of the frame budget at the rate a plugin asks to be drawn at, so
    // there is nothing to gain by moving it off and a visibly torn editor to lose.
    LaunchedEffect(ui) {
        var pixels = ByteArray(0)
        var surface: FramebufferSurface? = null
        var lastSerial = -1L

        while (true) {
            withFrameNanos {
                val info = ui.frameInfo()
                if (info != null && info.serial != lastSerial) {
                    val needed = info.height * info.width * 4
                    if (pixels.size != needed)
                        pixels = ByteArray(needed)
                    var target = surface
                    if (target == null || target.width != info.width || target.height != info.height) {
                        target = FramebufferSurface(info.width, info.height)
                        surface = target
                    }
                    // The plugin may have resized since the size was read, in which case it
                    // refuses the copy and this frame is skipped rather than drawn wrongly.
                    val copied = ui.copyFrame(pixels, info.width * 4)
                    if (copied != null) {
                        target.update(pixels, info.width * 4)
                        lastSerial = copied.serial
                        frame = target.image
                    }
                }
            }
        }
    }

    // ysfx asks for "the width of the frame buffer (having the scale factor applied)"
    // plus the display scale, so the plugin is given this composable's size in real
    // pixels. That also makes one framebuffer pixel one layout pixel, which is what lets
    // pointer positions go straight through untranslated.
    val density = LocalDensity.current
    Box(
        modifier.onSizeChanged { measured ->
            if (measured.width > 0 && measured.height > 0)
                ui.setSurfaceSize(measured.width, measured.height, density.density.toDouble())
        }
    ) {
        val current = frame
        Canvas(
            Modifier
                .fillMaxSize()
                .framebufferPointerInput(ui, pointer)
        ) {
            if (current != null)
                drawFramebuffer(current)
        }

        menu?.let { pending ->
            DropdownMenu(
                expanded = true,
                onDismissRequest = {
                    pending.complete(0)
                    menu = null
                },
                // Also pixels, for the same reason.
                offset = with(LocalDensity.current) { DpOffset(pending.x.toDp(), pending.y.toDp()) }
            ) {
                FramebufferMenuItems(pending.items) { chosen ->
                    pending.complete(chosen)
                    menu = null
                }
            }
        }
    }
}

private fun DrawScope.drawFramebuffer(image: ImageBitmap) {
    drawImage(image)
}

@Composable
private fun FramebufferMenuItems(items: List<FramebufferMenuItem>, onChoose: (Int) -> Unit) {
    items.forEach { item ->
        when {
            item.separator -> HorizontalDivider()
            // A submenu is shown inline with its label above it: Material's menus have no
            // nested popup, and hiding the items would be worse than indenting them.
            item.children.isNotEmpty() -> {
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {},
                    enabled = false
                )
                FramebufferMenuItems(item.children, onChoose)
            }
            else -> DropdownMenuItem(
                text = { Text(if (item.checked) "✓ ${item.label}" else item.label) },
                onClick = { onChoose(item.id) },
                enabled = !item.disabled
            )
        }
    }
}

private class PendingMenu(
    val items: List<FramebufferMenuItem>,
    val x: Int,
    val y: Int,
    val complete: (Int) -> Unit
)

/** What the pointer is doing, between one delivery and the next. */
private class PointerState {
    var x: Int = 0
    var y: Int = 0
    var buttons: Int = 0
    var modifiers: Int = 0
    var wheel: Double = 0.0
    var horizontalWheel: Double = 0.0
    var over: Boolean = false
    var cursor: FramebufferCursor = FramebufferCursor.ARROW
}

/**
 * Sends the pointer to the plugin.
 *
 * Every event is forwarded, not just the ones inside the editor: a control being dragged
 * needs to hear about the pointer leaving, and about the release wherever it happens.
 */
private fun Modifier.framebufferPointerInput(ui: FramebufferUi, state: PointerState): Modifier =
    this.pointerInput(ui) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull() ?: continue

                state.x = change.position.x.toInt()
                state.y = change.position.y.toInt()
                state.over = change.position.x >= 0 && change.position.y >= 0 &&
                        change.position.x < size.width && change.position.y < size.height

                val buttons = event.buttons
                state.buttons = 0
                if (buttons.isPrimaryPressed) state.buttons = state.buttons or FramebufferButtons.LEFT
                if (buttons.isSecondaryPressed) state.buttons = state.buttons or FramebufferButtons.RIGHT
                if (buttons.isTertiaryPressed) state.buttons = state.buttons or FramebufferButtons.MIDDLE

                val keyboard = event.keyboardModifiers
                state.modifiers = 0
                if (keyboard.isShiftPressed) state.modifiers = state.modifiers or FramebufferModifiers.SHIFT
                if (keyboard.isCtrlPressed) state.modifiers = state.modifiers or FramebufferModifiers.CONTROL
                if (keyboard.isAltPressed) state.modifiers = state.modifiers or FramebufferModifiers.ALT
                if (keyboard.isMetaPressed) state.modifiers = state.modifiers or FramebufferModifiers.SUPER

                if (event.type == PointerEventType.Scroll) {
                    // Compose reports scroll downwards as positive; the plugin expects the
                    // opposite, the way a wheel turns away from the user.
                    state.wheel = -change.scrollDelta.y.toDouble()
                    state.horizontalWheel = change.scrollDelta.x.toDouble()
                }

                ui.deliverInput(
                    FramebufferInput(
                        pointerX = state.x,
                        pointerY = state.y,
                        buttons = state.buttons,
                        modifiers = state.modifiers,
                        wheel = state.wheel,
                        horizontalWheel = state.horizontalWheel,
                        hasFocus = true,
                        visible = true,
                        pointerOver = state.over
                    )
                )
                // Scroll is a delta, so it is reported once and then forgotten.
                state.wheel = 0.0
                state.horizontalWheel = 0.0

                // Consumed so that the surrounding window does not also act on it, which
                // is what turned a drag inside the editor into a drag of the window.
                if (event.type != PointerEventType.Move || state.buttons != 0)
                    change.consume()
            }
        }
    }
