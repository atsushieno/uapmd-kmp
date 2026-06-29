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
        val actualFast = requireFastScanning
        if (observer == null) {
            wasmMod.uapmdScanToolPerformScanning(handle, actualFast, 0)
            return
        }
        val cbId = nextCallbackId()

        // C callback signatures (second arg is void* user_data which we pass as cbId):
        //   slow_scan_started(uint32_t total, void* ctx)  → "vii"
        //   bundle_scan_*(const char* path, void* ctx)    → "vii"
        //   slow_scan_completed(void* ctx)                → "vi"
        //   error_occurred(const char* msg, void* ctx)    → "vii"
        //   should_cancel(void* ctx) → bool               → "ii"
        val slowStartPtr      = makeCFunctionPtr(cbId, "uapmdDispatchScanSlowStart",    "vii")
        val bundleStartPtr    = makeCFunctionPtr(cbId, "uapmdDispatchScanBundleStart",   "vii")
        val bundleCompletePtr = makeCFunctionPtr(cbId, "uapmdDispatchScanBundleComplete","vii")
        val slowCompletePtr   = makeCFunctionPtr(cbId, "uapmdDispatchScanSlowComplete",  "vi")
        val errorPtr_         = makeCFunctionPtr(cbId, "uapmdDispatchScanError",         "vii")
        val cancelPtr         = makeCFunctionPtr(cbId, "uapmdDispatchScanCancel",        "ii")

        // Wrap the observer so cleanup happens when the scan actually completes.
        // The scan is async on WASM: performScanning returns before callbacks fire,
        // so we must NOT clean up in a finally block here.
        scanObservers[cbId] = object : ScanObserver by observer {
            override fun onSlowScanCompleted() {
                observer.onSlowScanCompleted()
                scanObservers.remove(cbId)
                removeCFunctionPtr(slowStartPtr)
                removeCFunctionPtr(bundleStartPtr)
                removeCFunctionPtr(bundleCompletePtr)
                removeCFunctionPtr(slowCompletePtr)
                removeCFunctionPtr(errorPtr_)
                removeCFunctionPtr(cancelPtr)
            }
        }

        // Build uapmd_scan_observer_t (28 bytes in WASM32):
        //   [0]  void* user_data
        //   [4]  slow_scan_started
        //   [8]  bundle_scan_started
        //   [12] bundle_scan_completed
        //   [16] slow_scan_completed
        //   [20] error_occurred
        //   [24] should_cancel
        val obsPtr = wasmMod.malloc(28)
        wasmMod.setValue(obsPtr + 0,  0.0,                       "i32")
        wasmMod.setValue(obsPtr + 4,  slowStartPtr.toDouble(),   "i32")
        wasmMod.setValue(obsPtr + 8,  bundleStartPtr.toDouble(), "i32")
        wasmMod.setValue(obsPtr + 12, bundleCompletePtr.toDouble(), "i32")
        wasmMod.setValue(obsPtr + 16, slowCompletePtr.toDouble(),"i32")
        wasmMod.setValue(obsPtr + 20, errorPtr_.toDouble(),      "i32")
        wasmMod.setValue(obsPtr + 24, cancelPtr.toDouble(),      "i32")
        wasmMod.uapmdScanToolPerformScanning(handle, actualFast, obsPtr)
        // The struct is copied into C lambdas during the call above; safe to free now.
        wasmMod.free(obsPtr)
        // Cleanup of function pointers and scanObservers is deferred to onSlowScanCompleted.
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
        val fnPtr = makeCFunctionPtr(cbId, "uapmdDispatchMakeAlive", "vii")
        wasmMod.uapmdInstancingMakeAlive(handle, 0, fnPtr)
    }

    override fun close() = wasmMod.uapmdInstancingDestroy(handle)
}
