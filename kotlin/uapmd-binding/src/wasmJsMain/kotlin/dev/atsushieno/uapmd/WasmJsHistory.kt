package dev.atsushieno.uapmd

/**
 * K/WasmJs bindings for the uapmd 0.5.6 project history.
 *
 * Struct field offsets are the wasm32 layout of the C headers. A function
 * returning a struct takes a hidden result pointer as its FIRST argument, and a
 * struct passed by value is passed as a pointer in its declared position.
 */

// ─── Struct offsets (wasm32) ─────────────────────────────────────────────────

internal object WasmOff {
    // uapmd_undo_result_t, sizeof 8
    const val RESULT_STATUS = 0
    const val RESULT_ERROR = 4
    const val RESULT_SIZE = 8

    // uapmd_undo_state_t, sizeof 56
    const val STATE_BUSY = 0
    const val STATE_COMPOUND_OPEN = 1
    const val STATE_GESTURE_OPEN = 2
    const val STATE_CAN_UNDO = 3
    const val STATE_CAN_REDO = 4
    const val STATE_DIRTY = 5
    const val STATE_COMPOUND_DESC = 8
    const val STATE_UNDO_DESC = 12
    const val STATE_REDO_DESC = 16
    const val STATE_HISTORY_SIZE = 24
    const val STATE_MAX_HISTORY_SIZE = 32
    const val STATE_CURRENT_ID = 40
    const val STATE_SAVED_ID = 48
    const val STATE_SIZE = 56

    const val ADDRESS_SIZE = 8

    // uapmd_track_attach_options_t, sizeof 12
    const val ATTACH_ID_POLICY = 0
    const val ATTACH_INSERTION_INDEX = 4
    const val ATTACH_INCLUDE_PLUGINS = 8
    const val ATTACH_INCLUDE_PLUGIN_STATE = 9
    const val ATTACH_INCLUDE_CLIPS = 10
    const val ATTACH_SIZE = 12

    // uapmd_time_reference_t, sizeof 16
    const val TIMEREF_TYPE = 0
    const val TIMEREF_REFERENCE_ID = 4
    const val TIMEREF_OFFSET = 8
    const val TIMEREF_SIZE = 16

    // uapmd_clip_marker_t, sizeof 32
    const val MARKER_ID = 0
    const val MARKER_OFFSET = 8
    const val MARKER_REF_TYPE = 16
    const val MARKER_REF_CLIP_ID = 20
    const val MARKER_REF_MARKER_ID = 24
    const val MARKER_NAME = 28
    const val MARKER_SIZE = 32

    // uapmd_audio_warp_point_t, sizeof 32
    const val WARP_OFFSET = 0
    const val WARP_SPEED_RATIO = 8
    const val WARP_REF_TYPE = 16
    const val WARP_REF_CLIP_ID = 20
    const val WARP_REF_MARKER_ID = 24
    const val WARP_SIZE = 32

    // uapmd_clip_add_result_t, sizeof 16
    const val CLIP_ADD_CLIP_ID = 0
    const val CLIP_ADD_SOURCE_NODE_ID = 4
    const val CLIP_ADD_SUCCESS = 8
    const val CLIP_ADD_ERROR = 12
    const val CLIP_ADD_SIZE = 16

    // uapmd_track_plugin_fragment_t, sizeof 28
    const val PLUGIN_NODE_ID = 0
    const val PLUGIN_PLUGIN_ID = 4
    const val PLUGIN_FORMAT = 8
    const val PLUGIN_DISPLAY_NAME = 12
    const val PLUGIN_GROUP_INDEX = 16
    const val PLUGIN_STATE_SIZE = 20
    const val PLUGIN_STATE = 24
    const val PLUGIN_SIZE = 28

    // uapmd_addin_info_t, sizeof 32
    const val ADDIN_PACKAGE_ID = 0
    const val ADDIN_ADDIN_ID = 4
    const val ADDIN_NAME = 8
    const val ADDIN_PATH = 12
    const val ADDIN_LIBRARY_PATH = 16
    const val ADDIN_BUILT_IN = 20
    const val ADDIN_STATE = 24
    const val ADDIN_MESSAGE = 28
    const val ADDIN_SIZE = 32

    // uapmd_clip_data_t, sizeof 128
    const val CLIP_ID = 0
    const val CLIP_POSITION = 8
    const val CLIP_DURATION = 24
    const val CLIP_GAIN = 40
    const val CLIP_MUTED = 48
    const val CLIP_NAME = 52
    const val CLIP_FILEPATH = 56
    const val CLIP_TYPE = 64
    const val CLIP_SIZE = 128
}

// ─── Memory helpers ──────────────────────────────────────────────────────────

internal fun wasmGetI32(ptr: Int): Int = wasmMod.getValue(ptr, "i32").toInt()
internal fun wasmGetBool(ptr: Int): Boolean = wasmMod.getValue(ptr, "i8").toInt() != 0
internal fun wasmGetF64(ptr: Int): Double = wasmMod.getValue(ptr, "double")
internal fun wasmGetStr(ptr: Int): String =
    wasmGetI32(ptr).let { if (it != 0) wasmMod.utf8ToString(it) else "" }

