@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.atsushieno.uapmd

import kotlinx.cinterop.*
import uapmd.*

// ─── Conversion helpers ──────────────────────────────────────────────────────

internal fun uapmd_undo_state_t.toKotlin() = UndoState(
    busy = busy,
    compoundOpen = compound_open,
    gestureOpen = gesture_open,
    canUndo = can_undo,
    canRedo = can_redo,
    dirty = dirty,
    compoundDescription = compound_description?.toKString() ?: "",
    undoDescription = undo_description?.toKString() ?: "",
    redoDescription = redo_description?.toKString() ?: "",
    historySizeInBytes = history_size_in_bytes.toLong(),
    maximumHistorySizeInBytes = maximum_history_size_in_bytes.toLong(),
    currentStateId = current_state_id.toLong(),
    savedStateId = saved_state_id.toLong()
)

internal fun uapmd_clip_marker_t.toKotlin() = ClipMarkerData(
    markerId = marker_id?.toKString() ?: "",
    clipPositionOffset = clip_position_offset,
    referenceType = WarpReferenceType.fromNative(reference_type.toInt()),
    referenceClipId = reference_clip_id?.toKString() ?: "",
    referenceMarkerId = reference_marker_id?.toKString() ?: "",
    name = name?.toKString() ?: ""
)

/** Fills a caller-scoped C array with the given markers. Returns null when empty. */
internal fun MemScope.markersToNative(markers: List<ClipMarkerData>): CPointer<uapmd_clip_marker_t>? {
    if (markers.isEmpty()) return null
    val buf = allocArray<uapmd_clip_marker_t>(markers.size)
    markers.forEachIndexed { i, m ->
        buf[i].marker_id = m.markerId.cstr.ptr
        buf[i].clip_position_offset = m.clipPositionOffset
        buf[i].reference_type = m.referenceType.nativeValue.toUInt()
        buf[i].reference_clip_id = m.referenceClipId.cstr.ptr
        buf[i].reference_marker_id = m.referenceMarkerId.cstr.ptr
        buf[i].name = m.name.cstr.ptr
    }
    return buf
}

internal fun MemScope.warpsToNative(warps: List<AudioWarpPointData>): CPointer<uapmd_audio_warp_point_t>? {
    if (warps.isEmpty()) return null
    val buf = allocArray<uapmd_audio_warp_point_t>(warps.size)
    warps.forEachIndexed { i, w ->
        buf[i].clip_position_offset = w.clipPositionOffset
        buf[i].speed_ratio = w.speedRatio
        buf[i].reference_type = w.referenceType.nativeValue.toUInt()
        buf[i].reference_clip_id = w.referenceClipId.cstr.ptr
        buf[i].reference_marker_id = w.referenceMarkerId.cstr.ptr
    }
    return buf
}

/**
 * Static trampolines for the C completion types. Each disposes its [StableRef]
 * after invoking, because the C API guarantees exactly one call.
 */
private val undoCompletionTrampoline = staticCFunction<CValue<uapmd_undo_result_t>, COpaquePointer?, Unit> { result, userData ->
    if (userData != null) {
        val ref = userData.asStableRef<(UndoResult) -> Unit>()
        result.useContents {
            ref.get()(UndoResult(UndoStatus.fromNative(status.toInt()), error?.toKString()))
        }
        ref.dispose()
    }
}

private val trackMutationTrampoline = staticCFunction<Int, CPointer<ByteVar>?, COpaquePointer?, Unit> { trackIndex, error, userData ->
    if (userData != null) {
        val ref = userData.asStableRef<(Int, String?) -> Unit>()
        ref.get()(trackIndex, error?.toKString())
        ref.dispose()
    }
}

private val trackFragmentTrampoline = staticCFunction<uapmd_track_fragment_t?, CPointer<ByteVar>?, COpaquePointer?, Unit> { fragment, error, userData ->
    if (userData != null) {
        val ref = userData.asStableRef<(TrackFragment?, String?) -> Unit>()
        ref.get()(fragment?.let { NativeTrackFragment(it) }, error?.toKString())
        ref.dispose()
    }
}

