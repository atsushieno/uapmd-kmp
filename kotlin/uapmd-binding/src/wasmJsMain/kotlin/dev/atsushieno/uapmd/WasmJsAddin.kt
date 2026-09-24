package dev.atsushieno.uapmd

class WasmJsAddinManager internal constructor(internal val handle: Int) : AddinManager {

    override fun initialize() = wasmMod.uapmdAddinManagerInitialize(handle)

    override fun setEnabled(packageId: String, addinId: String, enabled: Boolean): Boolean =
        withTwoCStringsKt(packageId, addinId) { p, a ->
            wasmMod.uapmdAddinManagerSetEnabled(handle, p, a, enabled)
        }

    override fun shutdown() = wasmMod.uapmdAddinManagerShutdown(handle)

    override fun registerCommandRegistry(registry: CommandRegistry) =
        wasmMod.uapmdAddinManagerRegisterCommandRegistry(handle, (registry as WasmJsCommandRegistry).handle)

    override fun registerProjectCommandRegistry(registry: CommandRegistry) =
        wasmMod.uapmdAddinManagerRegisterProjectCommandRegistry(handle, (registry as WasmJsCommandRegistry).handle)

    override fun registerClipCommandRegistry(registry: ClipCommandRegistry) =
        wasmMod.uapmdAddinManagerRegisterClipCommandRegistry(handle, (registry as WasmJsClipCommandRegistry).handle)

    override fun registerClipEditorRegistry(registry: ClipEditorRegistry) =
        wasmMod.uapmdAddinManagerRegisterClipEditorRegistry(handle, (registry as WasmJsClipEditorRegistry).handle)

    override fun registerStemSeparatorRegistry(registry: StemSeparatorRegistry) =
        wasmMod.uapmdAddinManagerRegisterStemSeparatorRegistry(handle, (registry as WasmJsStemSeparatorRegistry).handle)

    override fun registerPanelRegistry(registry: PanelRegistry) =
        wasmMod.uapmdAddinManagerRegisterPanelRegistry(handle, (registry as WasmJsPanelRegistry).handle)

    override fun registerAppModel(model: AppModel) =
        wasmMod.uapmdAddinManagerRegisterAppModel(handle, (model as WasmJsAppModel).handle)

    override val directories: List<String>
        get() = (0 until wasmMod.uapmdAddinManagerDirectoryCount(handle)).map { i ->
            readStringIndexed(handle, i) { h, idx, buf, size ->
                uapmdAddinManagerGetDirectory(h, idx, buf, size)
            }
        }

    override val addins: List<AddinInfo>
        get() = withWasmStruct(WasmOff.ADDIN_SIZE) { out ->
            (0 until wasmMod.uapmdAddinManagerAddinCount(handle)).mapNotNull { i ->
                if (!wasmMod.uapmdAddinManagerGetAddin(handle, i, out)) return@mapNotNull null
                AddinInfo(
                    packageId = wasmGetStr(out + WasmOff.ADDIN_PACKAGE_ID),
                    addinId = wasmGetStr(out + WasmOff.ADDIN_ADDIN_ID),
                    name = wasmGetStr(out + WasmOff.ADDIN_NAME),
                    path = wasmGetStr(out + WasmOff.ADDIN_PATH),
                    libraryPath = wasmGetStr(out + WasmOff.ADDIN_LIBRARY_PATH),
                    builtIn = wasmGetBool(out + WasmOff.ADDIN_BUILT_IN),
                    state = AddinState.fromNative(wasmGetI32(out + WasmOff.ADDIN_STATE)),
                    message = wasmGetStr(out + WasmOff.ADDIN_MESSAGE)
                )
            }
        }

    override val lastError: String
        get() = readString(handle) { h, buf, size -> uapmdAddinManagerLastError(h, buf, size) }

    override fun close() = wasmMod.uapmdAddinManagerDestroy(handle)
}

internal actual fun addinSupportsDynamicLoading(): Boolean = wasmMod.uapmdAddinSupportsDynamicLoading()

actual fun createAddinManager(): AddinManager =
    WasmJsAddinManager(wasmMod.uapmdAddinManagerCreate())

// ─── Host registries ─────────────────────────────────────────────────────────