/** Reads a 64-bit field as two little-endian 32-bit halves. */
internal fun wasmGetI64(ptr: Int): Long {
    val lo = wasmGetI32(ptr).toLong() and 0xFFFFFFFFL
    val hi = wasmGetI32(ptr + 4).toLong()
    return hi shl 32 or lo
}

internal fun wasmSetI32(ptr: Int, v: Int) { wasmMod.setValue(ptr, v.toDouble(), "i32") }
internal fun wasmSetI8(ptr: Int, v: Int) { wasmMod.setValue(ptr, v.toDouble(), "i8") }
internal fun wasmSetF64(ptr: Int, v: Double) { wasmMod.setValue(ptr, v, "double") }

internal fun <T> withWasmStruct(size: Int, block: (Int) -> T): T {
    val mod = wasmMod
    val ptr = mod.malloc(size)
    return try { block(ptr) } finally { mod.free(ptr) }
}

// ─── Struct encoders ─────────────────────────────────────────────────────────

private fun <T> withWasmMarkers(markers: List<ClipMarkerData>, block: (Int, Int) -> T): T {
    if (markers.isEmpty()) return block(0, 0)
    val mod = wasmMod
    val strings = mutableListOf<Int>()
    fun cstr(s: String): Int {
        val len = mod.lengthBytesUTF8(s) + 1
        val p = mod.malloc(len)
        mod.stringToUTF8(s, p, len)
        strings.add(p)
        return p
    }
    val base = mod.malloc(markers.size * WasmOff.MARKER_SIZE)
    return try {
        markers.forEachIndexed { i, m ->
            val b = base + i * WasmOff.MARKER_SIZE
            wasmSetI32(b + WasmOff.MARKER_ID, cstr(m.markerId))
            wasmSetF64(b + WasmOff.MARKER_OFFSET, m.clipPositionOffset)
            wasmSetI32(b + WasmOff.MARKER_REF_TYPE, m.referenceType.nativeValue)
            wasmSetI32(b + WasmOff.MARKER_REF_CLIP_ID, cstr(m.referenceClipId))
            wasmSetI32(b + WasmOff.MARKER_REF_MARKER_ID, cstr(m.referenceMarkerId))
            wasmSetI32(b + WasmOff.MARKER_NAME, cstr(m.name))
        }
        block(base, markers.size)
    } finally {
        strings.forEach { mod.free(it) }
        mod.free(base)
    }
}

private fun <T> withWasmWarps(warps: List<AudioWarpPointData>, block: (Int, Int) -> T): T {
    if (warps.isEmpty()) return block(0, 0)
    val mod = wasmMod
    val strings = mutableListOf<Int>()
    fun cstr(s: String): Int {
        val len = mod.lengthBytesUTF8(s) + 1
        val p = mod.malloc(len)
        mod.stringToUTF8(s, p, len)
        strings.add(p)
        return p
    }
    val base = mod.malloc(warps.size * WasmOff.WARP_SIZE)
    return try {
        warps.forEachIndexed { i, w ->
            val b = base + i * WasmOff.WARP_SIZE
            wasmSetF64(b + WasmOff.WARP_OFFSET, w.clipPositionOffset)
            wasmSetF64(b + WasmOff.WARP_SPEED_RATIO, w.speedRatio)
            wasmSetI32(b + WasmOff.WARP_REF_TYPE, w.referenceType.nativeValue)
            wasmSetI32(b + WasmOff.WARP_REF_CLIP_ID, cstr(w.referenceClipId))
            wasmSetI32(b + WasmOff.WARP_REF_MARKER_ID, cstr(w.referenceMarkerId))
        }
        block(base, warps.size)
    } finally {
        strings.forEach { mod.free(it) }
        mod.free(base)
    }
}

// ─── Struct decoders ─────────────────────────────────────────────────────────

private fun decodeUndoResult(ptr: Int) = UndoResult(
    UndoStatus.fromNative(wasmGetI32(ptr + WasmOff.RESULT_STATUS)),
    wasmGetI32(ptr + WasmOff.RESULT_ERROR).let { if (it != 0) wasmMod.utf8ToString(it) else null }
)

internal fun decodeUndoState(ptr: Int) = UndoState(
    busy = wasmGetBool(ptr + WasmOff.STATE_BUSY),
    compoundOpen = wasmGetBool(ptr + WasmOff.STATE_COMPOUND_OPEN),
    gestureOpen = wasmGetBool(ptr + WasmOff.STATE_GESTURE_OPEN),
    canUndo = wasmGetBool(ptr + WasmOff.STATE_CAN_UNDO),
    canRedo = wasmGetBool(ptr + WasmOff.STATE_CAN_REDO),
    dirty = wasmGetBool(ptr + WasmOff.STATE_DIRTY),
    compoundDescription = wasmGetStr(ptr + WasmOff.STATE_COMPOUND_DESC),
    undoDescription = wasmGetStr(ptr + WasmOff.STATE_UNDO_DESC),
    redoDescription = wasmGetStr(ptr + WasmOff.STATE_REDO_DESC),
    historySizeInBytes = wasmGetI64(ptr + WasmOff.STATE_HISTORY_SIZE),
    maximumHistorySizeInBytes = wasmGetI64(ptr + WasmOff.STATE_MAX_HISTORY_SIZE),
    currentStateId = wasmGetI64(ptr + WasmOff.STATE_CURRENT_ID),
    savedStateId = wasmGetI64(ptr + WasmOff.STATE_SAVED_ID)
)