private fun completionRef(completion: ((UndoResult) -> Unit)?): COpaquePointer? =
    completion?.let { StableRef.create(it).asCPointer() }

// ─── NativeUndoEngine ────────────────────────────────────────────────────────

class NativeUndoEngine internal constructor(private val handle: uapmd_undo_engine_t) : UndoEngine {
    override val state: UndoState
        get() = memScoped {
            val out = alloc<uapmd_undo_state_t>()
            uapmd_undo_engine_get_state(handle, out.ptr)
            out.toKotlin()
        }

    override fun undo(completion: ((UndoResult) -> Unit)?) =
        uapmd_undo_engine_undo(handle, completionRef(completion), undoCompletionTrampoline)

    override fun redo(completion: ((UndoResult) -> Unit)?) =
        uapmd_undo_engine_redo(handle, completionRef(completion), undoCompletionTrampoline)

    override fun beginCompound(description: String, origin: MutationOrigin): UndoResult =
        uapmd_undo_engine_begin_compound(handle, description, origin.nativeValue.toUInt())
            .useContents { UndoResult(UndoStatus.fromNative(status.toInt()), error?.toKString()) }

    override fun endCompound(completion: ((UndoResult) -> Unit)?) =
        uapmd_undo_engine_end_compound(handle, completionRef(completion), undoCompletionTrampoline)

    override fun cancelCompound(completion: ((UndoResult) -> Unit)?) =
        uapmd_undo_engine_cancel_compound(handle, completionRef(completion), undoCompletionTrampoline)

    override fun beginGesture(description: String, origin: MutationOrigin): UndoResult =
        uapmd_undo_engine_begin_gesture(handle, description, origin.nativeValue.toUInt())
            .useContents { UndoResult(UndoStatus.fromNative(status.toInt()), error?.toKString()) }

    override fun endGesture(completion: ((UndoResult) -> Unit)?) =
        uapmd_undo_engine_end_gesture(handle, completionRef(completion), undoCompletionTrampoline)

    override fun cancelGesture(completion: ((UndoResult) -> Unit)?) =
        uapmd_undo_engine_cancel_gesture(handle, completionRef(completion), undoCompletionTrampoline)

    override fun clear(markCurrentStateSaved: Boolean) = uapmd_undo_engine_clear(handle, markCurrentStateSaved)
    override fun markSaved() = uapmd_undo_engine_mark_saved(handle)
    override fun markStateSaved(stateId: Long) = uapmd_undo_engine_mark_state_saved(handle, stateId.toULong())
    override fun setMaximumHistorySizeInBytes(bytes: Long) =
        uapmd_undo_engine_set_maximum_history_size(handle, bytes.toULong())

    override fun shutdown() = uapmd_undo_engine_shutdown(handle)
}

// ─── NativeCommandManager ────────────────────────────────────────────────────

class NativeCommandManager internal constructor(private val handle: uapmd_command_manager_t) : CommandManager {
    override val state: UndoState
        get() = memScoped {
            val out = alloc<uapmd_undo_state_t>()
            uapmd_command_manager_get_state(handle, out.ptr)
            out.toKotlin()
        }

    override val history: UndoEngine
        get() = NativeUndoEngine(uapmd_command_manager_history(handle)!!)

    override fun undo(completion: ((UndoResult) -> Unit)?) =
        uapmd_command_manager_undo(handle, completionRef(completion), undoCompletionTrampoline)

    override fun redo(completion: ((UndoResult) -> Unit)?) =
        uapmd_command_manager_redo(handle, completionRef(completion), undoCompletionTrampoline)

    override fun beginStep(description: String, origin: MutationOrigin): UndoResult =
        uapmd_command_manager_begin_step(handle, description, origin.nativeValue.toUInt())
            .useContents { UndoResult(UndoStatus.fromNative(status.toInt()), error?.toKString()) }

    override fun endStep(completion: ((UndoResult) -> Unit)?) =
        uapmd_command_manager_end_step(handle, completionRef(completion), undoCompletionTrampoline)

    override fun cancelStep(completion: ((UndoResult) -> Unit)?) =
        uapmd_command_manager_cancel_step(handle, completionRef(completion), undoCompletionTrampoline)

