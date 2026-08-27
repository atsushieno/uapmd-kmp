@file:Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")

package dev.atsushieno.uapmd

/**
 * K/JS bindings for the uapmd 0.5.6 project history.
 *
 * Two Emscripten ABI rules drive everything here:
 *  - a function returning a struct takes a hidden result pointer as its FIRST
 *    argument and returns nothing;
 *  - a struct passed by value is passed as a pointer in its declared position.
 * Struct field offsets below are the wasm32 layout of the C headers.
 */

// ─── Struct offsets (wasm32) ─────────────────────────────────────────────────

internal object Off {
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

    // uapmd_clip_address_t / uapmd_plugin_address_t, sizeof 8
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

internal fun jsSetPtr(ptr: Int, v: Int) { jsMod.setValue(ptr, v, "i32") }
internal fun jsSetF64(ptr: Int, v: Double) { jsMod.setValue(ptr, v, "double") }

/** WASM_BIGINT=1 means scalar i64 parameters cross the boundary as BigInt. */
private fun bigInt(v: Long): dynamic = js("BigInt")(v.toString())

/** Reads a uint64 field written as two little-endian 32-bit halves. */
private fun jsGetU64AsLong(ptr: Int): Long = jsGetI64(ptr)

// ─── Struct encoders ─────────────────────────────────────────────────────────

/**
 * Allocates a C array of markers plus the strings they point at, runs [block]
 * with (arrayPtr, count), and frees everything afterwards. An empty list yields
 * a null pointer, which the C side reads as "no markers".
 */
private fun <T> withJsMarkers(markers: List<ClipMarkerData>, block: (Int, Int) -> T): T {
    if (markers.isEmpty()) return block(0, 0)
    val strings = mutableListOf<Int>()
    fun cstr(s: String): Int {
        val len = (jsMod.lengthBytesUTF8(s) as Int) + 1
        val p = jsMod._malloc(len) as Int
        jsMod.stringToUTF8(s, p, len)
        strings.add(p)
        return p
    }
    val base = jsMod._malloc(markers.size * Off.MARKER_SIZE) as Int
    return try {
        markers.forEachIndexed { i, m ->
            val b = base + i * Off.MARKER_SIZE
            jsSetPtr(b + Off.MARKER_ID, cstr(m.markerId))
            jsSetF64(b + Off.MARKER_OFFSET, m.clipPositionOffset)
            jsSetI32(b + Off.MARKER_REF_TYPE, m.referenceType.nativeValue)
            jsSetPtr(b + Off.MARKER_REF_CLIP_ID, cstr(m.referenceClipId))
            jsSetPtr(b + Off.MARKER_REF_MARKER_ID, cstr(m.referenceMarkerId))
            jsSetPtr(b + Off.MARKER_NAME, cstr(m.name))
        }
        block(base, markers.size)
    } finally {
        strings.forEach { jsMod._free(it) }
        jsMod._free(base)
    }
}

private fun <T> withJsWarps(warps: List<AudioWarpPointData>, block: (Int, Int) -> T): T {
    if (warps.isEmpty()) return block(0, 0)
    val strings = mutableListOf<Int>()
    fun cstr(s: String): Int {
        val len = (jsMod.lengthBytesUTF8(s) as Int) + 1
        val p = jsMod._malloc(len) as Int
        jsMod.stringToUTF8(s, p, len)
        strings.add(p)
        return p
    }
    val base = jsMod._malloc(warps.size * Off.WARP_SIZE) as Int
    return try {
        warps.forEachIndexed { i, w ->
            val b = base + i * Off.WARP_SIZE
            jsSetF64(b + Off.WARP_OFFSET, w.clipPositionOffset)
            jsSetF64(b + Off.WARP_SPEED_RATIO, w.speedRatio)
            jsSetI32(b + Off.WARP_REF_TYPE, w.referenceType.nativeValue)
            jsSetPtr(b + Off.WARP_REF_CLIP_ID, cstr(w.referenceClipId))
            jsSetPtr(b + Off.WARP_REF_MARKER_ID, cstr(w.referenceMarkerId))
        }
        block(base, warps.size)
    } finally {
        strings.forEach { jsMod._free(it) }
        jsMod._free(base)
    }
}

// ─── Struct decoders ─────────────────────────────────────────────────────────

private fun decodeUndoResult(ptr: Int) = UndoResult(
    UndoStatus.fromNative(jsGetI32(ptr + Off.RESULT_STATUS)),
    jsGetPtr(ptr + Off.RESULT_ERROR).let { if (it != 0) jsMod.UTF8ToString(it) as String else null }
)

internal fun decodeUndoState(ptr: Int) = UndoState(
    busy = jsGetBool(ptr + Off.STATE_BUSY),
    compoundOpen = jsGetBool(ptr + Off.STATE_COMPOUND_OPEN),
    gestureOpen = jsGetBool(ptr + Off.STATE_GESTURE_OPEN),
    canUndo = jsGetBool(ptr + Off.STATE_CAN_UNDO),
    canRedo = jsGetBool(ptr + Off.STATE_CAN_REDO),
    dirty = jsGetBool(ptr + Off.STATE_DIRTY),
    compoundDescription = jsGetStr(ptr + Off.STATE_COMPOUND_DESC),
    undoDescription = jsGetStr(ptr + Off.STATE_UNDO_DESC),
    redoDescription = jsGetStr(ptr + Off.STATE_REDO_DESC),
    historySizeInBytes = jsGetU64AsLong(ptr + Off.STATE_HISTORY_SIZE),
    maximumHistorySizeInBytes = jsGetU64AsLong(ptr + Off.STATE_MAX_HISTORY_SIZE),
    currentStateId = jsGetU64AsLong(ptr + Off.STATE_CURRENT_ID),
    savedStateId = jsGetU64AsLong(ptr + Off.STATE_SAVED_ID)
)

/** Emscripten passes a by-value struct argument as a pointer, so the callback
 *  signature is "vii": (resultPtr, userData). */
private fun makeJsUndoCompletion(completion: (UndoResult) -> Unit): Int {
    var slot = 0
    val fn: (dynamic, dynamic) -> Unit = { resultPtr, _ ->
        try {
            completion(decodeUndoResult(resultPtr as Int))
        } finally {
            removeJsCallback(slot)
        }
    }
    slot = addJsCallback(fn.asDynamic(), "vii")
    return slot
}

internal fun makeJsTrackMutation(callback: (Int, String?) -> Unit): Int {
    var slot = 0
    val fn: (dynamic, dynamic, dynamic) -> Unit = { trackIndex, errorPtr, _ ->
        try {
            val err = if ((errorPtr as Int) != 0) jsMod.UTF8ToString(errorPtr) as String else null
            callback(trackIndex as Int, err)
        } finally {
            removeJsCallback(slot)
        }
    }
    slot = addJsCallback(fn.asDynamic(), "viii")
    return slot
}

private fun makeJsTrackFragment(callback: (TrackFragment?, String?) -> Unit): Int {
    var slot = 0
    val fn: (dynamic, dynamic, dynamic) -> Unit = { fragmentPtr, errorPtr, _ ->
        try {
            val err = if ((errorPtr as Int) != 0) jsMod.UTF8ToString(errorPtr) as String else null
            val handle = fragmentPtr as Int
            callback(if (handle != 0) JsTrackFragment(handle) else null, err)
        } finally {
            removeJsCallback(slot)
        }
    }
    slot = addJsCallback(fn.asDynamic(), "viii")
    return slot
}

// ─── JsUndoEngine ────────────────────────────────────────────────────────────

class JsUndoEngine internal constructor(private val handle: Int) : UndoEngine {
    override val state: UndoState
        get() = withWasmMem(Off.STATE_SIZE) { p ->
            jsMod._uapmd_undo_engine_get_state(handle, p)
            decodeUndoState(p)
        }

