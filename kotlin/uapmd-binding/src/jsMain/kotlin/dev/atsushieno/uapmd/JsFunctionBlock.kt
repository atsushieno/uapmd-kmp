package dev.atsushieno.uapmd

// ─── JsMidiIO ─────────────────────────────────────────────────────────────────

// Handler function pointer → the MidiIO it was added to. Kept outside JsMidiIO because
// FunctionBlock.midiIo returns a new wrapper on every access, so a handler may be removed
// through a different one.
private val jsMidiInputHandlers = mutableMapOf<Int, Int>()

class JsMidiIO internal constructor(
    private val handle: Int
) : MidiIO {

    // The token is the handler's function pointer, which is what the C API removes by.
    override fun addInputHandler(receiver: (messages: UIntArray, timestamp: Long) -> Unit): Any {
        val fnPtr = makeJsMidiInputHandler(receiver)
        jsMod._uapmd_midi_io_add_input_handler(handle, fnPtr, 0)
        jsMidiInputHandlers[fnPtr] = handle
        return fnPtr
    }

    override fun removeInputHandler(token: Any) {
        val fnPtr = token as? Int ?: return
        if (jsMidiInputHandlers[fnPtr] != handle) return
        jsMidiInputHandlers.remove(fnPtr)
        jsMod._uapmd_midi_io_remove_input_handler(handle, fnPtr)
        removeJsCallback(fnPtr)
    }

    // The length is in bytes, and the int64_t timestamp must arrive as BigInt.
    override fun send(messages: UIntArray, timestamp: Long) {
        val ptr = jsMod._malloc(messages.size * 4) as Int
        try {
            messages.forEachIndexed { i, v -> jsSetI32(ptr + i * 4, v.toInt()) }
            jsMod._uapmd_midi_io_send(handle, ptr, messages.size * 4, js("BigInt")(timestamp.toString()))
        } finally { jsMod._free(ptr) }
    }
}

// ─── JsFunctionDevice ─────────────────────────────────────────────────────────

class JsFunctionDevice internal constructor(private val handle: Int) : FunctionDevice

// ─── JsFunctionBlock ─────────────────────────────────────────────────────────

class JsFunctionBlock internal constructor(
    private val handle: Int
) : FunctionBlock {
    override val midiIo: MidiIO   get() = JsMidiIO(jsMod._uapmd_fb_midi_io(handle) as Int)
    override var group: UByte
        get() = (jsMod._uapmd_fb_get_group(handle) as Int).toUByte()
        set(v) { jsMod._uapmd_fb_set_group(handle, v.toInt()) }
    override fun detachOutputMapper() = jsMod._uapmd_fb_detach_output_mapper(handle)
    override fun initialize()         = jsMod._uapmd_fb_initialize(handle)
}

// ─── JsFunctionBlockManager ───────────────────────────────────────────────────

class JsFunctionBlockManager internal constructor(
    private val handle: Int
) : FunctionBlockManager {
    override val count: Long    get() = (jsMod._uapmd_fbm_count(handle) as Int).toLong()
    override fun createDevice(): Long = (jsMod._uapmd_fbm_create_device(handle) as Int).toLong()
    override fun getDeviceByIndex(index: Int): FunctionDevice =
        JsFunctionDevice(jsMod._uapmd_fbm_get_device_by_index(handle, index) as Int)
    override fun getDeviceForInstance(instanceId: Int): FunctionDevice? {
        val dev = jsMod._uapmd_fbm_get_device_for_instance(handle, instanceId) as Int
        return if (dev == 0) null else JsFunctionDevice(dev)
    }
    override fun deleteEmptyDevices()     = jsMod._uapmd_fbm_delete_empty_devices(handle)
    override fun detachAllOutputMappers() = jsMod._uapmd_fbm_detach_all_output_mappers(handle)
    override fun clearAllDevices()        = jsMod._uapmd_fbm_clear_all_devices(handle)
}
