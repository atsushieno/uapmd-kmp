package dev.atsushieno.uapmd

// ─── Parallel-array encoding shared with uapmd_jni_history.cpp ───────────────

internal class MarkerArrays(markers: List<ClipMarkerData>) {
    val strings: Array<String?> = arrayOfNulls(markers.size * 4)
    val numbers = DoubleArray(markers.size)
    val refTypes = IntArray(markers.size)

    init {
        markers.forEachIndexed { i, m ->
            strings[i * 4 + 0] = m.markerId
            strings[i * 4 + 1] = m.referenceClipId
            strings[i * 4 + 2] = m.referenceMarkerId
            strings[i * 4 + 3] = m.name
            numbers[i] = m.clipPositionOffset
            refTypes[i] = m.referenceType.nativeValue
        }
    }
}

internal class WarpArrays(warps: List<AudioWarpPointData>) {
    val strings: Array<String?> = arrayOfNulls(warps.size * 2)
    val numbers = DoubleArray(warps.size * 2)
    val refTypes = IntArray(warps.size)

    init {
        warps.forEachIndexed { i, w ->
            strings[i * 2 + 0] = w.referenceClipId
            strings[i * 2 + 1] = w.referenceMarkerId
            numbers[i * 2 + 0] = w.clipPositionOffset
            numbers[i * 2 + 1] = w.speedRatio
            refTypes[i] = w.referenceType.nativeValue
        }
    }
}

/** Object[]{ LongArray(10), String, String, String } as packed by the JNI side. */
internal fun Array<Any>.toUndoState(): UndoState {
    val n = this[0] as LongArray
    return UndoState(
        busy = n[0] != 0L,
        compoundOpen = n[1] != 0L,
        gestureOpen = n[2] != 0L,
        canUndo = n[3] != 0L,
        canRedo = n[4] != 0L,
        dirty = n[5] != 0L,
        compoundDescription = this[1] as String,
        undoDescription = this[2] as String,
        redoDescription = this[3] as String,
        historySizeInBytes = n[6],
        maximumHistorySizeInBytes = n[7],
        currentStateId = n[8],
        savedStateId = n[9]
    )
}

/** Object[]{ LongArray(1) status, String? error }. */
internal fun Array<Any>?.toUndoResult(): UndoResult {
    if (this == null) return UndoResult(UndoStatus.Failed, "native call returned no result")
    val status = (this[0] as LongArray)[0].toInt()
    return UndoResult(UndoStatus.fromNative(status), this.getOrNull(1) as String?)
}

/**
 * The JNI trampolines resolve `invoke` reflectively, so the completion objects
 * only need a matching method signature — no shared interface.
 */
private class UndoCompletionCallback(private val completion: (UndoResult) -> Unit) {
    @Suppress("unused")
    fun invoke(statusOrdinal: Int, error: String?) =
        completion(UndoResult(UndoStatus.fromNative(statusOrdinal), error))
}

private class TrackMutationCallback(private val callback: (Int, String?) -> Unit) {
    @Suppress("unused")
    fun invoke(trackIndex: Int, error: String?) = callback(trackIndex, error)
}

private class TrackFragmentCallback(private val callback: (TrackFragment?, String?) -> Unit) {
    @Suppress("unused")
    fun invoke(fragmentHandle: Long, error: String?) =
        callback(if (fragmentHandle != 0L) AndroidTrackFragment(fragmentHandle) else null, error)
}

private fun undoCb(completion: ((UndoResult) -> Unit)?): Any? =
    completion?.let { UndoCompletionCallback(it) }

// ─── AndroidUndoEngine ───────────────────────────────────────────────────────

class AndroidUndoEngine internal constructor(private val handle: Long) : UndoEngine {
    override val state: UndoState
        get() = JniBridge.uapmdUndoEngineGetState(handle)?.toUndoState()
            ?: error("uapmdUndoEngineGetState returned null")

    override fun undo(completion: ((UndoResult) -> Unit)?) = JniBridge.uapmdUndoEngineUndo(handle, undoCb(completion))
    override fun redo(completion: ((UndoResult) -> Unit)?) = JniBridge.uapmdUndoEngineRedo(handle, undoCb(completion))