    override fun undo(completion: ((UndoResult) -> Unit)?) {
        jsMod._uapmd_undo_engine_undo(handle, 0, completion?.let { makeJsUndoCompletion(it) } ?: 0)
    }

    override fun redo(completion: ((UndoResult) -> Unit)?) {
        jsMod._uapmd_undo_engine_redo(handle, 0, completion?.let { makeJsUndoCompletion(it) } ?: 0)
    }

    override fun beginCompound(description: String, origin: MutationOrigin): UndoResult =
        withWasmMem(Off.RESULT_SIZE) { out ->
            withJsCString(description) { d ->
                jsMod._uapmd_undo_engine_begin_compound(out, handle, d, origin.nativeValue)
            }
            decodeUndoResult(out)
        }

    override fun endCompound(completion: ((UndoResult) -> Unit)?) {
        jsMod._uapmd_undo_engine_end_compound(handle, 0, completion?.let { makeJsUndoCompletion(it) } ?: 0)
    }

    override fun cancelCompound(completion: ((UndoResult) -> Unit)?) {
        jsMod._uapmd_undo_engine_cancel_compound(handle, 0, completion?.let { makeJsUndoCompletion(it) } ?: 0)
    }

    override fun beginGesture(description: String, origin: MutationOrigin): UndoResult =
        withWasmMem(Off.RESULT_SIZE) { out ->
            withJsCString(description) { d ->
                jsMod._uapmd_undo_engine_begin_gesture(out, handle, d, origin.nativeValue)
            }
            decodeUndoResult(out)
        }