private fun decodeCommandInfo(out: Int) = AddinCommandInfo(
    id = wasmGetStr(out + WasmOff.COMMAND_INFO_ID),
    title = wasmGetStr(out + WasmOff.COMMAND_INFO_TITLE),
    order = wasmGetI32(out + WasmOff.COMMAND_INFO_ORDER),
    enabled = wasmGetBool(out + WasmOff.COMMAND_INFO_ENABLED)
)

/** The target is passed by value, so it crosses as a pointer to a temporary. */
private fun <T> withWasmClipTarget(target: ClipCommandTarget, block: (Int) -> T): T =
    withWasmStruct(WasmOff.CLIP_TARGET_SIZE) { ptr ->
        wasmSetI32(ptr + WasmOff.CLIP_TARGET_TRACK_INDEX, target.trackIndex)
        wasmSetI32(ptr + WasmOff.CLIP_TARGET_CLIP_ID, target.clipId)
        wasmSetI8(ptr + WasmOff.CLIP_TARGET_MIDI_CLIP, if (target.isMidiClip) 1 else 0)
        wasmSetI8(ptr + WasmOff.CLIP_TARGET_MASTER_TRACK, if (target.isMasterTrack) 1 else 0)
        block(ptr)
    }

class WasmJsCommandRegistry internal constructor(internal val handle: Int) : CommandRegistry {
    override val commands: List<AddinCommandInfo>
        get() = withWasmStruct(WasmOff.COMMAND_INFO_SIZE) { out ->
            (0 until wasmMod.uapmdCommandRegistryCount(handle)).mapNotNull { i ->
                if (!wasmMod.uapmdCommandRegistryGet(handle, i, out)) null else decodeCommandInfo(out)
            }
        }

    override fun invoke(index: Int): Boolean = wasmMod.uapmdCommandRegistryInvoke(handle, index)

    override fun invokeById(id: String): Boolean =
        withCStringKt(id) { p -> wasmMod.uapmdCommandRegistryInvokeById(handle, p) }

    override fun close() = wasmMod.uapmdCommandRegistryDestroy(handle)
}

class WasmJsClipCommandRegistry internal constructor(internal val handle: Int) : ClipCommandRegistry {
    override val commands: List<AddinCommandInfo>
        get() = withWasmStruct(WasmOff.COMMAND_INFO_SIZE) { out ->
            (0 until wasmMod.uapmdClipCommandRegistryCount(handle)).mapNotNull { i ->
                if (!wasmMod.uapmdClipCommandRegistryGet(handle, i, out)) null else decodeCommandInfo(out)
            }
        }

    override fun appliesTo(index: Int, target: ClipCommandTarget): Boolean =
        withWasmClipTarget(target) { p -> wasmMod.uapmdClipCommandRegistryAppliesTo(handle, index, p) }

    override fun isEnabled(index: Int, target: ClipCommandTarget): Boolean =
        withWasmClipTarget(target) { p -> wasmMod.uapmdClipCommandRegistryEnabled(handle, index, p) }

    override fun invoke(index: Int, target: ClipCommandTarget): Boolean =
        withWasmClipTarget(target) { p -> wasmMod.uapmdClipCommandRegistryInvoke(handle, index, p) }

    override fun close() = wasmMod.uapmdClipCommandRegistryDestroy(handle)
}

class WasmJsClipEditorRegistry internal constructor(internal val handle: Int) : ClipEditorRegistry {
    override val editors: List<ClipEditorInfo>
        get() = withWasmStruct(WasmOff.CLIP_EDITOR_SIZE) { out ->
            (0 until wasmMod.uapmdClipEditorRegistryCount(handle)).mapNotNull { i ->
                if (!wasmMod.uapmdClipEditorRegistryGet(handle, i, out)) null
                else ClipEditorInfo(
                    wasmGetStr(out + WasmOff.CLIP_EDITOR_ID),
                    wasmGetStr(out + WasmOff.CLIP_EDITOR_NAME)
                )
            }
        }

    override fun close() = wasmMod.uapmdClipEditorRegistryDestroy(handle)
}

