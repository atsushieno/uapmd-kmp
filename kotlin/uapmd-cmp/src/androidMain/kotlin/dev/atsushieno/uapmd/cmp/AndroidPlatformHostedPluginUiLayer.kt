package dev.atsushieno.uapmd.cmp

import android.content.Context
import android.os.Build
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.androidaudioplugin.hosting.GuiHelper
import kotlin.math.roundToInt

private sealed interface AapHostLoadState {
    data object Loading : AapHostLoadState
    data class Ready(val state: AapHostState) : AapHostLoadState
    data class Error(val message: String) : AapHostLoadState
}

@Composable
internal fun AndroidPlatformHostedPluginUiLayer(
    host: UapmdHost,
    modifier: Modifier = Modifier
) {
    val engine = host.model.sequencer.engine
    val hostedInfos = host.platformHostedUiInstanceIds.mapNotNull { id ->
        engine.getPluginInstance(id)?.let { inst ->
            HostedInstanceInfo(id, inst.displayName, inst.pluginId, inst.aapUiHostDetails)
        }
    }
    Box(modifier = modifier) {
        hostedInfos.forEach { info ->
            // Keyed so that closing one window cannot hand its composition slot -
            // and the AndroidView holding its SurfaceView - to the next one.
            key(info.instanceId) {
                AapPluginUiWindow(
                    info = info,
                    onClose = { host.hidePluginUi(info.instanceId) },
                    onError = { host.reportPluginUiStatus(it) }
                )
            }
        }
    }
}

@Composable
private fun AapPluginUiWindow(
    info: HostedInstanceInfo,
    onClose: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    // What the plug-in UI may grow to. uapmd-app reads the activity's content
    // area for this; LocalWindowInfo is the Compose equivalent and, unlike the
    // display metrics, follows folds and multi-window.
    val available = LocalWindowInfo.current.containerSize
    val hostDetails = info.aapUiHostDetails
    if (hostDetails == null) {
        LaunchedEffect(info.pluginId) {
            onError("AAP UI host details are unavailable for ${info.displayName}.")
            onClose()
        }
        return
    }

    val hostLoadState by produceState<AapHostLoadState>(
        initialValue = AapHostLoadState.Loading,
        context,
        hostDetails.pluginPackageName,
        info.pluginId,
        hostDetails.instanceId
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            value = AapHostLoadState.Error("AAP GUI hosting requires Android 11 or later.")
            return@produceState
        }
        val host = withContext(Dispatchers.Default) {
            GuiHelper.NativeEmbeddedSurfaceControlHost(
                context,
                hostDetails.pluginPackageName,
                info.pluginId,
                hostDetails.instanceId
            )
        }
        try {
            // A plug-in with no native view of its own (AAP's default Compose UI,
            // e.g. the MDA plug-ins) reports no preferred size. Falling back to a
            // fixed 480x320 made that its content size, and since the viewport can
            // never exceed the content, its window could not be resized at all.
            // uapmd-app falls back to the host's content area instead; do the same.
            val preferred = host.getPreferredSizeOrFallback(
                available.width.coerceAtLeast(MIN_CONTENT_FALLBACK_WIDTH),
                available.height.coerceAtLeast(MIN_CONTENT_FALLBACK_HEIGHT)
            )
            value = AapHostLoadState.Ready(
                AapHostState(
                    host,
                    preferred.width.coerceAtLeast(MIN_CONTENT_FALLBACK_WIDTH),
                    preferred.height.coerceAtLeast(MIN_CONTENT_FALLBACK_HEIGHT)
                )
            )
            awaitDispose {
                host.close()
            }
        } catch (t: Throwable) {
            host.close()
            value = AapHostLoadState.Error(
                t.message ?: "Failed to create AAP GUI host for ${info.displayName}."
            )
        }
    }

    when (val state = hostLoadState) {
        AapHostLoadState.Loading -> return
        is AapHostLoadState.Error -> {
            LaunchedEffect(info.instanceId, state.message) {
                onError(state.message)
                onClose()
            }
            return
        }
        is AapHostLoadState.Ready -> AapPluginSurfaceWindow(
            title = info.displayName,
            state = state.state,
            available = available,
            onClose = onClose
        )
    }
}