    override fun beginCompound(description: String, origin: MutationOrigin): UndoResult =
        JniBridge.uapmdUndoEngineBeginCompound(handle, description, origin.nativeValue).toUndoResult()

    override fun endCompound(completion: ((UndoResult) -> Unit)?) =
        JniBridge.uapmdUndoEngineEndCompound(handle, undoCb(completion))

    override fun cancelCompound(completion: ((UndoResult) -> Unit)?) =
        JniBridge.uapmdUndoEngineCancelCompound(handle, undoCb(completion))

    override fun beginGesture(description: String, origin: MutationOrigin): UndoResult =
        JniBridge.uapmdUndoEngineBeginGesture(handle, description, origin.nativeValue).toUndoResult()

    override fun endGesture(completion: ((UndoResult) -> Unit)?) =
        JniBridge.uapmdUndoEngineEndGesture(handle, undoCb(completion))

    override fun cancelGesture(completion: ((UndoResult) -> Unit)?) =
        JniBridge.uapmdUndoEngineCancelGesture(handle, undoCb(completion))

    override fun clear(markCurrentStateSaved: Boolean) = JniBridge.uapmdUndoEngineClear(handle, markCurrentStateSaved)
    override fun markSaved() = JniBridge.uapmdUndoEngineMarkSaved(handle)
    override fun markStateSaved(stateId: Long) = JniBridge.uapmdUndoEngineMarkStateSaved(handle, stateId)
    override fun setMaximumHistorySizeInBytes(bytes: Long) = JniBridge.uapmdUndoEngineSetMaximumHistorySize(handle, bytes)
    override fun shutdown() = JniBridge.uapmdUndoEngineShutdown(handle)
}

// ─── AndroidCommandManager ───────────────────────────────────────────────────

class AndroidCommandManager internal constructor(private val handle: Long) : CommandManager {
    override val state: UndoState
        get() = JniBridge.uapmdCommandManagerGetState(handle)?.toUndoState()
            ?: error("uapmdCommandManagerGetState returned null")

    override val history: UndoEngine get() = AndroidUndoEngine(JniBridge.uapmdCommandManagerHistory(handle))

    override fun undo(completion: ((UndoResult) -> Unit)?) = JniBridge.uapmdCommandManagerUndo(handle, undoCb(completion))
    override fun redo(completion: ((UndoResult) -> Unit)?) = JniBridge.uapmdCommandManagerRedo(handle, undoCb(completion))

    override fun beginStep(description: String, origin: MutationOrigin): UndoResult =
        JniBridge.uapmdCommandManagerBeginStep(handle, description, origin.nativeValue).toUndoResult()

    override fun endStep(completion: ((UndoResult) -> Unit)?) =
        JniBridge.uapmdCommandManagerEndStep(handle, undoCb(completion))

    override fun cancelStep(completion: ((UndoResult) -> Unit)?) =
        JniBridge.uapmdCommandManagerCancelStep(handle, undoCb(completion))

    override fun beginGesture(description: String, origin: MutationOrigin): UndoResult =
        JniBridge.uapmdCommandManagerBeginGesture(handle, description, origin.nativeValue).toUndoResult()

    override fun endGesture(completion: ((UndoResult) -> Unit)?) =
        JniBridge.uapmdCommandManagerEndGesture(handle, undoCb(completion))

    override fun cancelGesture(completion: ((UndoResult) -> Unit)?) =
        JniBridge.uapmdCommandManagerCancelGesture(handle, undoCb(completion))

    override fun shutdown() = JniBridge.uapmdCommandManagerShutdown(handle)
}

// ─── AndroidProjectCommands ──────────────────────────────────────────────────

class AndroidProjectCommands internal constructor(private val handle: Long) : ProjectCommands {
    override val history: CommandManager get() = AndroidCommandManager(JniBridge.uapmdCommandsHistory(handle))

