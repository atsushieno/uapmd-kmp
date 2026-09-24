@file:Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")

package dev.atsushieno.uapmd

class JsAddinManager internal constructor(internal val handle: Int) : AddinManager {

    override fun initialize() { jsMod._uapmd_addin_manager_initialize(handle) }

    override fun setEnabled(packageId: String, addinId: String, enabled: Boolean): Boolean =
        withJsTwoCStrings(packageId, addinId) { p, a ->
            jsMod._uapmd_addin_manager_set_enabled(handle, p, a, enabled) as Boolean
        }

    override fun shutdown() { jsMod._uapmd_addin_manager_shutdown(handle) }

    override fun registerCommandRegistry(registry: CommandRegistry) {
        jsMod._uapmd_addin_manager_register_command_registry(handle, (registry as JsCommandRegistry).handle)
    }

    override fun registerProjectCommandRegistry(registry: CommandRegistry) {
        jsMod._uapmd_addin_manager_register_project_command_registry(handle, (registry as JsCommandRegistry).handle)
    }

    override fun registerClipCommandRegistry(registry: ClipCommandRegistry) {
        jsMod._uapmd_addin_manager_register_clip_command_registry(handle, (registry as JsClipCommandRegistry).handle)
    }

    override fun registerClipEditorRegistry(registry: ClipEditorRegistry) {
        jsMod._uapmd_addin_manager_register_clip_editor_registry(handle, (registry as JsClipEditorRegistry).handle)
    }

    override fun registerStemSeparatorRegistry(registry: StemSeparatorRegistry) {
        jsMod._uapmd_addin_manager_register_stem_separator_registry(handle, (registry as JsStemSeparatorRegistry).handle)
    }

    override fun registerPanelRegistry(registry: PanelRegistry) {
        jsMod._uapmd_addin_manager_register_panel_registry(handle, (registry as JsPanelRegistry).handle)
    }

    override fun registerAppModel(model: AppModel) {
        jsMod._uapmd_addin_manager_register_app_model(handle, (model as JsAppModel).handle)
    }

    override val directories: List<String>
        get() = (0 until (jsMod._uapmd_addin_manager_directory_count(handle) as Int)).map { i ->
            readJsStringIndexed(handle, i) { h, idx, buf, size ->
                jsMod._uapmd_addin_manager_get_directory(h, idx, buf, size) as Int
            }
        }

    override val addins: List<AddinInfo>
        get() = withWasmMem(JsAddinOffsets.SIZE) { out ->
            (0 until (jsMod._uapmd_addin_manager_addin_count(handle) as Int)).mapNotNull { i ->
                if (!(jsMod._uapmd_addin_manager_get_addin(handle, i, out) as Boolean)) return@mapNotNull null
                AddinInfo(
                    packageId = jsGetStr(out + JsAddinOffsets.PACKAGE_ID),
                    addinId = jsGetStr(out + JsAddinOffsets.ADDIN_ID),
                    name = jsGetStr(out + JsAddinOffsets.NAME),
                    path = jsGetStr(out + JsAddinOffsets.PATH),
                    libraryPath = jsGetStr(out + JsAddinOffsets.LIBRARY_PATH),
                    builtIn = jsGetBool(out + JsAddinOffsets.BUILT_IN),
                    state = AddinState.fromNative(jsGetI32(out + JsAddinOffsets.STATE)),
                    message = jsGetStr(out + JsAddinOffsets.MESSAGE)
                )
            }
        }

    override val lastError: String
        get() = readJsString(handle) { h, buf, size -> jsMod._uapmd_addin_manager_last_error(h, buf, size) as Int }

    override fun close() { jsMod._uapmd_addin_manager_destroy(handle) }
}

internal actual fun addinSupportsDynamicLoading(): Boolean =
    jsMod._uapmd_addin_supports_dynamic_loading() as Boolean

actual fun createAddinManager(): AddinManager =
    JsAddinManager(jsMod._uapmd_addin_manager_create() as Int)

// ─── Host registries ─────────────────────────────────────────────────────────

private fun decodeJsCommandInfo(out: Int) = AddinCommandInfo(
    id = jsGetStr(out + Off.COMMAND_INFO_ID),
    title = jsGetStr(out + Off.COMMAND_INFO_TITLE),
    order = jsGetI32(out + Off.COMMAND_INFO_ORDER),
    enabled = jsGetBool(out + Off.COMMAND_INFO_ENABLED)
)

