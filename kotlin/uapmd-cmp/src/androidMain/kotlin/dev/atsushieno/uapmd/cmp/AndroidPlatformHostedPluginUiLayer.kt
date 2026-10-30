package dev.atsushieno.uapmd.cmp

import android.content.Context
import android.os.Build
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.atsushieno.uapmd.AapUiHostDetails
import dev.atsushieno.uapmd.cmp.ui.FloatingWindowManager
import dev.atsushieno.uapmd.cmp.ui.ResizeGripContentOverlap
import dev.atsushieno.uapmd.cmp.ui.windowSizeForContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.androidaudioplugin.hosting.GuiHelper
import kotlin.math.roundToInt

private sealed interface AapHostLoadState {
    data object Loading : AapHostLoadState
    data class Ready(val state: AapHostState) : AapHostLoadState
    data class Error(val message: String) : AapHostLoadState
}

private const val AapUiWindowKeyPrefix = "aap-ui:"

/** The window key for one instance's AAP editor. */
internal fun aapUiWindowKey(instanceId: Int): String = "$AapUiWindowKeyPrefix$instanceId"

/**
 * Keeps a floating window open for each AAP plug-in UI.
 *
 * uapmd-app ("AAP GUI can be layered appropriately now") gave an editor's title
 * bar, focus, resizing and scrollbars to its own windowing and kept only the
 * plug-in content as a live SurfaceView, placed in the window order. Here that
 * windowing is [FloatingWindowManager]: an AAP editor is an ordinary window whose
 * content is the plug-in surface, so it drags, raises and closes like the Details
 * and Plugin Selector windows, and those can be drawn over it.
 */
@Composable
internal fun AndroidPlatformHostedPluginUiLayer(host: UapmdHost, windows: FloatingWindowManager) {
    val ids = host.platformHostedUiInstanceIds

    LaunchedEffect(ids) {
        ids.forEach { instanceId ->
            val key = aapUiWindowKey(instanceId)
            if (windows.isOpen(key))
                return@forEach

            val instance = host.model.sequencer.engine.getPluginInstance(instanceId)
            val details = instance?.aapUiHostDetails
            if (instance == null || details == null) {
                host.reportPluginUiStatus(
                    "AAP UI host details are unavailable for ${instance?.displayName ?: "instance $instanceId"}."
                )
                host.hidePluginUi(instanceId)
                return@forEach
            }

            val info = HostedInstanceInfo(instanceId, instance.displayName, instance.pluginId, details)
            windows.open(
                key = key,
                title = "${instance.displayName} (${instance.formatName})",
                // Resized to the plug-in's preferred size once the plug-in reports it.
                initialSize = ProvisionalWindowSize,
                onClose = { host.hidePluginUi(instanceId) }
            ) {
                AapPluginUiWindowContent(info, windows, key) { message ->
                    host.reportPluginUiStatus(message)
                    windows.close(key)
                }
            }
        }

        // A hidden editor, or one whose plug-in is gone, takes its window with it.
        windows.closeWhere { key ->
            if (!key.startsWith(AapUiWindowKeyPrefix)) return@closeWhere false
            val id = key.removePrefix(AapUiWindowKeyPrefix).toIntOrNull()
            id != null && id !in ids
        }
    }
}

@Composable
private fun AapPluginUiWindowContent(
    info: HostedInstanceInfo,
    windows: FloatingWindowManager,
    key: String,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    // What the plug-in UI may grow to. uapmd-app reads the activity's content
    // area for this; LocalWindowInfo is the Compose equivalent and, unlike the
    // display metrics, follows folds and multi-window.
    val available = LocalWindowInfo.current.containerSize
    val hostDetails = info.aapUiHostDetails

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
            // fixed 480x320 made that its content size, so it could never use a
            // larger window; uapmd-app falls back to the host's content area instead.
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
        AapHostLoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Connecting to ${info.displayName}…", style = MaterialTheme.typography.bodySmall)
        }
        is AapHostLoadState.Error -> LaunchedEffect(state.message) { onError(state.message) }
        is AapHostLoadState.Ready -> AapPluginSurface(state.state, windows, key, available)
    }
}

private data class AapHostState(
    val host: GuiHelper.NativeEmbeddedSurfaceControlHost,
    val contentWidth: Int,
    val contentHeight: Int
)

/** The viewport configuration the remote editor last laid itself out for. */
private class AppliedViewport {
    var configuration: GuiHelper.ViewportConfiguration? = null
}