    override fun setClipEnabled(trackIndex: Int, clipId: Int, enabled: Boolean, origin: MutationOrigin) =
        JniBridge.uapmdCommandsSetClipEnabled(handle, trackIndex, clipId, enabled, origin.nativeValue)

    override fun setClipAnchor(trackIndex: Int, clipId: Int, anchor: TimeReference, origin: MutationOrigin) =
        JniBridge.uapmdCommandsSetClipAnchor(
            handle, trackIndex, clipId, anchor.type.nativeValue, anchor.referenceId, anchor.offset, origin.nativeValue
        )

    override fun setClipGain(trackIndex: Int, clipId: Int, gain: Double, origin: MutationOrigin) =
        JniBridge.uapmdCommandsSetClipGain(handle, trackIndex, clipId, gain, origin.nativeValue)

    override fun setClipMuted(trackIndex: Int, clipId: Int, muted: Boolean, origin: MutationOrigin) =
        JniBridge.uapmdCommandsSetClipMuted(handle, trackIndex, clipId, muted, origin.nativeValue)

    override fun resizeClip(trackIndex: Int, clipId: Int, newDurationSamples: Long, origin: MutationOrigin) =
        JniBridge.uapmdCommandsResizeClip(handle, trackIndex, clipId, newDurationSamples, origin.nativeValue)

    override fun setClipName(trackIndex: Int, clipId: Int, name: String, origin: MutationOrigin) =
        JniBridge.uapmdCommandsSetClipName(handle, trackIndex, clipId, name, origin.nativeValue)

    override fun setClipFilepath(trackIndex: Int, clipId: Int, filepath: String, origin: MutationOrigin) =
        JniBridge.uapmdCommandsSetClipFilepath(handle, trackIndex, clipId, filepath, origin.nativeValue)

    override fun setClipNeedsFileSave(trackIndex: Int, clipId: Int, needsSave: Boolean, origin: MutationOrigin) =
        JniBridge.uapmdCommandsSetClipNeedsFileSave(handle, trackIndex, clipId, needsSave, origin.nativeValue)

    override fun setClipMarkers(trackIndex: Int, clipId: Int, markers: List<ClipMarkerData>, origin: MutationOrigin): Boolean {
        val a = MarkerArrays(markers)
        return JniBridge.uapmdCommandsSetClipMarkers(handle, trackIndex, clipId, a.strings, a.numbers, a.refTypes, origin.nativeValue)
    }

    override fun setClipAudioWarps(trackIndex: Int, clipId: Int, warps: List<AudioWarpPointData>, origin: MutationOrigin): Boolean {
        val a = WarpArrays(warps)
        return JniBridge.uapmdCommandsSetClipAudioWarps(handle, trackIndex, clipId, a.strings, a.numbers, a.refTypes, origin.nativeValue)
    }

    override fun setTrackGain(trackIndex: Int, gain: Double, origin: MutationOrigin) =
        JniBridge.uapmdCommandsSetTrackGain(handle, trackIndex, gain, origin.nativeValue)

    override fun setTrackMuted(trackIndex: Int, muted: Boolean, origin: MutationOrigin) =
        JniBridge.uapmdCommandsSetTrackMuted(handle, trackIndex, muted, origin.nativeValue)

    override fun setTrackSolo(trackIndex: Int, solo: Boolean, origin: MutationOrigin) =
        JniBridge.uapmdCommandsSetTrackSolo(handle, trackIndex, solo, origin.nativeValue)

    override fun setTrackBypassed(trackIndex: Int, bypassed: Boolean, origin: MutationOrigin) =
        JniBridge.uapmdCommandsSetTrackBypassed(handle, trackIndex, bypassed, origin.nativeValue)

    override fun setTrackFreezePolicyEnabled(trackIndex: Int, enabled: Boolean, origin: MutationOrigin) =
        JniBridge.uapmdCommandsSetTrackFreezePolicyEnabled(handle, trackIndex, enabled, origin.nativeValue)

    override fun setPluginBypassed(instanceId: Int, bypassed: Boolean, origin: MutationOrigin) =
        JniBridge.uapmdCommandsSetPluginBypassed(handle, instanceId, bypassed, origin.nativeValue)

