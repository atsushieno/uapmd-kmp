package dev.atsushieno.uapmd

import com.sun.jna.Pointer
import com.sun.jna.Structure
import dev.atsushieno.uapmd.jna.UapmdAudioWarpPoint
import dev.atsushieno.uapmd.jna.UapmdClipAddress
import dev.atsushieno.uapmd.jna.UapmdClipData
import dev.atsushieno.uapmd.jna.UapmdClipMarker
import dev.atsushieno.uapmd.jna.UapmdPluginAddress
import dev.atsushieno.uapmd.jna.UapmdTimeReference
import dev.atsushieno.uapmd.jna.UapmdTrackAttachOptions
import dev.atsushieno.uapmd.jna.UapmdTrackPluginFragment
import dev.atsushieno.uapmd.jna.UapmdUndoResult
import dev.atsushieno.uapmd.jna.UapmdUndoState
import dev.atsushieno.uapmd.jna.UndoCompletionCb
import dev.atsushieno.uapmd.jna.TrackFragmentCb
import dev.atsushieno.uapmd.jna.TrackMutationCb

// ─── Conversion helpers ──────────────────────────────────────────────────────

internal fun UapmdUndoResult.toKotlin(): UndoResult =
    UndoResult(UndoStatus.fromNative(status), error)

internal fun UapmdUndoState.toKotlin(): UndoState = UndoState(
    busy = busy != 0.toByte(),
    compoundOpen = compound_open != 0.toByte(),
    gestureOpen = gesture_open != 0.toByte(),
    canUndo = can_undo != 0.toByte(),
    canRedo = can_redo != 0.toByte(),
    dirty = dirty != 0.toByte(),
    compoundDescription = compound_description ?: "",
    undoDescription = undo_description ?: "",
    redoDescription = redo_description ?: "",
    historySizeInBytes = history_size_in_bytes,
    maximumHistorySizeInBytes = maximum_history_size_in_bytes,
    currentStateId = current_state_id,
    savedStateId = saved_state_id
)

internal fun TimeReference.toJvmByVal(): UapmdTimeReference.ByVal =
    UapmdTimeReference.ByVal().also {
        it.type = type.nativeValue
        it.reference_id = referenceId
        it.offset = offset
    }

internal fun TrackAttachOptions.toJvmByVal(): UapmdTrackAttachOptions.ByVal =
    UapmdTrackAttachOptions.ByVal().also {
        it.id_policy = idPolicy.nativeValue
        it.insertion_index = insertionIndex
        it.include_plugins = if (includePlugins) 1 else 0
        it.include_plugin_state = if (includePluginState) 1 else 0
        it.include_clips = if (includeClips) 1 else 0
    }

/**
 * Lays a list of markers out as one contiguous native array. Returns null for
 * an empty list, which the C side reads as "no markers".
 */
internal fun List<ClipMarkerData>.toJvmArray(): UapmdClipMarker? {
    if (isEmpty()) return null
    @Suppress("UNCHECKED_CAST")
    val arr = UapmdClipMarker().toArray(size) as Array<UapmdClipMarker>
    forEachIndexed { i, m ->
        arr[i].marker_id = m.markerId
        arr[i].clip_position_offset = m.clipPositionOffset
        arr[i].reference_type = m.referenceType.nativeValue
        arr[i].reference_clip_id = m.referenceClipId
        arr[i].reference_marker_id = m.referenceMarkerId
        arr[i].name = m.name
        arr[i].write()
    }
    return arr[0]
}

@JvmName("warpsToJvmArray")
internal fun List<AudioWarpPointData>.toJvmArray(): UapmdAudioWarpPoint? {
    if (isEmpty()) return null
    @Suppress("UNCHECKED_CAST")
    val arr = UapmdAudioWarpPoint().toArray(size) as Array<UapmdAudioWarpPoint>
    forEachIndexed { i, w ->
        arr[i].clip_position_offset = w.clipPositionOffset
        arr[i].speed_ratio = w.speedRatio
        arr[i].reference_type = w.referenceType.nativeValue
        arr[i].reference_clip_id = w.referenceClipId
        arr[i].reference_marker_id = w.referenceMarkerId
        arr[i].write()
    }
    return arr[0]
}