    override fun endGesture(completion: ((UndoResult) -> Unit)?) {
        jsMod._uapmd_undo_engine_end_gesture(handle, 0, completion?.let { makeJsUndoCompletion(it) } ?: 0)
    }

    override fun cancelGesture(completion: ((UndoResult) -> Unit)?) {
        jsMod._uapmd_undo_engine_cancel_gesture(handle, 0, completion?.let { makeJsUndoCompletion(it) } ?: 0)
    }

    override fun clear(markCurrentStateSaved: Boolean): Boolean =
        jsMod._uapmd_undo_engine_clear(handle, markCurrentStateSaved) as Boolean

    override fun markSaved(): Boolean = jsMod._uapmd_undo_engine_mark_saved(handle) as Boolean

    override fun markStateSaved(stateId: Long): Boolean =
        jsMod._uapmd_undo_engine_mark_state_saved(handle, bigInt(stateId)) as Boolean

    override fun setMaximumHistorySizeInBytes(bytes: Long): Boolean =
        jsMod._uapmd_undo_engine_set_maximum_history_size(handle, bigInt(bytes)) as Boolean

    override fun shutdown() { jsMod._uapmd_undo_engine_shutdown(handle) }
}

// ─── JsCommandManager ────────────────────────────────────────────────────────

class JsCommandManager internal constructor(private val handle: Int) : CommandManager {
    override val state: UndoState
        get() = withWasmMem(Off.STATE_SIZE) { p ->
            jsMod._uapmd_command_manager_get_state(handle, p)
            decodeUndoState(p)
        }

    override val history: UndoEngine get() = JsUndoEngine(jsMod._uapmd_command_manager_history(handle) as Int)

    override fun undo(completion: ((UndoResult) -> Unit)?) {
        jsMod._uapmd_command_manager_undo(handle, 0, completion?.let { makeJsUndoCompletion(it) } ?: 0)
    }

    override fun redo(completion: ((UndoResult) -> Unit)?) {
        jsMod._uapmd_command_manager_redo(handle, 0, completion?.let { makeJsUndoCompletion(it) } ?: 0)
    }

    override fun beginStep(description: String, origin: MutationOrigin): UndoResult =
        withWasmMem(Off.RESULT_SIZE) { out ->
            withJsCString(description) { d ->
                jsMod._uapmd_command_manager_begin_step(out, handle, d, origin.nativeValue)
            }
            decodeUndoResult(out)
        }

    override fun endStep(completion: ((UndoResult) -> Unit)?) {
        jsMod._uapmd_command_manager_end_step(handle, 0, completion?.let { makeJsUndoCompletion(it) } ?: 0)
    }

    override fun cancelStep(completion: ((UndoResult) -> Unit)?) {
        jsMod._uapmd_command_manager_cancel_step(handle, 0, completion?.let { makeJsUndoCompletion(it) } ?: 0)
    }

    override fun beginGesture(description: String, origin: MutationOrigin): UndoResult =
        withWasmMem(Off.RESULT_SIZE) { out ->
            withJsCString(description) { d ->
                jsMod._uapmd_command_manager_begin_gesture(out, handle, d, origin.nativeValue)
            }
            decodeUndoResult(out)
        }

    override fun endGesture(completion: ((UndoResult) -> Unit)?) {
        jsMod._uapmd_command_manager_end_gesture(handle, 0, completion?.let { makeJsUndoCompletion(it) } ?: 0)
    }

    override fun cancelGesture(completion: ((UndoResult) -> Unit)?) {
        jsMod._uapmd_command_manager_cancel_gesture(handle, 0, completion?.let { makeJsUndoCompletion(it) } ?: 0)
    }

    override fun shutdown() { jsMod._uapmd_command_manager_shutdown(handle) }
}

// ─── JsProjectCommands ───────────────────────────────────────────────────────

class JsProjectCommands internal constructor(private val handle: Int) : ProjectCommands {
    override val history: CommandManager get() = JsCommandManager(jsMod._uapmd_commands_history(handle) as Int)

    override fun setClipEnabled(trackIndex: Int, clipId: Int, enabled: Boolean, origin: MutationOrigin): Boolean =
        jsMod._uapmd_commands_set_clip_enabled(handle, trackIndex, clipId, enabled, origin.nativeValue) as Boolean

    override fun setClipAnchor(trackIndex: Int, clipId: Int, anchor: TimeReference, origin: MutationOrigin): Boolean =
        withWasmMem(Off.TIMEREF_SIZE) { p ->
            withJsCString(anchor.referenceId) { refPtr ->
                jsSetI32(p + Off.TIMEREF_TYPE, anchor.type.nativeValue)
                jsSetPtr(p + Off.TIMEREF_REFERENCE_ID, refPtr)
                jsSetF64(p + Off.TIMEREF_OFFSET, anchor.offset)
                jsMod._uapmd_commands_set_clip_anchor(handle, trackIndex, clipId, p, origin.nativeValue) as Boolean
            }
        }

