package dev.atsushieno.uapmd

// ─── Value types ─────────────────────────────────────────────────────────────

/**
 * Who is making a change. [User] and [Remote] edits enter the undo history;
 * [Load], [UndoRedo] and [Internal] changes are applied without being recorded.
 */
enum class MutationOrigin(val nativeValue: Int) {
    User(0), UndoRedo(1), Load(2), Remote(3), Internal(4);

    companion object {
        fun fromNative(v: Int): MutationOrigin = entries.firstOrNull { it.nativeValue == v } ?: User
    }
}

enum class UndoStatus(val nativeValue: Int) {
    Succeeded(0), Busy(1), NothingToUndo(2), NothingToRedo(3), Failed(4), Cancelled(5), Stopped(6);

    companion object {
        fun fromNative(v: Int): UndoStatus = entries.firstOrNull { it.nativeValue == v } ?: Failed
    }
}

/**
 * Whether an object being attached to the document keeps the identifiers it
 * already carries ([Restore], which is what undoing a delete requires) or is
 * given fresh ones ([Mint], which is what paste and duplicate require).
 */
enum class ObjectIdPolicy(val nativeValue: Int) {
    Restore(0), Mint(1);

    companion object {
        fun fromNative(v: Int): ObjectIdPolicy = entries.firstOrNull { it.nativeValue == v } ?: Mint
    }
}

data class UndoResult(val status: UndoStatus, val error: String?) {
    val succeeded: Boolean get() = status == UndoStatus.Succeeded

    companion object {
        val Success = UndoResult(UndoStatus.Succeeded, null)
    }
}

data class UndoState(
    val busy: Boolean,
    val compoundOpen: Boolean,
    val gestureOpen: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val dirty: Boolean,
    val compoundDescription: String,
    val undoDescription: String,
    val redoDescription: String,
    val historySizeInBytes: Long,
    val maximumHistorySizeInBytes: Long,
    val currentStateId: Long,
    val savedStateId: Long
)

enum class TimeReferenceType(val nativeValue: Int) {
    ContainerStart(0), ContainerEnd(1), Point(2);

    companion object {
        fun fromNative(v: Int): TimeReferenceType = entries.firstOrNull { it.nativeValue == v } ?: ContainerStart
    }
}

enum class WarpReferenceType(val nativeValue: Int) {
    Manual(0), ClipStart(1), ClipEnd(2), ClipMarker(3), MasterMarker(4);

    companion object {
        fun fromNative(v: Int): WarpReferenceType = entries.firstOrNull { it.nativeValue == v } ?: Manual
    }
}

data class TimeReference(
    val type: TimeReferenceType = TimeReferenceType.ContainerStart,
    val referenceId: String = "",
    /** Seconds. */
    val offset: Double = 0.0
)

data class ClipMarkerData(
    val markerId: String,
    val clipPositionOffset: Double,
    val referenceType: WarpReferenceType = WarpReferenceType.Manual,
    val referenceClipId: String = "",
    val referenceMarkerId: String = "",
    val name: String = ""
)

data class AudioWarpPointData(
    val clipPositionOffset: Double,
    val speedRatio: Double,
    val referenceType: WarpReferenceType = WarpReferenceType.Manual,
    val referenceClipId: String = "",
    val referenceMarkerId: String = ""
)

/** Stable document identity of one clip. */
data class ClipAddress(val trackReferenceId: String, val clipReferenceId: String)

/**
 * Stable document identity of one plug-in node. A runtime instance id is not
 * usable in history: removing and restoring a plug-in produces a new one.
 */
data class PluginAddress(val trackReferenceId: String, val nodeId: String)

/** Which parts of a captured track are applied when it is attached. */
data class TrackAttachOptions(
    val idPolicy: ObjectIdPolicy = ObjectIdPolicy.Mint,
    /** Negative appends. */
    val insertionIndex: Int = -1,
    val includePlugins: Boolean = true,
    /** Skipping state also skips the slowest part of attaching. */
    val includePluginState: Boolean = true,
    val includeClips: Boolean = true
)

