package dev.atsushieno.uapmd

// ─── JsMidiIO ─────────────────────────────────────────────────────────────────

class JsMidiIO internal constructor(
    private val handle: Int
) : MidiIO {

    private val handlerPtrs = mutableMapOf<Int, Int>() // token → fnPtr

    override fun addInputHandler(receiver: (messages: UIntArray, timestamp: Long) -> Unit): Any {
        val fnPtr = makeJsMidiInputHandler(receiver)
        val token = jsMod._uapmd_midi_io_add_input_handler(handle, fnPtr, 0) as Int
        handlerPtrs[token] = fnPtr
        return token
    }

    override fun removeInputHandler(token: Any) {
        val t = token as Int
        jsMod._uapmd_midi_io_remove_input_handler(handle, t)
        handlerPtrs.remove(t)?.let { removeJsCallback(it) }
    }

    override fun send(messages: UIntArray, timestamp: Long) {
        val ptr = jsMod._malloc(messages.size * 4) as Int
        try {
            messages.forEachIndexed { i, v -> jsSetI32(ptr + i * 4, v.toInt()) }
            jsMod._uapmd_midi_io_send(handle, ptr, messages.size, timestamp.toDouble())
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