    override fun setClipGain(trackIndex: Int, clipId: Int, gain: Double, origin: MutationOrigin): Boolean =
        jsMod._uapmd_commands_set_clip_gain(handle, trackIndex, clipId, gain, origin.nativeValue) as Boolean

    override fun setClipMuted(trackIndex: Int, clipId: Int, muted: Boolean, origin: MutationOrigin): Boolean =
        jsMod._uapmd_commands_set_clip_muted(handle, trackIndex, clipId, muted, origin.nativeValue) as Boolean

    override fun resizeClip(trackIndex: Int, clipId: Int, newDurationSamples: Long, origin: MutationOrigin): Boolean =
        jsMod._uapmd_commands_resize_clip(handle, trackIndex, clipId, bigInt(newDurationSamples), origin.nativeValue) as Boolean

    override fun setClipName(trackIndex: Int, clipId: Int, name: String, origin: MutationOrigin): Boolean =
        withJsCString(name) { p -> jsMod._uapmd_commands_set_clip_name(handle, trackIndex, clipId, p, origin.nativeValue) as Boolean }

    override fun setClipFilepath(trackIndex: Int, clipId: Int, filepath: String, origin: MutationOrigin): Boolean =
        withJsCString(filepath) { p -> jsMod._uapmd_commands_set_clip_filepath(handle, trackIndex, clipId, p, origin.nativeValue) as Boolean }

    override fun setClipNeedsFileSave(trackIndex: Int, clipId: Int, needsSave: Boolean, origin: MutationOrigin): Boolean =
        jsMod._uapmd_commands_set_clip_needs_file_save(handle, trackIndex, clipId, needsSave, origin.nativeValue) as Boolean

    override fun setClipMarkers(trackIndex: Int, clipId: Int, markers: List<ClipMarkerData>, origin: MutationOrigin): Boolean =
        withJsMarkers(markers) { ptr, count ->
            jsMod._uapmd_commands_set_clip_markers(handle, trackIndex, clipId, ptr, count, origin.nativeValue) as Boolean
        }

    override fun setClipAudioWarps(trackIndex: Int, clipId: Int, warps: List<AudioWarpPointData>, origin: MutationOrigin): Boolean =
        withJsWarps(warps) { ptr, count ->
            jsMod._uapmd_commands_set_clip_audio_warps(handle, trackIndex, clipId, ptr, count, origin.nativeValue) as Boolean
        }

    override fun setTrackGain(trackIndex: Int, gain: Double, origin: MutationOrigin): Boolean =
        jsMod._uapmd_commands_set_track_gain(handle, trackIndex, gain, origin.nativeValue) as Boolean

    override fun setTrackMuted(trackIndex: Int, muted: Boolean, origin: MutationOrigin): Boolean =
        jsMod._uapmd_commands_set_track_muted(handle, trackIndex, muted, origin.nativeValue) as Boolean

    override fun setTrackSolo(trackIndex: Int, solo: Boolean, origin: MutationOrigin): Boolean =
        jsMod._uapmd_commands_set_track_solo(handle, trackIndex, solo, origin.nativeValue) as Boolean

    override fun setTrackBypassed(trackIndex: Int, bypassed: Boolean, origin: MutationOrigin): Boolean =
        jsMod._uapmd_commands_set_track_bypassed(handle, trackIndex, bypassed, origin.nativeValue) as Boolean

    override fun setTrackFreezePolicyEnabled(trackIndex: Int, enabled: Boolean, origin: MutationOrigin): Boolean =
        jsMod._uapmd_commands_set_track_freeze_policy_enabled(handle, trackIndex, enabled, origin.nativeValue) as Boolean

    override fun setPluginBypassed(instanceId: Int, bypassed: Boolean, origin: MutationOrigin): Boolean =
        jsMod._uapmd_commands_set_plugin_bypassed(handle, instanceId, bypassed, origin.nativeValue) as Boolean

    override fun setPluginParameterValue(instanceId: Int, parameterIndex: Int, value: Double, origin: MutationOrigin): Boolean =
        jsMod._uapmd_commands_set_plugin_parameter_value(handle, instanceId, parameterIndex, value, origin.nativeValue) as Boolean

    override fun setPluginPerNoteControllerValue(
        instanceId: Int, contextType: Int, note: Int, channel: Int, group: Int, extra: Int,
        parameterIndex: Int, value: Double, origin: MutationOrigin
    ): Boolean = jsMod._uapmd_commands_set_plugin_per_note_controller_value(
        handle, instanceId, contextType, note, channel, group, extra, parameterIndex, value, origin.nativeValue
    ) as Boolean

