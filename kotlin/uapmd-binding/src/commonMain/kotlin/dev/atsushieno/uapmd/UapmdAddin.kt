package dev.atsushieno.uapmd

enum class AddinState(val nativeValue: Int) {
    Inactive(0), Initializing(1), Active(2), CleaningUp(3), Failed(4);

    companion object {
        fun fromNative(v: Int): AddinState = entries.firstOrNull { it.nativeValue == v } ?: Inactive
    }
}

data class AddinInfo(
    val packageId: String,
    val addinId: String,
    val name: String,
    /** Extension point path this addin attaches to. */
    val path: String,
    /** Empty for built-in addins. */
    val libraryPath: String,
    val builtIn: Boolean,
    val state: AddinState,
    /** Failure detail, or empty. */
    val message: String
)

/**
 * Host side of the uapmd addin system (uapmd 0.5.6). An addin is a package that
 * attaches itself to named extension points a host publishes; ARA support is
 * one such addin.
 *
 * The usual sequence is: create the manager, publish the engine's extension
 * points with [SequencerEngine.registerAddinExtensionPoints], then
 * [initialize] to load whatever is installed.
 *
 * Extension points other than the engine's cannot be published from Kotlin:
 * they are C++ interface pointers with no meaningful representation here.
 */
interface AddinManager : AutoCloseable {
    fun initialize()
    fun setEnabled(packageId: String, addinId: String, enabled: Boolean): Boolean
    fun shutdown()

    /** Directories scanned for installed addin packages. */
    val directories: List<String>
    val addins: List<AddinInfo>
    val lastError: String

    companion object {
        /**
         * False on platforms without dynamic loading (Wasm, iOS), where only
         * built-in addins are available.
         */
        val supportsDynamicLoading: Boolean get() = addinSupportsDynamicLoading()
    }
}

internal expect fun addinSupportsDynamicLoading(): Boolean
