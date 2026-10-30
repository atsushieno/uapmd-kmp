package dev.atsushieno.uapmd

// ─── WasmJsMidiIO ────────────────────────────────────────────────────────────

// Handler function pointer → (the MidiIO it was added to, its callback id). Kept outside
// WasmJsMidiIO because FunctionBlock.midiIo returns a new wrapper on every access, so a
// handler may be removed through a different one.
private val midiInputFnPtrs = mutableMapOf<Int, Pair<Int, Int>>()

class WasmJsMidiIO internal constructor(
    private val handle: Int
) : MidiIO {

    // The token is the handler's function pointer, which is what the C API removes by.
    override fun addInputHandler(receiver: (messages: UIntArray, timestamp: Long) -> Unit): Any {
        val cbId = nextCallbackId()
        midiInputHandlers[cbId] = receiver
        // C: void(void* context, uapmd_ump_t* ump, size_t size_in_bytes, int64 timestamp)
        val fnPtr = makeCFunctionPtr(cbId, "uapmdDispatchMidiInput", "viiij")
        wasmMod.uapmdMidiIoAddInputHandler(handle, fnPtr, 0)
        midiInputFnPtrs[fnPtr] = handle to cbId
        return fnPtr
    }

    override fun removeInputHandler(token: Any) {
        val fnPtr = token as? Int ?: return
        val (owner, cbId) = midiInputFnPtrs[fnPtr] ?: return
        if (owner != handle) return
        midiInputFnPtrs.remove(fnPtr)
        wasmMod.uapmdMidiIoRemoveInputHandler(handle, fnPtr)
        midiInputHandlers.remove(cbId)
        removeCFunctionPtr(fnPtr)
    }

    override fun send(messages: UIntArray, timestamp: Long) {
        val mod = wasmMod
        val ptr = mod.malloc(messages.size * 4)
        try {
            messages.forEachIndexed { i, v -> mod.setValue(ptr + i * 4, v.toDouble(), "i32") }
            mod.uapmdMidiIoSend(handle, ptr, messages.size * 4, timestamp)
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