    override fun setPluginGroup(instanceId: Int, group: UByte, origin: MutationOrigin): Boolean =
        jsMod._uapmd_commands_set_plugin_group(handle, instanceId, group.toInt(), origin.nativeValue) as Boolean

    override fun setMasterTrackMarkers(markers: List<ClipMarkerData>, origin: MutationOrigin): Boolean =
        withJsMarkers(markers) { ptr, count ->
            jsMod._uapmd_commands_set_master_track_markers(handle, ptr, count, origin.nativeValue) as Boolean
        }
}

// ─── JsProjectAddressBook ────────────────────────────────────────────────────

class JsProjectAddressBook internal constructor(private val handle: Int) : ProjectAddressBook {
    override fun timelineTrack(trackReferenceId: String): TimelineTrack? =
        withJsCString(trackReferenceId) { p ->
            (jsMod._uapmd_addresses_timeline_track(handle, p) as Int).takeIf { it != 0 }?.let { JsTimelineTrack(it) }
        }

    override fun sequencerTrack(trackReferenceId: String): SequencerTrack? =
        withJsCString(trackReferenceId) { p ->
            (jsMod._uapmd_addresses_sequencer_track(handle, p) as Int).takeIf { it != 0 }?.let { JsSequencerTrack(it) }
        }

    override fun trackIndex(trackReferenceId: String): Int =
        withJsCString(trackReferenceId) { p -> jsMod._uapmd_addresses_track_index(handle, p) as Int }

    override fun clipId(address: ClipAddress): Int =
        withWasmMem(Off.ADDRESS_SIZE) { s ->
            withJsTwoCStrings(address.trackReferenceId, address.clipReferenceId) { a, b ->
                jsSetPtr(s, a)
                jsSetPtr(s + 4, b)
                jsMod._uapmd_addresses_clip_id(handle, s) as Int
            }
        }

    override fun pluginInstanceId(address: PluginAddress): Int =
        withWasmMem(Off.ADDRESS_SIZE) { s ->
            withJsTwoCStrings(address.trackReferenceId, address.nodeId) { a, b ->
                jsSetPtr(s, a)
                jsSetPtr(s + 4, b)
                jsMod._uapmd_addresses_plugin_instance_id(handle, s) as Int
            }
        }

    override fun trackReferenceId(trackIndex: Int): String? {
        val p = jsMod._uapmd_addresses_track_reference_id(handle, trackIndex) as Int
        return if (p != 0) jsMod.UTF8ToString(p) as String else null
    }

    override fun clipAddress(trackIndex: Int, clipId: Int): ClipAddress? =
        withWasmMem(Off.ADDRESS_SIZE) { out ->
            if (!(jsMod._uapmd_addresses_clip_address(handle, trackIndex, clipId, out) as Boolean)) null
            else ClipAddress(jsGetStr(out), jsGetStr(out + 4))
        }

    override fun pluginAddress(instanceId: Int): PluginAddress? =
        withWasmMem(Off.ADDRESS_SIZE) { out ->
            if (!(jsMod._uapmd_addresses_plugin_address(handle, instanceId, out) as Boolean)) null
            else PluginAddress(jsGetStr(out), jsGetStr(out + 4))
        }
}

// ─── Fragments ───────────────────────────────────────────────────────────────

class JsClipFragment internal constructor(
    internal val handle: Int,
    /** Fragments borrowed from a track fragment are released with their owner. */
    private val owned: Boolean
) : ClipFragment {

    override val isMidi: Boolean get() = jsMod._uapmd_clip_fragment_is_midi(handle) as Boolean

    override val clip: ClipData
        get() = withWasmMem(Off.CLIP_SIZE) { p ->
            jsMod._uapmd_clip_fragment_get_clip(handle, p)
            ClipData(
                clipId = jsGetI32(p + Off.CLIP_ID),
                positionSamples = jsGetI64(p + Off.CLIP_POSITION),
                positionLegacyBeats = jsGetF64(p + Off.CLIP_POSITION + 8),
                durationSamples = jsGetI64(p + Off.CLIP_DURATION),
                gain = jsGetF64(p + Off.CLIP_GAIN),
                muted = jsGetBool(p + Off.CLIP_MUTED),
                name = jsGetStr(p + Off.CLIP_NAME),
                filepath = jsGetStr(p + Off.CLIP_FILEPATH),
                clipType = ClipType.fromNative(jsGetI32(p + Off.CLIP_TYPE))
            )
        }

    override val umpEvents: UIntArray
        get() {
            val n = jsMod._uapmd_clip_fragment_get_ump_events(handle, 0, 0) as Int
            if (n <= 0) return UIntArray(0)
            return withWasmMem(n * 4) { p ->
                jsMod._uapmd_clip_fragment_get_ump_events(handle, p, n)
                UIntArray(n) { jsGetI32(p + it * 4).toUInt() }
            }
        }

    override val umpTickTimestamps: LongArray
        get() {
            val n = jsMod._uapmd_clip_fragment_get_ump_tick_timestamps(handle, 0, 0) as Int
            if (n <= 0) return LongArray(0)
            return withWasmMem(n * 8) { p ->
                jsMod._uapmd_clip_fragment_get_ump_tick_timestamps(handle, p, n)
                LongArray(n) { jsGetI64(p + it * 8) }
            }
        }

    override val extensionState: Map<String, ByteArray>
        get() {
            val count = jsMod._uapmd_clip_fragment_extension_state_count(handle) as Int
            if (count == 0) return emptyMap()
            return (0 until count).associate { i ->
                val key = readJsStringIndexed(handle, i) { h, idx, buf, size ->
                    jsMod._uapmd_clip_fragment_extension_state_key(h, idx, buf, size) as Int
                }
                val size = jsMod._uapmd_clip_fragment_extension_state_data(handle, i, 0, 0) as Int
                val data = if (size <= 0) ByteArray(0) else withWasmMem(size) { p ->
                    jsMod._uapmd_clip_fragment_extension_state_data(handle, i, p, size)
                    ByteArray(size) { (jsMod.getValue(p + it, "i8") as Int).toByte() }
                }
                key to data
            }
        }

    override fun close() {
        if (owned) jsMod._uapmd_clip_fragment_destroy(handle)
    }
}

class JsTrackFragment internal constructor(internal val handle: Int) : TrackFragment {
    override val referenceId: String
        get() = readJsString(handle) { h, buf, size -> jsMod._uapmd_track_fragment_reference_id(h, buf, size) as Int }

