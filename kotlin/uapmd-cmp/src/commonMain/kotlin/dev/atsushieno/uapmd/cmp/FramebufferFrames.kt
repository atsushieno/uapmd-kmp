package dev.atsushieno.uapmd.cmp

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import dev.atsushieno.uapmd.FramebufferUi

/**
 * Hands [onFrame] each new frame the plugin draws, paced by the display, until the caller
 * is cancelled.
 *
 * Per platform because getting the pixels across is. On the web the plugin's frame,
 * Kotlin's heap and Skia's are three separate wasm memories, and a [ByteArray] in the
 * middle costs a JavaScript call per byte on each side of it.
 */
internal expect suspend fun pollFramebufferFrames(ui: FramebufferUi, onFrame: (ImageBitmap) -> Unit)

/**
 * [pollFramebufferFrames] through a [ByteArray] and a [FramebufferSurface], for platforms
 * where the plugin's memory is the app's own.
 *
 * Done on the display's thread deliberately. The surface is a Skia bitmap whose pixels
 * Compose reads when it draws, so writing it from anywhere else races the renderer and
 * tears the editor. Inside withFrameNanos the write lands between frames instead. It costs
 * about 250us for a 1080x400 editor -- under one percent of the frame budget at the rate a
 * plugin asks to be drawn at, so there is nothing to gain by moving it off and a visibly
 * torn editor to lose.
 */
internal suspend fun pollFramebufferFramesThroughByteArray(ui: FramebufferUi, onFrame: (ImageBitmap) -> Unit) {
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
                    onFrame(target.image)
                }
            }
        }
    }
}