@Composable
private fun AapPluginSurface(
    state: AapHostState,
    windows: FloatingWindowManager,
    key: String,
    available: IntSize
) {
    val density = LocalDensity.current
    val barPx = with(density) { ScrollbarThickness.roundToPx() }
    // The window's resize corner reaches into the bottom-right of its content.
    // The surface stays clear of it: a surface above the window takes the
    // touches there, and the corner could no longer be grabbed.
    val gripPx = with(density) { ResizeGripContentOverlap.roundToPx() }
    val surfaceView = state.host.surfaceView as SurfaceView

    var contentWidthPx by remember(state) { mutableStateOf(state.contentWidth) }
    var contentHeightPx by remember(state) { mutableStateOf(state.contentHeight) }
    var areaPx by remember(state) { mutableStateOf(IntSize.Zero) }
    var scrollX by remember(state) { mutableStateOf(0) }
    var scrollY by remember(state) { mutableStateOf(0) }
    var surfaceReady by remember(state) { mutableStateOf(false) }
    var connected by remember(state) { mutableStateOf(false) }
    val applied = remember(state) { AppliedViewport() }
    val currentHost by rememberUpdatedState(state.host)

    // The viewport is whatever the window leaves for it. A vertical scrollbar
    // takes room only when the content overflows; the bottom strip is there
    // anyway, for the resize corner, and holds the horizontal one.
    val vertical = contentHeightPx > areaPx.height - gripPx
    val viewportWidthPx = (areaPx.width - (if (vertical) barPx else 0)).coerceAtLeast(1)
    val viewportHeightPx = (areaPx.height - gripPx).coerceAtLeast(1)
    val horizontal = contentWidthPx > viewportWidthPx
    val maxScrollX = (contentWidthPx - viewportWidthPx).coerceAtLeast(0)
    val maxScrollY = (contentHeightPx - viewportHeightPx).coerceAtLeast(0)
    val effectiveScrollX = scrollX.coerceIn(0, maxScrollX)
    val effectiveScrollY = scrollY.coerceIn(0, maxScrollY)

    // The preferred size asks for a window size once, as uapmd-app's
    // resizeContent() does: at most 80% of the host window, plus whatever
    // scrollbar that leaves necessary. Later content measurements only move the
    // scrollbars. Growing the window with them fed a responsive (Compose)
    // editor's layout back into another, larger window size.
    LaunchedEffect(state) {
        val viewportW = state.contentWidth.coerceAtMost((available.width * INITIAL_MAX_HOST_FRACTION).toInt())
        val viewportH = state.contentHeight.coerceAtMost((available.height * INITIAL_MAX_HOST_FRACTION).toInt())
        val areaW = viewportW + if (state.contentHeight > viewportH) barPx else 0
        val areaH = viewportH + gripPx
        with(density) {
            val size = windowSizeForContent(DpSize(areaW.toDp(), areaH.toDp()))
            windows.resize(
                key,
                DpSize(
                    size.width.coerceAtMost((available.width * MAX_WINDOW_HOST_FRACTION).toDp()),
                    size.height.coerceAtMost((available.height * MAX_WINDOW_HOST_FRACTION).toDp())
                )
            )
        }
    }

    // Where the surface sits in the window stack; see applyWindowStackOrder().
    val front = windows.isFrontmost(key)
    val stackIndex = windows.stackIndex(key)
    val stackSize = windows.windowCount
    LaunchedEffect(surfaceView, front, stackIndex, stackSize) {
        surfaceView.applyWindowStackOrder(front, stackIndex, stackSize)
    }

    // Ordering matters for remote plugin UI setup, exactly as in uapmd-app's
    // PluginUiOverlay: the SurfaceView has to be in a live view tree with a
    // display and layout params before connect() may run, because
    // connectUINoHandler() reads surfaceView.display.displayId and hands the
    // plug-in this view's host token. Connecting during composition - before
    // Android has attached the view - raced SurfaceControlViewHost setup and
    // left the plug-in surface blank.
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
            connected = false
        }
    }

    DisposableEffect(state.host) {
        // Delivered on the main looper by aap-core.
        val listener: (Int, Int) -> Unit = { w, h ->
            if (w > 0 && h > 0 && (w != contentWidthPx || h != contentHeightPx)) {
                // The remote editor already laid itself out at this size: take it
                // as the content size, rather than echoing it back as another
                // relayout request (uapmd-app does the same).
                applied.configuration = applied.configuration?.copy(contentWidth = w, contentHeight = h)
                contentWidthPx = w
                contentHeightPx = h
            }
        }
        state.host.contentSizeChangedListeners.add(listener)
        onDispose {
            state.host.contentSizeChangedListeners.remove(listener)
        }
    }

    // Deliberately NOT keyed on the viewport size: it changes while the window is
    // laid out, which cancelled this effect mid-connect() and ran a second
    // connect(). The service then tore down the first GUI session ("Another GUI
    // controller ... was alive. Terminating it.") and the plug-in UI stayed blank.
    LaunchedEffect(currentHost, surfaceReady) {
        if (!surfaceReady || connected)
            return@LaunchedEffect
        // Connect at the full preferred content size so JUCE's peer view is not
        // constrained to the (smaller) viewport. JUCE then reports its actual size
        // via OPCODE_CONTENT_SIZE_CHANGED (e.g. Odin2 at 150% zoom = 1800x1200).
        currentHost.connect(state.contentWidth, state.contentHeight)
        currentHost.show()
        connected = true
    }

    LaunchedEffect(
        currentHost, viewportWidthPx, viewportHeightPx, contentWidthPx, contentHeightPx,
        effectiveScrollX, effectiveScrollY, connected
    ) {
        if (!connected || areaPx == IntSize.Zero)
            return@LaunchedEffect
        val configuration = GuiHelper.ViewportConfiguration(
            viewportWidth = viewportWidthPx,
            viewportHeight = viewportHeightPx,
            contentWidth = contentWidthPx,
            contentHeight = contentHeightPx,
            scrollX = effectiveScrollX,
            scrollY = effectiveScrollY
        )
        if (applied.configuration == configuration)
            return@LaunchedEffect
        applied.configuration = configuration
        currentHost.configureViewport(configuration)
    }

    // In the activity window, not in a Popup, so that text fields in the plug-in
    // UI can get the IME: aap-core hands focus to the plug-in's embedded window
    // through the SurfaceView's *host* window (grantEmbeddedWindowFocus), which
    // only takes effect while that host window holds window focus.
    Box(Modifier.fillMaxSize().onSizeChanged { areaPx = it }) {
        if (areaPx.width <= 0 || areaPx.height <= 0)
            return@Box
        val viewportWidthDp = with(density) { viewportWidthPx.toDp() }
        val viewportHeightDp = with(density) { viewportHeightPx.toDp() }
        AndroidView(
            modifier = Modifier.size(viewportWidthDp, viewportHeightDp),
            factory = { _: Context ->
                // Placed before it is attached, so it never shows above windows it should not.
                surfaceView.also { it.applyWindowStackOrder(front, stackIndex, stackSize) }
            },
            onRelease = { _: View ->
                surfaceReady = false
                connected = false
            },
            onReset = { _: View -> }
        )
        if (vertical) {
            ScrollbarTrack(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(ScrollbarThickness)
                    .height(viewportHeightDp),
                scrollValue = effectiveScrollY,
                maxValue = maxScrollY,
                isHorizontal = false,
                onScrollValueChange = { scrollY = it }
            )
        }
        if (horizontal) {
            ScrollbarTrack(
                modifier = Modifier
                    .offset { IntOffset(0, viewportHeightPx) }
                    .width(with(density) { (areaPx.width - gripPx).coerceAtLeast(1).toDp() })
                    .height(ScrollbarThickness),
                scrollValue = effectiveScrollX,
                maxValue = maxScrollX,
                isHorizontal = true,
                onScrollValueChange = { scrollX = it }
            )
        }
    }
}

