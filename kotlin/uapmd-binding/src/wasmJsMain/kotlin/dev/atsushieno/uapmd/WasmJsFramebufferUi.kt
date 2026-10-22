package dev.atsushieno.uapmd

// wasm32 layouts (verify with emcc -fdump-record-layouts-complete when they change):
//   uapmd_fbui_frame_info_t  width@0 height@4 stride_bytes@8 format@12 serial@16       (24)
//   uapmd_fbui_input_t       pointer_x@0 pointer_y@4 buttons@8 modifiers@12 wheel@16
//                            horizontal_wheel@24 has_focus@32 visible@33 pointer_over@34 (40)
//   uapmd_fbui_key_event_t   modifiers@0 character@4 key@8 pressed@12                 (16)
//   uapmd_fbui_menu_item_t   label@0 id@4 disabled@8 checked@9 separator@10
//                            child_count@12                                           (16)
private const val FRAME_INFO_SIZE = 24
private const val INPUT_SIZE = 40
private const val KEY_EVENT_SIZE = 16
private const val MENU_ITEM_SIZE = 16

// getValue() refreshes Module.HEAPU8 when another thread has grown memory since the main
// thread last looked; sliced through a stale view, the copy would come back short.
@JsFun("(mod, ptr, n) => { mod.getValue(ptr, 'i8'); return mod.HEAPU8.slice(ptr, ptr + n).buffer; }")
private external fun wasmHeapToArrayBuffer(mod: UapmdCApiModule, ptr: Int, size: Int): JsAny

@JsFun("(buffer) => new Int8Array(buffer)")
private external fun int8ArrayOf(buffer: JsAny): JsAny

@JsFun("(array, index) => array[index]")
private external fun int8At(array: JsAny, index: Int): Int

/**
 * A frame copied into a JavaScript `ArrayBuffer` of its own, by
 * [WasmJsFramebufferUi.copyFrameToArrayBuffer]. Rows are [FramebufferFrameInfo.strideBytes]
 * apart.
 */
class WasmJsFramebufferFrame internal constructor(
    /** The pixels, as an `ArrayBuffer`. */
    val pixels: JsAny,
    val info: FramebufferFrameInfo
)

/**
 * [FramebufferUi] over the C entry points.
 *
 * The plugin renders on a worker, but the services it asks for arrive on the main thread:
 * the C layer hands them over, because a worker cannot call into JavaScript that lives on
 * the main thread.
 */
class WasmJsFramebufferUi internal constructor(private val handle: Int) : FramebufferUi {

    // Table entries made for the current host; removed when it is replaced.
    private var hostCallbackId = 0
    private var menuCallback = 0
    private var cursorCallback = 0
    private var droppedFileCallback = 0

    override val preferredSize: FramebufferSize?
        get() = withWasmStruct(8) { out ->
            if (!wasmMod.uapmdInstanceFbuiPreferredSize(handle, out, out + 4)) null
            else FramebufferSize(wasmGetI32(out), wasmGetI32(out + 4))
        }

    override fun setSurfaceSize(width: Int, height: Int, scaleFactor: Double) {
        wasmMod.uapmdInstanceFbuiSetSurfaceSize(handle, width, height, scaleFactor)
    }

    override fun frameInfo(): FramebufferFrameInfo? = withWasmStruct(FRAME_INFO_SIZE) { out ->
        if (!wasmMod.uapmdInstanceFbuiFrameInfo(handle, out)) null else readFrameInfo(out)
    }

    /**
     * Correct, but costs a JavaScript call per byte: the frame is in the uapmd module's
     * memory and a [ByteArray] is in Kotlin's, and nothing copies between the two in
     * bulk. A browser host wants [copyFrameToArrayBuffer].
     */
    override fun copyFrame(destination: ByteArray, destinationStride: Int): FramebufferFrameInfo? {
        if (destination.isEmpty()) return null
        return withWasmStruct(destination.size) { dst ->
            withWasmStruct(FRAME_INFO_SIZE) { out ->
                if (!wasmMod.uapmdInstanceFbuiCopyFrame(handle, dst, destination.size, destinationStride, out))
                    return@withWasmStruct null
                val bytes = int8ArrayOf(wasmHeapToArrayBuffer(wasmMod, dst, destination.size))
                for (i in destination.indices)
                    destination[i] = int8At(bytes, i).toByte()
                readFrameInfo(out)
            }
        }
    }

    /**
     * [copyFrame] into a new JavaScript `ArrayBuffer` rather than a [ByteArray], with rows
     * packed at `width * 4` bytes. Null when the plugin has not drawn yet.
     *
     * An `ArrayBuffer` is filled in one copy, and it is what browser graphics APIs take,
     * Skia's included -- so the pixels never pass through Kotlin at all, which at a
     * megabyte or two a frame is the difference between an editor that keeps up and one
     * that does not.
     */
    fun copyFrameToArrayBuffer(): WasmJsFramebufferFrame? {
        val info = frameInfo() ?: return null
        val stride = info.width * 4
        val capacity = stride * info.height
        if (capacity <= 0) return null
        return withWasmStruct(capacity) { dst ->
            withWasmStruct(FRAME_INFO_SIZE) { out ->
                // Refused when the plugin grew since frameInfo(); the next frame catches up.
                if (!wasmMod.uapmdInstanceFbuiCopyFrame(handle, dst, capacity, stride, out))
                    return@withWasmStruct null
                val copied = readFrameInfo(out)
                WasmJsFramebufferFrame(
                    wasmHeapToArrayBuffer(wasmMod, dst, copied.strideBytes * copied.height),
                    copied
                )
            }
        }
    }

