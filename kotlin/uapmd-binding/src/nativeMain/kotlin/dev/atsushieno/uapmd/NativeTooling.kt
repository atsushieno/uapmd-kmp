package dev.atsushieno.uapmd

import kotlinx.cinterop.*
import uapmd.*

class NativeScanTool internal constructor(
    internal val handle: uapmd_scan_tool_t
) : ScanTool {

    override val catalogEntryCount: UInt get() = uapmd_scan_tool_catalog_entry_count(handle)
    override val formatCount: UInt get() = uapmd_scan_tool_format_count(handle)

    override fun getFormatName(index: UInt): String =
        readCString { buf, size -> uapmd_scan_tool_get_format_name(handle, index, buf, size) }

    override var cacheFile: String
        get() = readCString { buf, size -> uapmd_scan_tool_get_cache_file(handle, buf, size) }
        set(value) { uapmd_scan_tool_set_cache_file(handle, value) }

    override fun saveCache() = uapmd_scan_tool_save_cache(handle)
    override fun saveCacheTo(path: String) = uapmd_scan_tool_save_cache_to(handle, path)

    override fun performScanning(requireFastScanning: Boolean, observer: ScanObserver?) {
        if (observer == null) {
            uapmd_scan_tool_perform_scanning(handle, requireFastScanning, null)
            return
        }
        // Scanning is synchronous; StableRef is valid for the full duration.
        val ref = StableRef.create(observer)
        memScoped {
            val obs = alloc<uapmd_scan_observer_t>()
            obs.user_data = ref.asCPointer()
            obs.slow_scan_started = staticCFunction { total, userData ->
                userData?.asStableRef<ScanObserver>()?.get()?.onSlowScanStarted(total)
            }
            obs.bundle_scan_started = staticCFunction { path, userData ->
                userData?.asStableRef<ScanObserver>()?.get()
                    ?.onBundleScanStarted(path?.toKString() ?: "")
            }
            obs.bundle_scan_completed = staticCFunction { path, userData ->
                userData?.asStableRef<ScanObserver>()?.get()
                    ?.onBundleScanCompleted(path?.toKString() ?: "")
            }
            obs.slow_scan_completed = staticCFunction { userData ->
                userData?.asStableRef<ScanObserver>()?.get()?.onSlowScanCompleted()
            }
            obs.error_occurred = staticCFunction { message, userData ->
                userData?.asStableRef<ScanObserver>()?.get()
                    ?.onErrorOccurred(message?.toKString() ?: "")
            }
            obs.should_cancel = staticCFunction { userData ->
                userData?.asStableRef<ScanObserver>()?.get()?.shouldCancel() ?: false
            }
            uapmd_scan_tool_perform_scanning(handle, requireFastScanning, obs.ptr)
        }
        ref.dispose()
    }

    override val blocklistCount: UInt get() = uapmd_scan_tool_blocklist_count(handle)

    override fun getBlocklistEntry(index: UInt): BlocklistEntry? = memScoped {
        val out = alloc<uapmd_blocklist_entry_t>()
        if (!uapmd_scan_tool_get_blocklist_entry(handle, index, out.ptr)) return null
        BlocklistEntry(
            id = out.id?.toKString() ?: "",
            format = out.format?.toKString() ?: "",
            pluginId = out.plugin_id?.toKString() ?: "",
            reason = out.reason?.toKString() ?: ""
        )
    }

    override fun flushBlocklist() = uapmd_scan_tool_flush_blocklist(handle)

    override fun unblockBundle(entryId: String): Boolean =
        uapmd_scan_tool_unblock_bundle(handle, entryId)

    override fun clearBlocklist() = uapmd_scan_tool_clear_blocklist(handle)

    override fun addToBlocklist(formatName: String, pluginId: String, reason: String) =
        uapmd_scan_tool_add_to_blocklist(handle, formatName, pluginId, reason)

    override val lastScanError: String
        get() = readCString { buf, size -> uapmd_scan_tool_last_scan_error(handle, buf, size) }

    override fun close() = uapmd_scan_tool_destroy(handle)
}

// ---------------------------------------------------------------------------

class NativeFormatManager internal constructor(
    private val handle: uapmd_format_manager_t
) : FormatManager {

    override val formatCount: UInt get() = uapmd_format_manager_format_count(handle)

    override fun getFormatName(index: UInt): String =
        readCString { buf, size -> uapmd_format_manager_get_format_name(handle, index, buf, size) }

    override fun close() = uapmd_format_manager_destroy(handle)
}

// ---------------------------------------------------------------------------

class NativePluginInstancing internal constructor(
    private val handle: uapmd_plugin_instancing_t
) : PluginInstancing {

    override val state: InstancingState
        get() = InstancingState.entries[uapmd_instancing_state(handle).toInt()]

    override fun makeAlive(callback: (String?) -> Unit) {
        val ref = StableRef.create(callback)
        uapmd_instancing_make_alive(
            handle, ref.asCPointer(),
            staticCFunction { error, userData ->
                if (userData == null) return@staticCFunction
                val cb = userData.asStableRef<(String?) -> Unit>()
                cb.get()(error?.toKString())
                cb.dispose()
            }
        )
    }

    override fun close() = uapmd_instancing_destroy(handle)
}