    override val volume: Double get() = jsMod._uapmd_track_fragment_volume(handle) as Double
    override val muted: Boolean get() = jsMod._uapmd_track_fragment_muted(handle) as Boolean
    override val solo: Boolean get() = jsMod._uapmd_track_fragment_solo(handle) as Boolean

    override val graphType: String
        get() = readJsString(handle) { h, buf, size -> jsMod._uapmd_track_fragment_graph_type(h, buf, size) as Int }

    override val graphBytes: ByteArray
        get() {
            val size = jsMod._uapmd_track_fragment_graph_bytes(handle, 0, 0) as Int
            if (size <= 0) return ByteArray(0)
            return withWasmMem(size) { p ->
                jsMod._uapmd_track_fragment_graph_bytes(handle, p, size)
                ByteArray(size) { (jsMod.getValue(p + it, "i8") as Int).toByte() }
            }
        }

    override val clips: List<ClipFragment>
        get() = (0 until (jsMod._uapmd_track_fragment_clip_count(handle) as Int)).mapNotNull { i ->
            (jsMod._uapmd_track_fragment_get_clip(handle, i) as Int).takeIf { it != 0 }?.let { JsClipFragment(it, owned = false) }
        }

    override val plugins: List<TrackPluginFragment>
        get() = withWasmMem(Off.PLUGIN_SIZE) { out ->
            (0 until (jsMod._uapmd_track_fragment_plugin_count(handle) as Int)).mapNotNull { i ->
                if (!(jsMod._uapmd_track_fragment_get_plugin(handle, i, out) as Boolean)) return@mapNotNull null
                val stateSize = jsGetI32(out + Off.PLUGIN_STATE_SIZE)
                val statePtr = jsGetPtr(out + Off.PLUGIN_STATE)
                TrackPluginFragment(
                    nodeId = jsGetStr(out + Off.PLUGIN_NODE_ID),
                    pluginId = jsGetStr(out + Off.PLUGIN_PLUGIN_ID),
                    format = jsGetStr(out + Off.PLUGIN_FORMAT),
                    displayName = jsGetStr(out + Off.PLUGIN_DISPLAY_NAME),
                    groupIndex = jsGetI32(out + Off.PLUGIN_GROUP_INDEX),
                    state = if (stateSize <= 0 || statePtr == 0) ByteArray(0)
                            else ByteArray(stateSize) { (jsMod.getValue(statePtr + it, "i8") as Int).toByte() }
                )
            }
        }

    override fun close() { jsMod._uapmd_track_fragment_destroy(handle) }
}

// ─── Timeline history implementation ─────────────────────────────────────────

internal class JsTimelineHistory(private val handle: Int) {
    val undoEngine: UndoEngine get() = JsUndoEngine(jsMod._uapmd_tl_undo_engine(handle) as Int)
    val commands: ProjectCommands get() = JsProjectCommands(jsMod._uapmd_tl_commands(handle) as Int)
    val addresses: ProjectAddressBook get() = JsProjectAddressBook(jsMod._uapmd_tl_addresses(handle) as Int)