    override fun setPluginParameterValue(instanceId: Int, parameterIndex: Int, value: Double, origin: MutationOrigin) =
        JniBridge.uapmdCommandsSetPluginParameterValue(handle, instanceId, parameterIndex, value, origin.nativeValue)

    override fun setPluginPerNoteControllerValue(
        instanceId: Int, contextType: Int, note: Int, channel: Int, group: Int, extra: Int,
        parameterIndex: Int, value: Double, origin: MutationOrigin
    ) = JniBridge.uapmdCommandsSetPluginPerNoteControllerValue(
        handle, instanceId, contextType, note, channel, group, extra, parameterIndex, value, origin.nativeValue
    )

    override fun setPluginGroup(instanceId: Int, group: UByte, origin: MutationOrigin) =
        JniBridge.uapmdCommandsSetPluginGroup(handle, instanceId, group.toByte(), origin.nativeValue)

    override fun setMasterTrackMarkers(markers: List<ClipMarkerData>, origin: MutationOrigin): Boolean {
        val a = MarkerArrays(markers)
        return JniBridge.uapmdCommandsSetMasterTrackMarkers(handle, a.strings, a.numbers, a.refTypes, origin.nativeValue)
    }
}

// ─── AndroidProjectAddressBook ───────────────────────────────────────────────

class AndroidProjectAddressBook internal constructor(private val handle: Long) : ProjectAddressBook {
    override fun timelineTrack(trackReferenceId: String): TimelineTrack? =
        JniBridge.uapmdAddressesTimelineTrack(handle, trackReferenceId).takeIf { it != 0L }?.let { AndroidTimelineTrack(it) }

    override fun sequencerTrack(trackReferenceId: String): SequencerTrack? =
        JniBridge.uapmdAddressesSequencerTrack(handle, trackReferenceId).takeIf { it != 0L }?.let { AndroidSequencerTrack(it) }

    override fun trackIndex(trackReferenceId: String) = JniBridge.uapmdAddressesTrackIndex(handle, trackReferenceId)

    override fun clipId(address: ClipAddress) =
        JniBridge.uapmdAddressesClipId(handle, address.trackReferenceId, address.clipReferenceId)

    override fun pluginInstanceId(address: PluginAddress) =
        JniBridge.uapmdAddressesPluginInstanceId(handle, address.trackReferenceId, address.nodeId)

    override fun trackReferenceId(trackIndex: Int) = JniBridge.uapmdAddressesTrackReferenceId(handle, trackIndex)

    override fun clipAddress(trackIndex: Int, clipId: Int): ClipAddress? =
        JniBridge.uapmdAddressesClipAddress(handle, trackIndex, clipId)?.let { ClipAddress(it[0], it[1]) }

    override fun pluginAddress(instanceId: Int): PluginAddress? =
        JniBridge.uapmdAddressesPluginAddress(handle, instanceId)?.let { PluginAddress(it[0], it[1]) }
}

// ─── Fragments ───────────────────────────────────────────────────────────────

class AndroidClipFragment internal constructor(
    internal val handle: Long,
    /** Fragments borrowed from a track fragment are released with their owner. */
    private val owned: Boolean
) : ClipFragment {

    override val isMidi: Boolean get() = JniBridge.uapmdClipFragmentIsMidi(handle)

    override val clip: ClipData
        get() {
            val strings = arrayOfNulls<String>(2)
            val n = JniBridge.uapmdClipFragmentGetClip(handle, strings)
                ?: error("uapmdClipFragmentGetClip returned null")
            return ClipData(
                clipId = n[0].toInt(),
                positionSamples = n[1].toLong(),
                positionLegacyBeats = n[2],
                durationSamples = n[3].toLong(),
                gain = n[4],
                muted = n[5] != 0.0,
                name = strings[0] ?: "",
                filepath = strings[1] ?: "",
                clipType = ClipType.fromNative(n[6].toInt())
            )
        }

    override val umpEvents: UIntArray
        get() = JniBridge.uapmdClipFragmentGetUmpEvents(handle).let { raw -> UIntArray(raw.size) { raw[it].toUInt() } }

    override val umpTickTimestamps: LongArray get() = JniBridge.uapmdClipFragmentGetUmpTickTimestamps(handle)

    override val extensionState: Map<String, ByteArray>
        get() = (0 until JniBridge.uapmdClipFragmentExtensionStateCount(handle)).associate { i ->
            JniBridge.uapmdClipFragmentExtensionStateKey(handle, i) to
                JniBridge.uapmdClipFragmentExtensionStateData(handle, i)
        }

    override fun close() {
        if (owned) JniBridge.uapmdClipFragmentDestroy(handle)
    }
}