internal fun UapmdClipMarker.toKotlin(): ClipMarkerData = ClipMarkerData(
    markerId = marker_id ?: "",
    clipPositionOffset = clip_position_offset,
    referenceType = WarpReferenceType.fromNative(reference_type),
    referenceClipId = reference_clip_id ?: "",
    referenceMarkerId = reference_marker_id ?: "",
    name = name ?: ""
)

/**
 * JNA keeps a strong reference to a [com.sun.jna.Callback] only while the Java
 * side does. Each asynchronous history call therefore parks its callback here
 * until the native side has invoked it exactly once.
 */
private val pendingCallbacks = java.util.Collections.synchronizedSet(mutableSetOf<Any>())

private fun completionCallback(completion: ((UndoResult) -> Unit)?): UndoCompletionCb? {
    if (completion == null) return null
    lateinit var cb: UndoCompletionCb
    cb = object : UndoCompletionCb {
        override fun invoke(result: UapmdUndoResult.ByVal, userData: Pointer?) {
            try {
                completion(result.toKotlin())
            } finally {
                pendingCallbacks.remove(cb)
            }
        }
    }
    pendingCallbacks.add(cb)
    return cb
}

private fun trackMutationCallback(callback: (Int, String?) -> Unit): TrackMutationCb {
    lateinit var cb: TrackMutationCb
    cb = object : TrackMutationCb {
        override fun invoke(trackIndex: Int, error: String?, userData: Pointer?) {
            try {
                callback(trackIndex, error)
            } finally {
                pendingCallbacks.remove(cb)
            }
        }
    }
    pendingCallbacks.add(cb)
    return cb
}

// ─── JvmUndoEngine ───────────────────────────────────────────────────────────

class JvmUndoEngine internal constructor(private val handle: Pointer) : UndoEngine {
    override val state: UndoState
        get() = UapmdUndoState().also { lib.uapmd_undo_engine_get_state(handle, it) }.toKotlin()

    override fun undo(completion: ((UndoResult) -> Unit)?) =
        lib.uapmd_undo_engine_undo(handle, null, completionCallback(completion))

    override fun redo(completion: ((UndoResult) -> Unit)?) =
        lib.uapmd_undo_engine_redo(handle, null, completionCallback(completion))

    override fun beginCompound(description: String, origin: MutationOrigin): UndoResult =
        lib.uapmd_undo_engine_begin_compound(handle, description, origin.nativeValue).toKotlin()

    override fun endCompound(completion: ((UndoResult) -> Unit)?) =
        lib.uapmd_undo_engine_end_compound(handle, null, completionCallback(completion))

    override fun cancelCompound(completion: ((UndoResult) -> Unit)?) =
        lib.uapmd_undo_engine_cancel_compound(handle, null, completionCallback(completion))

    override fun beginGesture(description: String, origin: MutationOrigin): UndoResult =
        lib.uapmd_undo_engine_begin_gesture(handle, description, origin.nativeValue).toKotlin()

    override fun endGesture(completion: ((UndoResult) -> Unit)?) =
        lib.uapmd_undo_engine_end_gesture(handle, null, completionCallback(completion))

    override fun cancelGesture(completion: ((UndoResult) -> Unit)?) =
        lib.uapmd_undo_engine_cancel_gesture(handle, null, completionCallback(completion))

    override fun clear(markCurrentStateSaved: Boolean): Boolean =
        lib.uapmd_undo_engine_clear(handle, markCurrentStateSaved)

    override fun markSaved(): Boolean = lib.uapmd_undo_engine_mark_saved(handle)
    override fun markStateSaved(stateId: Long): Boolean = lib.uapmd_undo_engine_mark_state_saved(handle, stateId)
    override fun setMaximumHistorySizeInBytes(bytes: Long): Boolean =
        lib.uapmd_undo_engine_set_maximum_history_size(handle, bytes)

