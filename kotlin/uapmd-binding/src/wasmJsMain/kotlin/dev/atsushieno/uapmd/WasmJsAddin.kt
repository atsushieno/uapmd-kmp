package dev.atsushieno.uapmd

class WasmJsAddinManager internal constructor(internal val handle: Int) : AddinManager {

    override fun initialize() = wasmMod.uapmdAddinManagerInitialize(handle)

    override fun setEnabled(packageId: String, addinId: String, enabled: Boolean): Boolean =
        withTwoCStringsKt(packageId, addinId) { p, a ->
            wasmMod.uapmdAddinManagerSetEnabled(handle, p, a, enabled)
        }

    override fun shutdown() = wasmMod.uapmdAddinManagerShutdown(handle)

    override val directories: List<String>
        get() = (0 until wasmMod.uapmdAddinManagerDirectoryCount(handle)).map { i ->
            readStringIndexed(handle, i) { h, idx, buf, size ->
                uapmdAddinManagerGetDirectory(h, idx, buf, size)
            }
        }

    override val addins: List<AddinInfo>
        get() = withWasmStruct(WasmOff.ADDIN_SIZE) { out ->
            (0 until wasmMod.uapmdAddinManagerAddinCount(handle)).mapNotNull { i ->
                if (!wasmMod.uapmdAddinManagerGetAddin(handle, i, out)) return@mapNotNull null
                AddinInfo(
                    packageId = wasmGetStr(out + WasmOff.ADDIN_PACKAGE_ID),
                    addinId = wasmGetStr(out + WasmOff.ADDIN_ADDIN_ID),
                    name = wasmGetStr(out + WasmOff.ADDIN_NAME),
                    path = wasmGetStr(out + WasmOff.ADDIN_PATH),
                    libraryPath = wasmGetStr(out + WasmOff.ADDIN_LIBRARY_PATH),
                    builtIn = wasmGetBool(out + WasmOff.ADDIN_BUILT_IN),
                    state = AddinState.fromNative(wasmGetI32(out + WasmOff.ADDIN_STATE)),
                    message = wasmGetStr(out + WasmOff.ADDIN_MESSAGE)
                )
            }
        }

    override val lastError: String
        get() = readString(handle) { h, buf, size -> uapmdAddinManagerLastError(h, buf, size) }

    override fun close() = wasmMod.uapmdAddinManagerDestroy(handle)
}

internal actual fun addinSupportsDynamicLoading(): Boolean = wasmMod.uapmdAddinSupportsDynamicLoading()

actual fun createAddinManager(): AddinManager =
    WasmJsAddinManager(wasmMod.uapmdAddinManagerCreate())