    fun <T> documentTransaction(block: () -> T): T {
        jsMod._uapmd_tl_begin_document_transaction(handle)
        try {
            return block()
        } finally {
            jsMod._uapmd_tl_end_document_transaction(handle)
        }
    }

    fun removeClip(trackIndex: Int, clipId: Int, origin: MutationOrigin): Boolean =
        jsMod._uapmd_tl_remove_clip_with_origin(handle, trackIndex, clipId, origin.nativeValue) as Boolean

    fun clearClipsFromTrack(trackIndex: Int, origin: MutationOrigin): Boolean =
        jsMod._uapmd_tl_clear_clips_from_track(handle, trackIndex, origin.nativeValue) as Boolean

    fun isClipEnabled(trackIndex: Int, clipId: Int): Boolean =
        jsMod._uapmd_tl_clip_enabled(handle, trackIndex, clipId) as Boolean

    fun replaceMidiClipContent(
        trackIndex: Int, clipId: Int, umpEvents: UIntArray, tickTimestamps: LongArray, origin: MutationOrigin
    ): Boolean = withWasmMem(maxOf(1, umpEvents.size * 4)) { eventsPtr ->
        withWasmMem(maxOf(1, tickTimestamps.size * 8)) { ticksPtr ->
            umpEvents.forEachIndexed { i, v -> jsSetI32(eventsPtr + i * 4, v.toInt()) }
            tickTimestamps.forEachIndexed { i, v ->
                jsSetI32(ticksPtr + i * 8, v.toInt())
                jsSetI32(ticksPtr + i * 8 + 4, (v ushr 32).toInt())
            }
            jsMod._uapmd_tl_replace_midi_clip_content(
                handle, trackIndex, clipId,
                if (umpEvents.isEmpty()) 0 else eventsPtr, umpEvents.size,
                if (tickTimestamps.isEmpty()) 0 else ticksPtr, tickTimestamps.size,
                origin.nativeValue
            ) as Boolean
        }
    }

    fun replaceAudioClipContent(
        trackIndex: Int, clipId: Int, filepath: String,
        markers: List<ClipMarkerData>, warps: List<AudioWarpPointData>,
        masterMarkers: List<ClipMarkerData>, origin: MutationOrigin
    ): Boolean = withJsCString(filepath) { fp ->
        withJsMarkers(markers) { mPtr, mCount ->
            withJsWarps(warps) { wPtr, wCount ->
                withJsMarkers(masterMarkers) { mmPtr, mmCount ->
                    jsMod._uapmd_tl_replace_audio_clip_content(
                        handle, trackIndex, clipId, fp,
                        mPtr, mCount, wPtr, wCount, mmPtr, mmCount, origin.nativeValue
                    ) as Boolean
                }
            }
        }
    }

    fun captureClipFragment(trackIndex: Int, clipId: Int): ClipFragment? =
        (jsMod._uapmd_tl_capture_clip_fragment(handle, trackIndex, clipId) as Int)
            .takeIf { it != 0 }?.let { JsClipFragment(it, owned = true) }

    fun attachClipFragment(trackIndex: Int, fragment: ClipFragment, idPolicy: ObjectIdPolicy): ClipAddResult =
        withWasmMem(Off.CLIP_ADD_SIZE) { out ->
            jsMod._uapmd_tl_attach_clip_fragment(
                out, handle, trackIndex, (fragment as JsClipFragment).handle, idPolicy.nativeValue
            )
            jsDecodeClipAddResult(out)
        }

    fun captureTrackFragment(trackIndex: Int, callback: (TrackFragment?, String?) -> Unit) {
        jsMod._uapmd_tl_capture_track_fragment(handle, trackIndex, 0, makeJsTrackFragment(callback))
    }

    fun attachTrackFragment(fragment: TrackFragment, options: TrackAttachOptions, callback: (Int, String?) -> Unit) {
        withWasmMem(Off.ATTACH_SIZE) { p ->
            jsSetI32(p + Off.ATTACH_ID_POLICY, options.idPolicy.nativeValue)
            jsSetI32(p + Off.ATTACH_INSERTION_INDEX, options.insertionIndex)
            jsSetI8(p + Off.ATTACH_INCLUDE_PLUGINS, if (options.includePlugins) 1 else 0)
            jsSetI8(p + Off.ATTACH_INCLUDE_PLUGIN_STATE, if (options.includePluginState) 1 else 0)
            jsSetI8(p + Off.ATTACH_INCLUDE_CLIPS, if (options.includeClips) 1 else 0)
            jsMod._uapmd_tl_attach_track_fragment(
                handle, (fragment as JsTrackFragment).handle, p, 0, makeJsTrackMutation(callback)
            )
        }
    }

    fun addEmptyTrack(origin: MutationOrigin, callback: (Int, String?) -> Unit) {
        jsMod._uapmd_tl_add_empty_track(handle, origin.nativeValue, 0, makeJsTrackMutation(callback))
    }