    override fun beginGesture(description: String, origin: MutationOrigin): UndoResult =
        uapmd_command_manager_begin_gesture(handle, description, origin.nativeValue.toUInt())
            .useContents { UndoResult(UndoStatus.fromNative(status.toInt()), error?.toKString()) }

    override fun endGesture(completion: ((UndoResult) -> Unit)?) =
        uapmd_command_manager_end_gesture(handle, completionRef(completion), undoCompletionTrampoline)

    override fun cancelGesture(completion: ((UndoResult) -> Unit)?) =
        uapmd_command_manager_cancel_gesture(handle, completionRef(completion), undoCompletionTrampoline)

    override fun shutdown() = uapmd_command_manager_shutdown(handle)
}

// ─── NativeProjectCommands ───────────────────────────────────────────────────

class NativeProjectCommands internal constructor(private val handle: uapmd_project_commands_t) : ProjectCommands {
    override val history: CommandManager
        get() = NativeCommandManager(uapmd_commands_history(handle)!!)

    override fun setClipEnabled(trackIndex: Int, clipId: Int, enabled: Boolean, origin: MutationOrigin) =
        uapmd_commands_set_clip_enabled(handle, trackIndex, clipId, enabled, origin.nativeValue.toUInt())

    override fun setClipAnchor(trackIndex: Int, clipId: Int, anchor: TimeReference, origin: MutationOrigin): Boolean =
        memScoped {
            val ref = cValue<uapmd_time_reference_t> {
                type = anchor.type.nativeValue.toUInt()
                reference_id = anchor.referenceId.cstr.getPointer(this@memScoped)
                offset = anchor.offset
            }
            uapmd_commands_set_clip_anchor(handle, trackIndex, clipId, ref, origin.nativeValue.toUInt())
        }

    override fun setClipGain(trackIndex: Int, clipId: Int, gain: Double, origin: MutationOrigin) =
        uapmd_commands_set_clip_gain(handle, trackIndex, clipId, gain, origin.nativeValue.toUInt())

    override fun setClipMuted(trackIndex: Int, clipId: Int, muted: Boolean, origin: MutationOrigin) =
        uapmd_commands_set_clip_muted(handle, trackIndex, clipId, muted, origin.nativeValue.toUInt())

    override fun resizeClip(trackIndex: Int, clipId: Int, newDurationSamples: Long, origin: MutationOrigin) =
        uapmd_commands_resize_clip(handle, trackIndex, clipId, newDurationSamples, origin.nativeValue.toUInt())

    override fun setClipName(trackIndex: Int, clipId: Int, name: String, origin: MutationOrigin) =
        uapmd_commands_set_clip_name(handle, trackIndex, clipId, name, origin.nativeValue.toUInt())

    override fun setClipFilepath(trackIndex: Int, clipId: Int, filepath: String, origin: MutationOrigin) =
        uapmd_commands_set_clip_filepath(handle, trackIndex, clipId, filepath, origin.nativeValue.toUInt())

    override fun setClipNeedsFileSave(trackIndex: Int, clipId: Int, needsSave: Boolean, origin: MutationOrigin) =
        uapmd_commands_set_clip_needs_file_save(handle, trackIndex, clipId, needsSave, origin.nativeValue.toUInt())

    override fun setClipMarkers(trackIndex: Int, clipId: Int, markers: List<ClipMarkerData>, origin: MutationOrigin) =
        memScoped {
            uapmd_commands_set_clip_markers(
                handle, trackIndex, clipId, markersToNative(markers), markers.size.toUInt(), origin.nativeValue.toUInt()
            )
        }

    override fun setClipAudioWarps(trackIndex: Int, clipId: Int, warps: List<AudioWarpPointData>, origin: MutationOrigin) =
        memScoped {
            uapmd_commands_set_clip_audio_warps(
                handle, trackIndex, clipId, warpsToNative(warps), warps.size.toUInt(), origin.nativeValue.toUInt()
            )
        }

    override fun setTrackGain(trackIndex: Int, gain: Double, origin: MutationOrigin) =
        uapmd_commands_set_track_gain(handle, trackIndex, gain, origin.nativeValue.toUInt())

