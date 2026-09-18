package dev.atsushieno.uapmd.cmp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dev.atsushieno.uapmd.PluginInstanceConfig
import dev.atsushieno.uapmd.cleanupAppModel
import dev.atsushieno.uapmd.getAppModel
import dev.atsushieno.uapmd.initJvmEventLoop
import dev.atsushieno.uapmd.instantiateAppModel

/**
 * Headless check of the framebuffer editor path, below Compose.
 *
 * The editor drew nothing in the application and the question is where the chain stops:
 * whether the instance reports a framebuffer editor at all, whether the plugin ever draws
 * a frame, and whether the frame that comes back holds anything. Compose is not involved,
 * so a pass here puts the fault in the renderer and a failure puts it below.
 *
 * Run with: ./gradlew :uapmd-cmp:runFramebufferUiProbe
 */
// What JNA thinks these structs look like, for comparison with the C header. A Java
// layout that disagrees means native code reads or writes past the end of JNA's buffer,
// which corrupts memory rather than failing.
private fun reportStructLayouts() {
    val m = com.sun.jna.Structure::class.java.getDeclaredMethod("fieldOffset", String::class.java)
    m.isAccessible = true
    fun show(name: String, st: com.sun.jna.Structure, vararg fields: String) {
        st.size()
        println("  %-11s size=%d  %s".format(name, st.size(),
            fields.joinToString(" ") { "$it=${m.invoke(st, it)}" }))
    }
    println("JNA struct layouts:")
    show("frame_info", dev.atsushieno.uapmd.jna.UapmdFbuiFrameInfo(),
        "width", "height", "strideBytes", "format", "serial")
    show("input", dev.atsushieno.uapmd.jna.UapmdFbuiInput(),
        "wheel", "horizontalWheel", "hasFocus", "visible", "pointerOver")
    show("key_event", dev.atsushieno.uapmd.jna.UapmdFbuiKeyEvent(),
        "modifiers", "character", "key", "pressed")
    show("menu_item", dev.atsushieno.uapmd.jna.UapmdFbuiMenuItem(),
        "label", "id", "disabled", "checked", "separator", "childCount")
}