data class TrackPluginFragment(
    val nodeId: String,
    val pluginId: String,
    val format: String,
    val displayName: String,
    val groupIndex: Int,
    val state: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is TrackPluginFragment &&
            nodeId == other.nodeId && pluginId == other.pluginId && format == other.format &&
            displayName == other.displayName && groupIndex == other.groupIndex &&
            state.contentEquals(other.state))

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + pluginId.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + groupIndex
        result = 31 * result + state.contentHashCode()
        return result
    }
}

// ─── Interfaces ──────────────────────────────────────────────────────────────

/**
 * Thread-affine asynchronous project history. Its methods are called on the
 * model thread; completions may arrive on any thread.
 */
interface UndoEngine {
    val state: UndoState

    fun undo(completion: ((UndoResult) -> Unit)? = null)
    fun redo(completion: ((UndoResult) -> Unit)? = null)

    /**
     * Opens one named history step. Operations performed while it is open are
     * applied immediately but enter history only when [endCompound] succeeds.
     * Nested compounds are deliberately rejected.
     */
    fun beginCompound(description: String, origin: MutationOrigin = MutationOrigin.User): UndoResult
    fun endCompound(completion: ((UndoResult) -> Unit)? = null)
    /** Reverts every successfully performed child in reverse order. */
    fun cancelCompound(completion: ((UndoResult) -> Unit)? = null)

    /**
     * A gesture is a named compound scope that coalesces adjacent compatible
     * operations: intermediate values are applied, but history retains only the
     * initial and final ones.
     */
    fun beginGesture(description: String, origin: MutationOrigin = MutationOrigin.User): UndoResult
    fun endGesture(completion: ((UndoResult) -> Unit)? = null)
    fun cancelGesture(completion: ((UndoResult) -> Unit)? = null)

    /** Fails while an operation is pending or a compound step is open. */
    fun clear(markCurrentStateSaved: Boolean = true): Boolean
    fun markSaved(): Boolean
    fun markStateSaved(stateId: Long): Boolean
    fun setMaximumHistorySizeInBytes(bytes: Long): Boolean

    /** Rejects new work and completes pending notification as Cancelled. */
    fun shutdown()
}

/** Groups several commands into one named history step. */
interface CommandManager {
    val state: UndoState
    val history: UndoEngine

    fun undo(completion: ((UndoResult) -> Unit)? = null)
    fun redo(completion: ((UndoResult) -> Unit)? = null)

    fun beginStep(description: String, origin: MutationOrigin = MutationOrigin.User): UndoResult
    fun endStep(completion: ((UndoResult) -> Unit)? = null)
    fun cancelStep(completion: ((UndoResult) -> Unit)? = null)

    fun beginGesture(description: String, origin: MutationOrigin = MutationOrigin.User): UndoResult
    fun endGesture(completion: ((UndoResult) -> Unit)? = null)
    fun cancelGesture(completion: ((UndoResult) -> Unit)? = null)

    fun shutdown()
}

/**
 * The undoable edits a project supports. Use [TimelineFacade] for reading the
 * document and for changes that are not user edits; use this for anything a
 * user could undo.
 *
 * Each function returns false when the target does not exist or the change was
 * rejected; setting the value already in place succeeds without creating a
 * history step.
 */
interface ProjectCommands {
    val history: CommandManager

    fun setClipEnabled(trackIndex: Int, clipId: Int, enabled: Boolean, origin: MutationOrigin = MutationOrigin.User): Boolean
    fun setClipAnchor(trackIndex: Int, clipId: Int, anchor: TimeReference, origin: MutationOrigin = MutationOrigin.User): Boolean
    fun setClipGain(trackIndex: Int, clipId: Int, gain: Double, origin: MutationOrigin = MutationOrigin.User): Boolean
    fun setClipMuted(trackIndex: Int, clipId: Int, muted: Boolean, origin: MutationOrigin = MutationOrigin.User): Boolean
    fun resizeClip(trackIndex: Int, clipId: Int, newDurationSamples: Long, origin: MutationOrigin = MutationOrigin.User): Boolean
    fun setClipName(trackIndex: Int, clipId: Int, name: String, origin: MutationOrigin = MutationOrigin.User): Boolean
    fun setClipFilepath(trackIndex: Int, clipId: Int, filepath: String, origin: MutationOrigin = MutationOrigin.User): Boolean
    fun setClipNeedsFileSave(trackIndex: Int, clipId: Int, needsSave: Boolean, origin: MutationOrigin = MutationOrigin.User): Boolean
    fun setClipMarkers(trackIndex: Int, clipId: Int, markers: List<ClipMarkerData>, origin: MutationOrigin = MutationOrigin.User): Boolean
    fun setClipAudioWarps(trackIndex: Int, clipId: Int, warps: List<AudioWarpPointData>, origin: MutationOrigin = MutationOrigin.User): Boolean