// ─── Callback registration ───────────────────────────────────────────────────

/** The C callback takes the result struct by value, i.e. as a pointer: "vii". */
private fun undoCompletionPtr(completion: ((UndoResult) -> Unit)?): Int {
    if (completion == null) return 0
    val cbId = nextCallbackId()
    pendingUndoCompletions[cbId] = completion
    return makeCFunctionPtr(cbId, "uapmdDispatchUndoCompletion", "vii")
}

private fun trackMutationPtr(callback: (Int, String?) -> Unit): Int {
    val cbId = nextCallbackId()
    pendingTrackMutations[cbId] = callback
    return makeCFunctionPtr(cbId, "uapmdDispatchTrackMutation", "viii")
}

private fun trackFragmentPtr(callback: (TrackFragment?, String?) -> Unit): Int {
    val cbId = nextCallbackId()
    pendingTrackFragments[cbId] = callback
    return makeCFunctionPtr(cbId, "uapmdDispatchTrackFragment", "viii")
}

// ─── WasmJsUndoEngine ────────────────────────────────────────────────────────

class WasmJsUndoEngine internal constructor(private val handle: Int) : UndoEngine {
    override val state: UndoState
        get() = withWasmStruct(WasmOff.STATE_SIZE) { p ->
            wasmMod.uapmdUndoEngineGetState(handle, p)
            decodeUndoState(p)
        }

    override fun undo(completion: ((UndoResult) -> Unit)?) =
        wasmMod.uapmdUndoEngineUndo(handle, 0, undoCompletionPtr(completion))

    override fun redo(completion: ((UndoResult) -> Unit)?) =
        wasmMod.uapmdUndoEngineRedo(handle, 0, undoCompletionPtr(completion))

    override fun beginCompound(description: String, origin: MutationOrigin): UndoResult =
        withWasmStruct(WasmOff.RESULT_SIZE) { out ->
            withCStringKt(description) { d -> wasmMod.uapmdUndoEngineBeginCompound(out, handle, d, origin.nativeValue) }
            decodeUndoResult(out)
        }

    override fun endCompound(completion: ((UndoResult) -> Unit)?) =
        wasmMod.uapmdUndoEngineEndCompound(handle, 0, undoCompletionPtr(completion))

    override fun cancelCompound(completion: ((UndoResult) -> Unit)?) =
        wasmMod.uapmdUndoEngineCancelCompound(handle, 0, undoCompletionPtr(completion))

    override fun beginGesture(description: String, origin: MutationOrigin): UndoResult =
        withWasmStruct(WasmOff.RESULT_SIZE) { out ->
            withCStringKt(description) { d -> wasmMod.uapmdUndoEngineBeginGesture(out, handle, d, origin.nativeValue) }
            decodeUndoResult(out)
        }

    override fun endGesture(completion: ((UndoResult) -> Unit)?) =
        wasmMod.uapmdUndoEngineEndGesture(handle, 0, undoCompletionPtr(completion))

    override fun cancelGesture(completion: ((UndoResult) -> Unit)?) =
        wasmMod.uapmdUndoEngineCancelGesture(handle, 0, undoCompletionPtr(completion))

    override fun clear(markCurrentStateSaved: Boolean): Boolean =
        wasmMod.uapmdUndoEngineClear(handle, markCurrentStateSaved)

    override fun markSaved(): Boolean = wasmMod.uapmdUndoEngineMarkSaved(handle)

    override fun markStateSaved(stateId: Long): Boolean =
        wasmUndoEngineMarkStateSaved(wasmMod, handle, stateId.toString())

    override fun setMaximumHistorySizeInBytes(bytes: Long): Boolean =
        wasmUndoEngineSetMaximumHistorySize(wasmMod, handle, bytes.toString())

    override fun shutdown() = wasmMod.uapmdUndoEngineShutdown(handle)
}

// ─── WasmJsCommandManager ────────────────────────────────────────────────────

class WasmJsCommandManager internal constructor(private val handle: Int) : CommandManager {
    override val state: UndoState
        get() = withWasmStruct(WasmOff.STATE_SIZE) { p ->
            wasmMod.uapmdCommandManagerGetState(handle, p)
            decodeUndoState(p)
        }

    override val history: UndoEngine get() = WasmJsUndoEngine(wasmMod.uapmdCommandManagerHistory(handle))

    override fun undo(completion: ((UndoResult) -> Unit)?) =
        wasmMod.uapmdCommandManagerUndo(handle, 0, undoCompletionPtr(completion))

    override fun redo(completion: ((UndoResult) -> Unit)?) =
        wasmMod.uapmdCommandManagerRedo(handle, 0, undoCompletionPtr(completion))