    override fun shutdown() = lib.uapmd_undo_engine_shutdown(handle)
}

// ─── JvmCommandManager ───────────────────────────────────────────────────────

class JvmCommandManager internal constructor(private val handle: Pointer) : CommandManager {
    override val state: UndoState
        get() = UapmdUndoState().also { lib.uapmd_command_manager_get_state(handle, it) }.toKotlin()

    override val history: UndoEngine
        get() = JvmUndoEngine(lib.uapmd_command_manager_history(handle) ?: error("no history engine"))

    override fun undo(completion: ((UndoResult) -> Unit)?) =
        lib.uapmd_command_manager_undo(handle, null, completionCallback(completion))

    override fun redo(completion: ((UndoResult) -> Unit)?) =
        lib.uapmd_command_manager_redo(handle, null, completionCallback(completion))

    override fun beginStep(description: String, origin: MutationOrigin): UndoResult =
        lib.uapmd_command_manager_begin_step(handle, description, origin.nativeValue).toKotlin()

    override fun endStep(completion: ((UndoResult) -> Unit)?) =
        lib.uapmd_command_manager_end_step(handle, null, completionCallback(completion))

    override fun cancelStep(completion: ((UndoResult) -> Unit)?) =
        lib.uapmd_command_manager_cancel_step(handle, null, completionCallback(completion))

    override fun beginGesture(description: String, origin: MutationOrigin): UndoResult =
        lib.uapmd_command_manager_begin_gesture(handle, description, origin.nativeValue).toKotlin()

    override fun endGesture(completion: ((UndoResult) -> Unit)?) =
        lib.uapmd_command_manager_end_gesture(handle, null, completionCallback(completion))

    override fun cancelGesture(completion: ((UndoResult) -> Unit)?) =
        lib.uapmd_command_manager_cancel_gesture(handle, null, completionCallback(completion))

    override fun shutdown() = lib.uapmd_command_manager_shutdown(handle)
}

// ─── JvmProjectCommands ──────────────────────────────────────────────────────

class JvmProjectCommands internal constructor(private val handle: Pointer) : ProjectCommands {
    override val history: CommandManager
        get() = JvmCommandManager(lib.uapmd_commands_history(handle) ?: error("no command manager"))

    override fun setClipEnabled(trackIndex: Int, clipId: Int, enabled: Boolean, origin: MutationOrigin) =
        lib.uapmd_commands_set_clip_enabled(handle, trackIndex, clipId, enabled, origin.nativeValue)

    override fun setClipAnchor(trackIndex: Int, clipId: Int, anchor: TimeReference, origin: MutationOrigin) =
        lib.uapmd_commands_set_clip_anchor(handle, trackIndex, clipId, anchor.toJvmByVal(), origin.nativeValue)

    override fun setClipGain(trackIndex: Int, clipId: Int, gain: Double, origin: MutationOrigin) =
        lib.uapmd_commands_set_clip_gain(handle, trackIndex, clipId, gain, origin.nativeValue)

    override fun setClipMuted(trackIndex: Int, clipId: Int, muted: Boolean, origin: MutationOrigin) =
        lib.uapmd_commands_set_clip_muted(handle, trackIndex, clipId, muted, origin.nativeValue)

    override fun resizeClip(trackIndex: Int, clipId: Int, newDurationSamples: Long, origin: MutationOrigin) =
        lib.uapmd_commands_resize_clip(handle, trackIndex, clipId, newDurationSamples, origin.nativeValue)

    override fun setClipName(trackIndex: Int, clipId: Int, name: String, origin: MutationOrigin) =
        lib.uapmd_commands_set_clip_name(handle, trackIndex, clipId, name, origin.nativeValue)

    override fun setClipFilepath(trackIndex: Int, clipId: Int, filepath: String, origin: MutationOrigin) =
        lib.uapmd_commands_set_clip_filepath(handle, trackIndex, clipId, filepath, origin.nativeValue)