    fun removeTrack(trackIndex: Int, origin: MutationOrigin, callback: (Int, String?) -> Unit) {
        jsMod._uapmd_tl_remove_track(handle, trackIndex, origin.nativeValue, 0, makeJsTrackMutation(callback))
    }

    fun recordTrackAddition(trackIndex: Int, origin: MutationOrigin, callback: (Int, String?) -> Unit) {
        jsMod._uapmd_tl_record_track_addition(handle, trackIndex, origin.nativeValue, 0, makeJsTrackMutation(callback))
    }

    fun setPluginState(instanceId: Int, state: ByteArray, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) {
        withWasmMem(maxOf(1, state.size)) { p ->
            state.forEachIndexed { i, b -> jsSetI8(p + i, b.toInt()) }
            jsMod._uapmd_tl_set_plugin_state(
                handle, instanceId, if (state.isEmpty()) 0 else p, state.size, origin.nativeValue,
                0, completion?.let { makeJsUndoCompletion(it) } ?: 0
            )
        }
    }

    fun loadPluginPreset(instanceId: Int, presetIndex: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) {
        jsMod._uapmd_tl_load_plugin_preset(
            handle, instanceId, presetIndex, origin.nativeValue, 0,
            completion?.let { makeJsUndoCompletion(it) } ?: 0
        )
    }

    fun recordPluginInstanceAddition(instanceId: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) {
        jsMod._uapmd_tl_record_plugin_instance_addition(
            handle, instanceId, origin.nativeValue, 0, completion?.let { makeJsUndoCompletion(it) } ?: 0
        )
    }

    fun removePluginInstance(instanceId: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) {
        jsMod._uapmd_tl_remove_plugin_instance(
            handle, instanceId, origin.nativeValue, 0, completion?.let { makeJsUndoCompletion(it) } ?: 0
        )
    }

    val hasPendingPluginMutations: Boolean
        get() = jsMod._uapmd_tl_has_pending_plugin_mutations(handle) as Boolean
}

// ─── Engine dirty state and master markers ───────────────────────────────────

internal class JsEngineHistory(private val handle: Int) {
    val isProjectDirty: Boolean get() = jsMod._uapmd_engine_is_project_dirty(handle) as Boolean
    fun isTrackDirty(trackIndex: Int): Boolean = jsMod._uapmd_engine_is_track_dirty(handle, trackIndex) as Boolean
    fun markTrackDirty(trackIndex: Int, dirty: Boolean) { jsMod._uapmd_engine_mark_track_dirty(handle, trackIndex, dirty) }
    fun clearTrackDirtyState() { jsMod._uapmd_engine_clear_track_dirty_state(handle) }

    var masterTrackMarkers: List<ClipMarkerData>
        get() {
            val count = jsMod._uapmd_engine_master_marker_count(handle) as Int
            if (count == 0) return emptyList()
            return withWasmMem(Off.MARKER_SIZE) { out ->
                (0 until count).mapNotNull { i ->
                    if (!(jsMod._uapmd_engine_get_master_marker(handle, i, out) as Boolean)) return@mapNotNull null
                    ClipMarkerData(
                        markerId = jsGetStr(out + Off.MARKER_ID),
                        clipPositionOffset = jsGetF64(out + Off.MARKER_OFFSET),
                        referenceType = WarpReferenceType.fromNative(jsGetI32(out + Off.MARKER_REF_TYPE)),
                        referenceClipId = jsGetStr(out + Off.MARKER_REF_CLIP_ID),
                        referenceMarkerId = jsGetStr(out + Off.MARKER_REF_MARKER_ID),
                        name = jsGetStr(out + Off.MARKER_NAME)
                    )
                }
            }
        }
        set(value) {
            withJsMarkers(value) { ptr, count -> jsMod._uapmd_engine_set_master_markers(handle, ptr, count) }
        }

    fun registerAddinExtensionPoints(manager: AddinManager) {
        jsMod._uapmd_engine_register_addin_extension_points(handle, (manager as JsAddinManager).handle)
    }
}

// ─── Addin offsets shared with JsAddin.kt ────────────────────────────────────

internal object JsAddinOffsets {
    const val PACKAGE_ID = Off.ADDIN_PACKAGE_ID
    const val ADDIN_ID = Off.ADDIN_ADDIN_ID
    const val NAME = Off.ADDIN_NAME
    const val PATH = Off.ADDIN_PATH
    const val LIBRARY_PATH = Off.ADDIN_LIBRARY_PATH
    const val BUILT_IN = Off.ADDIN_BUILT_IN
    const val STATE = Off.ADDIN_STATE
    const val MESSAGE = Off.ADDIN_MESSAGE
    const val SIZE = Off.ADDIN_SIZE
}
