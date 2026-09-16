package dev.atsushieno.uapmd

enum class AddinState(val nativeValue: Int) {
    Inactive(0), Initializing(1), Active(2), CleaningUp(3), Failed(4);

    companion object {
        fun fromNative(v: Int): AddinState = entries.firstOrNull { it.nativeValue == v } ?: Inactive
    }
}

data class AddinInfo(
    val packageId: String,
    val addinId: String,
    val name: String,
    /** Extension point path this addin attaches to. */
    val path: String,
    /** Empty for built-in addins. */
    val libraryPath: String,
    val builtIn: Boolean,
    val state: AddinState,
    /** Failure detail, or empty. */
    val message: String
)

/**
 * Which call publishes which extension point, since they are split between the
 * engine and the registries here:
 *
 * | Extension point | Published by | Addins |
 * |---|---|---|
 * | `/uapmd/engine/v1`, `/uapmd/audio-graph/provider/v1` | [SequencerEngine.registerAddinExtensionPoints] | ARA, graph providers |
 * | `/uapmd/app/command/v1` | [registerCommandRegistry] | MIR analysis, Basic Pitch, DrumScript |
 * | `/uapmd/app/clip-command/v1` | [registerClipCommandRegistry] | their clip-scoped counterparts |
 * | `/uapmd/app/timeline/clip-editor/v1` | [registerClipEditorRegistry] | timeline clip editors |
 * | `/uapmd/audio-import/stem-separator/v1` | [registerStemSeparatorRegistry] | Demucs, BS-Roformer |
 *
 * Any other extension point still cannot be published from Kotlin: it would be
 * a C++ interface pointer with no meaningful representation here.
 */
interface AddinManager : AutoCloseable {
    fun initialize()
    fun setEnabled(packageId: String, addinId: String, enabled: Boolean): Boolean
    fun shutdown()

    /**
     * Publish a host registry. Each must be registered before [initialize], and
     * must outlive [shutdown]: whatever an addin put in one would otherwise
     * dangle.
     */
    fun registerCommandRegistry(registry: CommandRegistry)
    fun registerClipCommandRegistry(registry: ClipCommandRegistry)
    fun registerClipEditorRegistry(registry: ClipEditorRegistry)
    fun registerStemSeparatorRegistry(registry: StemSeparatorRegistry)

    /** Directories scanned for installed addin packages. */
    val directories: List<String>
    val addins: List<AddinInfo>
    val lastError: String

    companion object {
        /**
         * False on platforms without dynamic loading (Wasm, iOS), where only
         * built-in addins are available.
         */
        val supportsDynamicLoading: Boolean get() = addinSupportsDynamicLoading()
    }
}

internal expect fun addinSupportsDynamicLoading(): Boolean

// ─── Host registries ─────────────────────────────────────────────────────────
//
// Every index below addresses into a registry's current contents, which change
// when an addin is enabled or disabled. Treat an index as valid only until the
// next AddinManager.setEnabled() or initialize() -- in practice, for one UI
// update.

/** One command an addin contributed. */
data class AddinCommandInfo(
    val id: String,
    val title: String,
    /** The addin's own placement hint; the host decides where it goes. */
    val order: Int,
    /** False: present it, greyed out. */
    val enabled: Boolean
)

/** Application-wide commands contributed by addins. */
interface CommandRegistry : AutoCloseable {
    val commands: List<AddinCommandInfo>
    fun invoke(index: Int): Boolean
    /** Survives the list changing underneath, which an index does not. */
    fun invokeById(id: String): Boolean

    companion object {
        fun create(): CommandRegistry = createCommandRegistry()
    }
}

/**
 * The clip a clip-scoped command is being offered for. The identifiers are the
 * ones the rest of this API takes, so a command resolves the clip through the
 * engine rather than through anything carried here.
 */
data class ClipCommandTarget(
    val trackIndex: Int,
    val clipId: Int,
    val isMidiClip: Boolean = false,
    val isMasterTrack: Boolean = false
)

/**
 * Commands scoped to one clip. Offer them wherever a clip is presented, asking
 * [appliesTo] first: false hides the command for that clip entirely, while true
 * with [isEnabled] false means show it greyed out.
 *
 * [AddinCommandInfo.enabled] is always true on entries from here; a clip
 * command's enablement depends on the target, so [isEnabled] is the real answer.
 */
interface ClipCommandRegistry : AutoCloseable {
    val commands: List<AddinCommandInfo>
    fun appliesTo(index: Int, target: ClipCommandTarget): Boolean
    fun isEnabled(index: Int, target: ClipCommandTarget): Boolean
    fun invoke(index: Int, target: ClipCommandTarget): Boolean

    companion object {
        fun create(): ClipCommandRegistry = createClipCommandRegistry()
    }
}

data class ClipEditorInfo(val id: String, val name: String)

/**
 * Timeline clip editors contributed by addins.
 *
 * Only the registry is bound, not the editors themselves: an editor draws
 * itself by being called inside the host's own immediate-mode UI loop, which a
 * Compose host does not have and cannot give it. Publishing the extension point
 * still matters, because an addin that asks for it fails to load when it is
 * missing, and enumerating lets a host report what it is declining to show.
 */
interface ClipEditorRegistry : AutoCloseable {
    val editors: List<ClipEditorInfo>

    companion object {
        fun create(): ClipEditorRegistry = createClipEditorRegistry()
    }
}

/**
 * A stem separation backend contributed by an addin.
 *
 * [modelFileExtensions] is empty when the separator needs no model file; when
 * it is not, the host picks a matching file and passes it to
 * [StemSeparatorRegistry.importAudioFile].
 */
data class StemSeparatorInfo(
    val id: String,
    val name: String,
    val modelFileLabel: String,
    val modelFileExtensions: List<String>
) {
    val requiresModelFile: Boolean get() = modelFileExtensions.isNotEmpty()
}

/** One separated stem, ready to become a clip. */
data class AudioStemImport(
    val stemName: String,
    val filepath: String,
    val clipDisplayName: String
)

data class AudioImportResult(
    val success: Boolean,
    val canceled: Boolean,
    val error: String?,
    val warnings: List<String>,
    val stems: List<AudioStemImport>
)

/** Stem separation backends contributed by addins. */
interface StemSeparatorRegistry : AutoCloseable {
    val separators: List<StemSeparatorInfo>

    /**
     * Separates [filepath] into stems and writes them under [outputDirectory].
     *
     * Blocking, and slow by nature -- it runs a neural model over the whole
     * file -- so call it off the UI thread. It holds a lease on the separator
     * for the whole run, so disabling the contributing addin meanwhile cancels
     * the run rather than unloading the code underneath it.
     *
     * [separatorId] names one of [separators]; an unknown or withdrawn one
     * fails rather than silently picking another. [modelPath] is the file the
     * separator asked for, and may be null when it asked for none. [progress]
     * is called from the worker thread; return false from it to cancel.
     */
    fun importAudioFile(
        separatorId: String,
        filepath: String,
        outputDirectory: String,
        modelPath: String? = null,
        progress: ((progress: Float, message: String) -> Boolean)? = null
    ): AudioImportResult

    companion object {
        fun create(): StemSeparatorRegistry = createStemSeparatorRegistry()
    }
}

internal expect fun createCommandRegistry(): CommandRegistry
internal expect fun createClipCommandRegistry(): ClipCommandRegistry
internal expect fun createClipEditorRegistry(): ClipEditorRegistry
internal expect fun createStemSeparatorRegistry(): StemSeparatorRegistry