/** The target is passed by value, so it crosses as a pointer to a temporary. */
private fun <T> withJsClipTarget(target: ClipCommandTarget, block: (Int) -> T): T =
    withWasmMem(Off.CLIP_TARGET_SIZE) { ptr ->
        jsSetI32(ptr + Off.CLIP_TARGET_TRACK_INDEX, target.trackIndex)
        jsSetI32(ptr + Off.CLIP_TARGET_CLIP_ID, target.clipId)
        jsSetI8(ptr + Off.CLIP_TARGET_MIDI_CLIP, if (target.isMidiClip) 1 else 0)
        jsSetI8(ptr + Off.CLIP_TARGET_MASTER_TRACK, if (target.isMasterTrack) 1 else 0)
        block(ptr)
    }

class JsCommandRegistry internal constructor(internal val handle: Int) : CommandRegistry {
    override val commands: List<AddinCommandInfo>
        get() = withWasmMem(Off.COMMAND_INFO_SIZE) { out ->
            (0 until (jsMod._uapmd_command_registry_count(handle) as Int)).mapNotNull { i ->
                if (!(jsMod._uapmd_command_registry_get(handle, i, out) as Boolean)) null
                else decodeJsCommandInfo(out)
            }
        }

    override fun invoke(index: Int): Boolean =
        jsMod._uapmd_command_registry_invoke(handle, index) as Boolean

    override fun invokeById(id: String): Boolean =
        withJsCString(id) { p -> jsMod._uapmd_command_registry_invoke_by_id(handle, p) as Boolean }

    override fun close() { jsMod._uapmd_command_registry_destroy(handle) }
}

class JsClipCommandRegistry internal constructor(internal val handle: Int) : ClipCommandRegistry {
    override val commands: List<AddinCommandInfo>
        get() = withWasmMem(Off.COMMAND_INFO_SIZE) { out ->
            (0 until (jsMod._uapmd_clip_command_registry_count(handle) as Int)).mapNotNull { i ->
                if (!(jsMod._uapmd_clip_command_registry_get(handle, i, out) as Boolean)) null
                else decodeJsCommandInfo(out)
            }
        }

    override fun appliesTo(index: Int, target: ClipCommandTarget): Boolean =
        withJsClipTarget(target) { p -> jsMod._uapmd_clip_command_registry_applies_to(handle, index, p) as Boolean }

    override fun isEnabled(index: Int, target: ClipCommandTarget): Boolean =
        withJsClipTarget(target) { p -> jsMod._uapmd_clip_command_registry_enabled(handle, index, p) as Boolean }

    override fun invoke(index: Int, target: ClipCommandTarget): Boolean =
        withJsClipTarget(target) { p -> jsMod._uapmd_clip_command_registry_invoke(handle, index, p) as Boolean }

    override fun close() { jsMod._uapmd_clip_command_registry_destroy(handle) }
}

class JsClipEditorRegistry internal constructor(internal val handle: Int) : ClipEditorRegistry {
    override val editors: List<ClipEditorInfo>
        get() = withWasmMem(Off.CLIP_EDITOR_SIZE) { out ->
            (0 until (jsMod._uapmd_clip_editor_registry_count(handle) as Int)).mapNotNull { i ->
                if (!(jsMod._uapmd_clip_editor_registry_get(handle, i, out) as Boolean)) null
                else ClipEditorInfo(jsGetStr(out + Off.CLIP_EDITOR_ID), jsGetStr(out + Off.CLIP_EDITOR_NAME))
            }
        }

    override fun close() { jsMod._uapmd_clip_editor_registry_destroy(handle) }
}

