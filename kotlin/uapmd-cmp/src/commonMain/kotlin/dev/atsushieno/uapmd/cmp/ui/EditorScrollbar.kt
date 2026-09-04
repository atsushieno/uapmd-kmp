package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Space a scrollbar takes from the editor beside it.
 *
 * Wider than the bar looks, because this is the part a finger has to hit: the
 * painted track and thumb are [ThumbThickness] centred in it and the rest is a
 * transparent gutter that still takes pointer events. A bar drawn at a size a
 * mouse can hit is roughly 2 mm across on a phone, and on touch there is no wheel
 * and no hover to expand it on approach — it has to be born big enough.
 */
val EditorScrollbarThickness = 28.dp

/** What the track and thumb are actually painted at, centred in the footprint. */
private val ThumbThickness = 12.dp

/**
 * However long the content is, a thumb shorter than this cannot be grabbed. Long
 * clips reach this floor easily, so it is a touch target rather than a hairline.
 */
private val MinThumbLength = 48.dp

/**
 * A scrollbar for a [ScrollState], for the editors that draw themselves into a
 * `Canvas` inside a scrolling viewport.
 *
 * Compose Multiplatform has no common scrollbar — `VerticalScrollbar` is desktop
 * only — and the piano roll needs one on every target it runs on. Dragging the
 * canvas is not a substitute: a clip full of notes leaves no empty space to grab,
 * and a drag that starts on a note moves the note instead of the view.
 */
@Composable
fun VerticalEditorScrollbar(state: ScrollState, modifier: Modifier = Modifier) {
    EditorScrollbar(state, vertical = true, modifier = modifier.width(EditorScrollbarThickness))
}

@Composable
fun HorizontalEditorScrollbar(state: ScrollState, modifier: Modifier = Modifier) {
    EditorScrollbar(state, vertical = false, modifier = modifier.height(EditorScrollbarThickness))
}

@Composable
private fun EditorScrollbar(state: ScrollState, vertical: Boolean, modifier: Modifier) {
    val c = editorPalette
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var trackPx by remember { mutableStateOf(0) }

    val max = state.maxValue
    // Both are only known once the scrolling container has been measured, and until
    // then maxValue is Int.MAX_VALUE — geometry from that would flash a hairline
    // thumb for a frame, so no thumb is drawn until they are real.
    val viewport = state.viewportSize
    val ready = trackPx > 0 && viewport > 0 && max > 0

    val minThumb = with(density) { MinThumbLength.toPx() }
    val thumbPx =
        if (!ready) 0f
        else (trackPx * viewport.toFloat() / (viewport.toFloat() + max.toFloat()))
            .coerceIn(minThumb.coerceAtMost(trackPx.toFloat()), trackPx.toFloat())
    val travel = (trackPx - thumbPx).coerceAtLeast(0f)
    val offsetPx = if (travel <= 0f) 0f else travel * (state.value.toFloat() / max)
    // One pixel of thumb travel is this many pixels of scroll, which is what makes a
    // drag of the thumb move the view by the matching amount.
    val scrollPerThumbPx = if (travel <= 0f) 0f else max / travel

    // The geometry changes with every pixel scrolled. Read through these rather than
    // keyed into `pointerInput`, or each scrolled pixel restarts the gesture handler
    // and the drag it is in the middle of dies.
    val geometry by rememberUpdatedState(ThumbGeometry(ready, thumbPx, offsetPx, scrollPerThumbPx, viewport, max))

    Box(
        modifier
            .onSizeChanged { trackPx = if (vertical) it.height else it.width }
            .pointerInput(vertical) {
                detectTapGestures { pos ->
                    val g = geometry
                    if (!g.ready) return@detectTapGestures
                    val at = if (vertical) pos.y else pos.x
                    // A tap on the thumb is not a page: the thumb's own handler takes
                    // drags only, so its taps fall through to here.
                    if (at >= g.offsetPx && at <= g.offsetPx + g.thumbPx) return@detectTapGestures
                    val page = if (at < g.offsetPx) -g.viewport else g.viewport
                    scope.launch { state.animateScrollTo((state.value + page).coerceIn(0, g.max)) }
                }
            }
    ) {
        // The painted track is the slim strip down the middle of the footprint; the
        // gutter either side of it stays transparent but still takes taps.
        Box(
            (if (vertical)
                Modifier.align(Alignment.TopCenter).width(ThumbThickness).fillMaxHeight()
            else
                Modifier.align(Alignment.CenterStart).height(ThumbThickness).fillMaxWidth())
                .background(c.scrollTrack)
        )
        if (!ready) return@Box
        // The thumb's *box* spans the whole footprint, so the finger has all of it to
        // land on; only the child inside it is painted at the slim width.
        Box(
            (if (vertical)
                Modifier.fillMaxWidth()
                    .height(with(density) { thumbPx.toDp() })
                    .offset(y = with(density) { offsetPx.toDp() })
            else
                Modifier.fillMaxHeight()
                    .width(with(density) { thumbPx.toDp() })
                    .offset(x = with(density) { offsetPx.toDp() }))
                .pointerInput(vertical) {
                    detectDragGestures { change, delta ->
                        change.consume()
                        // dispatchRawDelta rather than scrollTo: it lands on this
                        // event instead of in a coroutine the next event cancels, so
                        // the thumb keeps up with the pointer.
                        state.dispatchRawDelta(
                            (if (vertical) delta.y else delta.x) * geometry.scrollPerThumbPx
                        )
                    }
                }
        ) {
            Box(
                (if (vertical)
                    Modifier.width(ThumbThickness).fillMaxHeight()
                else
                    Modifier.height(ThumbThickness).fillMaxWidth())
                    .align(Alignment.Center)
                    .background(c.scrollThumb, RoundedCornerShape(ThumbThickness / 2))
            )
        }
    }
}

private data class ThumbGeometry(
    val ready: Boolean,
    val thumbPx: Float,
    val offsetPx: Float,
    val scrollPerThumbPx: Float,
    val viewport: Int,
    val max: Int
)