    override fun beginStep(description: String, origin: MutationOrigin): UndoResult =
        withWasmStruct(WasmOff.RESULT_SIZE) { out ->
            withCStringKt(description) { d -> wasmMod.uapmdCommandManagerBeginStep(out, handle, d, origin.nativeValue) }
            decodeUndoResult(out)
        }

    override fun endStep(completion: ((UndoResult) -> Unit)?) =
        wasmMod.uapmdCommandManagerEndStep(handle, 0, undoCompletionPtr(completion))

    override fun cancelStep(completion: ((UndoResult) -> Unit)?) =
        wasmMod.uapmdCommandManagerCancelStep(handle, 0, undoCompletionPtr(completion))

    override fun beginGesture(description: String, origin: MutationOrigin): UndoResult =
        withWasmStruct(WasmOff.RESULT_SIZE) { out ->
            withCStringKt(description) { d -> wasmMod.uapmdCommandManagerBeginGesture(out, handle, d, origin.nativeValue) }
            decodeUndoResult(out)
        }

    override fun endGesture(completion: ((UndoResult) -> Unit)?) =
        wasmMod.uapmdCommandManagerEndGesture(handle, 0, undoCompletionPtr(completion))

    override fun cancelGesture(completion: ((UndoResult) -> Unit)?) =
        wasmMod.uapmdCommandManagerCancelGesture(handle, 0, undoCompletionPtr(completion))

    override fun shutdown() = wasmMod.uapmdCommandManagerShutdown(handle)
}

// ─── WasmJsProjectCommands ───────────────────────────────────────────────────

class WasmJsProjectCommands internal constructor(private val handle: Int) : ProjectCommands {
    override val history: CommandManager get() = WasmJsCommandManager(wasmMod.uapmdCommandsHistory(handle))

    override fun setClipEnabled(trackIndex: Int, clipId: Int, enabled: Boolean, origin: MutationOrigin) =
        wasmMod.uapmdCommandsSetClipEnabled(handle, trackIndex, clipId, enabled, origin.nativeValue)

    override fun setClipAnchor(trackIndex: Int, clipId: Int, anchor: TimeReference, origin: MutationOrigin): Boolean =
        withWasmStruct(WasmOff.TIMEREF_SIZE) { p ->
            withCStringKt(anchor.referenceId) { refPtr ->
                wasmSetI32(p + WasmOff.TIMEREF_TYPE, anchor.type.nativeValue)
                wasmSetI32(p + WasmOff.TIMEREF_REFERENCE_ID, refPtr)
                wasmSetF64(p + WasmOff.TIMEREF_OFFSET, anchor.offset)
                wasmMod.uapmdCommandsSetClipAnchor(handle, trackIndex, clipId, p, origin.nativeValue)
            }
        }

    override fun setClipGain(trackIndex: Int, clipId: Int, gain: Double, origin: MutationOrigin) =
        wasmMod.uapmdCommandsSetClipGain(handle, trackIndex, clipId, gain, origin.nativeValue)

    override fun setClipMuted(trackIndex: Int, clipId: Int, muted: Boolean, origin: MutationOrigin) =
        wasmMod.uapmdCommandsSetClipMuted(handle, trackIndex, clipId, muted, origin.nativeValue)

    override fun resizeClip(trackIndex: Int, clipId: Int, newDurationSamples: Long, origin: MutationOrigin) =
        wasmCommandsResizeClip(wasmMod, handle, trackIndex, clipId, newDurationSamples.toString(), origin.nativeValue)

    override fun setClipName(trackIndex: Int, clipId: Int, name: String, origin: MutationOrigin) =
        withCStringKt(name) { p -> wasmMod.uapmdCommandsSetClipName(handle, trackIndex, clipId, p, origin.nativeValue) }

    override fun setClipFilepath(trackIndex: Int, clipId: Int, filepath: String, origin: MutationOrigin) =
        withCStringKt(filepath) { p -> wasmMod.uapmdCommandsSetClipFilepath(handle, trackIndex, clipId, p, origin.nativeValue) }

    override fun setClipNeedsFileSave(trackIndex: Int, clipId: Int, needsSave: Boolean, origin: MutationOrigin) =
        wasmMod.uapmdCommandsSetClipNeedsFileSave(handle, trackIndex, clipId, needsSave, origin.nativeValue)

    override fun setClipMarkers(trackIndex: Int, clipId: Int, markers: List<ClipMarkerData>, origin: MutationOrigin) =
        withWasmMarkers(markers) { ptr, count ->
            wasmMod.uapmdCommandsSetClipMarkers(handle, trackIndex, clipId, ptr, count, origin.nativeValue)
        }

    override fun setClipAudioWarps(trackIndex: Int, clipId: Int, warps: List<AudioWarpPointData>, origin: MutationOrigin) =
        withWasmWarps(warps) { ptr, count ->
            wasmMod.uapmdCommandsSetClipAudioWarps(handle, trackIndex, clipId, ptr, count, origin.nativeValue)
        }

    override fun setTrackGain(trackIndex: Int, gain: Double, origin: MutationOrigin) =
        wasmMod.uapmdCommandsSetTrackGain(handle, trackIndex, gain, origin.nativeValue)