class JsStemSeparatorRegistry internal constructor(internal val handle: Int) : StemSeparatorRegistry {
    override val separators: List<StemSeparatorInfo>
        get() = withWasmMem(Off.SEPARATOR_INFO_SIZE) { out ->
            (0 until (jsMod._uapmd_stem_separator_registry_count(handle) as Int)).mapNotNull { i ->
                if (!(jsMod._uapmd_stem_separator_registry_get(handle, i, out) as Boolean)) return@mapNotNull null
                val extensionCount = jsGetI32(out + Off.SEPARATOR_EXTENSION_COUNT)
                StemSeparatorInfo(
                    id = jsGetStr(out + Off.SEPARATOR_ID),
                    name = jsGetStr(out + Off.SEPARATOR_NAME),
                    modelFileLabel = jsGetStr(out + Off.SEPARATOR_MODEL_LABEL),
                    modelFileExtensions = (0 until extensionCount).map { e ->
                        readJsStringIndexed2(handle, i, e) { h, idx, ext, buf, size ->
                            jsMod._uapmd_stem_separator_registry_get_model_extension(h, idx, ext, buf, size) as Int
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
        // blocks the calling thread for its whole duration. The progress
        // callback fires repeatedly on that thread, so the function-table slot
        // is released only once the import has returned.
        var slot = 0
        if (progress != null) {
            val fn = { value: Float, messagePtr: Int, _: Int ->
                val message = if (messagePtr != 0) jsMod.UTF8ToString(messagePtr) as String else ""
                if (progress(value, message)) 1 else 0
            }
            slot = addJsCallback(fn.asDynamic(), "ifii")
        }
        try {
            return withWasmMem(Off.IMPORT_RESULT_SIZE) { out ->
                withJsCString(separatorId) { sep ->
                    withJsCString(filepath) { file ->
                        withJsCString(outputDirectory) { dir ->
                            withJsCString(modelPath) { model ->
                                jsMod._uapmd_import_audio_file(out, handle, sep, file, dir, model, 0, slot)
                            }
                        }
                    }
                }
                val warningCount = jsGetI32(out + Off.IMPORT_WARNING_COUNT)
                val warningsPtr = jsGetPtr(out + Off.IMPORT_WARNINGS)
                val stemCount = jsGetI32(out + Off.IMPORT_STEM_COUNT)
                val stemsPtr = jsGetPtr(out + Off.IMPORT_STEMS)
                AudioImportResult(
                    success = jsGetBool(out + Off.IMPORT_SUCCESS),
                    canceled = jsGetBool(out + Off.IMPORT_CANCELED),
                    error = jsGetPtr(out + Off.IMPORT_ERROR).let { if (it != 0) jsMod.UTF8ToString(it) as String else null },
                    warnings = if (warningsPtr == 0) emptyList() else
                        (0 until warningCount).map { i -> jsGetStr(warningsPtr + i * 4) },
                    stems = if (stemsPtr == 0) emptyList() else (0 until stemCount).map { i ->
                        val base = stemsPtr + i * Off.STEM_SIZE
                        AudioStemImport(
                            jsGetStr(base + Off.STEM_NAME),
                            jsGetStr(base + Off.STEM_FILEPATH),
                            jsGetStr(base + Off.STEM_DISPLAY_NAME)
                        )
                    }
                )
            }
        } finally {
            if (slot != 0) removeJsCallback(slot)
        }
    }

    override fun close() { jsMod._uapmd_stem_separator_registry_destroy(handle) }
}

/**
 * Read an output string that takes two index parameters:
 *   size_t fn(handle, index, index2, char* buf, size_t)
 */
private fun readJsStringIndexed2(handle: Int, index: Int, index2: Int, fn: (Int, Int, Int, Int, Int) -> Int): String {
    val size = fn(handle, index, index2, 0, 0)
    if (size <= 0) return ""
    val ptr = jsMod._malloc(size) as Int
    return try {
        fn(handle, index, index2, ptr, size)
        jsMod.UTF8ToString(ptr, size - 1) as String
    } finally { jsMod._free(ptr) }
}

internal actual fun createCommandRegistry(): CommandRegistry =
    JsCommandRegistry(jsMod._uapmd_command_registry_create() as Int)

internal actual fun createClipCommandRegistry(): ClipCommandRegistry =
    JsClipCommandRegistry(jsMod._uapmd_clip_command_registry_create() as Int)

internal actual fun createClipEditorRegistry(): ClipEditorRegistry =
    JsClipEditorRegistry(jsMod._uapmd_clip_editor_registry_create() as Int)

internal actual fun createStemSeparatorRegistry(): StemSeparatorRegistry =
    JsStemSeparatorRegistry(jsMod._uapmd_stem_separator_registry_create() as Int)

class JsPanelRegistry internal constructor(internal val handle: Int) : PanelRegistry {
    override fun update() { jsMod._uapmd_panel_registry_update(handle) }
    override fun clearRetainedPanels() { jsMod._uapmd_panel_registry_clear_retained_panels(handle) }
    override fun close() { jsMod._uapmd_panel_registry_destroy(handle) }
}

internal actual fun createPanelRegistry(): PanelRegistry =
    JsPanelRegistry(jsMod._uapmd_panel_registry_create() as Int)

// ─── Augene2 ─────────────────────────────────────────────────────────────────

// uapmd_augene2_source_t: path@0, external_path@4, compile@8, sizeof 12;
// uapmd_augene2_track_mapping_t: key@0, track_index@4, sizeof 8 (emcc-verified).
private object JsAugene2Offsets {
    const val SOURCE_PATH = 0
    const val SOURCE_EXTERNAL_PATH = 4
    const val SOURCE_COMPILE = 8
    const val SOURCE_SIZE = 12
    const val MAPPING_KEY = 0
    const val MAPPING_TRACK_INDEX = 4
    const val MAPPING_SIZE = 8
}

class JsAugene2Integration internal constructor(internal val handle: Int) : Augene2Integration {
    override var isOpen: Boolean
        get() = (jsMod._uapmd_augene2_integration_is_open(handle) as Int) != 0
        set(value) { jsMod._uapmd_augene2_integration_set_open(handle, value) }
    override val busy: Boolean get() = (jsMod._uapmd_augene2_integration_busy(handle) as Int) != 0
    override val compiling: Boolean get() = (jsMod._uapmd_augene2_integration_compiling(handle) as Int) != 0
    override val sources: List<Augene2IntegrationSource>
        get() = withWasmMem(JsAugene2Offsets.SOURCE_SIZE) { out ->
            (0 until (jsMod._uapmd_augene2_integration_source_count(handle) as Int)).mapNotNull { i ->
                if ((jsMod._uapmd_augene2_integration_get_source(handle, i, out) as Int) == 0) return@mapNotNull null
                Augene2IntegrationSource(
                    jsGetStr(out + JsAugene2Offsets.SOURCE_PATH),
                    jsGetStr(out + JsAugene2Offsets.SOURCE_EXTERNAL_PATH),
                    jsGetBool(out + JsAugene2Offsets.SOURCE_COMPILE)
                )
            }
        }
    override val trackMappings: List<Augene2TrackMapping>
        get() = withWasmMem(JsAugene2Offsets.MAPPING_SIZE) { out ->
            (0 until (jsMod._uapmd_augene2_integration_track_mapping_count(handle) as Int)).mapNotNull { i ->
                if ((jsMod._uapmd_augene2_integration_get_track_mapping(handle, i, out) as Int) == 0) return@mapNotNull null
                Augene2TrackMapping(jsGetStr(out + JsAugene2Offsets.MAPPING_KEY), jsGetI32(out + JsAugene2Offsets.MAPPING_TRACK_INDEX))
            }
        }
    override val status: String
        get() = readJsString(handle) { h, buf, size -> jsMod._uapmd_augene2_integration_status(h, buf, size) as Int }
    override val diagnostics: List<String>
        get() = (0 until (jsMod._uapmd_augene2_integration_diagnostic_count(handle) as Int)).map { i ->
            readJsStringIndexed(handle, i) { h, idx, buf, size ->
                jsMod._uapmd_augene2_integration_get_diagnostic(h, idx, buf, size) as Int
            }
        }
    override var resourceFolder: String
        get() = readJsString(handle) { h, buf, size -> jsMod._uapmd_augene2_integration_resource_folder(h, buf, size) as Int }
        set(value) { withJsCString(value) { p -> jsMod._uapmd_augene2_integration_set_resource_folder(handle, p) } }

    override fun importSources(compile: Boolean) { jsMod._uapmd_augene2_integration_import_sources(handle, compile) }
    override fun relinkSource(path: String) {
        withJsCString(path) { p -> jsMod._uapmd_augene2_integration_relink_source(handle, p) }
    }
    override fun removeSource(path: String) {
        withJsCString(path) { p -> jsMod._uapmd_augene2_integration_remove_source(handle, p) }
    }
    override fun compile() { jsMod._uapmd_augene2_integration_compile(handle) }
    override fun close() { jsMod._uapmd_augene2_integration_release(handle) }
}

internal actual fun augene2Available(): Boolean = (jsMod._uapmd_augene2_available() as Int) != 0

internal actual fun augene2RegisterProjectService(timeline: TimelineFacade, panels: PanelRegistry) {
    jsMod._uapmd_augene2_register_project_service(
        (timeline as JsTimelineFacade).handle, (panels as JsPanelRegistry).handle)
}

internal actual fun augene2Integration(): Augene2Integration? =
    (jsMod._uapmd_augene2_integration() as Int).takeIf { it != 0 }?.let { JsAugene2Integration(it) }
