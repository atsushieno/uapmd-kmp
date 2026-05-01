package dev.atsushieno.uapmd

// ─── WasmJsMidiIO ────────────────────────────────────────────────────────────

class WasmJsMidiIO internal constructor(
    private val handle: Int
) : MidiIO {

    // token (Int) → cbId used to store the handler + fnPtr for removal
    private val handlerMap = mutableMapOf<Int, Pair<Int, Int>>() // token → (cbId, fnPtr)

    override fun addInputHandler(receiver: (messages: UIntArray, timestamp: Long) -> Unit): Any {
        val cbId = nextCallbackId()
        midiInputHandlers[cbId] = receiver
        // Callback sig: void(const uint32_t* messages, size_t count, int64_t timestamp, void* ctx)
        // In 32-bit Wasm: "viiii" (timestamp as two ints) or "viii" with timestamp as double
        // Use "viii" treating timestamp as a double (WASM_BIGINT not guaranteed here)
        val fnPtr = makeCFunctionPtr(cbId, "uapmdDispatchMidiInput", "viid")
        val token = wasmMod.uapmdMidiIoAddInputHandler(handle, fnPtr, 0)
        handlerMap[token] = Pair(cbId, fnPtr)
        return token
    }

    override fun removeInputHandler(token: Any) {
        val intToken = token as Int
        val (cbId, fnPtr) = handlerMap.remove(intToken) ?: return
        wasmMod.uapmdMidiIoRemoveInputHandler(handle, intToken)
        midiInputHandlers.remove(cbId)
        removeCFunctionPtr(fnPtr)
    }

    override fun send(messages: UIntArray, timestamp: Long) {
        val mod = wasmMod
        val ptr = mod.malloc(messages.size * 4)
        try {
            messages.forEachIndexed { i, v -> mod.setValue((ptr + i * 4).toDouble(), v.toDouble(), "i32") }
            mod.uapmdMidiIoSend(handle, ptr, messages.size, timestamp.toDouble())
        } finally { mod.free(ptr) }
    }
}

// ─── WasmJsFunctionDevice ─────────────────────────────────────────────────────

class WasmJsFunctionDevice internal constructor(
    private val handle: Int
) : FunctionDevice

// ─── WasmJsFunctionBlock ─────────────────────────────────────────────────────

class WasmJsFunctionBlock internal constructor(
    private val handle: Int
) : FunctionBlock {

    override val midiIo: MidiIO
        get() = WasmJsMidiIO(wasmMod.uapmdFbMidiIo(handle))

    override val instanceId: Int
        get() = wasmMod.uapmdFbInstanceId(handle)

    override var group: UByte
        get() = wasmMod.uapmdFbGetGroup(handle).toUByte()
        set(v) { wasmMod.uapmdFbSetGroup(handle, v.toInt()) }

    override fun detachOutputMapper() = wasmMod.uapmdFbDetachOutputMapper(handle)
    override fun initialize()         = wasmMod.uapmdFbInitialize(handle)
}

// ─── WasmJsFunctionBlockManager ───────────────────────────────────────────────

class WasmJsFunctionBlockManager internal constructor(
    private val handle: Int
) : FunctionBlockManager {

    override val count: Long
        get() = wasmMod.uapmdFbmCount(handle).toLong()

    override fun createDevice(): Long =
        wasmMod.uapmdFbmCreateDevice(handle).toLong()

    override fun getDeviceByIndex(index: Int): FunctionDevice =
        WasmJsFunctionDevice(wasmMod.uapmdFbmGetDeviceByIndex(handle, index))

    override fun getDeviceForInstance(instanceId: Int): FunctionDevice? {
        val dev = wasmMod.uapmdFbmGetDeviceForInstance(handle, instanceId)
        return if (dev == 0) null else WasmJsFunctionDevice(dev)
    }

    override fun deleteEmptyDevices()      = wasmMod.uapmdFbmDeleteEmptyDevices(handle)
    override fun detachAllOutputMappers()  = wasmMod.uapmdFbmDetachAllOutputMappers(handle)
    override fun clearAllDevices()         = wasmMod.uapmdFbmClearAllDevices(handle)
}
