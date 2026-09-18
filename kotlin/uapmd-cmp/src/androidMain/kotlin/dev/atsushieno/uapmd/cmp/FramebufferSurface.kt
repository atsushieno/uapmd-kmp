package dev.atsushieno.uapmd.cmp

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android has no BGRA configuration, so this is the one platform where the pixels have to
 * be rearranged. ARGB_8888 holds them as R, G, B, A in memory, which is the plugin's order
 * with the red and blue ends swapped.
 */
actual class FramebufferSurface actual constructor(
    actual val width: Int,
    actual val height: Int
) {
    private val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val argb = IntArray(width * height)

    actual fun update(pixels: ByteArray, strideBytes: Int) {
        for (y in 0 until height) {
            var src = y * strideBytes
            var dst = y * width
            for (x in 0 until width) {
                val b = pixels[src].toInt() and 0xFF
                val g = pixels[src + 1].toInt() and 0xFF
                val r = pixels[src + 2].toInt() and 0xFF
                // The plugin draws opaque; its own alpha channel is not meaningful.
                argb[dst] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                src += 4
                dst++
            }
        }
        bitmap.setPixels(argb, 0, width, 0, 0, width, height)
    }

    actual val image: ImageBitmap
        get() = bitmap.asImageBitmap()
}
