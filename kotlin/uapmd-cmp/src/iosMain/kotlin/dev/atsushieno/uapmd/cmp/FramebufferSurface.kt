package dev.atsushieno.uapmd.cmp

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/** Skia stores BGRA_8888 in exactly the order the plugin draws, so nothing is converted. */
actual class FramebufferSurface actual constructor(
    actual val width: Int,
    actual val height: Int
) {
    private val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
    private val bitmap = Bitmap().apply { allocPixels(info) }

    actual fun update(pixels: ByteArray, strideBytes: Int) {
        bitmap.installPixels(info, pixels, strideBytes)
    }

    actual val image: ImageBitmap
        get() = bitmap.asComposeImageBitmap()
}