/**
 * Places the plug-in surface in the window stack, as uapmd-app now does.
 *
 * Behind the activity window, a SurfaceView shows through the hole it punches
 * when it is drawn, in Compose's draw order, so a window drawn later covers it
 * like any other window's content - including another editor's. Android
 * delivers no input to an embedded window behind its host, though, so the front
 * editor, which nothing is drawn over, goes above the window, where its plug-in
 * receives touches directly. A background editor still gets the touch that
 * raises it: Compose hands it to the SurfaceView, and aap-core transfers that
 * gesture to the plug-in on Android 15 and later.
 */
private fun SurfaceView.applyWindowStackOrder(front: Boolean, stackIndex: Int, stackSize: Int) {
    if (Build.VERSION.SDK_INT >= 36) {
        // Negative orders are behind the window and a higher order is above a
        // lower one, so background editors keep the order of their windows.
        setCompositionOrder(if (front) 1 else stackIndex - stackSize - 1)
    } else {
        // Earlier releases have a single order behind the window, so where two
        // background editors overlap either may show.
        setZOrderOnTop(front)
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

private val ScrollbarThickness = 12.dp

/** Until the plug-in reports the size it wants. */
private val ProvisionalWindowSize = DpSize(480.dp, 360.dp)

/** Floor for a content size, used when a plug-in reports no preferred size. */
private const val MIN_CONTENT_FALLBACK_WIDTH = 480
private const val MIN_CONTENT_FALLBACK_HEIGHT = 320

/** Fraction of the host window a freshly opened plug-in viewport may occupy. */
private const val INITIAL_MAX_HOST_FRACTION = 0.8f

/** Fraction of the host window a freshly opened plug-in window may occupy, chrome included. */
private const val MAX_WINDOW_HOST_FRACTION = 0.85f

/** The four fields this layer needs about a platform-hosted instance. */
internal data class HostedInstanceInfo(
    val instanceId: Int,
    val displayName: String,
    val pluginId: String,
    val aapUiHostDetails: AapUiHostDetails
)
