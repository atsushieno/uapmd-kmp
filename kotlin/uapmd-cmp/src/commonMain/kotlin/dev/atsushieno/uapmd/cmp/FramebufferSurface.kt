package dev.atsushieno.uapmd.cmp

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Somewhere to put a plugin's frames, kept across frames rather than rebuilt for each.
 *
 * A plugin editor redraws whenever it likes, and an [ImageBitmap] built from scratch every
 * time would be a megabyte or two of garbage per frame. This holds one surface of a fixed
 * size and overwrites its pixels, which is all the change from one frame to the next
 * actually is.
 *
 * Pixels arrive as BGRA, one row every `strideBytes`, which is what JSFX draws and what
 * Skia calls BGRA_8888 — so on the Skia platforms nothing is converted at all.
 */
expect class FramebufferSurface(width: Int, height: Int) {
    val width: Int
    val height: Int

    /** Replaces the contents. `pixels` must hold at least `height * strideBytes` bytes. */
    fun update(pixels: ByteArray, strideBytes: Int)

    /** The current contents, for drawing. */
    val image: ImageBitmap
}
