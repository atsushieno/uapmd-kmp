package dev.atsushieno.uapmd

// ─── AndroidScanTool ─────────────────────────────────────────────────────────

class AndroidScanTool internal constructor(
    internal val handle: Long
) : ScanTool {

    override val catalogEntryCount: UInt get() = JniBridge.uapmdScanToolCatalogEntryCount(handle).toUInt()
    override val formatCount: UInt get() = JniBridge.uapmdScanToolFormatCount(handle).toUInt()

    override fun getFormatName(index: UInt): String =
        JniBridge.uapmdScanToolGetFormatName(handle, index.toInt())

    override var cacheFile: String
        get() = JniBridge.uapmdScanToolGetCacheFile(handle)
        set(value) { JniBridge.uapmdScanToolSetCacheFile(handle, value) }

    override fun saveCache() = JniBridge.uapmdScanToolSaveCache(handle)

    override fun saveCacheTo(path: String) = JniBridge.uapmdScanToolSaveCacheTo(handle, path)

    override fun performScanning(requireFastScanning: Boolean, observer: ScanObserver?) {
        val slowStartCb = observer?.let {
            object : Any() {
                @Suppress("unused")
                fun invoke(total: Int) = it.onSlowScanStarted(total.toUInt())
            }
        }
        val bundleStartCb = observer?.let {
            object : Any() {
                @Suppress("unused")
                fun invoke(path: String?) = it.onBundleScanStarted(path ?: "")
            }
        }
        val bundleCompleteCb = observer?.let {
            object : Any() {
                @Suppress("unused")
                fun invoke(path: String?) = it.onBundleScanCompleted(path ?: "")
            }
        }
        val slowCompleteCb = observer?.let {
            object : Any() {
                @Suppress("unused")
                fun invoke() = it.onSlowScanCompleted()
            }
        }
        val errorCb = observer?.let {
            object : Any() {
                @Suppress("unused")
                fun invoke(msg: String?) = it.onErrorOccurred(msg ?: "")
            }
        }
        val cancelCb = observer?.let {
            object : Any() {
                @Suppress("unused")
                fun invoke(): Boolean = it.shouldCancel()
            }
        }
        JniBridge.uapmdScanToolPerformScanning(
            handle, requireFastScanning,
            slowStartCb, bundleStartCb, bundleCompleteCb,
            slowCompleteCb, errorCb, cancelCb
        )
    }

    override val blocklistCount: UInt get() = JniBridge.uapmdScanToolBlocklistCount(handle).toUInt()

    override fun getBlocklistEntry(index: UInt): BlocklistEntry? {
        val arr = JniBridge.uapmdScanToolGetBlocklistEntry(handle, index.toInt()) ?: return null
        return BlocklistEntry(arr[0] ?: "", arr[1] ?: "", arr[2] ?: "", arr[3] ?: "")
    }

    override fun flushBlocklist() = JniBridge.uapmdScanToolFlushBlocklist(handle)

    override fun unblockBundle(entryId: String): Boolean =
        JniBridge.uapmdScanToolUnblockBundle(handle, entryId)

    override fun clearBlocklist() = JniBridge.uapmdScanToolClearBlocklist(handle)

    override fun addToBlocklist(formatName: String, pluginId: String, reason: String) =
        JniBridge.uapmdScanToolAddToBlocklist(handle, formatName, pluginId, reason)

    override val lastScanError: String get() = JniBridge.uapmdScanToolLastScanError(handle)

    override fun close() = JniBridge.uapmdScanToolDestroy(handle)
}

// ─── AndroidFormatManager ────────────────────────────────────────────────────

class AndroidFormatManager internal constructor(
    private val handle: Long
) : FormatManager {

    override val formatCount: UInt get() = JniBridge.uapmdFormatManagerFormatCount(handle).toUInt()

    override fun getFormatName(index: UInt): String =
        JniBridge.uapmdFormatManagerGetFormatName(handle, index.toInt())

    override fun close() = JniBridge.uapmdFormatManagerDestroy(handle)
}

// ─── AndroidPluginInstancing ─────────────────────────────────────────────────

class AndroidPluginInstancing internal constructor(
    private val handle: Long
) : PluginInstancing {

    override val state: InstancingState get() =
        InstancingState.fromNative(JniBridge.uapmdInstancingState(handle))

    override fun makeAlive(callback: (String?) -> Unit) {
        val cb = object : Any() {
            @Suppress("unused")
            fun invoke(error: String?) = callback(error)
        }
        JniBridge.uapmdInstancingMakeAlive(handle, cb)
    }

    override fun close() = JniBridge.uapmdInstancingDestroy(handle)
}