class AndroidTrackFragment internal constructor(internal val handle: Long) : TrackFragment {
    override val referenceId: String get() = JniBridge.uapmdTrackFragmentReferenceId(handle)
    override val volume: Double get() = JniBridge.uapmdTrackFragmentVolume(handle)
    override val muted: Boolean get() = JniBridge.uapmdTrackFragmentMuted(handle)
    override val solo: Boolean get() = JniBridge.uapmdTrackFragmentSolo(handle)
    override val graphType: String get() = JniBridge.uapmdTrackFragmentGraphType(handle)
    override val graphBytes: ByteArray get() = JniBridge.uapmdTrackFragmentGraphBytes(handle)

    override val clips: List<ClipFragment>
        get() = (0 until JniBridge.uapmdTrackFragmentClipCount(handle)).mapNotNull { i ->
            JniBridge.uapmdTrackFragmentGetClip(handle, i).takeIf { it != 0L }?.let { AndroidClipFragment(it, owned = false) }
        }

    override val plugins: List<TrackPluginFragment>
        get() = (0 until JniBridge.uapmdTrackFragmentPluginCount(handle)).mapNotNull { i ->
            val strings = arrayOfNulls<String>(4)
            val state = JniBridge.uapmdTrackFragmentGetPlugin(handle, i, strings) ?: return@mapNotNull null
            TrackPluginFragment(
                nodeId = strings[0] ?: "",
                pluginId = strings[1] ?: "",
                format = strings[2] ?: "",
                displayName = strings[3] ?: "",
                groupIndex = JniBridge.uapmdTrackFragmentPluginGroupIndex(handle, i),
                state = state
            )
        }

    override fun close() = JniBridge.uapmdTrackFragmentDestroy(handle)
}

// ─── Timeline history implementation ─────────────────────────────────────────

internal class AndroidTimelineHistory(private val handle: Long) {
    val undoEngine: UndoEngine get() = AndroidUndoEngine(JniBridge.uapmdTlUndoEngine(handle))
    val commands: ProjectCommands get() = AndroidProjectCommands(JniBridge.uapmdTlCommands(handle))
    val addresses: ProjectAddressBook get() = AndroidProjectAddressBook(JniBridge.uapmdTlAddresses(handle))

    fun <T> documentTransaction(block: () -> T): T {
        JniBridge.uapmdTlBeginDocumentTransaction(handle)
        try {
            return block()
        } finally {
            JniBridge.uapmdTlEndDocumentTransaction(handle)
        }
    }

    fun removeClip(trackIndex: Int, clipId: Int, origin: MutationOrigin) =
        JniBridge.uapmdTlRemoveClipWithOrigin(handle, trackIndex, clipId, origin.nativeValue)

    fun clearClipsFromTrack(trackIndex: Int, origin: MutationOrigin) =
        JniBridge.uapmdTlClearClipsFromTrack(handle, trackIndex, origin.nativeValue)

    fun isClipEnabled(trackIndex: Int, clipId: Int) = JniBridge.uapmdTlClipEnabled(handle, trackIndex, clipId)

    fun replaceMidiClipContent(
        trackIndex: Int, clipId: Int, umpEvents: UIntArray, tickTimestamps: LongArray, origin: MutationOrigin
    ) = JniBridge.uapmdTlReplaceMidiClipContent(
        handle, trackIndex, clipId,
        IntArray(umpEvents.size) { umpEvents[it].toInt() }.takeIf { it.isNotEmpty() },
        tickTimestamps.takeIf { it.isNotEmpty() },
        origin.nativeValue
    )

