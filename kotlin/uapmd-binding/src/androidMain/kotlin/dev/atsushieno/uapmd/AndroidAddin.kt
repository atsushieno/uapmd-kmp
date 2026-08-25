package dev.atsushieno.uapmd

class AndroidAddinManager internal constructor(
    internal val handle: Long
) : AddinManager {

    override fun initialize() = JniBridge.uapmdAddinManagerInitialize(handle)

    override fun setEnabled(packageId: String, addinId: String, enabled: Boolean): Boolean =
        JniBridge.uapmdAddinManagerSetEnabled(handle, packageId, addinId, enabled)

    override fun shutdown() = JniBridge.uapmdAddinManagerShutdown(handle)

    override val directories: List<String>
        get() = (0 until JniBridge.uapmdAddinManagerDirectoryCount(handle)).map {
            JniBridge.uapmdAddinManagerGetDirectory(handle, it)
        }

    override val addins: List<AddinInfo>
        get() = (0 until JniBridge.uapmdAddinManagerAddinCount(handle)).mapNotNull { i ->
            val strings = arrayOfNulls<String>(6)
            val flags = JniBridge.uapmdAddinManagerGetAddin(handle, i, strings) ?: return@mapNotNull null
            AddinInfo(
                packageId = strings[0] ?: "",
                addinId = strings[1] ?: "",
                name = strings[2] ?: "",
                path = strings[3] ?: "",
                libraryPath = strings[4] ?: "",
                builtIn = flags[0] != 0,
                state = AddinState.fromNative(flags[1]),
                message = strings[5] ?: ""
            )
        }

    override val lastError: String get() = JniBridge.uapmdAddinManagerLastError(handle)

    override fun close() = JniBridge.uapmdAddinManagerDestroy(handle)
}

internal actual fun addinSupportsDynamicLoading(): Boolean = JniBridge.uapmdAddinSupportsDynamicLoading()

actual fun createAddinManager(): AddinManager {
    val handle = JniBridge.uapmdAddinManagerCreate()
    require(handle != 0L) { "uapmdAddinManagerCreate returned null" }
    return AndroidAddinManager(handle)
}
