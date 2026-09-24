@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.atsushieno.uapmd

import kotlinx.cinterop.*
import uapmd.*

class NativeAddinManager internal constructor(
    internal val handle: uapmd_addin_manager_t
) : AddinManager {

    override fun initialize() = uapmd_addin_manager_initialize(handle)

    override fun setEnabled(packageId: String, addinId: String, enabled: Boolean): Boolean =
        uapmd_addin_manager_set_enabled(handle, packageId, addinId, enabled)

    override fun shutdown() = uapmd_addin_manager_shutdown(handle)

    override fun registerCommandRegistry(registry: CommandRegistry) =
        uapmd_addin_manager_register_command_registry(handle, (registry as NativeCommandRegistry).handle)

    override fun registerProjectCommandRegistry(registry: CommandRegistry) =
        uapmd_addin_manager_register_project_command_registry(handle, (registry as NativeCommandRegistry).handle)

    override fun registerClipCommandRegistry(registry: ClipCommandRegistry) =
        uapmd_addin_manager_register_clip_command_registry(handle, (registry as NativeClipCommandRegistry).handle)

    override fun registerClipEditorRegistry(registry: ClipEditorRegistry) =
        uapmd_addin_manager_register_clip_editor_registry(handle, (registry as NativeClipEditorRegistry).handle)

    override fun registerStemSeparatorRegistry(registry: StemSeparatorRegistry) =
        uapmd_addin_manager_register_stem_separator_registry(handle, (registry as NativeStemSeparatorRegistry).handle)

    override fun registerPanelRegistry(registry: PanelRegistry) =
        uapmd_addin_manager_register_panel_registry(handle, (registry as NativePanelRegistry).handle)

    override fun registerAppModel(model: AppModel) =
        uapmd_addin_manager_register_app_model(handle, (model as NativeAppModel).handle)

    override val directories: List<String>
        get() = (0 until uapmd_addin_manager_directory_count(handle).toInt()).map { i ->
            readCString { buf, size -> uapmd_addin_manager_get_directory(handle, i.toUInt(), buf, size) }
        }

    override val addins: List<AddinInfo>
        get() = memScoped {
            val out = alloc<uapmd_addin_info_t>()
            (0 until uapmd_addin_manager_addin_count(handle).toInt()).mapNotNull { i ->
                if (!uapmd_addin_manager_get_addin(handle, i.toUInt(), out.ptr)) return@mapNotNull null
                AddinInfo(
                    packageId = out.package_id?.toKString() ?: "",
                    addinId = out.addin_id?.toKString() ?: "",
                    name = out.name?.toKString() ?: "",
                    path = out.path?.toKString() ?: "",
                    libraryPath = out.library_path?.toKString() ?: "",
                    builtIn = out.built_in,
                    state = AddinState.fromNative(out.state.toInt()),
                    message = out.message?.toKString() ?: ""
                )
            }
        }

    override val lastError: String
        get() = readCString { buf, size -> uapmd_addin_manager_last_error(handle, buf, size) }

    override fun close() = uapmd_addin_manager_destroy(handle)
}

internal actual fun addinSupportsDynamicLoading(): Boolean = uapmd_addin_supports_dynamic_loading()

actual fun createAddinManager(): AddinManager =
    NativeAddinManager(uapmd_addin_manager_create() ?: error("uapmd_addin_manager_create failed"))

// ─── Host registries ─────────────────────────────────────────────────────────

class NativeCommandRegistry internal constructor(internal val handle: uapmd_command_registry_t) : CommandRegistry {
    override val commands: List<AddinCommandInfo>
        get() = memScoped {
            val out = alloc<uapmd_addin_command_info_t>()
            (0u until uapmd_command_registry_count(handle)).mapNotNull { i ->
                if (!uapmd_command_registry_get(handle, i, out.ptr)) null else out.toKotlin()
            }
        }

    override fun invoke(index: Int): Boolean = uapmd_command_registry_invoke(handle, index.toUInt())
    override fun invokeById(id: String): Boolean = uapmd_command_registry_invoke_by_id(handle, id)
    override fun close() = uapmd_command_registry_destroy(handle)
}