private data class AapHostState(
    val host: GuiHelper.NativeEmbeddedSurfaceControlHost,
    val contentWidth: Int,
    val contentHeight: Int
)

@Composable
private fun AapPluginSurfaceWindow(
    title: String,
    state: AapHostState,
    available: IntSize,
    onClose: () -> Unit
) {
    val density = LocalDensity.current
    val scrollbarThickness = 12.dp
    val resizeHandleSize = 20.dp
    // Only an estimate of the title bar's own height, used to keep the window
    // within the host window; the bar itself is sized by its content.
    val titleBarHeight = 52.dp
    val scrollbarThicknessPx = with(density) { scrollbarThickness.roundToPx() }
    val chromeHeightPx = with(density) { (titleBarHeight + scrollbarThickness).roundToPx() }
    // The viewport may grow up to the content size, but never past what the host
    // window can actually show. Mirrors uapmd-app's constrainViewportSize().
    val maxAvailableWidthPx = (available.width - scrollbarThicknessPx).coerceAtLeast(1)
    val maxAvailableHeightPx = (available.height - chromeHeightPx).coerceAtLeast(1)
    val minDimensionPx = with(density) { MIN_VIEWPORT_DIMENSION.roundToPx() }

    var offsetX by remember(title) { mutableStateOf(32f) }
    var offsetY by remember(title) { mutableStateOf(32f) }
    var contentWidthPx by remember(state) { mutableStateOf(state.contentWidth) }
    var contentHeightPx by remember(state) { mutableStateOf(state.contentHeight) }
    // Open at 80% of the host window at most, as uapmd-app does, so the window
    // lands fully on screen with room to grab its edges.
    var viewportWidthPx by remember(state) {
        mutableStateOf(
            constrainViewport(
                state.contentWidth, (maxAvailableWidthPx * INITIAL_MAX_HOST_FRACTION).toInt(),
                state.contentWidth, minDimensionPx
            )
        )
    }
    var viewportHeightPx by remember(state) {
        mutableStateOf(
            constrainViewport(
                state.contentHeight, (maxAvailableHeightPx * INITIAL_MAX_HOST_FRACTION).toInt(),
                state.contentHeight, minDimensionPx
            )
        )
    }
    var scrollX by remember { mutableStateOf(0) }
    var scrollY by remember { mutableStateOf(0) }
    var surfaceReady by remember(state) { mutableStateOf(false) }
    var connected by remember(state) { mutableStateOf(false) }
    var resizeStartWidth by remember { mutableStateOf(viewportWidthPx) }
    var resizeStartHeight by remember { mutableStateOf(viewportHeightPx) }
    var resizeDragWidth by remember { mutableStateOf(0f) }
    var resizeDragHeight by remember { mutableStateOf(0f) }
    val currentHost by rememberUpdatedState(state.host)
    val viewportWidthDp = with(density) { viewportWidthPx.toDp() }
    val viewportHeightDp = with(density) { viewportHeightPx.toDp() }
    val maxViewportWidthPx = minOf(contentWidthPx, maxAvailableWidthPx).coerceAtLeast(1)
    val maxViewportHeightPx = minOf(contentHeightPx, maxAvailableHeightPx).coerceAtLeast(1)
    val minViewportWidthPx = minDimensionPx.coerceAtMost(maxViewportWidthPx)
    val minViewportHeightPx = minDimensionPx.coerceAtMost(maxViewportHeightPx)
    val maxScrollX = (contentWidthPx - viewportWidthPx).coerceAtLeast(0)
    val maxScrollY = (contentHeightPx - viewportHeightPx).coerceAtLeast(0)
    val effectiveScrollX = scrollX.coerceIn(0, maxScrollX)
    val effectiveScrollY = scrollY.coerceIn(0, maxScrollY)
    val frameWidthDp = viewportWidthDp + if (maxScrollY > 0) scrollbarThickness else 0.dp

    DisposableEffect(currentHost) {
        onDispose {
            connected = false
        }
    }

    // Ordering matters for remote plugin UI setup, exactly as in uapmd-app's
    // PluginUiOverlay.scheduleSurfaceReadyNotification: the SurfaceView has to be
    // in a live view tree with a display and layout params before connect() may
    // run, because connectUINoHandler() reads surfaceView.display.displayId and
    // hands the plug-in this view's host token. Connecting during composition -
    // before Android has attached the view - raced SurfaceControlViewHost setup
    // and left the plug-in surface blank.
    DisposableEffect(currentHost) {
        val view = currentHost.surfaceView
        var cancelled = false
        val notifyWhenReady = object : Runnable {
            override fun run() {
                if (cancelled)
                    return
                if (!view.isAttachedToWindow || view.display == null || view.layoutParams == null) {
                    view.post(this)
                    return
                }
                surfaceReady = true
            }
        }
        view.post(notifyWhenReady)
        onDispose {
            cancelled = true
            surfaceReady = false
        }
    }

    DisposableEffect(state.host) {
        val listener: (Int, Int) -> Unit = { w, h ->
            // Skip notifications that arrive at the current viewport dimensions.
            // These come from relayout() calls triggered by configureViewport, not from
            // genuine content-size changes (e.g. plugin zoom level changes).
            // Also skip if nothing actually changed.
            val isViewportSized = w == viewportWidthPx && h == viewportHeightPx
            val isSameSize = w == contentWidthPx && h == contentHeightPx
            if (!isViewportSized && !isSameSize) {
                contentWidthPx = w
                contentHeightPx = h
                if (viewportWidthPx > w) viewportWidthPx = w
                if (viewportHeightPx > h) viewportHeightPx = h
            }
        }
        state.host.contentSizeChangedListeners.add(listener)
        onDispose {
            state.host.contentSizeChangedListeners.remove(listener)
        }
    }

    // Deliberately NOT keyed on the viewport size: it changes while the popup is
    // laid out, which cancelled this effect mid-connect() and ran a second
    // connect(). The service then tore down the first GUI session ("Another GUI
    // controller ... was alive. Terminating it.") and the plug-in UI stayed blank.
    LaunchedEffect(currentHost, surfaceReady) {
        if (!surfaceReady || connected)
            return@LaunchedEffect
        // Connect at the full preferred content size so JUCE's peer view is not constrained
        // to the (smaller) viewport dimensions. This allows JUCE to report its actual preferred
        // size via OPCODE_CONTENT_SIZE_CHANGED (e.g. Odin2 at 150% zoom = 1800x1200),
        // which then updates contentWidthPx and triggers a correct configureViewport call.
        currentHost.connect(state.contentWidth, state.contentHeight)
        currentHost.show()
        connected = true
    }

    LaunchedEffect(currentHost, viewportWidthPx, viewportHeightPx, contentWidthPx, contentHeightPx, effectiveScrollX, effectiveScrollY, connected) {
        if (!connected)
            return@LaunchedEffect
        currentHost.configureViewport(
            GuiHelper.ViewportConfiguration(
                viewportWidth = viewportWidthPx,
                viewportHeight = viewportHeightPx,
                contentWidth = contentWidthPx,
                contentHeight = contentHeightPx,
                scrollX = effectiveScrollX,
                scrollY = effectiveScrollY
            )
        )
    }

    // Drawn in the activity window, not in a Popup, so that text fields in the
    // plug-in UI can get the IME. aap-core (0.11.2+) hands focus to the plug-in's
    // embedded window through the SurfaceView's *host* window
    // (SurfaceView.onFocusChanged -> grantEmbeddedWindowFocus), which only takes
    // effect while that host window holds window focus. A Popup window is
    // FLAG_NOT_FOCUSABLE, so it never did; a focusable one would instead keep
    // window focus - and with it the IME and key shortcuts - away from the rest
    // of the app for as long as any plug-in UI is open. In the activity window,
    // view focus decides which side gets the keyboard, exactly as in uapmd-app's
    // PluginUiOverlay: aap-core focuses the SurfaceView when the plug-in UI is
    // touched, and Compose's interop takes that focus back when a Compose text
    // field is focused.
    Box(
        modifier = Modifier
            // Measured on its own terms, as the Popup was. The layer loses the IME
            // height (safeDrawing insets) while a keyboard is up, and that must not
            // squeeze the plug-in surface.
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                // A separate popup window swallowed every press within its bounds;
                // in-window, presses on the frame would fall through to the
                // timeline underneath unless something here is hit.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true)
                            awaitPointerEvent()
                    }
                }
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .width(frameWidthDp)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer)
                TextButton(onClick = onClose) {
                    Text("Close")
                }
            }
            Row {
                Box(
                    modifier = Modifier
                        .width(viewportWidthDp)
                        .height(viewportHeightDp)
                        .border(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { _: Context -> state.host.surfaceView },
                        onRelease = { _: View ->
                            surfaceReady = false
                            connected = false
                        },
                        onReset = { _: View -> }
                    )
                }
                if (maxScrollY > 0) {
                    ScrollbarTrack(
                        modifier = Modifier
                            .width(scrollbarThickness)
                            .height(viewportHeightDp),
                        scrollValue = effectiveScrollY,
                        maxValue = maxScrollY,
                        isHorizontal = false,
                        onScrollValueChange = { scrollY = it }
                    )
                }
            }
            Row {
                if (maxScrollX > 0) {
                    ScrollbarTrack(
                        modifier = Modifier
                            .width(viewportWidthDp)
                            .height(scrollbarThickness),
                        scrollValue = effectiveScrollX,
                        maxValue = maxScrollX,
                        isHorizontal = true,
                        onScrollValueChange = { scrollX = it }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .width(viewportWidthDp)
                            .height(resizeHandleSize)
                    )
                }
                ResizeHandle(
                    modifier = Modifier
                        .width(if (maxScrollY > 0) scrollbarThickness else resizeHandleSize)
                        .height(if (maxScrollX > 0) scrollbarThickness else resizeHandleSize),
                    onResizeStart = {
                        resizeStartWidth = viewportWidthPx
                        resizeStartHeight = viewportHeightPx
                        resizeDragWidth = 0f
                        resizeDragHeight = 0f
                    },
                    onResize = { dragWidth, dragHeight ->
                        resizeDragWidth += dragWidth
                        resizeDragHeight += dragHeight
                        viewportWidthPx = (resizeStartWidth + resizeDragWidth.roundToInt())
                            .coerceIn(minViewportWidthPx, maxViewportWidthPx)
                        viewportHeightPx = (resizeStartHeight + resizeDragHeight.roundToInt())
                            .coerceIn(minViewportHeightPx, maxViewportHeightPx)
                    }
                )
            }
        }
    }
}