    override fun setTrackMuted(trackIndex: Int, muted: Boolean, origin: MutationOrigin) =
        wasmMod.uapmdCommandsSetTrackMuted(handle, trackIndex, muted, origin.nativeValue)

    override fun setTrackSolo(trackIndex: Int, solo: Boolean, origin: MutationOrigin) =
        wasmMod.uapmdCommandsSetTrackSolo(handle, trackIndex, solo, origin.nativeValue)

    override fun setTrackBypassed(trackIndex: Int, bypassed: Boolean, origin: MutationOrigin) =
        wasmMod.uapmdCommandsSetTrackBypassed(handle, trackIndex, bypassed, origin.nativeValue)

    override fun setTrackFreezePolicyEnabled(trackIndex: Int, enabled: Boolean, origin: MutationOrigin) =
        wasmMod.uapmdCommandsSetTrackFreezePolicyEnabled(handle, trackIndex, enabled, origin.nativeValue)

    override fun setPluginBypassed(instanceId: Int, bypassed: Boolean, origin: MutationOrigin) =
        wasmMod.uapmdCommandsSetPluginBypassed(handle, instanceId, bypassed, origin.nativeValue)

    override fun setPluginParameterValue(instanceId: Int, parameterIndex: Int, value: Double, origin: MutationOrigin) =
        wasmMod.uapmdCommandsSetPluginParameterValue(handle, instanceId, parameterIndex, value, origin.nativeValue)

    override fun setPluginPerNoteControllerValue(
        instanceId: Int, contextType: Int, note: Int, channel: Int, group: Int, extra: Int,
        parameterIndex: Int, value: Double, origin: MutationOrigin
    ) = wasmMod.uapmdCommandsSetPluginPerNoteControllerValue(
        handle, instanceId, contextType, note, channel, group, extra, parameterIndex, value, origin.nativeValue
    )

    override fun setPluginGroup(instanceId: Int, group: UByte, origin: MutationOrigin) =
        wasmMod.uapmdCommandsSetPluginGroup(handle, instanceId, group.toInt(), origin.nativeValue)

    override fun setMasterTrackMarkers(markers: List<ClipMarkerData>, origin: MutationOrigin) =
        withWasmMarkers(markers) { ptr, count ->
            wasmMod.uapmdCommandsSetMasterTrackMarkers(handle, ptr, count, origin.nativeValue)
        }
}

// ─── WasmJsProjectAddressBook ────────────────────────────────────────────────

class WasmJsProjectAddressBook internal constructor(private val handle: Int) : ProjectAddressBook {
    override fun timelineTrack(trackReferenceId: String): TimelineTrack? =
        withCStringKt(trackReferenceId) { p ->
            wasmMod.uapmdAddressesTimelineTrack(handle, p).takeIf { it != 0 }?.let { WasmJsTimelineTrack(it) }
        }

    override fun sequencerTrack(trackReferenceId: String): SequencerTrack? =
        withCStringKt(trackReferenceId) { p ->
            wasmMod.uapmdAddressesSequencerTrack(handle, p).takeIf { it != 0 }?.let { WasmJsSequencerTrack(it) }
        }

    override fun trackIndex(trackReferenceId: String): Int =
        withCStringKt(trackReferenceId) { p -> wasmMod.uapmdAddressesTrackIndex(handle, p) }

    override fun clipId(address: ClipAddress): Int =
        withWasmStruct(WasmOff.ADDRESS_SIZE) { s ->
            withTwoCStringsKt(address.trackReferenceId, address.clipReferenceId) { a, b ->
                wasmSetI32(s, a)
                wasmSetI32(s + 4, b)
                wasmMod.uapmdAddressesClipId(handle, s)
            }
        }

    override fun pluginInstanceId(address: PluginAddress): Int =
        withWasmStruct(WasmOff.ADDRESS_SIZE) { s ->
            withTwoCStringsKt(address.trackReferenceId, address.nodeId) { a, b ->
                wasmSetI32(s, a)
                wasmSetI32(s + 4, b)
                wasmMod.uapmdAddressesPluginInstanceId(handle, s)
            }
        }

    override fun trackReferenceId(trackIndex: Int): String? =
        wasmMod.uapmdAddressesTrackReferenceId(handle, trackIndex).takeIf { it != 0 }?.let { wasmMod.utf8ToString(it) }

    override fun clipAddress(trackIndex: Int, clipId: Int): ClipAddress? =
        withWasmStruct(WasmOff.ADDRESS_SIZE) { out ->
            if (!wasmMod.uapmdAddressesClipAddress(handle, trackIndex, clipId, out)) null
            else ClipAddress(wasmGetStr(out), wasmGetStr(out + 4))
        }

    override fun pluginAddress(instanceId: Int): PluginAddress? =
        withWasmStruct(WasmOff.ADDRESS_SIZE) { out ->
            if (!wasmMod.uapmdAddressesPluginAddress(handle, instanceId, out)) null
            else PluginAddress(wasmGetStr(out), wasmGetStr(out + 4))
        }
}

// ─── Fragments ───────────────────────────────────────────────────────────────

