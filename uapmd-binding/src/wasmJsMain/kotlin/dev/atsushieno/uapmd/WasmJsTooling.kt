package dev.atsushieno.uapmd

// ─── WasmJsScanTool ──────────────────────────────────────────────────────────

class WasmJsScanTool internal constructor(
    internal val handle: Int
) : ScanTool {

    override val catalogEntryCount: UInt
        get() = wasmMod.uapmdScanToolCatalogEntryCount(handle).toUInt()

    override val formatCount: UInt
        get() = wasmMod.uapmdScanToolFormatCount(handle).toUInt()

    override fun getFormatName(index: UInt): String =
        readStringIndexed(handle, index.toInt()) { h, i, buf, size ->
            uapmdScanToolGetFormatName(h, i, buf, size)
        }

    override var cacheFile: String
        get() = readString(handle) { h, buf, size -> uapmdScanToolGetCacheFile(h, buf, size) }
        set(value) = withCStringKt(value) { ptr -> wasmMod.uapmdScanToolSetCacheFile(handle, ptr) }

    override fun saveCache() = wasmMod.uapmdScanToolSaveCache(handle)

    override fun saveCacheTo(path: String) =
        withCStringKt(path) { ptr -> wasmMod.uapmdScanToolSaveCacheTo(handle, ptr) }

    override fun performScanning(requireFastScanning: Boolean, observer: ScanObserver?) {
        if (observer == null) {
            wasmMod.uapmdScanToolPerformScanning(handle, requireFastScanning, 0, 0, 0, 0, 0, 0, 0)
            return
        }
        val cbId = nextCallbackId()
        scanObservers[cbId] = observer
        try {
            // Signatures: slowStart "vi", bundleStart "vi", bundleComplete "vi",
            //             slowComplete "v", error "vi", cancel "i" (returns bool)
            val slowStartPtr      = makeCFunctionPtr(cbId, "uapmdDispatchScanSlowStart",    "vi")
            val bundleStartPtr    = makeCFunctionPtr(cbId, "uapmdDispatchScanBundleStart",   "vi")
            val bundleCompletePtr = makeCFunctionPtr(cbId, "uapmdDispatchScanBundleComplete","vi")
            val slowCompletePtr   = makeCFunctionPtr(cbId, "uapmdDispatchScanSlowComplete",  "vi")
            val errorPtr_         = makeCFunctionPtr(cbId, "uapmdDispatchScanError",         "vi")
            val cancelPtr         = makeCFunctionPtr(cbId, "uapmdDispatchScanCancel",        "ii")

            wasmMod.uapmdScanToolPerformScanning(
                handle, requireFastScanning,
                slowStartPtr, bundleStartPtr, bundleCompletePtr,
                slowCompletePtr, errorPtr_, cancelPtr, 0
            )

            // Clean up function table entries after sync scan completes
            removeCFunctionPtr(slowStartPtr)
            removeCFunctionPtr(bundleStartPtr)
            removeCFunctionPtr(bundleCompletePtr)
            removeCFunctionPtr(slowCompletePtr)
            removeCFunctionPtr(errorPtr_)
            removeCFunctionPtr(cancelPtr)
        } finally {
            scanObservers.remove(cbId)
        }
    }

    override val blocklistCount: UInt
        get() = wasmMod.uapmdScanToolBlocklistCount(handle).toUInt()

    override fun getBlocklistEntry(index: UInt): BlocklistEntry? {
        val mod = wasmMod
        val ptr = mod.malloc(16) // sizeof uapmd_blocklist_entry_t (4 char* pointers)
        return try {
            if (!mod.uapmdScanToolGetBlocklistEntry(handle, index.toInt(), ptr)) null
            else {
                fun getStr(o: Int): String {
                    val strPtr = mod.getValue(ptr + o, "i32").toInt()
                    return if (strPtr != 0) mod.utf8ToString(strPtr) else ""
                }
                BlocklistEntry(
                    id       = getStr(0),
                    format   = getStr(4),
                    pluginId = getStr(8),
                    reason   = getStr(12)
                )
            }
        } finally { mod.free(ptr) }
    }

    override fun flushBlocklist() = wasmMod.uapmdScanToolFlushBlocklist(handle)

    override fun unblockBundle(entryId: String): Boolean =
        withCStringKt(entryId) { ptr -> wasmMod.uapmdScanToolUnblockBundle(handle, ptr) }

    override fun clearBlocklist() = wasmMod.uapmdScanToolClearBlocklist(handle)

    override fun addToBlocklist(formatName: String, pluginId: String, reason: String) =
        withThreeCStringsKt(formatName, pluginId, reason) { fPtr, idPtr, rPtr ->
            wasmMod.uapmdScanToolAddToBlocklist(handle, fPtr, idPtr, rPtr)
        }

    override val lastScanError: String
        get() = readString(handle) { h, buf, size -> uapmdScanToolLastScanError(h, buf, size) }

    override fun close() = wasmMod.uapmdScanToolDestroy(handle)
}

// ─── WasmJsFormatManager ─────────────────────────────────────────────────────

class WasmJsFormatManager internal constructor(
    private val handle: Int
) : FormatManager {

    override val formatCount: UInt
        get() = wasmMod.uapmdFormatManagerFormatCount(handle).toUInt()

    override fun getFormatName(index: UInt): String =
        readStringIndexed(handle, index.toInt()) { h, i, buf, size ->
            uapmdFormatManagerGetFormatName(h, i, buf, size)
        }

    override fun close() = wasmMod.uapmdFormatManagerDestroy(handle)
}

// ─── WasmJsPluginInstancing ───────────────────────────────────────────────────

class WasmJsPluginInstancing internal constructor(
    private val handle: Int
) : PluginInstancing {

    override val state: InstancingState
        get() = InstancingState.fromNative(wasmMod.uapmdInstancingState(handle))

    override fun makeAlive(callback: (String?) -> Unit) {
        val cbId = nextCallbackId()
        pendingMakeAliveCallbacks[cbId] = callback
        val fnPtr = makeCFunctionPtr(cbId, "uapmdDispatchMakeAlive", "vi")
        wasmMod.uapmdInstancingMakeAlive(handle, fnPtr, 0)
    }

    override fun close() = wasmMod.uapmdInstancingDestroy(handle)
}