class NativeClipCommandRegistry internal constructor(internal val handle: uapmd_clip_command_registry_t) : ClipCommandRegistry {
    override val commands: List<AddinCommandInfo>
        get() = memScoped {
            val out = alloc<uapmd_addin_command_info_t>()
            (0u until uapmd_clip_command_registry_count(handle)).mapNotNull { i ->
                if (!uapmd_clip_command_registry_get(handle, i, out.ptr)) null else out.toKotlin()
            }
        }

    override fun appliesTo(index: Int, target: ClipCommandTarget): Boolean =
        uapmd_clip_command_registry_applies_to(handle, index.toUInt(), target.toNative())

    override fun isEnabled(index: Int, target: ClipCommandTarget): Boolean =
        uapmd_clip_command_registry_enabled(handle, index.toUInt(), target.toNative())

    override fun invoke(index: Int, target: ClipCommandTarget): Boolean =
        uapmd_clip_command_registry_invoke(handle, index.toUInt(), target.toNative())

    override fun close() = uapmd_clip_command_registry_destroy(handle)
}

class NativeClipEditorRegistry internal constructor(internal val handle: uapmd_clip_editor_registry_t) : ClipEditorRegistry {
    override val editors: List<ClipEditorInfo>
        get() = memScoped {
            val out = alloc<uapmd_clip_editor_info_t>()
            (0u until uapmd_clip_editor_registry_count(handle)).mapNotNull { i ->
                if (!uapmd_clip_editor_registry_get(handle, i, out.ptr)) null
                else ClipEditorInfo(out.id?.toKString() ?: "", out.name?.toKString() ?: "")
            }
        }

    override fun close() = uapmd_clip_editor_registry_destroy(handle)
}

