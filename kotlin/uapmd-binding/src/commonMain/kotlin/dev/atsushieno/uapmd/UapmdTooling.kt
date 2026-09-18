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

    val searchPathSettingsFile: String
    /** Run after the formats are registered and before the first scan. */
    fun loadSearchPathSettings()
    fun saveSearchPathSettings()

    /** False for a format enumerated by the OS (AU); the rest below then do nothing. */
    fun formatUsesSearchPaths(formatIndex: UInt): Boolean
    fun getFormatDefaultSearchPaths(formatIndex: UInt): List<String>
    fun getFormatSearchPaths(formatIndex: UInt): List<String>
    fun addFormatSearchPath(formatIndex: UInt, path: String)
    fun setFormatSearchPaths(formatIndex: UInt, paths: List<String>)
    fun getFormatUseDefaultSearchPaths(formatIndex: UInt): Boolean
    fun setFormatUseDefaultSearchPaths(formatIndex: UInt, value: Boolean)
}

/**
 * Where uapmd keeps the plugin list cache, the blocklist and the search path
 * settings. Android and iOS must set it before anything that writes exists;
 * elsewhere it is worked out already. An empty path means nothing persists.
 */
expect var applicationDataDirectory: String

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
