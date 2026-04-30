package dev.atsushieno.uapmd

import kotlinx.cinterop.*
import uapmd.*

// Top-level static function for MIDI input dispatch (no captures allowed).
private val nativeMidiInputHandler: uapmd_ump_receiver_t =
    staticCFunction { context, ump, sizeBytes, timestamp ->
        if (context == null || ump == null) return@staticCFunction
        val cb = context.asStableRef<(UIntArray, Long) -> Unit>().get()
        val count = (sizeBytes / 4u).toInt()
        cb(UIntArray(count) { i -> ump[i] }, timestamp)
    }

class NativeMidiIO internal constructor(
    private val handle: uapmd_midi_io_t
) : MidiIO {

    override fun addInputHandler(receiver: (UIntArray, Long) -> Unit): Any {
        val ref = StableRef.create(receiver)
        uapmd_midi_io_add_input_handler(handle, nativeMidiInputHandler, ref.asCPointer())
        return ref
    }

    override fun removeInputHandler(token: Any) {
        uapmd_midi_io_remove_input_handler(handle, nativeMidiInputHandler)
        (token as? StableRef<*>)?.dispose()
    }

    override fun send(messages: UIntArray, timestamp: Long) {
        messages.usePinned { pinned ->
            uapmd_midi_io_send(handle, pinned.addressOf(0), messages.size.toULong(), timestamp)
        }
    }
}

// ---------------------------------------------------------------------------

class NativeFunctionDevice internal constructor(
    internal val handle: uapmd_function_device_t
) : FunctionDevice

// ---------------------------------------------------------------------------

class NativeFunctionBlock internal constructor(
    private val handle: uapmd_function_block_t
) : FunctionBlock {

    override val midiIo: MidiIO get() = NativeMidiIO(uapmd_fb_midi_io(handle)!!)
    override val instanceId: Int get() = uapmd_fb_instance_id(handle)

    override var group: UByte
        get() = uapmd_fb_get_group(handle)
        set(value) { uapmd_fb_set_group(handle, value) }

    override fun detachOutputMapper() = uapmd_fb_detach_output_mapper(handle)
    override fun initialize() = uapmd_fb_initialize(handle)
}

// ---------------------------------------------------------------------------

class NativeFunctionBlockManager internal constructor(
    private val handle: uapmd_function_block_mgr_t
) : FunctionBlockManager {

    override val count: Long get() = uapmd_fbm_count(handle).toLong()
    override fun createDevice(): Long = uapmd_fbm_create_device(handle).toLong()

    override fun getDeviceByIndex(index: Int): FunctionDevice =
        NativeFunctionDevice(uapmd_fbm_get_device_by_index(handle, index)!!)

    override fun getDeviceForInstance(instanceId: Int): FunctionDevice? =
        uapmd_fbm_get_device_for_instance(handle, instanceId)?.let { NativeFunctionDevice(it) }

    override fun deleteEmptyDevices() = uapmd_fbm_delete_empty_devices(handle)
    override fun detachAllOutputMappers() = uapmd_fbm_detach_all_output_mappers(handle)
    override fun clearAllDevices() = uapmd_fbm_clear_all_devices(handle)
}

// ---------------------------------------------------------------------------

class NativeUmpInputMapper internal constructor(
    private val handle: uapmd_ump_input_mapper_t
) : UmpInputMapper {

    override fun setParameterValue(index: UShort, value: Double) =
        uapmd_ump_in_set_parameter_value(handle, index, value)

    override fun getParameterValue(index: UShort): Double =
        uapmd_ump_in_get_parameter_value(handle, index)

    override fun setPerNoteControllerValue(note: UByte, index: UByte, value: Double) =
        uapmd_ump_in_set_per_note_controller_value(handle, note, index, value)

    override fun loadPreset(index: UInt) =
        uapmd_ump_in_load_preset(handle, index)
}

// ---------------------------------------------------------------------------

class NativeUmpOutputMapper internal constructor(
    private val handle: uapmd_ump_output_mapper_t
) : UmpOutputMapper {

    override fun sendParameterValue(index: UShort, value: Double) =
        uapmd_ump_out_send_parameter_value(handle, index, value)

    override fun sendPerNoteControllerValue(note: UByte, index: UByte, value: Double) =
        uapmd_ump_out_send_per_note_controller_value(handle, note, index, value)

    override fun sendPresetIndexChange(index: UInt) =
        uapmd_ump_out_send_preset_index_change(handle, index)
}