    override fun setTrackMuted(trackIndex: Int, muted: Boolean, origin: MutationOrigin) =
        uapmd_commands_set_track_muted(handle, trackIndex, muted, origin.nativeValue.toUInt())

    override fun setTrackSolo(trackIndex: Int, solo: Boolean, origin: MutationOrigin) =
        uapmd_commands_set_track_solo(handle, trackIndex, solo, origin.nativeValue.toUInt())

    override fun setTrackBypassed(trackIndex: Int, bypassed: Boolean, origin: MutationOrigin) =
        uapmd_commands_set_track_bypassed(handle, trackIndex, bypassed, origin.nativeValue.toUInt())

    override fun setTrackFreezePolicyEnabled(trackIndex: Int, enabled: Boolean, origin: MutationOrigin) =
        uapmd_commands_set_track_freeze_policy_enabled(handle, trackIndex, enabled, origin.nativeValue.toUInt())

    override fun setPluginBypassed(instanceId: Int, bypassed: Boolean, origin: MutationOrigin) =
        uapmd_commands_set_plugin_bypassed(handle, instanceId, bypassed, origin.nativeValue.toUInt())

    override fun setPluginParameterValue(instanceId: Int, parameterIndex: Int, value: Double, origin: MutationOrigin) =
        uapmd_commands_set_plugin_parameter_value(handle, instanceId, parameterIndex, value, origin.nativeValue.toUInt())

    override fun setPluginPerNoteControllerValue(
        instanceId: Int, contextType: Int, note: Int, channel: Int, group: Int, extra: Int,
        parameterIndex: Int, value: Double, origin: MutationOrigin
    ) = uapmd_commands_set_plugin_per_note_controller_value(
        handle, instanceId, contextType.toUInt(),
        note.toUInt(), channel.toUInt(), group.toUInt(), extra.toUInt(),
        parameterIndex, value, origin.nativeValue.toUInt()
    )

    override fun setPluginGroup(instanceId: Int, group: UByte, origin: MutationOrigin) =
        uapmd_commands_set_plugin_group(handle, instanceId, group, origin.nativeValue.toUInt())

    override fun setMasterTrackMarkers(markers: List<ClipMarkerData>, origin: MutationOrigin) = memScoped {
        uapmd_commands_set_master_track_markers(
            handle, markersToNative(markers), markers.size.toUInt(), origin.nativeValue.toUInt()
        )
    }
}

// ─── NativeProjectAddressBook ────────────────────────────────────────────────

class NativeProjectAddressBook internal constructor(private val handle: uapmd_address_book_t) : ProjectAddressBook {
    override fun timelineTrack(trackReferenceId: String): TimelineTrack? =
        uapmd_addresses_timeline_track(handle, trackReferenceId)?.let { NativeTimelineTrack(it) }

    override fun sequencerTrack(trackReferenceId: String): SequencerTrack? =
        uapmd_addresses_sequencer_track(handle, trackReferenceId)?.let { NativeSequencerTrack(it) }

    override fun trackIndex(trackReferenceId: String): Int =
        uapmd_addresses_track_index(handle, trackReferenceId)

    override fun clipId(address: ClipAddress): Int = memScoped {
        val a = cValue<uapmd_clip_address_t> {
            track_reference_id = address.trackReferenceId.cstr.getPointer(this@memScoped)
            clip_reference_id = address.clipReferenceId.cstr.getPointer(this@memScoped)
        }
        uapmd_addresses_clip_id(handle, a)
    }

    override fun pluginInstanceId(address: PluginAddress): Int = memScoped {
        val a = cValue<uapmd_plugin_address_t> {
            track_reference_id = address.trackReferenceId.cstr.getPointer(this@memScoped)
            node_id = address.nodeId.cstr.getPointer(this@memScoped)
        }
        uapmd_addresses_plugin_instance_id(handle, a)
    }

    override fun trackReferenceId(trackIndex: Int): String? =
        uapmd_addresses_track_reference_id(handle, trackIndex)?.toKString()

