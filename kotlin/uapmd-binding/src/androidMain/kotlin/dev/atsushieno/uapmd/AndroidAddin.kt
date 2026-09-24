package dev.atsushieno.uapmd

class AndroidAddinManager internal constructor(
    internal val handle: Long
) : AddinManager {

    override fun initialize() = JniBridge.uapmdAddinManagerInitialize(handle)

    override fun setEnabled(packageId: String, addinId: String, enabled: Boolean): Boolean =
        JniBridge.uapmdAddinManagerSetEnabled(handle, packageId, addinId, enabled)

    override fun shutdown() = JniBridge.uapmdAddinManagerShutdown(handle)

    override fun registerCommandRegistry(registry: CommandRegistry) =
        JniBridge.uapmdAddinManagerRegisterCommandRegistry(handle, (registry as AndroidCommandRegistry).handle)

    override fun registerProjectCommandRegistry(registry: CommandRegistry) =
        JniBridge.uapmdAddinManagerRegisterProjectCommandRegistry(handle, (registry as AndroidCommandRegistry).handle)

    override fun registerClipCommandRegistry(registry: ClipCommandRegistry) =
        JniBridge.uapmdAddinManagerRegisterClipCommandRegistry(handle, (registry as AndroidClipCommandRegistry).handle)

    override fun registerClipEditorRegistry(registry: ClipEditorRegistry) =
        JniBridge.uapmdAddinManagerRegisterClipEditorRegistry(handle, (registry as AndroidClipEditorRegistry).handle)

    override fun registerStemSeparatorRegistry(registry: StemSeparatorRegistry) =
        JniBridge.uapmdAddinManagerRegisterStemSeparatorRegistry(handle, (registry as AndroidStemSeparatorRegistry).handle)

    override fun registerPanelRegistry(registry: PanelRegistry) =
        JniBridge.uapmdAddinManagerRegisterPanelRegistry(handle, (registry as AndroidPanelRegistry).handle)

    override fun registerAppModel(model: AppModel) =
        JniBridge.uapmdAddinManagerRegisterAppModel(handle, (model as AndroidAppModel).handle)

    override val directories: List<String>
        get() = (0 until JniBridge.uapmdAddinManagerDirectoryCount(handle)).map {
            JniBridge.uapmdAddinManagerGetDirectory(handle, it)
        }

    override val addins: List<AddinInfo>
        get() = (0 until JniBridge.uapmdAddinManagerAddinCount(handle)).mapNotNull { i ->
            val strings = arrayOfNulls<String>(6)
            val flags = JniBridge.uapmdAddinManagerGetAddin(handle, i, strings) ?: return@mapNotNull null
            AddinInfo(
                packageId = strings[0] ?: "",
                addinId = strings[1] ?: "",
                name = strings[2] ?: "",
                path = strings[3] ?: "",
                libraryPath = strings[4] ?: "",
                builtIn = flags[0] != 0,
                state = AddinState.fromNative(flags[1]),
                message = strings[5] ?: ""
            )
        }

    override val lastError: String get() = JniBridge.uapmdAddinManagerLastError(handle)

    override fun close() = JniBridge.uapmdAddinManagerDestroy(handle)
}

internal actual fun addinSupportsDynamicLoading(): Boolean = JniBridge.uapmdAddinSupportsDynamicLoading()

actual fun createAddinManager(): AddinManager {
    val handle = JniBridge.uapmdAddinManagerCreate()
    require(handle != 0L) { "uapmdAddinManagerCreate returned null" }
    return AndroidAddinManager(handle)
}

// ─── Host registries ─────────────────────────────────────────────────────────

class AndroidCommandRegistry internal constructor(internal val handle: Long) : CommandRegistry {
    override val commands: List<AddinCommandInfo>
        get() = (0 until JniBridge.uapmdCommandRegistryCount(handle)).mapNotNull { i ->
            val strings = arrayOfNulls<String>(2)
            val flags = JniBridge.uapmdCommandRegistryGet(handle, i, strings) ?: return@mapNotNull null
            AddinCommandInfo(strings[0] ?: "", strings[1] ?: "", flags[0], flags[1] != 0)
        }

    override fun invoke(index: Int): Boolean = JniBridge.uapmdCommandRegistryInvoke(handle, index)
    override fun invokeById(id: String): Boolean = JniBridge.uapmdCommandRegistryInvokeById(handle, id)
    override fun close() = JniBridge.uapmdCommandRegistryDestroy(handle)
}