@Composable
private fun ScrollbarTrack(
    modifier: Modifier,
    scrollValue: Int,
    maxValue: Int,
    isHorizontal: Boolean,
    onScrollValueChange: (Int) -> Unit
) {
    var trackSize by remember { mutableStateOf(0) }
    var dragStartScrollValue by remember { mutableStateOf(0) }
    var dragTotal by remember { mutableStateOf(0f) }
    val currentScrollValue by rememberUpdatedState(scrollValue)
    val currentOnScrollValueChange by rememberUpdatedState(onScrollValueChange)
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.2f))
            .onSizeChanged { trackSize = if (isHorizontal) it.width else it.height }
    ) {
        val contentSize = trackSize + maxValue
        val thumbSize = if (contentSize > 0)
            (trackSize * (trackSize.toFloat() / contentSize)).toInt().coerceAtLeast(24)
        else
            trackSize
        val maxThumbOffset = (trackSize - thumbSize).coerceAtLeast(0)
        val thumbOffset = if (maxValue == 0) 0 else (maxThumbOffset * (scrollValue.toFloat() / maxValue)).toInt()

        val thumbModifier = if (isHorizontal) {
            Modifier
                .width(with(density) { thumbSize.toDp() })
                .fillMaxHeight()
                .offset(x = with(density) { thumbOffset.toDp() })
                .pointerInput(maxValue, maxThumbOffset) {
                    detectDragGestures(
                        onDragStart = {
                            dragStartScrollValue = currentScrollValue
                            dragTotal = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragTotal += dragAmount.x
                            updateScrollFromDrag(
                                dragStartScrollValue,
                                dragTotal,
                                maxValue,
                                maxThumbOffset,
                                currentOnScrollValueChange
                            )
                        }
                    )
                }
        } else {
            Modifier
                .fillMaxWidth()
                .height(with(density) { thumbSize.toDp() })
                .offset(y = with(density) { thumbOffset.toDp() })
                .pointerInput(maxValue, maxThumbOffset) {
                    detectDragGestures(
                        onDragStart = {
                            dragStartScrollValue = currentScrollValue
                            dragTotal = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragTotal += dragAmount.y
                            updateScrollFromDrag(
                                dragStartScrollValue,
                                dragTotal,
                                maxValue,
                                maxThumbOffset,
                                currentOnScrollValueChange
                            )
                        }
                    )
                }
        }

        Box(thumbModifier.background(Color.White.copy(alpha = 0.75f)))
    }
}

