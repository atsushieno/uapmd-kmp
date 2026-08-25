package dev.atsushieno.uapmd

import com.sun.jna.Pointer
import dev.atsushieno.uapmd.jna.UapmdAddinInfo

class JvmAddinManager internal constructor(
    internal val handle: Pointer
) : AddinManager {

    override fun initialize() = lib.uapmd_addin_manager_initialize(handle)

    override fun setEnabled(packageId: String, addinId: String, enabled: Boolean): Boolean =
        lib.uapmd_addin_manager_set_enabled(handle, packageId, addinId, enabled)

    override fun shutdown() = lib.uapmd_addin_manager_shutdown(handle)

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