    override fun clipAddress(trackIndex: Int, clipId: Int): ClipAddress? = memScoped {
        val out = alloc<uapmd_clip_address_t>()
        if (!uapmd_addresses_clip_address(handle, trackIndex, clipId, out.ptr)) return null
        ClipAddress(out.track_reference_id?.toKString() ?: "", out.clip_reference_id?.toKString() ?: "")
    }

    override fun pluginAddress(instanceId: Int): PluginAddress? = memScoped {
        val out = alloc<uapmd_plugin_address_t>()
        if (!uapmd_addresses_plugin_address(handle, instanceId, out.ptr)) return null
        PluginAddress(out.track_reference_id?.toKString() ?: "", out.node_id?.toKString() ?: "")
    }
}

// ─── Fragments ───────────────────────────────────────────────────────────────

class NativeClipFragment internal constructor(
    internal val handle: uapmd_clip_fragment_t,
    /** Fragments borrowed from a track fragment are released with their owner. */
    private val owned: Boolean
) : ClipFragment {

    override val isMidi: Boolean get() = uapmd_clip_fragment_is_midi(handle)

    override val clip: ClipData
        get() = memScoped {
            val c = alloc<uapmd_clip_data_t>()
            uapmd_clip_fragment_get_clip(handle, c.ptr)
            ClipData(
                clipId = c.clip_id,
                positionSamples = c.position.samples,
                positionLegacyBeats = c.position.legacy_beats,
                durationSamples = c.duration_samples,
                gain = c.gain,
                muted = c.muted,
                name = c.name?.toKString() ?: "",
                filepath = c.filepath?.toKString() ?: "",
                clipType = ClipType.fromNative(c.clip_type.toInt())
            )
        }

    override val umpEvents: UIntArray
        get() = memScoped {
            val n = uapmd_clip_fragment_get_ump_events(handle, null, 0u).toInt()
            if (n <= 0) return UIntArray(0)
            val buf = allocArray<UIntVar>(n)
            uapmd_clip_fragment_get_ump_events(handle, buf, n.toUInt())
            UIntArray(n) { buf[it] }
        }

    override val umpTickTimestamps: LongArray
        get() = memScoped {
            val n = uapmd_clip_fragment_get_ump_tick_timestamps(handle, null, 0u).toInt()
            if (n <= 0) return LongArray(0)
            val buf = allocArray<ULongVar>(n)
            uapmd_clip_fragment_get_ump_tick_timestamps(handle, buf, n.toUInt())
            LongArray(n) { buf[it].toLong() }
        }

    override val extensionState: Map<String, ByteArray>
        get() {
            val count = uapmd_clip_fragment_extension_state_count(handle).toInt()
            if (count == 0) return emptyMap()
            return (0 until count).associate { i ->
                val key = readCString { buf, size -> uapmd_clip_fragment_extension_state_key(handle, i.toUInt(), buf, size) }
                val size = uapmd_clip_fragment_extension_state_data(handle, i.toUInt(), null, 0u).toInt()
                val data = ByteArray(size)
                if (size > 0) data.usePinned { uapmd_clip_fragment_extension_state_data(handle, i.toUInt(), it.addressOf(0).reinterpret(), size.toULong()) }
                key to data
            }
        }

    override fun close() {
        if (owned) uapmd_clip_fragment_destroy(handle)
    }
}

class NativeTrackFragment internal constructor(internal val handle: uapmd_track_fragment_t) : TrackFragment {
    override val referenceId: String
        get() = readCString { buf, size -> uapmd_track_fragment_reference_id(handle, buf, size) }

    override val volume: Double get() = uapmd_track_fragment_volume(handle)
    override val muted: Boolean get() = uapmd_track_fragment_muted(handle)
    override val solo: Boolean get() = uapmd_track_fragment_solo(handle)

    override val graphType: String
        get() = readCString { buf, size -> uapmd_track_fragment_graph_type(handle, buf, size) }

    override val graphBytes: ByteArray
        get() {
            val size = uapmd_track_fragment_graph_bytes(handle, null, 0u).toInt()
            if (size <= 0) return ByteArray(0)
            val data = ByteArray(size)
            data.usePinned { uapmd_track_fragment_graph_bytes(handle, it.addressOf(0).reinterpret(), size.toULong()) }
            return data
        }

