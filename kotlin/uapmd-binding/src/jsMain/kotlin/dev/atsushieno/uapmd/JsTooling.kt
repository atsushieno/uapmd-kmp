package dev.atsushieno.uapmd

// ─── JsScanTool ───────────────────────────────────────────────────────────────

class JsScanTool internal constructor(
    internal val handle: Int
) : ScanTool {

    override val catalogEntryCount: UInt
        get() = (jsMod._uapmd_scan_tool_catalog_entry_count(handle) as Int).toUInt()

    override val formatCount: UInt
        get() = (jsMod._uapmd_scan_tool_format_count(handle) as Int).toUInt()

    override fun getFormatName(index: UInt): String =
        readJsStringIndexed(handle, index.toInt()) { h, i, buf, sz ->
            jsMod._uapmd_scan_tool_get_format_name(h, i, buf, sz) as Int
        }

    override var cacheFile: String
        get() = readJsString(handle) { h, buf, sz ->
            jsMod._uapmd_scan_tool_get_cache_file(h, buf, sz) as Int
        }
        set(value) = withJsCString(value) { ptr -> jsMod._uapmd_scan_tool_set_cache_file(handle, ptr) }

    override fun saveCache() = jsMod._uapmd_scan_tool_save_cache(handle)

    override fun saveCacheTo(path: String) =
        withJsCString(path) { ptr -> jsMod._uapmd_scan_tool_save_cache_to(handle, ptr) }

    override fun performScanning(requireFastScanning: Boolean, observer: ScanObserver?) {
        if (observer == null) {
            jsMod._uapmd_scan_tool_perform_scanning(handle, requireFastScanning, 0, 0, 0, 0, 0, 0, 0)
            return
        }
        val cbId = nextJsCbId()
        jsScanObservers[cbId] = observer
        val ptrs = makeScanObserverPtrs(cbId)
        try {
            jsMod._uapmd_scan_tool_perform_scanning(
                handle, requireFastScanning,
                ptrs.slowStart, ptrs.bundleStart, ptrs.bundleComplete,
                ptrs.slowComplete, ptrs.error, ptrs.cancel, 0
            )
        } finally {
            freeScanObserverPtrs(ptrs)
            jsScanObservers.remove(cbId)
        }
    }

    override val blocklistCount: UInt
        get() = (jsMod._uapmd_scan_tool_blocklist_count(handle) as Int).toUInt()

    override fun getBlocklistEntry(index: UInt): BlocklistEntry? =
        withWasmMem(16) { ptr ->
            if (!(jsMod._uapmd_scan_tool_get_blocklist_entry(handle, index.toInt(), ptr) as Boolean)) null
            else jsDecodeBlocklistEntry(ptr)
        }

    override fun flushBlocklist() = jsMod._uapmd_scan_tool_flush_blocklist(handle)

    override fun unblockBundle(entryId: String): Boolean =
        withJsCString(entryId) { ptr -> jsMod._uapmd_scan_tool_unblock_bundle(handle, ptr) as Boolean }

    override fun clearBlocklist() = jsMod._uapmd_scan_tool_clear_blocklist(handle)

    override fun addToBlocklist(formatName: String, pluginId: String, reason: String) =
        withJsThreeCStrings(formatName, pluginId, reason) { fPtr, idPtr, rPtr ->
            jsMod._uapmd_scan_tool_add_to_blocklist(handle, fPtr, idPtr, rPtr)
        }

    override val lastScanError: String
        get() = readJsString(handle) { h, buf, sz ->
            jsMod._uapmd_scan_tool_last_scan_error(h, buf, sz) as Int
        }

    override fun close() = jsMod._uapmd_scan_tool_destroy(handle)
}

// ─── JsFormatManager ─────────────────────────────────────────────────────────

class JsFormatManager internal constructor(
    private val handle: Int
) : FormatManager {

    override val formatCount: UInt
        get() = (jsMod._uapmd_format_manager_format_count(handle) as Int).toUInt()

    override fun getFormatName(index: UInt): String =
        readJsStringIndexed(handle, index.toInt()) { h, i, buf, sz ->
            jsMod._uapmd_format_manager_get_format_name(h, i, buf, sz) as Int
        }

    override fun close() = jsMod._uapmd_format_manager_destroy(handle)
}

// ─── JsPluginInstancing ───────────────────────────────────────────────────────

class JsPluginInstancing internal constructor(
    private val handle: Int
) : PluginInstancing {

    override val state: InstancingState
        get() = InstancingState.fromNative(jsMod._uapmd_instancing_state(handle) as Int)

    override fun makeAlive(callback: (String?) -> Unit) {
        val fnPtr = makeJsErrorCallback(callback)
        jsMod._uapmd_instancing_make_alive(handle, fnPtr, 0)
    }

    override fun close() = jsMod._uapmd_instancing_destroy(handle)
}
