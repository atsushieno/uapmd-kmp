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
