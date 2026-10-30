package dev.atsushieno.uapmd

import kotlin.concurrent.Volatile
import kotlinx.cinterop.*
import uapmd.*

// uapmd removes MIDI input handlers by function pointer, and a staticCFunction cannot capture,
// so handlers registered one by one would all share one pointer: removing any of them would
// unregister whichever was added first. Each native MidiIO gets a single registration instead,
// whose context dispatches to the receivers currently added to it.
private class NativeMidiInputHandler(val receiver: (UIntArray, Long) -> Unit)

private class NativeMidiInputDispatcher {
    // Replaced, never mutated: the MIDI input thread reads it while handlers come and go.
    @Volatile var handlers: List<NativeMidiInputHandler> = emptyList()
    val ref: StableRef<NativeMidiInputDispatcher> = StableRef.create(this)
}

// Keyed by the native MidiIO, because FunctionBlock.midiIo hands out a fresh wrapper per access.
private val nativeMidiInputDispatchers = mutableMapOf<Long, NativeMidiInputDispatcher>()

// Top-level static function for MIDI input dispatch (no captures allowed).
private val nativeMidiInputHandler: uapmd_ump_receiver_t =
    staticCFunction { context, ump, sizeBytes, timestamp ->
        if (context == null || ump == null) return@staticCFunction
        val dispatcher = context.asStableRef<NativeMidiInputDispatcher>().get()
        val count = (sizeBytes / 4u).toInt()
        for (h in dispatcher.handlers)
            h.receiver(UIntArray(count) { i -> ump[i] }, timestamp)
    }

class NativeMidiIO internal constructor(
    private val handle: uapmd_midi_io_t
) : MidiIO {

    // The token is the NativeMidiInputHandler added to this MidiIO's dispatcher.
    override fun addInputHandler(receiver: (UIntArray, Long) -> Unit): Any {
        val handler = NativeMidiInputHandler(receiver)
        val dispatcher = nativeMidiInputDispatchers.getOrPut(handle.rawValue.toLong()) { NativeMidiInputDispatcher() }
        val first = dispatcher.handlers.isEmpty()
        dispatcher.handlers += handler
        if (first)
            uapmd_midi_io_add_input_handler(handle, nativeMidiInputHandler, dispatcher.ref.asCPointer())
        return handler
    }

    override fun removeInputHandler(token: Any) {
        val handler = token as? NativeMidiInputHandler ?: return
        val key = handle.rawValue.toLong()
        val dispatcher = nativeMidiInputDispatchers[key] ?: return
        if (handler !in dispatcher.handlers) return
        dispatcher.handlers -= handler
        if (dispatcher.handlers.isNotEmpty()) return
        uapmd_midi_io_remove_input_handler(handle, nativeMidiInputHandler)
        nativeMidiInputDispatchers.remove(key)
        dispatcher.ref.dispose()
    }

    // The length is in bytes.
    override fun send(messages: UIntArray, timestamp: Long) {
        messages.usePinned { pinned ->
            uapmd_midi_io_send(handle, pinned.addressOf(0), (messages.size * 4).toULong(), timestamp)
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

    override fun requestPresetLoad(index: UInt) =
        uapmd_ump_in_request_preset_load(handle, index)
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
