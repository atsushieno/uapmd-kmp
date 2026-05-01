package dev.atsushieno.uapmd

import com.sun.jna.Pointer
import dev.atsushieno.uapmd.jna.UmpReceiverCallback

// ─── JvmMidiIO ───────────────────────────────────────────────────────────────

class JvmMidiIO internal constructor(
    private val handle: Pointer
) : MidiIO {

    // Maps token → JNA Callback so we can remove by the same native pointer.
    private val callbackMap = mutableMapOf<Any, UmpReceiverCallback>()

    override fun addInputHandler(receiver: (UIntArray, Long) -> Unit): Any {
        val cb = object : UmpReceiverCallback {
            override fun invoke(context: Pointer?, ump: Pointer?, sizeInBytes: Long, timestamp: Long) {
                if (ump == null) return
                val count = (sizeInBytes / 4).toInt()
                val arr = UIntArray(count) { i -> ump.getInt((i * 4).toLong()).toUInt() }
                receiver(arr, timestamp)
            }
        }
        // user_data = null because the handler is captured in the closure
        lib.uapmd_midi_io_add_input_handler(handle, cb, null)
        callbackMap[cb] = cb
        return cb
    }

    override fun removeInputHandler(token: Any) {
        val cb = callbackMap.remove(token) ?: return
        lib.uapmd_midi_io_remove_input_handler(handle, cb)
    }

    override fun send(messages: UIntArray, timestamp: Long) {
        val arr = IntArray(messages.size) { messages[it].toInt() }
        lib.uapmd_midi_io_send(handle, arr, (messages.size * 4L), timestamp)
    }
}

// ─── JvmFunctionBlock ────────────────────────────────────────────────────────

class JvmFunctionBlock internal constructor(
    private val handle: Pointer
) : FunctionBlock {

    override val midiIo: MidiIO
        get() = JvmMidiIO(lib.uapmd_fb_midi_io(handle) ?: error("uapmd_fb_midi_io returned null"))

    override val instanceId: Int get() = lib.uapmd_fb_instance_id(handle)

    override var group: UByte
        get() = lib.uapmd_fb_get_group(handle).toUByte()
        set(value) { lib.uapmd_fb_set_group(handle, value.toByte()) }

    override fun detachOutputMapper() = lib.uapmd_fb_detach_output_mapper(handle)
    override fun initialize() = lib.uapmd_fb_initialize(handle)
}

// ─── JvmFunctionDevice ───────────────────────────────────────────────────────

class JvmFunctionDevice internal constructor(
    @Suppress("unused") private val handle: Pointer
) : FunctionDevice

// ─── JvmFunctionBlockManager ─────────────────────────────────────────────────

class JvmFunctionBlockManager internal constructor(
    private val handle: Pointer
) : FunctionBlockManager {

    override val count: Long get() = lib.uapmd_fbm_count(handle)

    override fun createDevice(): Long = lib.uapmd_fbm_create_device(handle)

    override fun getDeviceByIndex(index: Int): FunctionDevice =
        JvmFunctionDevice(lib.uapmd_fbm_get_device_by_index(handle, index) ?: error("device not found at index $index"))

    override fun getDeviceForInstance(instanceId: Int): FunctionDevice? =
        lib.uapmd_fbm_get_device_for_instance(handle, instanceId)?.let { JvmFunctionDevice(it) }

    override fun deleteEmptyDevices() = lib.uapmd_fbm_delete_empty_devices(handle)
    override fun detachAllOutputMappers() = lib.uapmd_fbm_detach_all_output_mappers(handle)
    override fun clearAllDevices() = lib.uapmd_fbm_clear_all_devices(handle)
}

// ─── JvmUmpInputMapper ───────────────────────────────────────────────────────

class JvmUmpInputMapper internal constructor(
    private val handle: Pointer
) : UmpInputMapper {

    override fun setParameterValue(index: UShort, value: Double) =
        lib.uapmd_ump_in_set_parameter_value(handle, index.toInt(), value)

    override fun getParameterValue(index: UShort): Double =
        lib.uapmd_ump_in_get_parameter_value(handle, index.toInt())

    override fun setPerNoteControllerValue(note: UByte, index: UByte, value: Double) =
        lib.uapmd_ump_in_set_per_note_controller_value(handle, note.toByte(), index.toByte(), value)

    override fun loadPreset(index: UInt) =
        lib.uapmd_ump_in_load_preset(handle, index.toInt())
}

// ─── JvmUmpOutputMapper ──────────────────────────────────────────────────────

class JvmUmpOutputMapper internal constructor(
    private val handle: Pointer
) : UmpOutputMapper {

    override fun sendParameterValue(index: UShort, value: Double) =
        lib.uapmd_ump_out_send_parameter_value(handle, index.toInt(), value)

    override fun sendPerNoteControllerValue(note: UByte, index: UByte, value: Double) =
        lib.uapmd_ump_out_send_per_note_controller_value(handle, note.toByte(), index.toByte(), value)

    override fun sendPresetIndexChange(index: UInt) =
        lib.uapmd_ump_out_send_preset_index_change(handle, index.toInt())
}
