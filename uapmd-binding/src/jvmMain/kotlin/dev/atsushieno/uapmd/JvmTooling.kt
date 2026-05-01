package dev.atsushieno.uapmd

import com.sun.jna.Pointer
import dev.atsushieno.uapmd.jna.*

// ─── JvmScanTool ─────────────────────────────────────────────────────────────

class JvmScanTool internal constructor(
    internal val handle: Pointer
) : ScanTool {

    override val catalogEntryCount: UInt get() = lib.uapmd_scan_tool_catalog_entry_count(handle).toUInt()

    override val formatCount: UInt get() = lib.uapmd_scan_tool_format_count(handle).toUInt()

    override fun getFormatName(index: UInt): String =
        readJvmString { buf, size -> lib.uapmd_scan_tool_get_format_name(handle, index.toInt(), buf, size) }

    override var cacheFile: String
        get() = readJvmString { buf, size -> lib.uapmd_scan_tool_get_cache_file(handle, buf, size) }
        set(value) { lib.uapmd_scan_tool_set_cache_file(handle, value) }

    override fun saveCache() = lib.uapmd_scan_tool_save_cache(handle)

    override fun saveCacheTo(path: String) = lib.uapmd_scan_tool_save_cache_to(handle, path)

    override fun performScanning(requireFastScanning: Boolean, observer: ScanObserver?) {
        if (observer == null) {
            lib.uapmd_scan_tool_perform_scanning(handle, requireFastScanning, null)
            return
        }
        val obs = UapmdScanObserver()
        obs.user_data = null  // context is captured in each callback closure
        obs.slow_scan_started = object : ScanStartedCb {
            override fun invoke(totalBundles: Int, userData: Pointer?) =
                observer.onSlowScanStarted(totalBundles.toUInt())
        }
        obs.bundle_scan_started = object : BundleScanCb {
            override fun invoke(bundlePath: String?, userData: Pointer?) =
                observer.onBundleScanStarted(bundlePath ?: "")
        }
        obs.bundle_scan_completed = object : BundleScanCb {
            override fun invoke(bundlePath: String?, userData: Pointer?) =
                observer.onBundleScanCompleted(bundlePath ?: "")
        }
        obs.slow_scan_completed = object : ScanCompletedCb {
            override fun invoke(userData: Pointer?) = observer.onSlowScanCompleted()
        }
        obs.error_occurred = object : ScanErrorCb {
            override fun invoke(message: String?, userData: Pointer?) =
                observer.onErrorOccurred(message ?: "")
        }
        obs.should_cancel = object : ScanShouldCancelCb {
            override fun invoke(userData: Pointer?): Boolean = observer.shouldCancel()
        }
        lib.uapmd_scan_tool_perform_scanning(handle, requireFastScanning, obs)
    }

    override val blocklistCount: UInt get() = lib.uapmd_scan_tool_blocklist_count(handle).toUInt()

    override fun getBlocklistEntry(index: UInt): BlocklistEntry? {
        val out = UapmdBlocklistEntry()
        if (!lib.uapmd_scan_tool_get_blocklist_entry(handle, index.toInt(), out)) return null
        return BlocklistEntry(
            id = out.id ?: "",
            format = out.format ?: "",
            pluginId = out.plugin_id ?: "",
            reason = out.reason ?: ""
        )
    }

    override fun flushBlocklist() = lib.uapmd_scan_tool_flush_blocklist(handle)

    override fun unblockBundle(entryId: String): Boolean =
        lib.uapmd_scan_tool_unblock_bundle(handle, entryId)

    override fun clearBlocklist() = lib.uapmd_scan_tool_clear_blocklist(handle)

    override fun addToBlocklist(formatName: String, pluginId: String, reason: String) =
        lib.uapmd_scan_tool_add_to_blocklist(handle, formatName, pluginId, reason)

    override val lastScanError: String
        get() = readJvmString { buf, size -> lib.uapmd_scan_tool_last_scan_error(handle, buf, size) }

    override fun close() = lib.uapmd_scan_tool_destroy(handle)
}

// ─── JvmFormatManager ────────────────────────────────────────────────────────

class JvmFormatManager internal constructor(
    private val handle: Pointer
) : FormatManager {

    override val formatCount: UInt get() = lib.uapmd_format_manager_format_count(handle).toUInt()

    override fun getFormatName(index: UInt): String =
        readJvmString { buf, size -> lib.uapmd_format_manager_get_format_name(handle, index.toInt(), buf, size) }

    override fun close() = lib.uapmd_format_manager_destroy(handle)
}

// ─── JvmPluginInstancing ─────────────────────────────────────────────────────

class JvmPluginInstancing internal constructor(
    private val handle: Pointer
) : PluginInstancing {

    override val state: InstancingState
        get() = InstancingState.fromNative(lib.uapmd_instancing_state(handle))

    override fun makeAlive(callback: (error: String?) -> Unit) {
        val cb = object : InstancingCb {
            override fun invoke(error: String?, userData: Pointer?) = callback(error)
        }
        lib.uapmd_instancing_make_alive(handle, null, cb)
    }

    override fun close() = lib.uapmd_instancing_destroy(handle)
}
