package dev.atsushieno.uapmd

interface ScanObserver {
    fun onSlowScanStarted(totalBundles: UInt) {}
    fun onBundleScanStarted(bundlePath: String) {}
    fun onBundleScanCompleted(bundlePath: String) {}
    fun onSlowScanCompleted() {}
    fun onErrorOccurred(message: String) {}
    fun shouldCancel(): Boolean = false
}

interface ScanTool : AutoCloseable {
    val catalogEntryCount: UInt
    val formatCount: UInt
    fun getFormatName(index: UInt): String
    var cacheFile: String
    fun saveCache()
    fun saveCacheTo(path: String)
    /** requireFastScanning mirrors the C API parameter (false = full slow scan). */
    fun performScanning(requireFastScanning: Boolean, observer: ScanObserver? = null)
    val blocklistCount: UInt
    fun getBlocklistEntry(index: UInt): BlocklistEntry?
    fun flushBlocklist()
    fun unblockBundle(entryId: String): Boolean
    fun clearBlocklist()
    fun addToBlocklist(formatName: String, pluginId: String, reason: String)
    val lastScanError: String
}

/** Minimal format manager – exposes available plugin formats by name. */
interface FormatManager : AutoCloseable {
    val formatCount: UInt
    fun getFormatName(index: UInt): String
}

/**
 * Lifecycle manager for a single plugin's instantiation process.
 * Created per (format, pluginId) pair via [createPluginInstancing].
 */
interface PluginInstancing : AutoCloseable {
    val state: InstancingState
    fun makeAlive(callback: (error: String?) -> Unit)
}
