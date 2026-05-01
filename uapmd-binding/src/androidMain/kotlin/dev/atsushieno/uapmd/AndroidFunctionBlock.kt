package dev.atsushieno.uapmd

// ─── AndroidMidiIO ───────────────────────────────────────────────────────────

class AndroidMidiIO internal constructor(
    private val handle: Long
) : MidiIO {

    // Map handler-ID (Long) → strong-ref callback object to prevent GC.
    private val handlerMap = mutableMapOf<Long, Any>()

    override fun addInputHandler(receiver: (UIntArray, Long) -> Unit): Any {
        val cb = object : Any() {
            @Suppress("unused")
            fun invoke(ump: IntArray, timestamp: Long) =
                receiver(UIntArray(ump.size) { ump[it].toUInt() }, timestamp)
        }
        val id = JniBridge.uapmdMidiIoAddInputHandler(handle, cb)
        handlerMap[id] = cb
        return id   // token is the handler ID
    }

    override fun removeInputHandler(token: Any) {
        val id = token as Long
        handlerMap.remove(id)
        JniBridge.uapmdMidiIoRemoveInputHandler(handle, id)
    }

    override fun send(messages: UIntArray, timestamp: Long) =
        JniBridge.uapmdMidiIoSend(handle, IntArray(messages.size) { messages[it].toInt() }, timestamp)
}

// ─── AndroidFunctionBlock ────────────────────────────────────────────────────

class AndroidFunctionBlock internal constructor(
    private val handle: Long
) : FunctionBlock {

    override val midiIo: MidiIO get() = AndroidMidiIO(JniBridge.uapmdFbMidiIo(handle))
    override val instanceId: Int get() = JniBridge.uapmdFbInstanceId(handle)

    override var group: UByte
        get() = JniBridge.uapmdFbGetGroup(handle).toUByte()
        set(value) { JniBridge.uapmdFbSetGroup(handle, value.toByte()) }

    override fun detachOutputMapper() = JniBridge.uapmdFbDetachOutputMapper(handle)
    override fun initialize() = JniBridge.uapmdFbInitialize(handle)
}

// ─── AndroidFunctionDevice ───────────────────────────────────────────────────

class AndroidFunctionDevice internal constructor(
    @Suppress("unused") private val handle: Long
) : FunctionDevice

// ─── AndroidFunctionBlockManager ─────────────────────────────────────────────

class AndroidFunctionBlockManager internal constructor(
    private val handle: Long
) : FunctionBlockManager {

    override val count: Long get() = JniBridge.uapmdFbmCount(handle)

    override fun createDevice(): Long = JniBridge.uapmdFbmCreateDevice(handle)

    override fun getDeviceByIndex(index: Int): FunctionDevice =
        AndroidFunctionDevice(JniBridge.uapmdFbmGetDeviceByIndex(handle, index))

    override fun getDeviceForInstance(instanceId: Int): FunctionDevice? {
        val h = JniBridge.uapmdFbmGetDeviceForInstance(handle, instanceId)
        return if (h == 0L) null else AndroidFunctionDevice(h)
    }

    override fun deleteEmptyDevices() = JniBridge.uapmdFbmDeleteEmptyDevices(handle)
    override fun detachAllOutputMappers() = JniBridge.uapmdFbmDetachAllOutputMappers(handle)
    override fun clearAllDevices() = JniBridge.uapmdFbmClearAllDevices(handle)
}

// ─── AndroidUmpInputMapper ───────────────────────────────────────────────────

class AndroidUmpInputMapper internal constructor(
    private val handle: Long
) : UmpInputMapper {

    override fun setParameterValue(index: UShort, value: Double) =
        JniBridge.uapmdUmpInSetParameterValue(handle, index.toInt(), value)

    override fun getParameterValue(index: UShort): Double =
        JniBridge.uapmdUmpInGetParameterValue(handle, index.toInt())

    override fun setPerNoteControllerValue(note: UByte, index: UByte, value: Double) =
        JniBridge.uapmdUmpInSetPerNoteControllerValue(handle, note.toByte(), index.toByte(), value)

    override fun loadPreset(index: UInt) =
        JniBridge.uapmdUmpInLoadPreset(handle, index.toInt())
}

// ─── AndroidUmpOutputMapper ──────────────────────────────────────────────────

class AndroidUmpOutputMapper internal constructor(
    private val handle: Long
) : UmpOutputMapper {

    override fun sendParameterValue(index: UShort, value: Double) =
        JniBridge.uapmdUmpOutSendParameterValue(handle, index.toInt(), value)

    override fun sendPerNoteControllerValue(note: UByte, index: UByte, value: Double) =
        JniBridge.uapmdUmpOutSendPerNoteControllerValue(handle, note.toByte(), index.toByte(), value)

    override fun sendPresetIndexChange(index: UInt) =
        JniBridge.uapmdUmpOutSendPresetIndexChange(handle, index.toInt())
}