class WasmJsClipFragment internal constructor(
    internal val handle: Int,
    /** Fragments borrowed from a track fragment are released with their owner. */
    private val owned: Boolean
) : ClipFragment {

    override val isMidi: Boolean get() = wasmMod.uapmdClipFragmentIsMidi(handle)

    override val clip: ClipData
        get() = withWasmStruct(WasmOff.CLIP_SIZE) { p ->
            wasmMod.uapmdClipFragmentGetClip(handle, p)
            ClipData(
                clipId = wasmGetI32(p + WasmOff.CLIP_ID),
                positionSamples = wasmGetI64(p + WasmOff.CLIP_POSITION),
                positionLegacyBeats = wasmGetF64(p + WasmOff.CLIP_POSITION + 8),
                durationSamples = wasmGetI64(p + WasmOff.CLIP_DURATION),
                gain = wasmGetF64(p + WasmOff.CLIP_GAIN),
                muted = wasmGetBool(p + WasmOff.CLIP_MUTED),
                name = wasmGetStr(p + WasmOff.CLIP_NAME),
                filepath = wasmGetStr(p + WasmOff.CLIP_FILEPATH),
                clipType = ClipType.fromNative(wasmGetI32(p + WasmOff.CLIP_TYPE))
            )
        }

    override val umpEvents: UIntArray
        get() {
            val n = wasmMod.uapmdClipFragmentGetUmpEvents(handle, 0, 0)
            if (n <= 0) return UIntArray(0)
            return withWasmStruct(n * 4) { p ->
                wasmMod.uapmdClipFragmentGetUmpEvents(handle, p, n)
                UIntArray(n) { wasmGetI32(p + it * 4).toUInt() }
            }
        }

    override val umpTickTimestamps: LongArray
        get() {
            val n = wasmMod.uapmdClipFragmentGetUmpTickTimestamps(handle, 0, 0)
            if (n <= 0) return LongArray(0)
            return withWasmStruct(n * 8) { p ->
                wasmMod.uapmdClipFragmentGetUmpTickTimestamps(handle, p, n)
                LongArray(n) { wasmGetI64(p + it * 8) }
            }
        }

    override val extensionState: Map<String, ByteArray>
        get() {
            val count = wasmMod.uapmdClipFragmentExtensionStateCount(handle)
            if (count == 0) return emptyMap()
            return (0 until count).associate { i ->
                val key = readStringIndexed(handle, i) { h, idx, buf, size ->
                    uapmdClipFragmentExtensionStateKey(h, idx, buf, size)
                }
                val size = wasmMod.uapmdClipFragmentExtensionStateData(handle, i, 0, 0)
                val data = if (size <= 0) ByteArray(0) else withWasmStruct(size) { p ->
                    wasmMod.uapmdClipFragmentExtensionStateData(handle, i, p, size)
                    ByteArray(size) { wasmMod.getValue(p + it, "i8").toInt().toByte() }
                }
                key to data
            }
        }

    override fun close() {
        if (owned) wasmMod.uapmdClipFragmentDestroy(handle)
    }
}

class WasmJsTrackFragment internal constructor(internal val handle: Int) : TrackFragment {
    override val referenceId: String
        get() = readString(handle) { h, buf, size -> uapmdTrackFragmentReferenceId(h, buf, size) }

    override val volume: Double get() = wasmMod.uapmdTrackFragmentVolume(handle)
    override val muted: Boolean get() = wasmMod.uapmdTrackFragmentMuted(handle)
    override val solo: Boolean get() = wasmMod.uapmdTrackFragmentSolo(handle)

    override val graphType: String
        get() = readString(handle) { h, buf, size -> uapmdTrackFragmentGraphType(h, buf, size) }

    override val graphBytes: ByteArray
        get() {
            val size = wasmMod.uapmdTrackFragmentGraphBytes(handle, 0, 0)
            if (size <= 0) return ByteArray(0)
            return withWasmStruct(size) { p ->
                wasmMod.uapmdTrackFragmentGraphBytes(handle, p, size)
                ByteArray(size) { wasmMod.getValue(p + it, "i8").toInt().toByte() }
            }
        }

    override val clips: List<ClipFragment>
        get() = (0 until wasmMod.uapmdTrackFragmentClipCount(handle)).mapNotNull { i ->
            wasmMod.uapmdTrackFragmentGetClip(handle, i).takeIf { it != 0 }?.let { WasmJsClipFragment(it, owned = false) }
        }

    override val plugins: List<TrackPluginFragment>
        get() = withWasmStruct(WasmOff.PLUGIN_SIZE) { out ->
            (0 until wasmMod.uapmdTrackFragmentPluginCount(handle)).mapNotNull { i ->
                if (!wasmMod.uapmdTrackFragmentGetPlugin(handle, i, out)) return@mapNotNull null
                val stateSize = wasmGetI32(out + WasmOff.PLUGIN_STATE_SIZE)
                val statePtr = wasmGetI32(out + WasmOff.PLUGIN_STATE)
                TrackPluginFragment(
                    nodeId = wasmGetStr(out + WasmOff.PLUGIN_NODE_ID),
                    pluginId = wasmGetStr(out + WasmOff.PLUGIN_PLUGIN_ID),
                    format = wasmGetStr(out + WasmOff.PLUGIN_FORMAT),
                    displayName = wasmGetStr(out + WasmOff.PLUGIN_DISPLAY_NAME),
                    groupIndex = wasmGetI32(out + WasmOff.PLUGIN_GROUP_INDEX),
                    state = if (stateSize <= 0 || statePtr == 0) ByteArray(0)
                            else ByteArray(stateSize) { wasmMod.getValue(statePtr + it, "i8").toInt().toByte() }
                )
            }
        }