fun main() {
    reportStructLayouts()
    initJvmEventLoop()
    instantiateAppModel()
    val model = getAppModel()
    model.notifyUiReady()
    model.notifyPersistentStorageReady()
    model.setAudioEngineEnabled(true)

    // The model scans on its own once the UI and storage are ready. Starting a second
    // scan alongside it races the first, and instantiation then looks the plug-in up in a
    // catalogue that is being rebuilt underneath it.
    val scanDeadline = System.currentTimeMillis() + 120000
    while (model.isScanning && System.currentTimeMillis() < scanDeadline)
        Thread.sleep(100)
    Thread.sleep(500)

    val pluginHost = model.sequencer.engine.pluginHost
    val catalog = (0 until pluginHost.catalogEntryCount.toInt())
        .mapNotNull { pluginHost.getCatalogEntry(it.toUInt()) }
    // Most JSFX effects have no @gfx section at all and so have no editor; picking the
    // first one in the catalogue proves nothing. -Duapmd.probe.plugin names one.
    val wanted = System.getProperty("uapmd.probe.plugin") ?: "OTT"
    val jsfxEntries = catalog.filter { it.format == "JSFX" }
    val jsfx = jsfxEntries.firstOrNull { it.displayName.contains(wanted, ignoreCase = true) }
        ?: jsfxEntries.firstOrNull()
    if (jsfx == null) {
        println("FAIL: no JSFX effect in the catalogue (${catalog.size} entries)")
        cleanupAppModel()
        return
    }
    println("${jsfxEntries.size} JSFX effects; matching \"$wanted\"")
    println("using ${jsfx.displayName} (${jsfx.pluginId})")

    var instanceId = -1
    var error: String? = null
    var done = false
    model.createPluginInstance(jsfx.format, jsfx.pluginId, -1, PluginInstanceConfig()) { result ->
        instanceId = result.instanceId
        error = result.error
        done = true
    }
    val deadline = System.currentTimeMillis() + 15000
    while (!done && System.currentTimeMillis() < deadline)
        Thread.sleep(20)
    if (instanceId < 0) {
        println("FAIL: could not instantiate: ${error ?: "timed out"}")
        cleanupAppModel()
        return
    }

    val instance = model.sequencer.engine.getPluginInstance(instanceId)
    if (instance == null) {
        println("FAIL: instance $instanceId is not retrievable")
        cleanupAppModel()
        return
    }
    println("instance $instanceId: hasUiSupport=${instance.hasUiSupport}")


    val ui = instance.framebufferUi
    if (ui == null) {
        println("FAIL: framebufferUi is null -- the binding does not see the extension")
        cleanupAppModel()
        return
    }

    val preferred = ui.preferredSize
    println("preferredSize=$preferred")
    val width = preferred?.width ?: 640
    val height = preferred?.height ?: 480
    ui.setSurfaceSize(width, height, 1.0)
    ui.setDisplayed(true)

    // The plugin renders on its own thread, so give it a moment and watch the serial.
    var info = ui.frameInfo()
    val frameDeadline = System.currentTimeMillis() + 5000
    while (info == null && System.currentTimeMillis() < frameDeadline) {
        Thread.sleep(50)
        info = ui.frameInfo()
    }
    if (info == null) {
        println("FAIL: the plugin never reported a frame (displayed=true, ${width}x${height})")
        ui.setDisplayed(false)
        cleanupAppModel()
        return
    }
    println("frameInfo: ${info.width}x${info.height} stride=${info.strideBytes} " +
            "format=${info.format} serial=${info.serial}")

    val pixels = ByteArray(info.height * info.width * 4)
    val copied = ui.copyFrame(pixels, info.width * 4)
    if (copied == null) {
        println("FAIL: copyFrame refused ${pixels.size} bytes at stride ${info.width * 4}")
        ui.setDisplayed(false)
        cleanupAppModel()
        return
    }

    // A frame of nothing but zeroes is a frame the plugin has not really drawn into.
    var nonZero = 0
    for (b in pixels) if (b.toInt() != 0) nonZero++
    println("copied serial=${copied.serial} bytes=${pixels.size} nonZeroBytes=$nonZero")

    // And does it keep drawing?
    Thread.sleep(500)
    val later = ui.frameInfo()
    println("serial after 500ms: ${later?.serial} (was ${copied.serial})")

    // And the last layer below the composable: the same pixels into the surface Compose
    // draws from. A window is not needed to find out whether they survive the trip.
    val surface = FramebufferSurface(copied.width, copied.height)
    surface.update(pixels, copied.width * 4)
    val readBack = IntArray(copied.width * copied.height)
    surface.image.readPixels(readBack, 0, 0, copied.width, copied.height)
    var opaqueLit = 0
    for (argb in readBack) if ((argb and 0x00FFFFFF) != 0) opaqueLit++
    println("surface: ${surface.width}x${surface.height} litPixels=$opaqueLit of ${readBack.size}")

    // How often the plugin actually redraws. JSFX reads the mouse inside @gfx, so this
    // rate *is* the rate at which a drag is noticed, however fast events are delivered --
    // an editor redrawing a couple of times a second cannot be dragged, however correct
    // each frame is, which is why it is part of the verdict below.
    var dragRate = 0.0
    run {
        fun rateOver(seconds: Long): Double {
            val from = ui.frameInfo()?.serial ?: 0
            val at = System.nanoTime()
            Thread.sleep(seconds * 1000)
            val to = ui.frameInfo()?.serial ?: 0
            return (to - from) / ((System.nanoTime() - at) / 1e9)
        }
        println("idle redraw rate: %.1f fps".format(rateOver(1)))

        val startSerial = ui.frameInfo()?.serial ?: 0
        val startedAt = System.nanoTime()
        // Pretend the pointer is being dragged across the editor, as a user would.
        var x = 100
        while (System.nanoTime() - startedAt < 2_000_000_000L) {
            ui.deliverInput(
                dev.atsushieno.uapmd.FramebufferInput(
                    pointerX = x, pointerY = 200,
                    buttons = dev.atsushieno.uapmd.FramebufferButtons.LEFT,
                    hasFocus = true, visible = true, pointerOver = true
                )
            )
            x = if (x > 900) 100 else x + 3
            Thread.sleep(8)
        }
        val elapsed = (System.nanoTime() - startedAt) / 1e9
        dragRate = ((ui.frameInfo()?.serial ?: 0) - startSerial) / elapsed
        println("redraw rate while dragged: %.1f fps".format(dragRate))
    }

    // Every surface this reaches declares its pixels opaque, so the frames have to be.
    // Alpha that is merely left over from whatever the buffer held is not a harmless
    // detail: a host that composites with it makes the editor pulse as it redraws, and
    // the value drifts frame to frame, so a single frame cannot show it.
    var framesOpaque = true
    run {
        var seen = -1L
        var n = 0
        val until = System.currentTimeMillis() + 3000
        while (n < 6 && System.currentTimeMillis() < until) {
            val fi = ui.frameInfo() ?: break
            if (fi.serial == seen) { Thread.sleep(2); continue }
            seen = fi.serial
            val buf = ByteArray(fi.height * fi.width * 4)
            if (ui.copyFrame(buf, fi.width * 4) == null) continue
            var opaque = 0
            for (k in 3 until buf.size step 4) if (buf[k].toInt() and 0xFF == 255) opaque++
            val fraction = 4.0 * opaque / buf.size
            if (fraction < 1.0) {
                framesOpaque = false
                println("frame $n is only %.1f%% opaque".format(100 * fraction))
            }
            n++
        }
        println("frames opaque: $framesOpaque")
    }

    // The editor as Compose actually draws it. Everything above tests the binding; this
    // renders the real composable off-screen, which is the only check here that would
    // notice the editor drawing nothing, or drawing one frame and then freezing.
    var composedBlank = 0
    var composedDistinct = 0
    run {
        val w = 1080
        val h = 400
        val scene = androidx.compose.ui.ImageComposeScene(w, h) {
            FramebufferPluginEditor(ui, Modifier.fillMaxSize())
        }
        val sums = mutableListOf<Long>()
        var t = 0L
        repeat(40) {
            t += 16_000_000L
            val img = scene.render(t)
            val bmp = org.jetbrains.skia.Bitmap().apply {
                allocPixels(org.jetbrains.skia.ImageInfo(w, h,
                    org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE))
            }
            img.readPixels(bmp, 0, 0)
            val px = bmp.readPixels() ?: return@repeat
            var n = 0
            var sum = 0L
            for (k in px.indices step 4) {
                if (px[k].toInt() != 0 || px[k+1].toInt() != 0 || px[k+2].toInt() != 0) n++
                sum = sum * 31 + (px[k].toInt() and 0xFF)
            }
            if (n * 4.0 / px.size < 0.10) composedBlank++
            sums.add(sum)
            Thread.sleep(16)
        }
        scene.close()
        composedDistinct = sums.distinct().size
        println("composed ${sums.size} frames: $composedBlank blank, $composedDistinct distinct")
    }

    // Open and close the editor the way closing its window does, with input still
    // arriving across the close: the plugin's renderer does not stop when the editor goes,
    // so anything the host handed it has to outlive the teardown.
    run {
        repeat(3) {
            val scene = androidx.compose.ui.ImageComposeScene(600, 300) {
                FramebufferPluginEditor(ui, Modifier.fillMaxSize())
            }
            var t = 0L
            var x = 100
            repeat(8) {
                t += 16_000_000L
                x = if (x > 500) 100 else x + 23
                ui.deliverInput(dev.atsushieno.uapmd.FramebufferInput(
                    pointerX = x, pointerY = 150,
                    buttons = dev.atsushieno.uapmd.FramebufferButtons.LEFT,
                    hasFocus = true, visible = true, pointerOver = true))
                scene.render(t); Thread.sleep(16)
            }
            scene.close()
            repeat(20) {
                ui.deliverInput(dev.atsushieno.uapmd.FramebufferInput(
                    pointerX = 200, pointerY = 150, hasFocus = false,
                    visible = false, pointerOver = false))
                if (it % 10 == 0) System.gc()
                Thread.sleep(20)
            }
        }
        println("editor opened and closed 3 times without crashing")
    }

    // Resizing the window has to reach the plugin, or it keeps drawing at its old size
    // in the corner of a larger window.
    val resizedWidth = 820
    val resizedHeight = 300
    ui.setSurfaceSize(resizedWidth, resizedHeight, 1.0)
    var resized = ui.frameInfo()
    val resizeDeadline = System.currentTimeMillis() + 3000
    while (System.currentTimeMillis() < resizeDeadline &&
           (resized == null || resized.width != resizedWidth || resized.height != resizedHeight)) {
        Thread.sleep(50)
        resized = ui.frameInfo()
    }
    val resizeOk = resized != null && resized.width == resizedWidth && resized.height == resizedHeight
    println("after setSurfaceSize(${resizedWidth}x$resizedHeight): " +
            "${resized?.width}x${resized?.height} serial=${resized?.serial}")

    // Ten is well under the rate a plugin asks for and well over the rate a hidden
    // editor is ticked at, so it separates the two without pinning down either.
    val responsive = dragRate >= 10.0
    // More than one distinct frame is the point: a frozen editor renders perfectly well.
    val composes = composedBlank == 0 && composedDistinct > 1
    val ok = nonZero > 0 && opaqueLit > 0 && resizeOk && responsive && composes && framesOpaque
    println(
        when {
            ok -> "PASS: the plugin draws at a usable rate, the binding reads it, " +
                    "and Compose renders it frame by frame"
            nonZero == 0 -> "FAIL: frames arrive but every byte is zero"
            !resizeOk -> "FAIL: the plugin ignored the new surface size"
            !responsive -> "FAIL: the plugin redraws at %.1f fps -- too slow to be dragged"
                .format(dragRate)
            composedBlank > 0 -> "FAIL: Compose drew nothing on $composedBlank of 40 frames"
            composedDistinct <= 1 -> "FAIL: Compose drew the same frame throughout -- frozen"
            !framesOpaque -> "FAIL: the plugin's frames are not opaque, so hosts that " +
                "composite with the alpha will see the editor flicker"
            else -> "FAIL: the frame has pixels but the Compose surface came back blank"
        }
    )

    ui.setDisplayed(false)
    cleanupAppModel()
}