    fun replaceAudioClipContent(
        trackIndex: Int, clipId: Int, filepath: String,
        markers: List<ClipMarkerData>, warps: List<AudioWarpPointData>,
        masterMarkers: List<ClipMarkerData>, origin: MutationOrigin
    ): Boolean {
        val m = MarkerArrays(markers)
        val w = WarpArrays(warps)
        val mm = MarkerArrays(masterMarkers)
        return JniBridge.uapmdTlReplaceAudioClipContent(
            handle, trackIndex, clipId, filepath,
            m.strings, m.numbers, m.refTypes,
            w.strings, w.numbers, w.refTypes,
            mm.strings, mm.numbers, mm.refTypes,
            origin.nativeValue
        )
    }

    fun captureClipFragment(trackIndex: Int, clipId: Int): ClipFragment? =
        JniBridge.uapmdTlCaptureClipFragment(handle, trackIndex, clipId)
            .takeIf { it != 0L }?.let { AndroidClipFragment(it, owned = true) }

    fun attachClipFragment(trackIndex: Int, fragment: ClipFragment, idPolicy: ObjectIdPolicy): ClipAddResult {
        val strings = arrayOfNulls<String>(1)
        val r = JniBridge.uapmdTlAttachClipFragment(
            handle, trackIndex, (fragment as AndroidClipFragment).handle, idPolicy.nativeValue, strings
        )
        return ClipAddResult(r[0], r[1], r[2] != 0, strings[0])
    }

    fun captureTrackFragment(trackIndex: Int, callback: (TrackFragment?, String?) -> Unit) =
        JniBridge.uapmdTlCaptureTrackFragment(handle, trackIndex, TrackFragmentCallback(callback))

    fun attachTrackFragment(fragment: TrackFragment, options: TrackAttachOptions, callback: (Int, String?) -> Unit) =
        JniBridge.uapmdTlAttachTrackFragment(
            handle, (fragment as AndroidTrackFragment).handle,
            options.idPolicy.nativeValue, options.insertionIndex,
            options.includePlugins, options.includePluginState, options.includeClips,
            TrackMutationCallback(callback)
        )

    fun addEmptyTrack(origin: MutationOrigin, callback: (Int, String?) -> Unit) =
        JniBridge.uapmdTlAddEmptyTrackUndoable(handle, origin.nativeValue, TrackMutationCallback(callback))

    fun removeTrack(trackIndex: Int, origin: MutationOrigin, callback: (Int, String?) -> Unit) =
        JniBridge.uapmdTlRemoveTrackUndoable(handle, trackIndex, origin.nativeValue, TrackMutationCallback(callback))

    fun recordTrackAddition(trackIndex: Int, origin: MutationOrigin, callback: (Int, String?) -> Unit) =
        JniBridge.uapmdTlRecordTrackAddition(handle, trackIndex, origin.nativeValue, TrackMutationCallback(callback))

    fun setPluginState(instanceId: Int, state: ByteArray, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        JniBridge.uapmdTlSetPluginState(handle, instanceId, state.takeIf { it.isNotEmpty() }, origin.nativeValue, undoCb(completion))

    fun loadPluginPreset(instanceId: Int, presetIndex: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        JniBridge.uapmdTlLoadPluginPreset(handle, instanceId, presetIndex, origin.nativeValue, undoCb(completion))

    fun recordPluginInstanceAddition(instanceId: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        JniBridge.uapmdTlRecordPluginInstanceAddition(handle, instanceId, origin.nativeValue, undoCb(completion))

    fun removePluginInstance(instanceId: Int, origin: MutationOrigin, completion: ((UndoResult) -> Unit)?) =
        JniBridge.uapmdTlRemovePluginInstanceUndoable(handle, instanceId, origin.nativeValue, undoCb(completion))

    val hasPendingPluginMutations: Boolean get() = JniBridge.uapmdTlHasPendingPluginMutations(handle)
}