    override val clips: List<ClipFragment>
        get() = (0 until uapmd_track_fragment_clip_count(handle).toInt()).mapNotNull { i ->
            uapmd_track_fragment_get_clip(handle, i.toUInt())?.let { NativeClipFragment(it, owned = false) }
        }

    override val plugins: List<TrackPluginFragment>
        get() = memScoped {
            (0 until uapmd_track_fragment_plugin_count(handle).toInt()).mapNotNull { i ->
                val out = alloc<uapmd_track_plugin_fragment_t>()
                if (!uapmd_track_fragment_get_plugin(handle, i.toUInt(), out.ptr)) return@mapNotNull null
                val stateSize = out.state_size.toInt()
                val state = ByteArray(stateSize)
                val src = out.state
                if (stateSize > 0 && src != null)
                    for (j in 0 until stateSize) state[j] = src[j].toByte()
                TrackPluginFragment(
                    nodeId = out.node_id?.toKString() ?: "",
                    pluginId = out.plugin_id?.toKString() ?: "",
                    format = out.format?.toKString() ?: "",
                    displayName = out.display_name?.toKString() ?: "",
                    groupIndex = out.group_index,
                    state = state
                )
            }
        }

    override fun close() = uapmd_track_fragment_destroy(handle)
}

// ─── Timeline history implementation ─────────────────────────────────────────

internal class NativeTimelineHistory(private val handle: uapmd_timeline_facade_t) {
    val undoEngine: UndoEngine get() = NativeUndoEngine(uapmd_tl_undo_engine(handle)!!)
    val commands: ProjectCommands get() = NativeProjectCommands(uapmd_tl_commands(handle)!!)
    val addresses: ProjectAddressBook get() = NativeProjectAddressBook(uapmd_tl_addresses(handle)!!)

    fun <T> documentTransaction(block: () -> T): T {
        uapmd_tl_begin_document_transaction(handle)
        try {
            return block()
        } finally {
            uapmd_tl_end_document_transaction(handle)
        }
    }

    fun removeClip(trackIndex: Int, clipId: Int, origin: MutationOrigin) =
        uapmd_tl_remove_clip_with_origin(handle, trackIndex, clipId, origin.nativeValue.toUInt())

    fun clearClipsFromTrack(trackIndex: Int, origin: MutationOrigin) =
        uapmd_tl_clear_clips_from_track(handle, trackIndex, origin.nativeValue.toUInt())

    fun isClipEnabled(trackIndex: Int, clipId: Int) = uapmd_tl_clip_enabled(handle, trackIndex, clipId)

    fun replaceMidiClipContent(
        trackIndex: Int, clipId: Int, umpEvents: UIntArray, tickTimestamps: LongArray, origin: MutationOrigin
    ): Boolean = memScoped {
        val events = if (umpEvents.isEmpty()) null else allocArray<UIntVar>(umpEvents.size).also {
            umpEvents.forEachIndexed { i, v -> it[i] = v }
        }
        val ticks = if (tickTimestamps.isEmpty()) null else allocArray<ULongVar>(tickTimestamps.size).also {
            tickTimestamps.forEachIndexed { i, v -> it[i] = v.toULong() }
        }
        uapmd_tl_replace_midi_clip_content(
            handle, trackIndex, clipId, events, umpEvents.size.toUInt(),
            ticks, tickTimestamps.size.toUInt(), origin.nativeValue.toUInt()
        )
    }

    fun replaceAudioClipContent(
        trackIndex: Int, clipId: Int, filepath: String,
        markers: List<ClipMarkerData>, warps: List<AudioWarpPointData>,
        masterMarkers: List<ClipMarkerData>, origin: MutationOrigin
    ): Boolean = memScoped {
        uapmd_tl_replace_audio_clip_content(
            handle, trackIndex, clipId, filepath,
            markersToNative(markers), markers.size.toUInt(),
            warpsToNative(warps), warps.size.toUInt(),
            markersToNative(masterMarkers), masterMarkers.size.toUInt(),
            origin.nativeValue.toUInt()
        )
    }