class WasmJsStemSeparatorRegistry internal constructor(internal val handle: Int) : StemSeparatorRegistry {
    override val separators: List<StemSeparatorInfo>
        get() = withWasmStruct(WasmOff.SEPARATOR_INFO_SIZE) { out ->
            (0 until wasmMod.uapmdStemSeparatorRegistryCount(handle)).mapNotNull { i ->
                if (!wasmMod.uapmdStemSeparatorRegistryGet(handle, i, out)) return@mapNotNull null
                val extensionCount = wasmGetI32(out + WasmOff.SEPARATOR_EXTENSION_COUNT)
                StemSeparatorInfo(
                    id = wasmGetStr(out + WasmOff.SEPARATOR_ID),
                    name = wasmGetStr(out + WasmOff.SEPARATOR_NAME),
                    modelFileLabel = wasmGetStr(out + WasmOff.SEPARATOR_MODEL_LABEL),
                    modelFileExtensions = (0 until extensionCount).map { e ->
                        readStringIndexed2(handle, i, e) { h, idx, ext, buf, size ->
                            uapmdStemSeparatorRegistryGetModelExtension(h, idx, ext, buf, size)
                        }
                    }
                )
            }
        }

    override fun importAudioFile(
        separatorId: String,
        filepath: String,
        outputDirectory: String,
        modelPath: String?,
        progress: ((Float, String) -> Boolean)?
    ): AudioImportResult {
        // Separation is a long synchronous run inside the wasm module, which
        // blocks the calling thread for its whole duration. Progress is
        // reported through a C function pointer that calls back into here.
        val progressPtr = progress?.let {
            val cbId = nextCallbackId()
            pendingImportProgress[cbId] = it
            makeCFunctionPtr(cbId, "uapmdDispatchImportProgress", "ifii")
        } ?: 0
        return withWasmStruct(WasmOff.IMPORT_RESULT_SIZE) { out ->
            withCStringKt(separatorId) { sep ->
                withCStringKt(filepath) { file ->
                    withCStringKt(outputDirectory) { dir ->
                        withCStringKt(modelPath) { model ->
                            wasmMod.uapmdImportAudioFile(out, handle, sep, file, dir, model, 0, progressPtr)
                        }
                    }
                }
            }
            val warningCount = wasmGetI32(out + WasmOff.IMPORT_WARNING_COUNT)
            val warningsPtr = wasmGetI32(out + WasmOff.IMPORT_WARNINGS)
            val stemCount = wasmGetI32(out + WasmOff.IMPORT_STEM_COUNT)
            val stemsPtr = wasmGetI32(out + WasmOff.IMPORT_STEMS)
            AudioImportResult(
                success = wasmGetBool(out + WasmOff.IMPORT_SUCCESS),
                canceled = wasmGetBool(out + WasmOff.IMPORT_CANCELED),
                error = wasmGetI32(out + WasmOff.IMPORT_ERROR).let { if (it != 0) wasmMod.utf8ToString(it) else null },
                warnings = if (warningsPtr == 0) emptyList() else
                    (0 until warningCount).map { i -> wasmGetStr(warningsPtr + i * 4) },
                stems = if (stemsPtr == 0) emptyList() else (0 until stemCount).map { i ->
                    val base = stemsPtr + i * WasmOff.STEM_SIZE
                    AudioStemImport(
                        wasmGetStr(base + WasmOff.STEM_NAME),
                        wasmGetStr(base + WasmOff.STEM_FILEPATH),
                        wasmGetStr(base + WasmOff.STEM_DISPLAY_NAME)
                    )
                }
            )
        }
    }

    override fun close() = wasmMod.uapmdStemSeparatorRegistryDestroy(handle)
}

internal actual fun createCommandRegistry(): CommandRegistry =
    WasmJsCommandRegistry(wasmMod.uapmdCommandRegistryCreate())

internal actual fun createClipCommandRegistry(): ClipCommandRegistry =
    WasmJsClipCommandRegistry(wasmMod.uapmdClipCommandRegistryCreate())

internal actual fun createClipEditorRegistry(): ClipEditorRegistry =
    WasmJsClipEditorRegistry(wasmMod.uapmdClipEditorRegistryCreate())

internal actual fun createStemSeparatorRegistry(): StemSeparatorRegistry =
    WasmJsStemSeparatorRegistry(wasmMod.uapmdStemSeparatorRegistryCreate())

class WasmJsPanelRegistry internal constructor(internal val handle: Int) : PanelRegistry {
    override fun update() = wasmMod.uapmdPanelRegistryUpdate(handle)
    override fun clearRetainedPanels() = wasmMod.uapmdPanelRegistryClearRetainedPanels(handle)
    override fun close() = wasmMod.uapmdPanelRegistryDestroy(handle)
}

internal actual fun createPanelRegistry(): PanelRegistry =
    WasmJsPanelRegistry(wasmMod.uapmdPanelRegistryCreate())

