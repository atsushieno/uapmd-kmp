package dev.atsushieno.uapmd.cmp

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.atsushieno.uapmd.FramebufferPixelFormat
import dev.atsushieno.uapmd.FramebufferUi
import dev.atsushieno.uapmd.WasmJsFramebufferUi
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.webext.installPixelsFromArrayBuffer
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.khronos.webgl.ArrayBuffer

/**
 * The pixels go from the uapmd module's memory to Skia's in two bulk copies, through an
 * ArrayBuffer, and never through a ByteArray: skiko fills a bitmap from a ByteArray one
 * JavaScript call per byte, and reading the ByteArray out of uapmd costs the same again --
 * several million calls each time a 1080x400 editor redraws.
 *
 * Unlike on desktop, installing outside withFrameNanos cannot tear the editor: the page
 * has one thread, so a frame is whole before Compose next draws.
 */
@OptIn(ExperimentalSkikoApi::class)
internal actual suspend fun pollFramebufferFrames(ui: FramebufferUi, onFrame: (ImageBitmap) -> Unit) {
    if (ui !is WasmJsFramebufferUi)
        return pollFramebufferFramesThroughByteArray(ui, onFrame)

    // One bitmap, pointed at each frame's pixels in turn; Skia frees the ones it replaces.
    val bitmap = Bitmap()
    var lastSerial = -1L
    while (true) {
        val frame = withFrameNanos {
            val info = ui.frameInfo()
            if (info == null || info.serial == lastSerial) null else ui.copyFrameToArrayBuffer()
        } ?: continue
        val colorType =
            if (frame.info.format == FramebufferPixelFormat.RGBA8) ColorType.RGBA_8888 else ColorType.BGRA_8888
        val info = ImageInfo(frame.info.width, frame.info.height, colorType, ColorAlphaType.OPAQUE)
        if (bitmap.installPixelsFromArrayBuffer(info, frame.pixels.unsafeCast<ArrayBuffer>(), frame.info.strideBytes)) {
            lastSerial = frame.info.serial
            onFrame(bitmap.asComposeImageBitmap())
        }
    }
}
