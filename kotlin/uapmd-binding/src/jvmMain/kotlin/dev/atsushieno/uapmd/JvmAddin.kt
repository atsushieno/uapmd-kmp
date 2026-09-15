package dev.atsushieno.uapmd

import com.sun.jna.Pointer
import dev.atsushieno.uapmd.jna.ImportProgressCb
import dev.atsushieno.uapmd.jna.UapmdAddinCommandInfo
import dev.atsushieno.uapmd.jna.UapmdAddinInfo
import dev.atsushieno.uapmd.jna.UapmdAudioStemImport
import dev.atsushieno.uapmd.jna.UapmdClipCommandTarget
import dev.atsushieno.uapmd.jna.UapmdClipEditorInfo
import dev.atsushieno.uapmd.jna.UapmdStemSeparatorInfo

class JvmAddinManager internal constructor(
    internal val handle: Pointer
) : AddinManager {

    override fun initialize() = lib.uapmd_addin_manager_initialize(handle)

    override fun setEnabled(packageId: String, addinId: String, enabled: Boolean): Boolean =
        lib.uapmd_addin_manager_set_enabled(handle, packageId, addinId, enabled)

    override fun shutdown() = lib.uapmd_addin_manager_shutdown(handle)

    override fun registerCommandRegistry(registry: CommandRegistry) =
        lib.uapmd_addin_manager_register_command_registry(handle, (registry as JvmCommandRegistry).handle)

    override fun registerClipCommandRegistry(registry: ClipCommandRegistry) =
        lib.uapmd_addin_manager_register_clip_command_registry(handle, (registry as JvmClipCommandRegistry).handle)

    override fun registerClipEditorRegistry(registry: ClipEditorRegistry) =
        lib.uapmd_addin_manager_register_clip_editor_registry(handle, (registry as JvmClipEditorRegistry).handle)

    override fun registerStemSeparatorRegistry(registry: StemSeparatorRegistry) =
        lib.uapmd_addin_manager_register_stem_separator_registry(handle, (registry as JvmStemSeparatorRegistry).handle)

    override val directories: List<String>
        get() = (0 until lib.uapmd_addin_manager_directory_count(handle)).map { i ->
            readJvmString { buf, size -> lib.uapmd_addin_manager_get_directory(handle, i, buf, size) }
        }

    override val addins: List<AddinInfo>
        get() = (0 until lib.uapmd_addin_manager_addin_count(handle)).mapNotNull { i ->
            val out = UapmdAddinInfo()
            if (!lib.uapmd_addin_manager_get_addin(handle, i, out)) return@mapNotNull null
            out.read()
            AddinInfo(
                packageId = out.package_id ?: "",
                addinId = out.addin_id ?: "",
                name = out.name ?: "",
                path = out.path ?: "",
                libraryPath = out.library_path ?: "",
                builtIn = out.built_in != 0.toByte(),
                state = AddinState.fromNative(out.state),
                message = out.message ?: ""
            )
        }

    override val lastError: String
        get() = readJvmString { buf, size -> lib.uapmd_addin_manager_last_error(handle, buf, size) }

    override fun close() = lib.uapmd_addin_manager_destroy(handle)
}

internal actual fun addinSupportsDynamicLoading(): Boolean = lib.uapmd_addin_supports_dynamic_loading()

actual fun createAddinManager(): AddinManager =
    JvmAddinManager(lib.uapmd_addin_manager_create() ?: error("uapmd_addin_manager_create returned null"))

// ─── Host registries ─────────────────────────────────────────────────────────

class JvmCommandRegistry internal constructor(internal val handle: Pointer) : CommandRegistry {
    override val commands: List<AddinCommandInfo>
        get() = (0 until lib.uapmd_command_registry_count(handle)).mapNotNull { i ->
            val out = UapmdAddinCommandInfo()
            if (!lib.uapmd_command_registry_get(handle, i, out)) null else out.toKotlin()
        }

    override fun invoke(index: Int): Boolean = lib.uapmd_command_registry_invoke(handle, index)
    override fun invokeById(id: String): Boolean = lib.uapmd_command_registry_invoke_by_id(handle, id)
    override fun close() = lib.uapmd_command_registry_destroy(handle)
}

class JvmClipCommandRegistry internal constructor(internal val handle: Pointer) : ClipCommandRegistry {
    override val commands: List<AddinCommandInfo>
        get() = (0 until lib.uapmd_clip_command_registry_count(handle)).mapNotNull { i ->
            val out = UapmdAddinCommandInfo()
            if (!lib.uapmd_clip_command_registry_get(handle, i, out)) null else out.toKotlin()
        }