    override fun setClipNeedsFileSave(trackIndex: Int, clipId: Int, needsSave: Boolean, origin: MutationOrigin) =
        lib.uapmd_commands_set_clip_needs_file_save(handle, trackIndex, clipId, needsSave, origin.nativeValue)

    override fun setClipMarkers(trackIndex: Int, clipId: Int, markers: List<ClipMarkerData>, origin: MutationOrigin) =
        lib.uapmd_commands_set_clip_markers(handle, trackIndex, clipId, markers.toJvmArray(), markers.size, origin.nativeValue)

    override fun setClipAudioWarps(trackIndex: Int, clipId: Int, warps: List<AudioWarpPointData>, origin: MutationOrigin) =
        lib.uapmd_commands_set_clip_audio_warps(handle, trackIndex, clipId, warps.toJvmArray(), warps.size, origin.nativeValue)

    override fun setTrackGain(trackIndex: Int, gain: Double, origin: MutationOrigin) =
        lib.uapmd_commands_set_track_gain(handle, trackIndex, gain, origin.nativeValue)

    override fun setTrackMuted(trackIndex: Int, muted: Boolean, origin: MutationOrigin) =
        lib.uapmd_commands_set_track_muted(handle, trackIndex, muted, origin.nativeValue)

    override fun setTrackSolo(trackIndex: Int, solo: Boolean, origin: MutationOrigin) =
        lib.uapmd_commands_set_track_solo(handle, trackIndex, solo, origin.nativeValue)

    override fun setTrackBypassed(trackIndex: Int, bypassed: Boolean, origin: MutationOrigin) =
        lib.uapmd_commands_set_track_bypassed(handle, trackIndex, bypassed, origin.nativeValue)

    override fun setTrackFreezePolicyEnabled(trackIndex: Int, enabled: Boolean, origin: MutationOrigin) =
        lib.uapmd_commands_set_track_freeze_policy_enabled(handle, trackIndex, enabled, origin.nativeValue)

    override fun setPluginBypassed(instanceId: Int, bypassed: Boolean, origin: MutationOrigin) =
        lib.uapmd_commands_set_plugin_bypassed(handle, instanceId, bypassed, origin.nativeValue)

    override fun setPluginParameterValue(instanceId: Int, parameterIndex: Int, value: Double, origin: MutationOrigin) =
        lib.uapmd_commands_set_plugin_parameter_value(handle, instanceId, parameterIndex, value, origin.nativeValue)

    override fun setPluginPerNoteControllerValue(
        instanceId: Int, contextType: Int, note: Int, channel: Int, group: Int, extra: Int,
        parameterIndex: Int, value: Double, origin: MutationOrigin
    ) = lib.uapmd_commands_set_plugin_per_note_controller_value(
        handle, instanceId, contextType, note, channel, group, extra, parameterIndex, value, origin.nativeValue
    )

    override fun setPluginGroup(instanceId: Int, group: UByte, origin: MutationOrigin) =
        lib.uapmd_commands_set_plugin_group(handle, instanceId, group.toByte(), origin.nativeValue)

    override fun setMasterTrackMarkers(markers: List<ClipMarkerData>, origin: MutationOrigin) =
        lib.uapmd_commands_set_master_track_markers(handle, markers.toJvmArray(), markers.size, origin.nativeValue)
}

// ─── JvmProjectAddressBook ───────────────────────────────────────────────────

class JvmProjectAddressBook internal constructor(private val handle: Pointer) : ProjectAddressBook {
    override fun timelineTrack(trackReferenceId: String): TimelineTrack? =
        lib.uapmd_addresses_timeline_track(handle, trackReferenceId)?.let { JvmTimelineTrack(it) }

    override fun sequencerTrack(trackReferenceId: String): SequencerTrack? =
        lib.uapmd_addresses_sequencer_track(handle, trackReferenceId)?.let { JvmSequencerTrack(it) }