private fun updateScrollFromDrag(
    startScrollValue: Int,
    dragAmount: Float,
    maxValue: Int,
    maxThumbOffset: Int,
    onScrollValueChange: (Int) -> Unit
) {
    if (maxThumbOffset <= 0)
        return
    val next = startScrollValue + (dragAmount * maxValue / maxThumbOffset).roundToInt()
    onScrollValueChange(next.coerceIn(0, maxValue))
}

@Composable
private fun ResizeHandle(
    modifier: Modifier,
    onResizeStart: () -> Unit,
    onResize: (dragWidth: Float, dragHeight: Float) -> Unit
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.2f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onResizeStart() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onResize(dragAmount.x, dragAmount.y)
                    }
                )
            }
    ) {
        Text(
            text = "◢",
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.align(Alignment.Center)
        )
    }
}


/**
 * Smallest usable plug-in window, matching uapmd-app's MIN_DIMENSION_DP. It is a
 * ceiling as well as a floor: a plug-in whose content is smaller than this may
 * not be stretched past its own content size.
 */
private val MIN_VIEWPORT_DIMENSION = 200.dp

/** Floor for a content size, used when a plug-in reports no preferred size. */
private const val MIN_CONTENT_FALLBACK_WIDTH = 480
private const val MIN_CONTENT_FALLBACK_HEIGHT = 320

/** Fraction of the host window a freshly opened plug-in window may occupy. */
private const val INITIAL_MAX_HOST_FRACTION = 0.8f

private fun constrainViewport(contentPx: Int, maxAvailablePx: Int, desiredPx: Int, minPx: Int): Int {
    val max = minOf(contentPx, maxAvailablePx).coerceAtLeast(1)
    return desiredPx.coerceIn(minPx.coerceAtMost(max), max)
}

/** The four fields this layer needs about a platform-hosted instance. */
internal data class HostedInstanceInfo(
    val instanceId: Int,
    val displayName: String,
    val pluginId: String,
    val aapUiHostDetails: dev.atsushieno.uapmd.AapUiHostDetails?
)