class AndroidClipCommandRegistry internal constructor(internal val handle: Long) : ClipCommandRegistry {
    override val commands: List<AddinCommandInfo>
        get() = (0 until JniBridge.uapmdClipCommandRegistryCount(handle)).mapNotNull { i ->
            val strings = arrayOfNulls<String>(2)
            val flags = JniBridge.uapmdClipCommandRegistryGet(handle, i, strings) ?: return@mapNotNull null
            AddinCommandInfo(strings[0] ?: "", strings[1] ?: "", flags[0], flags[1] != 0)
        }

    override fun appliesTo(index: Int, target: ClipCommandTarget): Boolean =
        JniBridge.uapmdClipCommandRegistryAppliesTo(
            handle, index, target.trackIndex, target.clipId, target.isMidiClip, target.isMasterTrack)

    override fun isEnabled(index: Int, target: ClipCommandTarget): Boolean =
        JniBridge.uapmdClipCommandRegistryEnabled(
            handle, index, target.trackIndex, target.clipId, target.isMidiClip, target.isMasterTrack)

    override fun invoke(index: Int, target: ClipCommandTarget): Boolean =
        JniBridge.uapmdClipCommandRegistryInvoke(
            handle, index, target.trackIndex, target.clipId, target.isMidiClip, target.isMasterTrack)

    override fun close() = JniBridge.uapmdClipCommandRegistryDestroy(handle)
}

class AndroidClipEditorRegistry internal constructor(internal val handle: Long) : ClipEditorRegistry {
    override val editors: List<ClipEditorInfo>
        get() = (0 until JniBridge.uapmdClipEditorRegistryCount(handle)).mapNotNull { i ->
            val strings = arrayOfNulls<String>(2)
            if (!JniBridge.uapmdClipEditorRegistryGet(handle, i, strings)) return@mapNotNull null
            ClipEditorInfo(strings[0] ?: "", strings[1] ?: "")
        }

    override fun close() = JniBridge.uapmdClipEditorRegistryDestroy(handle)
}

class AndroidStemSeparatorRegistry internal constructor(internal val handle: Long) : StemSeparatorRegistry {
    override val separators: List<StemSeparatorInfo>
        get() = (0 until JniBridge.uapmdStemSeparatorRegistryCount(handle)).mapNotNull { i ->
            val strings = arrayOfNulls<String>(3)
            val extensionCount = JniBridge.uapmdStemSeparatorRegistryGet(handle, i, strings)
            if (extensionCount < 0) return@mapNotNull null
            StemSeparatorInfo(
                id = strings[0] ?: "",
                name = strings[1] ?: "",
                modelFileLabel = strings[2] ?: "",
                modelFileExtensions = (0 until extensionCount).map { e ->
                    JniBridge.uapmdStemSeparatorRegistryGetModelExtension(handle, i, e)
                }
            )
        }

    override fun importAudioFile(
        separatorId: String,
        filepath: String,
        outputDirectory: String,
        modelPath: String?,
        progress: ((Float, String) -> Boolean)?
    ): AudioImportResult {
        // The counts are not knowable before the import runs, and running it
        // twice is out of the question, so the first (and only) call gets a
        // buffer generous enough for any plausible result and the counts come
        // back alongside for the actual slicing.
        val counts = IntArray(2)
        val strings = arrayOfNulls<String>(1 + MAX_WARNINGS + MAX_STEMS * 3)
        val flags = JniBridge.uapmdImportAudioFile(
            handle, separatorId, filepath, outputDirectory, modelPath, progress, strings, counts
        )
        val warningCount = counts[0].coerceAtMost(MAX_WARNINGS)
        val stemCount = counts[1].coerceAtMost(MAX_STEMS)
        val stemBase = 1 + warningCount
        return AudioImportResult(
            success = flags[0] != 0,
            canceled = flags[1] != 0,
            error = strings[0]?.ifEmpty { null },
            warnings = (0 until warningCount).map { strings[1 + it] ?: "" },
            stems = (0 until stemCount).map { i ->
                AudioStemImport(
                    strings[stemBase + i * 3] ?: "",
                    strings[stemBase + i * 3 + 1] ?: "",
                    strings[stemBase + i * 3 + 2] ?: ""
                )
            }
        )
    }

    override fun close() = JniBridge.uapmdStemSeparatorRegistryDestroy(handle)

    private companion object {
        // Separators produce a handful of stems (Demucs: 4) and warn rarely.
        const val MAX_WARNINGS = 64
        const val MAX_STEMS = 64
    }
}