class NativeStemSeparatorRegistry internal constructor(internal val handle: uapmd_stem_separator_registry_t) : StemSeparatorRegistry {
    override val separators: List<StemSeparatorInfo>
        get() = memScoped {
            val out = alloc<uapmd_stem_separator_info_t>()
            (0u until uapmd_stem_separator_registry_count(handle)).mapNotNull { i ->
                if (!uapmd_stem_separator_registry_get(handle, i, out.ptr)) return@mapNotNull null
                StemSeparatorInfo(
                    id = out.id?.toKString() ?: "",
                    name = out.name?.toKString() ?: "",
                    modelFileLabel = out.model_file_label?.toKString() ?: "",
                    modelFileExtensions = (0u until out.model_file_extension_count).map { e ->
                        readCString { buf, size ->
                            uapmd_stem_separator_registry_get_model_extension(handle, i, e, buf, size)
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
        // The import blocks until it finishes, so the ref is disposed on the way
        // out rather than from the trampoline: the callback can fire many times.
        val ref = progress?.let { StableRef.create(it) }
        try {
            return uapmd_import_audio_file(
                handle, separatorId, filepath, outputDirectory, modelPath,
                ref?.asCPointer(),
                if (progress == null) null else staticCFunction { value: Float, message: CPointer<ByteVar>?, userData: COpaquePointer? ->
                    @Suppress("UNCHECKED_CAST")
                    val fn = userData!!.asStableRef<(Float, String) -> Boolean>().get()
                    fn(value, message?.toKString() ?: "")
                }
            ).useContents {
                AudioImportResult(
                    success = success,
                    canceled = canceled,
                    error = error?.toKString(),
                    warnings = (0u until warning_count).map { i -> warnings!![i.toInt()]?.toKString() ?: "" },
                    stems = (0u until stem_count).map { i ->
                        val stem = stems!![i.toInt()]
                        AudioStemImport(
                            stem.stem_name?.toKString() ?: "",
                            stem.filepath?.toKString() ?: "",
                            stem.clip_display_name?.toKString() ?: ""
                        )
                    }
                )
            }
        } finally {
            ref?.dispose()
        }
    }

    override fun close() = uapmd_stem_separator_registry_destroy(handle)
}

private fun uapmd_addin_command_info_t.toKotlin() =
    AddinCommandInfo(id?.toKString() ?: "", title?.toKString() ?: "", order, enabled)

private fun ClipCommandTarget.toNative(): CValue<uapmd_clip_command_target_t> =
    cValue<uapmd_clip_command_target_t> {
        track_index = trackIndex
        clip_id = clipId
        midi_clip = isMidiClip
        master_track = isMasterTrack
    }

internal actual fun createCommandRegistry(): CommandRegistry =
    NativeCommandRegistry(uapmd_command_registry_create() ?: error("uapmd_command_registry_create failed"))

internal actual fun createClipCommandRegistry(): ClipCommandRegistry =
    NativeClipCommandRegistry(uapmd_clip_command_registry_create() ?: error("uapmd_clip_command_registry_create failed"))

internal actual fun createClipEditorRegistry(): ClipEditorRegistry =
    NativeClipEditorRegistry(uapmd_clip_editor_registry_create() ?: error("uapmd_clip_editor_registry_create failed"))

internal actual fun createStemSeparatorRegistry(): StemSeparatorRegistry =
    NativeStemSeparatorRegistry(uapmd_stem_separator_registry_create() ?: error("uapmd_stem_separator_registry_create failed"))

class NativePanelRegistry internal constructor(internal val handle: uapmd_panel_registry_t) : PanelRegistry {
    override fun update() = uapmd_panel_registry_update(handle)
    override fun clearRetainedPanels() = uapmd_panel_registry_clear_retained_panels(handle)
    override fun close() = uapmd_panel_registry_destroy(handle)
}

internal actual fun createPanelRegistry(): PanelRegistry =
    NativePanelRegistry(uapmd_panel_registry_create() ?: error("uapmd_panel_registry_create failed"))

// ─── Augene2 ─────────────────────────────────────────────────────────────────

class NativeAugene2Integration internal constructor(
    internal val handle: uapmd_augene2_integration_t
) : Augene2Integration {
    override var isOpen: Boolean
        get() = uapmd_augene2_integration_is_open(handle)
        set(value) = uapmd_augene2_integration_set_open(handle, value)
    override val busy: Boolean get() = uapmd_augene2_integration_busy(handle)
    override val compiling: Boolean get() = uapmd_augene2_integration_compiling(handle)
    override val sources: List<Augene2IntegrationSource>
        get() = memScoped {
            val out = alloc<uapmd_augene2_source_t>()
            (0 until uapmd_augene2_integration_source_count(handle).toInt()).mapNotNull { i ->
                if (!uapmd_augene2_integration_get_source(handle, i.toUInt(), out.ptr)) return@mapNotNull null
                Augene2IntegrationSource(
                    out.path?.toKString() ?: "",
                    out.external_path?.toKString() ?: "",
                    out.compile
                )
            }
        }
    override val trackMappings: List<Augene2TrackMapping>
        get() = memScoped {
            val out = alloc<uapmd_augene2_track_mapping_t>()
            (0 until uapmd_augene2_integration_track_mapping_count(handle).toInt()).mapNotNull { i ->
                if (!uapmd_augene2_integration_get_track_mapping(handle, i.toUInt(), out.ptr)) return@mapNotNull null
                Augene2TrackMapping(out.key?.toKString() ?: "", out.track_index)
            }
        }
    override val status: String
        get() = readCString { buf, size -> uapmd_augene2_integration_status(handle, buf, size) }
    override val diagnostics: List<String>
        get() = (0 until uapmd_augene2_integration_diagnostic_count(handle).toInt()).map { i ->
            readCString { buf, size -> uapmd_augene2_integration_get_diagnostic(handle, i.toUInt(), buf, size) }
        }
    override var resourceFolder: String
        get() = readCString { buf, size -> uapmd_augene2_integration_resource_folder(handle, buf, size) }
        set(value) = uapmd_augene2_integration_set_resource_folder(handle, value)

    override fun importSources(compile: Boolean) = uapmd_augene2_integration_import_sources(handle, compile)
    override fun relinkSource(path: String) = uapmd_augene2_integration_relink_source(handle, path)
    override fun removeSource(path: String) = uapmd_augene2_integration_remove_source(handle, path)
    override fun compile() = uapmd_augene2_integration_compile(handle)
    override fun close() = uapmd_augene2_integration_release(handle)
}

internal actual fun augene2Available(): Boolean = uapmd_augene2_available()

internal actual fun augene2RegisterProjectService(timeline: TimelineFacade, panels: PanelRegistry) =
    uapmd_augene2_register_project_service(
        (timeline as NativeTimelineFacade).handle, (panels as NativePanelRegistry).handle)

internal actual fun augene2Integration(): Augene2Integration? =
    uapmd_augene2_integration()?.let { NativeAugene2Integration(it) }