    override fun trackIndex(trackReferenceId: String): Int =
        lib.uapmd_addresses_track_index(handle, trackReferenceId)

    override fun clipId(address: ClipAddress): Int =
        lib.uapmd_addresses_clip_id(handle, UapmdClipAddress.ByVal().also {
            it.track_reference_id = address.trackReferenceId
            it.clip_reference_id = address.clipReferenceId
        })

    override fun pluginInstanceId(address: PluginAddress): Int =
        lib.uapmd_addresses_plugin_instance_id(handle, UapmdPluginAddress.ByVal().also {
            it.track_reference_id = address.trackReferenceId
            it.node_id = address.nodeId
        })

    override fun trackReferenceId(trackIndex: Int): String? =
        lib.uapmd_addresses_track_reference_id(handle, trackIndex)

    override fun clipAddress(trackIndex: Int, clipId: Int): ClipAddress? {
        val out = UapmdClipAddress()
        if (!lib.uapmd_addresses_clip_address(handle, trackIndex, clipId, out)) return null
        return ClipAddress(out.track_reference_id ?: "", out.clip_reference_id ?: "")
    }

    override fun pluginAddress(instanceId: Int): PluginAddress? {
        val out = UapmdPluginAddress()
        if (!lib.uapmd_addresses_plugin_address(handle, instanceId, out)) return null
        return PluginAddress(out.track_reference_id ?: "", out.node_id ?: "")
    }
}

// ─── Fragments ───────────────────────────────────────────────────────────────

class JvmClipFragment internal constructor(
    internal val handle: Pointer,
    /** Fragments borrowed from a track fragment are released with their owner. */
    private val owned: Boolean
) : ClipFragment {

    override val isMidi: Boolean get() = lib.uapmd_clip_fragment_is_midi(handle)

    override val clip: ClipData
        get() {
            val c = UapmdClipData()
            lib.uapmd_clip_fragment_get_clip(handle, c)
            c.read()
            return ClipData(
                clipId = c.clip_id,
                positionSamples = c.position.samples,
                positionLegacyBeats = c.position.legacy_beats,
                durationSamples = c.duration_samples,
                gain = c.gain,
                muted = c.muted != 0.toByte(),
                name = c.name ?: "",
                filepath = c.filepath ?: "",
                clipType = ClipType.fromNative(c.clip_type)
            )
        }

    override val umpEvents: UIntArray
        get() {
            val n = lib.uapmd_clip_fragment_get_ump_events(handle, null, 0)
            if (n <= 0) return UIntArray(0)
            val buf = IntArray(n)
            lib.uapmd_clip_fragment_get_ump_events(handle, buf, n)
            return UIntArray(n) { buf[it].toUInt() }
        }

    override val umpTickTimestamps: LongArray
        get() {
            val n = lib.uapmd_clip_fragment_get_ump_tick_timestamps(handle, null, 0)
            if (n <= 0) return LongArray(0)
            val buf = LongArray(n)
            lib.uapmd_clip_fragment_get_ump_tick_timestamps(handle, buf, n)
            return buf
        }

    override val extensionState: Map<String, ByteArray>
        get() {
            val count = lib.uapmd_clip_fragment_extension_state_count(handle)
            if (count == 0) return emptyMap()
            return (0 until count).associate { i ->
                val key = readJvmString { buf, size -> lib.uapmd_clip_fragment_extension_state_key(handle, i, buf, size) }
                val size = lib.uapmd_clip_fragment_extension_state_data(handle, i, null, 0L).toInt()
                val data = ByteArray(size)
                if (size > 0) lib.uapmd_clip_fragment_extension_state_data(handle, i, data, size.toLong())
                key to data
            }
        }

    override fun close() {
        if (owned) lib.uapmd_clip_fragment_destroy(handle)
    }
}

class JvmTrackFragment internal constructor(internal val handle: Pointer) : TrackFragment {
    override val referenceId: String
        get() = readJvmString { buf, size -> lib.uapmd_track_fragment_reference_id(handle, buf, size) }