    override fun deliverInput(input: FramebufferInput, keys: List<FramebufferKeyEvent>) {
        // The keys follow the input in the same block; INPUT_SIZE keeps them aligned.
        withWasmStruct(INPUT_SIZE + keys.size * KEY_EVENT_SIZE) { p ->
            wasmSetI32(p, input.pointerX)
            wasmSetI32(p + 4, input.pointerY)
            wasmSetI32(p + 8, input.buttons)
            wasmSetI32(p + 12, input.modifiers)
            wasmSetF64(p + 16, input.wheel)
            wasmSetF64(p + 24, input.horizontalWheel)
            wasmSetI8(p + 32, if (input.hasFocus) 1 else 0)
            wasmSetI8(p + 33, if (input.visible) 1 else 0)
            wasmSetI8(p + 34, if (input.pointerOver) 1 else 0)
            val keysPtr = p + INPUT_SIZE
            keys.forEachIndexed { i, event ->
                val k = keysPtr + i * KEY_EVENT_SIZE
                wasmSetI32(k, event.modifiers)
                wasmSetI32(k + 4, event.character)
                wasmSetI32(k + 8, event.key.ordinal)
                wasmSetI8(k + 12, if (event.pressed) 1 else 0)
            }
            wasmMod.uapmdInstanceFbuiDeliverInput(handle, p, if (keys.isEmpty()) 0 else keysPtr, keys.size)
        }
    }

    override fun setDisplayed(displayed: Boolean) {
        wasmMod.uapmdInstanceFbuiSetDisplayed(handle, displayed)
    }

    override fun setHost(host: FramebufferUiHost?) {
        val previousId = hostCallbackId
        val previous = intArrayOf(menuCallback, cursorCallback, droppedFileCallback)

        if (host == null) {
            wasmMod.uapmdInstanceFbuiSetHost(handle, 0, 0, 0, 0)
            hostCallbackId = 0
            menuCallback = 0
            cursorCallback = 0
            droppedFileCallback = 0
        } else {
            val id = nextCallbackId()
            framebufferUiHosts[id] = host
            hostCallbackId = id
            menuCallback = makeCFunctionPtr(id, "uapmdDispatchFbuiMenu", "viiiiii")
            cursorCallback = makeCFunctionPtr(id, "uapmdDispatchFbuiCursor", "vii")
            droppedFileCallback = makeCFunctionPtr(id, "uapmdDispatchFbuiDroppedFile", "iiiii")
            wasmMod.uapmdInstanceFbuiSetHost(handle, menuCallback, cursorCallback, droppedFileCallback, 0)
        }

        // Only once the plugin holds the new ones: the old entries are gone after this.
        if (previousId != 0)
            framebufferUiHosts.remove(previousId)
        previous.forEach { if (it != 0) removeCFunctionPtr(it) }
    }

    private fun readFrameInfo(p: Int) = FramebufferFrameInfo(
        width = wasmGetI32(p),
        height = wasmGetI32(p + 4),
        strideBytes = wasmGetI32(p + 8),
        format = if (wasmGetI32(p + 12) == 1) FramebufferPixelFormat.RGBA8 else FramebufferPixelFormat.BGRA8,
        serial = wasmGetI64(p + 16)
    )
}

// ─── Callback dispatch ───────────────────────────────────────────────────────

private val framebufferUiHosts = mutableMapOf<Int, FramebufferUiHost>()

/**
 * The menu arrives flattened depth first: an item declaring N children is followed by
 * exactly those N, each of which may declare children of its own. Rebuilding the tree is
 * therefore a walk with an index that the recursion advances.
 */
private fun readFramebufferMenu(items: Int, count: Int): List<FramebufferMenuItem> {
    if (items == 0 || count <= 0) return emptyList()
    var index = 0
    fun take(howMany: Int): List<FramebufferMenuItem> {
        val out = ArrayList<FramebufferMenuItem>(howMany)
        repeat(howMany) {
            if (index >= count) return@repeat
            val p = items + index++ * MENU_ITEM_SIZE
            val children = take(wasmGetI32(p + 12))
            out.add(
                FramebufferMenuItem(
                    label = wasmGetStr(p),
                    id = wasmGetI32(p + 4),
                    disabled = wasmGetBool(p + 8),
                    checked = wasmGetBool(p + 9),
                    separator = wasmGetBool(p + 10),
                    children = children
                )
            )
        }
        return out
    }
    return take(count)
}

@JsExport
fun uapmdDispatchFbuiMenu(cbId: Int, request: Int, items: Int, itemCount: Int, x: Int, y: Int, userData: Int) {
    val host = framebufferUiHosts[cbId]
    if (host == null) {
        // Dismissing is still an answer; dropping the request hangs the plugin's renderer.
        wasmMod.uapmdInstanceFbuiCompleteMenu(request, 0)
        return
    }
    host.requestMenu(readFramebufferMenu(items, itemCount), x, y) { chosen ->
        wasmMod.uapmdInstanceFbuiCompleteMenu(request, chosen)
    }
}

@JsExport
fun uapmdDispatchFbuiCursor(cbId: Int, cursor: Int, userData: Int) {
    framebufferUiHosts[cbId]?.setCursor(FramebufferCursor.entries.getOrElse(cursor) { FramebufferCursor.ARROW })
}

/** Asked with no buffer first, for the size including the terminator; then filled. */
@JsExport
fun uapmdDispatchFbuiDroppedFile(cbId: Int, index: Int, buf: Int, bufSize: Int, userData: Int): Int {
    val path = framebufferUiHosts[cbId]?.droppedFile(index) ?: return 0
    if (path.isEmpty()) return 0
    val mod = wasmMod
    if (buf == 0 || bufSize <= 0)
        return mod.lengthBytesUTF8(path) + 1
    return mod.stringToUTF8(path, buf, bufSize)
}