internal actual fun createCommandRegistry(): CommandRegistry =
    AndroidCommandRegistry(JniBridge.uapmdCommandRegistryCreate())

internal actual fun createClipCommandRegistry(): ClipCommandRegistry =
    AndroidClipCommandRegistry(JniBridge.uapmdClipCommandRegistryCreate())

internal actual fun createClipEditorRegistry(): ClipEditorRegistry =
    AndroidClipEditorRegistry(JniBridge.uapmdClipEditorRegistryCreate())

internal actual fun createStemSeparatorRegistry(): StemSeparatorRegistry =
    AndroidStemSeparatorRegistry(JniBridge.uapmdStemSeparatorRegistryCreate())

class AndroidPanelRegistry internal constructor(internal val handle: Long) : PanelRegistry {
    override fun update() = JniBridge.uapmdPanelRegistryUpdate(handle)
    override fun clearRetainedPanels() = JniBridge.uapmdPanelRegistryClearRetainedPanels(handle)
    override fun close() = JniBridge.uapmdPanelRegistryDestroy(handle)
}

internal actual fun createPanelRegistry(): PanelRegistry {
    val handle = JniBridge.uapmdPanelRegistryCreate()
    require(handle != 0L) { "uapmdPanelRegistryCreate returned null" }
    return AndroidPanelRegistry(handle)
}

// ─── Augene2 ─────────────────────────────────────────────────────────────────

class AndroidAugene2Integration internal constructor(internal val handle: Long) : Augene2Integration {
    override var isOpen: Boolean
        get() = JniBridge.uapmdAugene2IntegrationIsOpen(handle)
        set(value) = JniBridge.uapmdAugene2IntegrationSetOpen(handle, value)
    override val busy: Boolean get() = JniBridge.uapmdAugene2IntegrationBusy(handle)
    override val compiling: Boolean get() = JniBridge.uapmdAugene2IntegrationCompiling(handle)
    override val sources: List<Augene2IntegrationSource>
        get() = (0 until JniBridge.uapmdAugene2IntegrationSourceCount(handle)).mapNotNull { i ->
            val strings = arrayOfNulls<String>(2)
            val flags = JniBridge.uapmdAugene2IntegrationGetSource(handle, i, strings) ?: return@mapNotNull null
            Augene2IntegrationSource(strings[0] ?: "", strings[1] ?: "", flags[0] != 0)
        }
    override val trackMappings: List<Augene2TrackMapping>
        get() = (0 until JniBridge.uapmdAugene2IntegrationTrackMappingCount(handle)).mapNotNull { i ->
            val strings = arrayOfNulls<String>(1)
            val values = JniBridge.uapmdAugene2IntegrationGetTrackMapping(handle, i, strings) ?: return@mapNotNull null
            Augene2TrackMapping(strings[0] ?: "", values[0])
        }
    override val status: String get() = JniBridge.uapmdAugene2IntegrationStatus(handle)
    override val diagnostics: List<String>
        get() = (0 until JniBridge.uapmdAugene2IntegrationDiagnosticCount(handle)).map {
            JniBridge.uapmdAugene2IntegrationGetDiagnostic(handle, it)
        }
    override var resourceFolder: String
        get() = JniBridge.uapmdAugene2IntegrationResourceFolder(handle)
        set(value) = JniBridge.uapmdAugene2IntegrationSetResourceFolder(handle, value)

    override fun importSources(compile: Boolean) = JniBridge.uapmdAugene2IntegrationImportSources(handle, compile)
    override fun relinkSource(path: String) = JniBridge.uapmdAugene2IntegrationRelinkSource(handle, path)
    override fun removeSource(path: String) = JniBridge.uapmdAugene2IntegrationRemoveSource(handle, path)
    override fun compile() = JniBridge.uapmdAugene2IntegrationCompile(handle)
    override fun close() = JniBridge.uapmdAugene2IntegrationRelease(handle)
}

internal actual fun augene2Available(): Boolean = JniBridge.uapmdAugene2Available()

internal actual fun augene2RegisterProjectService(timeline: TimelineFacade, panels: PanelRegistry) =
    JniBridge.uapmdAugene2RegisterProjectService(
        (timeline as AndroidTimelineFacade).handle, (panels as AndroidPanelRegistry).handle)

internal actual fun augene2Integration(): Augene2Integration? =
    JniBridge.uapmdAugene2Integration().takeIf { it != 0L }?.let { AndroidAugene2Integration(it) }