    override fun appliesTo(index: Int, target: ClipCommandTarget): Boolean =
        lib.uapmd_clip_command_registry_applies_to(handle, index, target.toJvmByVal())

    override fun isEnabled(index: Int, target: ClipCommandTarget): Boolean =
        lib.uapmd_clip_command_registry_enabled(handle, index, target.toJvmByVal())

    override fun invoke(index: Int, target: ClipCommandTarget): Boolean =
        lib.uapmd_clip_command_registry_invoke(handle, index, target.toJvmByVal())

    override fun close() = lib.uapmd_clip_command_registry_destroy(handle)
}

class JvmClipEditorRegistry internal constructor(internal val handle: Pointer) : ClipEditorRegistry {
    override val editors: List<ClipEditorInfo>
        get() = (0 until lib.uapmd_clip_editor_registry_count(handle)).mapNotNull { i ->
            val out = UapmdClipEditorInfo()
            if (!lib.uapmd_clip_editor_registry_get(handle, i, out)) null
            else ClipEditorInfo(out.id ?: "", out.name ?: "")
        }

    override fun close() = lib.uapmd_clip_editor_registry_destroy(handle)
}

class JvmStemSeparatorRegistry internal constructor(internal val handle: Pointer) : StemSeparatorRegistry {
    override val separators: List<StemSeparatorInfo>
        get() = (0 until lib.uapmd_stem_separator_registry_count(handle)).mapNotNull { i ->
            val out = UapmdStemSeparatorInfo()
            if (!lib.uapmd_stem_separator_registry_get(handle, i, out)) return@mapNotNull null
            StemSeparatorInfo(
                id = out.id ?: "",
                name = out.name ?: "",
                modelFileLabel = out.model_file_label ?: "",
                modelFileExtensions = (0 until out.model_file_extension_count).map { e ->
                    readJvmString { buf, size ->
                        lib.uapmd_stem_separator_registry_get_model_extension(handle, i, e, buf, size)
                    }
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
        // Held in a local for the duration of the (blocking) call: JNA keeps a
        // trampoline alive only while Java still references the callback.
        val cb = progress?.let {
            object : ImportProgressCb {
                override fun invoke(progress: Float, message: String?, userData: Pointer?): Boolean =
                    it(progress, message ?: "")
            }
        }
        val r = lib.uapmd_import_audio_file(
            handle, separatorId, filepath, outputDirectory, modelPath, null, cb
        )
        val warnings = if (r.warnings == null) emptyList() else
            r.warnings!!.getStringArray(0, r.warning_count).toList()
        val stemStride = UapmdAudioStemImport().size().toLong()
        val stems = if (r.stems == null) emptyList() else (0 until r.stem_count).map { i ->
            val s = UapmdAudioStemImport(r.stems!!.share(i * stemStride))
            AudioStemImport(s.stem_name ?: "", s.filepath ?: "", s.clip_display_name ?: "")
        }
        return AudioImportResult(
            success = r.success != 0.toByte(),
            canceled = r.canceled != 0.toByte(),
            error = r.error,
            warnings = warnings,
            stems = stems
        )
    }

    override fun close() = lib.uapmd_stem_separator_registry_destroy(handle)
}

private fun UapmdAddinCommandInfo.toKotlin() =
    AddinCommandInfo(id ?: "", title ?: "", order, enabled != 0.toByte())

private fun ClipCommandTarget.toJvmByVal() = UapmdClipCommandTarget.ByVal().also {
    it.track_index = trackIndex
    it.clip_id = clipId
    it.midi_clip = if (isMidiClip) 1 else 0
    it.master_track = if (isMasterTrack) 1 else 0
}

internal actual fun createCommandRegistry(): CommandRegistry =
    JvmCommandRegistry(lib.uapmd_command_registry_create() ?: error("uapmd_command_registry_create returned null"))

internal actual fun createClipCommandRegistry(): ClipCommandRegistry =
    JvmClipCommandRegistry(lib.uapmd_clip_command_registry_create() ?: error("uapmd_clip_command_registry_create returned null"))

internal actual fun createClipEditorRegistry(): ClipEditorRegistry =
    JvmClipEditorRegistry(lib.uapmd_clip_editor_registry_create() ?: error("uapmd_clip_editor_registry_create returned null"))

internal actual fun createStemSeparatorRegistry(): StemSeparatorRegistry =
    JvmStemSeparatorRegistry(lib.uapmd_stem_separator_registry_create() ?: error("uapmd_stem_separator_registry_create returned null"))