// ─── Augene2 ─────────────────────────────────────────────────────────────────

// uapmd_augene2_source_t: path@0, external_path@4, compile@8, sizeof 12;
// uapmd_augene2_track_mapping_t: key@0, track_index@4, sizeof 8 (emcc-verified).
private const val AUGENE2_SOURCE_PATH = 0
private const val AUGENE2_SOURCE_EXTERNAL_PATH = 4
private const val AUGENE2_SOURCE_COMPILE = 8
private const val AUGENE2_SOURCE_SIZE = 12
private const val AUGENE2_MAPPING_KEY = 0
private const val AUGENE2_MAPPING_TRACK_INDEX = 4
private const val AUGENE2_MAPPING_SIZE = 8

class WasmJsAugene2Integration internal constructor(internal val handle: Int) : Augene2Integration {
    override var isOpen: Boolean
        get() = wasmMod.uapmdAugene2IntegrationIsOpen(handle)
        set(value) = wasmMod.uapmdAugene2IntegrationSetOpen(handle, value)
    override val busy: Boolean get() = wasmMod.uapmdAugene2IntegrationBusy(handle)
    override val compiling: Boolean get() = wasmMod.uapmdAugene2IntegrationCompiling(handle)
    override val sources: List<Augene2IntegrationSource>
        get() = withWasmStruct(AUGENE2_SOURCE_SIZE) { out ->
            (0 until wasmMod.uapmdAugene2IntegrationSourceCount(handle)).mapNotNull { i ->
                if (!wasmMod.uapmdAugene2IntegrationGetSource(handle, i, out)) return@mapNotNull null
                Augene2IntegrationSource(
                    wasmGetStr(out + AUGENE2_SOURCE_PATH),
                    wasmGetStr(out + AUGENE2_SOURCE_EXTERNAL_PATH),
                    wasmGetBool(out + AUGENE2_SOURCE_COMPILE)
                )
            }
        }
    override val trackMappings: List<Augene2TrackMapping>
        get() = withWasmStruct(AUGENE2_MAPPING_SIZE) { out ->
            (0 until wasmMod.uapmdAugene2IntegrationTrackMappingCount(handle)).mapNotNull { i ->
                if (!wasmMod.uapmdAugene2IntegrationGetTrackMapping(handle, i, out)) return@mapNotNull null
                Augene2TrackMapping(wasmGetStr(out + AUGENE2_MAPPING_KEY), wasmGetI32(out + AUGENE2_MAPPING_TRACK_INDEX))
            }
        }
    override val status: String
        get() = readString(handle) { h, buf, size -> uapmdAugene2IntegrationStatus(h, buf, size) }
    override val diagnostics: List<String>
        get() = (0 until wasmMod.uapmdAugene2IntegrationDiagnosticCount(handle)).map { i ->
            readStringIndexed(handle, i) { h, idx, buf, size -> uapmdAugene2IntegrationGetDiagnostic(h, idx, buf, size) }
        }
    override var resourceFolder: String
        get() = readString(handle) { h, buf, size -> uapmdAugene2IntegrationResourceFolder(h, buf, size) }
        set(value) = withCStringKt(value) { p -> wasmMod.uapmdAugene2IntegrationSetResourceFolder(handle, p) }

    override fun importSources(compile: Boolean) = wasmMod.uapmdAugene2IntegrationImportSources(handle, compile)
    override fun relinkSource(path: String) =
        withCStringKt(path) { p -> wasmMod.uapmdAugene2IntegrationRelinkSource(handle, p) }
    override fun removeSource(path: String) =
        withCStringKt(path) { p -> wasmMod.uapmdAugene2IntegrationRemoveSource(handle, p) }
    override fun compile() = wasmMod.uapmdAugene2IntegrationCompile(handle)
    override fun close() = wasmMod.uapmdAugene2IntegrationRelease(handle)
}

internal actual fun augene2Available(): Boolean = wasmMod.uapmdAugene2Available()

internal actual fun augene2RegisterProjectService(timeline: TimelineFacade, panels: PanelRegistry) =
    wasmMod.uapmdAugene2RegisterProjectService(
        (timeline as WasmJsTimelineFacade).handle, (panels as WasmJsPanelRegistry).handle)

internal actual fun augene2Integration(): Augene2Integration? =
    wasmMod.uapmdAugene2Integration().takeIf { it != 0 }?.let { WasmJsAugene2Integration(it) }