    override val volume: Double get() = lib.uapmd_track_fragment_volume(handle)
    override val muted: Boolean get() = lib.uapmd_track_fragment_muted(handle)
    override val solo: Boolean get() = lib.uapmd_track_fragment_solo(handle)

    override val graphType: String
        get() = readJvmString { buf, size -> lib.uapmd_track_fragment_graph_type(handle, buf, size) }

    override val graphBytes: ByteArray
        get() {
            val size = lib.uapmd_track_fragment_graph_bytes(handle, null, 0L).toInt()
            if (size <= 0) return ByteArray(0)
            val data = ByteArray(size)
            lib.uapmd_track_fragment_graph_bytes(handle, data, size.toLong())
            return data
        }

    override val clips: List<ClipFragment>
        get() = (0 until lib.uapmd_track_fragment_clip_count(handle)).mapNotNull { i ->
            lib.uapmd_track_fragment_get_clip(handle, i)?.let { JvmClipFragment(it, owned = false) }
        }

    override val plugins: List<TrackPluginFragment>
        get() = (0 until lib.uapmd_track_fragment_plugin_count(handle)).mapNotNull { i ->
            val out = UapmdTrackPluginFragment()
            if (!lib.uapmd_track_fragment_get_plugin(handle, i, out)) return@mapNotNull null
            out.read()
            TrackPluginFragment(
                nodeId = out.node_id ?: "",
                pluginId = out.plugin_id ?: "",
                format = out.format ?: "",
                displayName = out.display_name ?: "",
                groupIndex = out.group_index,
                state = out.state?.getByteArray(0, out.state_size) ?: ByteArray(0)
            )
        }

    override fun close() = lib.uapmd_track_fragment_destroy(handle)
}

// ─── Timeline history extension implementation ───────────────────────────────
//
// Shared by JvmTimelineFacade; kept here so that the history surface stays in
// one file rather than doubling the size of JvmTimeline.kt.

internal class JvmTimelineHistory(private val handle: Pointer) {
    val undoEngine: UndoEngine
        get() = JvmUndoEngine(lib.uapmd_tl_undo_engine(handle) ?: error("no undo engine"))

    val commands: ProjectCommands
        get() = JvmProjectCommands(lib.uapmd_tl_commands(handle) ?: error("no project commands"))

    val addresses: ProjectAddressBook
        get() = JvmProjectAddressBook(lib.uapmd_tl_addresses(handle) ?: error("no address book"))

    fun <T> documentTransaction(block: () -> T): T {
        lib.uapmd_tl_begin_document_transaction(handle)
        try {
            return block()
        } finally {
            lib.uapmd_tl_end_document_transaction(handle)
        }
    }

    fun removeClip(trackIndex: Int, clipId: Int, origin: MutationOrigin) =
        lib.uapmd_tl_remove_clip_with_origin(handle, trackIndex, clipId, origin.nativeValue)

    fun clearClipsFromTrack(trackIndex: Int, origin: MutationOrigin) =
        lib.uapmd_tl_clear_clips_from_track(handle, trackIndex, origin.nativeValue)

    fun isClipEnabled(trackIndex: Int, clipId: Int) = lib.uapmd_tl_clip_enabled(handle, trackIndex, clipId)

    fun replaceMidiClipContent(
        trackIndex: Int, clipId: Int, umpEvents: UIntArray, tickTimestamps: LongArray, origin: MutationOrigin
    ): Boolean {
        val events = IntArray(umpEvents.size) { umpEvents[it].toInt() }
        return lib.uapmd_tl_replace_midi_clip_content(
            handle, trackIndex, clipId,
            events.takeIf { it.isNotEmpty() }, events.size,
            tickTimestamps.takeIf { it.isNotEmpty() }, tickTimestamps.size,
            origin.nativeValue
        )
    }

