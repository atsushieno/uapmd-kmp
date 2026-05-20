package dev.atsushieno.uapmd_kmp

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.atsushieno.uapmd_kmp.ui.InstanceInfo
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
    model: UapmdModel,
    modifier: Modifier = Modifier
) {
    val hostedInfos = model.platformHostedUiInstanceIds.mapNotNull { model.instanceInfos[it] }
    Box(modifier = modifier) {
        hostedInfos.forEach { info ->
            AapPluginUiPopup(
                info = info,
                onClose = { model.closePluginUi(info.instanceId) },
                onError = { model.reportPluginUiStatus(it) }
            )
        }
    }
}

@Composable
private fun AapPluginUiPopup(
    info: InstanceInfo,
    onClose: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
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
            val preferred = host.getPreferredSizeOrFallback(480, 320)
            value = AapHostLoadState.Ready(
                AapHostState(
                    host,
                    preferred.width.coerceAtLeast(240),
                    preferred.height.coerceAtLeast(180)
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
        is AapHostLoadState.Ready -> AapPluginSurfacePopup(
            title = info.displayName,
            state = state.state,
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
private fun AapPluginSurfacePopup(
    title: String,
    state: AapHostState,
    onClose: () -> Unit
) {
    var offsetX by remember(title) { mutableStateOf(32f) }
    var offsetY by remember(title) { mutableStateOf(32f) }
    var viewportWidthPx by remember(state.contentWidth) { mutableStateOf(state.contentWidth.coerceAtMost(720)) }
    var viewportHeightPx by remember(state.contentHeight) { mutableStateOf(state.contentHeight.coerceAtMost(540)) }
    var scrollX by remember { mutableStateOf(0) }
    var scrollY by remember { mutableStateOf(0) }
    var attached by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var resizeStartWidth by remember { mutableStateOf(viewportWidthPx) }
    var resizeStartHeight by remember { mutableStateOf(viewportHeightPx) }
    var resizeDragWidth by remember { mutableStateOf(0f) }
    var resizeDragHeight by remember { mutableStateOf(0f) }
    val currentHost by rememberUpdatedState(state.host)
    val density = LocalDensity.current
    val viewportWidthDp = with(density) { viewportWidthPx.toDp() }
    val viewportHeightDp = with(density) { viewportHeightPx.toDp() }
    val minViewportWidthPx = 240
    val minViewportHeightPx = 180
    val maxViewportWidthPx = state.contentWidth.coerceAtLeast(minViewportWidthPx)
    val maxViewportHeightPx = state.contentHeight.coerceAtLeast(minViewportHeightPx)
    val maxScrollX = (state.contentWidth - viewportWidthPx).coerceAtLeast(0)
    val maxScrollY = (state.contentHeight - viewportHeightPx).coerceAtLeast(0)
    val effectiveScrollX = scrollX.coerceIn(0, maxScrollX)
    val effectiveScrollY = scrollY.coerceIn(0, maxScrollY)
    val scrollbarThickness = 12.dp
    val resizeHandleSize = 20.dp
    val frameWidthDp = viewportWidthDp + if (maxScrollY > 0) scrollbarThickness else 0.dp

    DisposableEffect(currentHost) {
        onDispose {
            connected = false
        }
    }

    LaunchedEffect(attached, viewportWidthPx, viewportHeightPx, currentHost) {
        if (!attached || connected)
            return@LaunchedEffect
        currentHost.connect(viewportWidthPx, viewportHeightPx)
        currentHost.show()
        connected = true
    }

    LaunchedEffect(currentHost, viewportWidthPx, viewportHeightPx, effectiveScrollX, effectiveScrollY, state.contentWidth, state.contentHeight, connected) {
        if (!connected)
            return@LaunchedEffect
        currentHost.configureViewport(
            GuiHelper.ViewportConfiguration(
                viewportWidth = viewportWidthPx,
                viewportHeight = viewportHeightPx,
                contentWidth = state.contentWidth,
                contentHeight = state.contentHeight,
                scrollX = effectiveScrollX,
                scrollY = effectiveScrollY
            )
        )
    }

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
        properties = PopupProperties(clippingEnabled = false)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
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
                        .onSizeChanged {
                            viewportWidthPx = it.width.coerceAtLeast(1)
                            viewportHeightPx = it.height.coerceAtLeast(1)
                        }
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { _: Context ->
                            attached = true
                            state.host.surfaceView
                        },
                        onRelease = { _: View ->
                            attached = false
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