    fun captureClipFragment(trackIndex: Int, clipId: Int): ClipFragment? =
        uapmd_tl_capture_clip_fragment(handle, trackIndex, clipId)?.let { NativeClipFragment(it, owned = true) }

    fun attachClipFragment(trackIndex: Int, fragment: ClipFragment, idPolicy: ObjectIdPolicy): ClipAddResult =
        uapmd_tl_attach_clip_fragment(
            handle, trackIndex, (fragment as NativeClipFragment).handle, idPolicy.nativeValue.toUInt()
        ).useContents { ClipAddResult(clip_id, source_node_id, success, error?.toKString()) }

    fun captureTrackFragment(trackIndex: Int, callback: (TrackFragment?, String?) -> Unit) =
        uapmd_tl_capture_track_fragment(
            handle, trackIndex, StableRef.create(callback).asCPointer(), trackFragmentTrampoline
        )

    fun attachTrackFragment(fragment: TrackFragment, options: TrackAttachOptions, callback: (Int, String?) -> Unit) {
        val opts = cValue<uapmd_track_attach_options_t> {
            id_policy = options.idPolicy.nativeValue.toUInt()
            insertion_index = options.insertionIndex
            include_plugins = options.includePlugins
            include_plugin_state = options.includePluginState
            include_clips = options.includeClips
        }
        uapmd_tl_attach_track_fragment(
            handle, (fragment as NativeTrackFragment).handle, opts,
            StableRef.create(callback).asCPointer(), trackMutationTrampoline
        )
    }

    fun addEmptyTrack(origin: MutationOrigin, callback: (Int, String?) -> Unit) =
        uapmd_tl_add_empty_track(
            handle, origin.nativeValue.toUInt(), StableRef.create(callback).asCPointer(), trackMutationTrampoline
        )

    fun removeTrack(trackIndex: Int, origin: MutationOrigin, callback: (Int, String?) -> Unit) =
        uapmd_tl_remove_track(
            handle, trackIndex, origin.nativeValue.toUInt(),
            StableRef.create(callback).asCPointer(), trackMutationTrampoline
        )

    fun recordTrackAddition(trackIndex: Int, origin: MutationOrigin, callback: (Int, String?) -> Unit) =
        uapmd_tl_record_track_addition(
            handle, trackIndex, origin.nativeValue.toUInt(),
            StableRef.create(callback).asCPointer(), trackMutationTrampoline
        )

    fun setPluginState(instanceId: Int, state: ByteArray, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) {
        val ref = completionRefOrNull(completion)
        state.usePinned { pinned ->
            uapmd_tl_set_plugin_state(
                handle, instanceId,
                if (state.isEmpty()) null else pinned.addressOf(0).reinterpret(),
                state.size.toULong(), origin.nativeValue.toUInt(), ref, undoCompletionTrampoline
            )
        }
    }

    fun loadPluginPreset(instanceId: Int, presetIndex: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        uapmd_tl_load_plugin_preset(
            handle, instanceId, presetIndex, origin.nativeValue.toUInt(),
            completionRefOrNull(completion), undoCompletionTrampoline
        )

    fun recordPluginInstanceAddition(instanceId: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        uapmd_tl_record_plugin_instance_addition(
            handle, instanceId, origin.nativeValue.toUInt(),
            completionRefOrNull(completion), undoCompletionTrampoline
        )

    fun removePluginInstance(instanceId: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        uapmd_tl_remove_plugin_instance(
            handle, instanceId, origin.nativeValue.toUInt(),
            completionRefOrNull(completion), undoCompletionTrampoline
        )

    val hasPendingPluginMutations: Boolean get() = uapmd_tl_has_pending_plugin_mutations(handle)

    /**
     * These C entry points always take a callback, so a caller that does not
     * want one still gets the trampoline — with a null user_data the trampoline
     * simply does nothing.
     */
    private fun completionRefOrNull(completion: ((UndoResult) -> Unit)?): COpaquePointer? =
        completion?.let { StableRef.create(it).asCPointer() }
}