    override fun close() = wasmMod.uapmdTrackFragmentDestroy(handle)
}

// ─── Timeline history implementation ─────────────────────────────────────────

internal class WasmJsTimelineHistory(private val handle: Int) {
    val undoEngine: UndoEngine get() = WasmJsUndoEngine(wasmMod.uapmdTlUndoEngine(handle))
    val commands: ProjectCommands get() = WasmJsProjectCommands(wasmMod.uapmdTlCommands(handle))
    val addresses: ProjectAddressBook get() = WasmJsProjectAddressBook(wasmMod.uapmdTlAddresses(handle))

    fun <T> documentTransaction(block: () -> T): T {
        wasmMod.uapmdTlBeginDocumentTransaction(handle)
        try {
            return block()
        } finally {
            wasmMod.uapmdTlEndDocumentTransaction(handle)
        }
    }

    fun removeClip(trackIndex: Int, clipId: Int, origin: MutationOrigin) =
        wasmMod.uapmdTlRemoveClipWithOrigin(handle, trackIndex, clipId, origin.nativeValue)

    fun clearClipsFromTrack(trackIndex: Int, origin: MutationOrigin) =
        wasmMod.uapmdTlClearClipsFromTrack(handle, trackIndex, origin.nativeValue)

    fun isClipEnabled(trackIndex: Int, clipId: Int) = wasmMod.uapmdTlClipEnabled(handle, trackIndex, clipId)

    fun replaceMidiClipContent(
        trackIndex: Int, clipId: Int, umpEvents: UIntArray, tickTimestamps: LongArray, origin: MutationOrigin
    ): Boolean = withWasmStruct(maxOf(1, umpEvents.size * 4)) { eventsPtr ->
        withWasmStruct(maxOf(1, tickTimestamps.size * 8)) { ticksPtr ->
            umpEvents.forEachIndexed { i, v -> wasmSetI32(eventsPtr + i * 4, v.toInt()) }
            tickTimestamps.forEachIndexed { i, v ->
                wasmSetI32(ticksPtr + i * 8, v.toInt())
                wasmSetI32(ticksPtr + i * 8 + 4, (v ushr 32).toInt())
            }
            wasmMod.uapmdTlReplaceMidiClipContent(
                handle, trackIndex, clipId,
                if (umpEvents.isEmpty()) 0 else eventsPtr, umpEvents.size,
                if (tickTimestamps.isEmpty()) 0 else ticksPtr, tickTimestamps.size,
                origin.nativeValue
            )
        }
    }

    fun replaceAudioClipContent(
        trackIndex: Int, clipId: Int, filepath: String,
        markers: List<ClipMarkerData>, warps: List<AudioWarpPointData>,
        masterMarkers: List<ClipMarkerData>, origin: MutationOrigin
    ): Boolean = withCStringKt(filepath) { fp ->
        withWasmMarkers(markers) { mPtr, mCount ->
            withWasmWarps(warps) { wPtr, wCount ->
                withWasmMarkers(masterMarkers) { mmPtr, mmCount ->
                    wasmMod.uapmdTlReplaceAudioClipContent(
                        handle, trackIndex, clipId, fp,
                        mPtr, mCount, wPtr, wCount, mmPtr, mmCount, origin.nativeValue
                    )
                }
            }
        }
    }

    fun captureClipFragment(trackIndex: Int, clipId: Int): ClipFragment? =
        wasmMod.uapmdTlCaptureClipFragment(handle, trackIndex, clipId)
            .takeIf { it != 0 }?.let { WasmJsClipFragment(it, owned = true) }

    fun attachClipFragment(trackIndex: Int, fragment: ClipFragment, idPolicy: ObjectIdPolicy): ClipAddResult =
        withWasmStruct(WasmOff.CLIP_ADD_SIZE) { out ->
            wasmMod.uapmdTlAttachClipFragment(
                out, handle, trackIndex, (fragment as WasmJsClipFragment).handle, idPolicy.nativeValue
            )
            ClipAddResult(
                clipId = wasmGetI32(out + WasmOff.CLIP_ADD_CLIP_ID),
                sourceNodeId = wasmGetI32(out + WasmOff.CLIP_ADD_SOURCE_NODE_ID),
                success = wasmGetBool(out + WasmOff.CLIP_ADD_SUCCESS),
                error = wasmGetI32(out + WasmOff.CLIP_ADD_ERROR).let { if (it != 0) wasmMod.utf8ToString(it) else null }
            )
        }

    fun captureTrackFragment(trackIndex: Int, callback: (TrackFragment?, String?) -> Unit) =
        wasmMod.uapmdTlCaptureTrackFragment(handle, trackIndex, 0, trackFragmentPtr(callback))