    fun setTrackGain(trackIndex: Int, gain: Double, origin: MutationOrigin = MutationOrigin.User): Boolean
    fun setTrackMuted(trackIndex: Int, muted: Boolean, origin: MutationOrigin = MutationOrigin.User): Boolean
    fun setTrackSolo(trackIndex: Int, solo: Boolean, origin: MutationOrigin = MutationOrigin.User): Boolean
    fun setTrackBypassed(trackIndex: Int, bypassed: Boolean, origin: MutationOrigin = MutationOrigin.User): Boolean
    fun setTrackFreezePolicyEnabled(trackIndex: Int, enabled: Boolean, origin: MutationOrigin = MutationOrigin.User): Boolean

    fun setPluginBypassed(instanceId: Int, bypassed: Boolean, origin: MutationOrigin = MutationOrigin.User): Boolean
    fun setPluginParameterValue(instanceId: Int, parameterIndex: Int, value: Double, origin: MutationOrigin = MutationOrigin.User): Boolean
    /**
     * Per-note edits share the plug-in's identity and history, so changing the
     * selected key, channel or group does not silently bypass undo.
     * [contextType] carries `remidy::PerNoteControllerContextTypes` bit flags.
     */
    fun setPluginPerNoteControllerValue(
        instanceId: Int,
        contextType: Int,
        note: Int,
        channel: Int,
        group: Int,
        extra: Int,
        parameterIndex: Int,
        value: Double,
        origin: MutationOrigin = MutationOrigin.User
    ): Boolean
    fun setPluginGroup(instanceId: Int, group: UByte, origin: MutationOrigin = MutationOrigin.User): Boolean

    /**
     * The caller is responsible for validating marker identity and reference
     * cycles before submitting.
     */
    fun setMasterTrackMarkers(markers: List<ClipMarkerData>, origin: MutationOrigin = MutationOrigin.User): Boolean
}

/**
 * Translation between the persistent identities a history step carries and the
 * runtime indexes the engine works with. Not thread safe; call on the model
 * thread only.
 */
interface ProjectAddressBook {
    fun timelineTrack(trackReferenceId: String): TimelineTrack?
    fun sequencerTrack(trackReferenceId: String): SequencerTrack?
    /** Returns [MASTER_TRACK_INDEX] for the master track, -1 when unknown. */
    fun trackIndex(trackReferenceId: String): Int
    fun clipId(address: ClipAddress): Int
    fun pluginInstanceId(address: PluginAddress): Int

    fun trackReferenceId(trackIndex: Int): String?
    fun clipAddress(trackIndex: Int, clipId: Int): ClipAddress?
    fun pluginAddress(instanceId: Int): PluginAddress?

    companion object {
        /** Mirrors `UAPMD_MASTER_TRACK_INDEX`. */
        const val MASTER_TRACK_INDEX: Int = Int.MIN_VALUE
    }
}

/**
 * A clip detached from the document: the payload shared by an undo entry and a
 * clipboard entry. Fragments obtained from [TimelineFacade.captureClipFragment]
 * must be closed; those borrowed from a [TrackFragment] need not be.
 */
interface ClipFragment : AutoCloseable {
    val isMidi: Boolean
    val clip: ClipData
    val umpEvents: UIntArray
    val umpTickTimestamps: LongArray
    /** Opaque per-feature state covering this clip, keyed by extension id. */
    val extensionState: Map<String, ByteArray>
}

/**
 * A track detached from the document. Unlike a clip, a track owns live plug-in
 * instances, so both capturing and attaching one are asynchronous.
 */
interface TrackFragment : AutoCloseable {
    val referenceId: String
    val volume: Double
    val muted: Boolean
    val solo: Boolean
    val graphType: String
    val graphBytes: ByteArray
    val clips: List<ClipFragment>
    val plugins: List<TrackPluginFragment>
}
