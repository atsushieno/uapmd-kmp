package dev.atsushieno.uapmd

interface MidiIO {
    /** Returns an opaque token used to remove the handler. */
    fun addInputHandler(receiver: (messages: UIntArray, timestamp: Long) -> Unit): Any
    fun removeInputHandler(token: Any)
    fun send(messages: UIntArray, timestamp: Long)
}

/** Marker for a MIDI 2.0 function device (no public C API beyond retrieval). */
interface FunctionDevice

interface FunctionBlock {
    val midiIo: MidiIO
    val instanceId: Int
    var group: UByte
    fun detachOutputMapper()
    fun initialize()
}

interface FunctionBlockManager {
    val count: Long
    fun createDevice(): Long
    fun getDeviceByIndex(index: Int): FunctionDevice
    fun getDeviceForInstance(instanceId: Int): FunctionDevice?
    fun deleteEmptyDevices()
    fun detachAllOutputMappers()
    fun clearAllDevices()
}

interface UmpInputMapper {
    fun setParameterValue(index: UShort, value: Double)
    fun getParameterValue(index: UShort): Double
    fun setPerNoteControllerValue(note: UByte, index: UByte, value: Double)
    fun loadPreset(index: UInt)
}

interface UmpOutputMapper {
    fun sendParameterValue(index: UShort, value: Double)
    fun sendPerNoteControllerValue(note: UByte, index: UByte, value: Double)
    fun sendPresetIndexChange(index: UInt)
}