    fun attachTrackFragment(fragment: TrackFragment, options: TrackAttachOptions, callback: (Int, String?) -> Unit) {
        withWasmStruct(WasmOff.ATTACH_SIZE) { p ->
            wasmSetI32(p + WasmOff.ATTACH_ID_POLICY, options.idPolicy.nativeValue)
            wasmSetI32(p + WasmOff.ATTACH_INSERTION_INDEX, options.insertionIndex)
            wasmSetI8(p + WasmOff.ATTACH_INCLUDE_PLUGINS, if (options.includePlugins) 1 else 0)
            wasmSetI8(p + WasmOff.ATTACH_INCLUDE_PLUGIN_STATE, if (options.includePluginState) 1 else 0)
            wasmSetI8(p + WasmOff.ATTACH_INCLUDE_CLIPS, if (options.includeClips) 1 else 0)
            wasmMod.uapmdTlAttachTrackFragment(
                handle, (fragment as WasmJsTrackFragment).handle, p, 0, trackMutationPtr(callback)
            )
        }
    }

    fun addEmptyTrack(origin: MutationOrigin, callback: (Int, String?) -> Unit) =
        wasmMod.uapmdTlAddEmptyTrackUndoable(handle, origin.nativeValue, 0, trackMutationPtr(callback))

    fun removeTrack(trackIndex: Int, origin: MutationOrigin, callback: (Int, String?) -> Unit) =
        wasmMod.uapmdTlRemoveTrackUndoable(handle, trackIndex, origin.nativeValue, 0, trackMutationPtr(callback))

    fun recordTrackAddition(trackIndex: Int, origin: MutationOrigin, callback: (Int, String?) -> Unit) =
        wasmMod.uapmdTlRecordTrackAddition(handle, trackIndex, origin.nativeValue, 0, trackMutationPtr(callback))

    fun setPluginState(instanceId: Int, state: ByteArray, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) {
        withWasmStruct(maxOf(1, state.size)) { p ->
            state.forEachIndexed { i, b -> wasmSetI8(p + i, b.toInt()) }
            wasmMod.uapmdTlSetPluginState(
                handle, instanceId, if (state.isEmpty()) 0 else p, state.size, origin.nativeValue,
                0, undoCompletionPtr(completion)
            )
        }
    }

    fun loadPluginPreset(instanceId: Int, presetIndex: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        wasmMod.uapmdTlLoadPluginPreset(handle, instanceId, presetIndex, origin.nativeValue, 0, undoCompletionPtr(completion))

    fun recordPluginInstanceAddition(instanceId: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        wasmMod.uapmdTlRecordPluginInstanceAddition(handle, instanceId, origin.nativeValue, 0, undoCompletionPtr(completion))

    fun removePluginInstance(instanceId: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        wasmMod.uapmdTlRemovePluginInstanceUndoable(handle, instanceId, origin.nativeValue, 0, undoCompletionPtr(completion))

    val hasPendingPluginMutations: Boolean get() = wasmMod.uapmdTlHasPendingPluginMutations(handle)
}

// ─── Engine dirty state and master markers ───────────────────────────────────

internal class WasmJsEngineHistory(private val handle: Int) {
    val isProjectDirty: Boolean get() = wasmMod.uapmdEngineIsProjectDirty(handle)
    fun isTrackDirty(trackIndex: Int) = wasmMod.uapmdEngineIsTrackDirty(handle, trackIndex)
    fun markTrackDirty(trackIndex: Int, dirty: Boolean) = wasmMod.uapmdEngineMarkTrackDirty(handle, trackIndex, dirty)
    fun clearTrackDirtyState() = wasmMod.uapmdEngineClearTrackDirtyState(handle)

    var masterTrackMarkers: List<ClipMarkerData>
        get() {
            val count = wasmMod.uapmdEngineMasterMarkerCount(handle)
            if (count == 0) return emptyList()
            return withWasmStruct(WasmOff.MARKER_SIZE) { out ->
                (0 until count).mapNotNull { i ->
                    if (!wasmMod.uapmdEngineGetMasterMarker(handle, i, out)) return@mapNotNull null
                    ClipMarkerData(
                        markerId = wasmGetStr(out + WasmOff.MARKER_ID),
                        clipPositionOffset = wasmGetF64(out + WasmOff.MARKER_OFFSET),
                        referenceType = WarpReferenceType.fromNative(wasmGetI32(out + WasmOff.MARKER_REF_TYPE)),
                        referenceClipId = wasmGetStr(out + WasmOff.MARKER_REF_CLIP_ID),
                        referenceMarkerId = wasmGetStr(out + WasmOff.MARKER_REF_MARKER_ID),
                        name = wasmGetStr(out + WasmOff.MARKER_NAME)
                    )
                }
            }
        }
        set(value) {
            withWasmMarkers(value) { ptr, count -> wasmMod.uapmdEngineSetMasterMarkers(handle, ptr, count) }
        }

    fun registerAddinExtensionPoints(manager: AddinManager) =
        wasmMod.uapmdEngineRegisterAddinExtensionPoints(handle, (manager as WasmJsAddinManager).handle)
}