    fun replaceAudioClipContent(
        trackIndex: Int, clipId: Int, filepath: String,
        markers: List<ClipMarkerData>, warps: List<AudioWarpPointData>,
        masterMarkers: List<ClipMarkerData>, origin: MutationOrigin
    ): Boolean = lib.uapmd_tl_replace_audio_clip_content(
        handle, trackIndex, clipId, filepath,
        markers.toJvmArray(), markers.size,
        warps.toJvmArray(), warps.size,
        masterMarkers.toJvmArray(), masterMarkers.size,
        origin.nativeValue
    )

    fun captureClipFragment(trackIndex: Int, clipId: Int): ClipFragment? =
        lib.uapmd_tl_capture_clip_fragment(handle, trackIndex, clipId)?.let { JvmClipFragment(it, owned = true) }

    fun attachClipFragment(trackIndex: Int, fragment: ClipFragment, idPolicy: ObjectIdPolicy): ClipAddResult {
        val r = lib.uapmd_tl_attach_clip_fragment(
            handle, trackIndex, (fragment as JvmClipFragment).handle, idPolicy.nativeValue
        )
        return ClipAddResult(r.clip_id, r.source_node_id, r.success != 0.toByte(), r.error)
    }

    fun captureTrackFragment(trackIndex: Int, callback: (TrackFragment?, String?) -> Unit) {
        lateinit var cb: TrackFragmentCb
        cb = object : TrackFragmentCb {
            override fun invoke(fragment: Pointer?, error: String?, userData: Pointer?) {
                try {
                    callback(fragment?.let { JvmTrackFragment(it) }, error)
                } finally {
                    trackFragmentCallbacks.remove(cb)
                }
            }
        }
        trackFragmentCallbacks.add(cb)
        lib.uapmd_tl_capture_track_fragment(handle, trackIndex, null, cb)
    }

    fun attachTrackFragment(
        fragment: TrackFragment, options: TrackAttachOptions, callback: (Int, String?) -> Unit
    ) = lib.uapmd_tl_attach_track_fragment(
        handle, (fragment as JvmTrackFragment).handle, options.toJvmByVal(), null, trackMutationCallback(callback)
    )

    fun addEmptyTrack(origin: MutationOrigin, callback: (Int, String?) -> Unit) =
        lib.uapmd_tl_add_empty_track(handle, origin.nativeValue, null, trackMutationCallback(callback))

    fun removeTrack(trackIndex: Int, origin: MutationOrigin, callback: (Int, String?) -> Unit) =
        lib.uapmd_tl_remove_track(handle, trackIndex, origin.nativeValue, null, trackMutationCallback(callback))

    fun recordTrackAddition(trackIndex: Int, origin: MutationOrigin, callback: (Int, String?) -> Unit) =
        lib.uapmd_tl_record_track_addition(handle, trackIndex, origin.nativeValue, null, trackMutationCallback(callback))

    fun setPluginState(instanceId: Int, state: ByteArray, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        lib.uapmd_tl_set_plugin_state(
            handle, instanceId, state.takeIf { it.isNotEmpty() }, state.size.toLong(),
            origin.nativeValue, null, completionCallback(completion)
        )

    fun loadPluginPreset(instanceId: Int, presetIndex: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        lib.uapmd_tl_load_plugin_preset(handle, instanceId, presetIndex, origin.nativeValue, null, completionCallback(completion))

    fun recordPluginInstanceAddition(instanceId: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        lib.uapmd_tl_record_plugin_instance_addition(handle, instanceId, origin.nativeValue, null, completionCallback(completion))

    fun removePluginInstance(instanceId: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        lib.uapmd_tl_remove_plugin_instance(handle, instanceId, origin.nativeValue, null, completionCallback(completion))

    val hasPendingPluginMutations: Boolean get() = lib.uapmd_tl_has_pending_plugin_mutations(handle)

    private companion object {
        val trackFragmentCallbacks = java.util.Collections.synchronizedSet(mutableSetOf<TrackFragmentCb>())
    }
}
