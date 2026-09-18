package dev.atsushieno.uapmd

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import dev.atsushieno.uapmd.jna.*

/**
 * [FramebufferUi] over the C entry points.
 *
 * The callbacks the plugin uses arrive on whatever thread it renders on, which is not
 * the UI thread. Nothing here hops threads on the caller's behalf: a host that touches
 * UI state from them must marshal it itself, and the Compose host does.
 */
internal class JvmFramebufferUi(private val handle: Pointer) : FramebufferUi {

    // JNA collects a callback as soon as nothing on the Kotlin side references it, and
    // the plugin would then call into freed memory. They live as long as the host does.
    private var menuCallback: FbuiMenuCb? = null
    private var cursorCallback: FbuiCursorCb? = null
    private var droppedFileCallback: FbuiDroppedFileCb? = null

    override val preferredSize: FramebufferSize?
        get() {
            val width = IntByReference()
            val height = IntByReference()
            if (!lib.uapmd_instance_fbui_preferred_size(handle, width, height))
                return null
            return FramebufferSize(width.value, height.value)
        }

    override fun setSurfaceSize(width: Int, height: Int, scaleFactor: Double) {
        lib.uapmd_instance_fbui_set_surface_size(handle, width, height, scaleFactor)
    }

    override fun frameInfo(): FramebufferFrameInfo? {
        val info = UapmdFbuiFrameInfo()
        if (!lib.uapmd_instance_fbui_frame_info(handle, info))
            return null
        return info.toCommon()
    }

    override fun copyFrame(destination: ByteArray, destinationStride: Int): FramebufferFrameInfo? {
        val info = UapmdFbuiFrameInfo()
        val copied = lib.uapmd_instance_fbui_copy_frame(
            handle, destination, destination.size.toLong(), destinationStride, info)
        return if (copied) info.toCommon() else null
    }

    // Reused across calls. A JNA Structure allocates native memory and lays its fields
    // out on construction, and this is called for every pointer event a drag produces.
    private val nativeInput = UapmdFbuiInput()

    override fun deliverInput(input: FramebufferInput, keys: List<FramebufferKeyEvent>) {
        val native = nativeInput.apply {
            pointerX = input.pointerX
            pointerY = input.pointerY
            buttons = input.buttons
            modifiers = input.modifiers
            wheel = input.wheel
            horizontalWheel = input.horizontalWheel
            hasFocus = if (input.hasFocus) 1 else 0
            visible = if (input.visible) 1 else 0
            pointerOver = if (input.pointerOver) 1 else 0
        }
        if (keys.isEmpty()) {
            lib.uapmd_instance_fbui_deliver_input(handle, native, null, 0)
            return
        }
        // Contiguous, because the C side reads it as an array.
        val template = UapmdFbuiKeyEvent()
        @Suppress("UNCHECKED_CAST")
        val array = template.toArray(keys.size) as Array<UapmdFbuiKeyEvent>
        keys.forEachIndexed { index, event ->
            array[index].apply {
                modifiers = event.modifiers
                character = event.character
                key = event.key.ordinal
                pressed = if (event.pressed) 1 else 0
                write()
            }
        }
        lib.uapmd_instance_fbui_deliver_input(
            handle, native, array[0].pointer, keys.size.toLong())
    }

    override fun setDisplayed(displayed: Boolean) {
        lib.uapmd_instance_fbui_set_displayed(handle, if (displayed) 1 else 0)
    }

    override fun setHost(host: FramebufferUiHost?) {
        if (host == null) {
            lib.uapmd_instance_fbui_set_host(handle, null, null, null, null)
            menuCallback = null
            cursorCallback = null
            droppedFileCallback = null
            return
        }

        menuCallback = object : FbuiMenuCb {
            override fun invoke(request: Pointer?, items: Pointer?, itemCount: Long,
                                x: Int, y: Int, userData: Pointer?) {
                val parsed = readMenu(items, itemCount.toInt())
                host.requestMenu(parsed, x, y) { chosen ->
                    lib.uapmd_instance_fbui_complete_menu(request, chosen)
                }
            }
        }
        cursorCallback = object : FbuiCursorCb {
            override fun invoke(cursor: Int, userData: Pointer?) {
                val shapes = FramebufferCursor.entries
                host.setCursor(shapes.getOrElse(cursor) { FramebufferCursor.ARROW })
            }
        }
        droppedFileCallback = object : FbuiDroppedFileCb {
            override fun invoke(index: Int, buf: Pointer?, bufSize: Long, userData: Pointer?): Long {
                val path = host.droppedFile(index) ?: return 0
                if (buf == null || bufSize <= 0) return 0
                val bytes = path.encodeToByteArray()
                val length = minOf(bytes.size.toLong(), bufSize - 1).toInt()
                buf.write(0, bytes, 0, length)
                buf.setByte(length.toLong(), 0)
                return length.toLong()
            }
        }
        lib.uapmd_instance_fbui_set_host(
            handle, menuCallback, cursorCallback, droppedFileCallback, null)
    }

    private fun UapmdFbuiFrameInfo.toCommon() = FramebufferFrameInfo(
        width = width,
        height = height,
        strideBytes = strideBytes,
        format = if (format == 1) FramebufferPixelFormat.RGBA8 else FramebufferPixelFormat.BGRA8,
        serial = serial
    )

    /**
     * The menu arrives flattened depth first: an item declaring N children is followed by
     * exactly those N, each of which may declare children of its own. Rebuilding the tree
     * is therefore a walk with an index that the recursion advances.
     */
    private fun readMenu(items: Pointer?, count: Int): List<FramebufferMenuItem> {
        if (items == null || count <= 0) return emptyList()
        val first = UapmdFbuiMenuItem(items).apply { read() }
        @Suppress("UNCHECKED_CAST")
        val flat = first.toArray(count) as Array<UapmdFbuiMenuItem>
        flat.forEach { it.read() }

        var index = 0
        fun take(howMany: Int): List<FramebufferMenuItem> {
            val out = ArrayList<FramebufferMenuItem>(howMany)
            repeat(howMany) {
                if (index >= flat.size) return@repeat
                val native = flat[index++]
                val children = take(native.childCount)
                out.add(
                    FramebufferMenuItem(
                        label = native.label ?: "",
                        id = native.id,
                        disabled = native.disabled.toInt() != 0,
                        checked = native.checked.toInt() != 0,
                        separator = native.separator.toInt() != 0,
                        children = children
                    )
                )
            }
            return out
        }
        return take(count)
    }
}
