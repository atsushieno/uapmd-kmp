@file:Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")

package dev.atsushieno.uapmd

class JsAddinManager internal constructor(internal val handle: Int) : AddinManager {

    override fun initialize() { jsMod._uapmd_addin_manager_initialize(handle) }

    override fun setEnabled(packageId: String, addinId: String, enabled: Boolean): Boolean =
        withJsTwoCStrings(packageId, addinId) { p, a ->
            jsMod._uapmd_addin_manager_set_enabled(handle, p, a, enabled) as Boolean
        }

    override fun shutdown() { jsMod._uapmd_addin_manager_shutdown(handle) }

    override val directories: List<String>
        get() = (0 until (jsMod._uapmd_addin_manager_directory_count(handle) as Int)).map { i ->
            readJsStringIndexed(handle, i) { h, idx, buf, size ->
                jsMod._uapmd_addin_manager_get_directory(h, idx, buf, size) as Int
            }
        }

    override val addins: List<AddinInfo>
        get() = withWasmMem(JsAddinOffsets.SIZE) { out ->
            (0 until (jsMod._uapmd_addin_manager_addin_count(handle) as Int)).mapNotNull { i ->
                if (!(jsMod._uapmd_addin_manager_get_addin(handle, i, out) as Boolean)) return@mapNotNull null
                AddinInfo(
                    packageId = jsGetStr(out + JsAddinOffsets.PACKAGE_ID),
                    addinId = jsGetStr(out + JsAddinOffsets.ADDIN_ID),
                    name = jsGetStr(out + JsAddinOffsets.NAME),
                    path = jsGetStr(out + JsAddinOffsets.PATH),
                    libraryPath = jsGetStr(out + JsAddinOffsets.LIBRARY_PATH),
                    builtIn = jsGetBool(out + JsAddinOffsets.BUILT_IN),
                    state = AddinState.fromNative(jsGetI32(out + JsAddinOffsets.STATE)),
                    message = jsGetStr(out + JsAddinOffsets.MESSAGE)
                )
            }
        }

    override val lastError: String
        get() = readJsString(handle) { h, buf, size -> jsMod._uapmd_addin_manager_last_error(h, buf, size) as Int }

    override fun close() { jsMod._uapmd_addin_manager_destroy(handle) }
}

internal actual fun addinSupportsDynamicLoading(): Boolean =
    jsMod._uapmd_addin_supports_dynamic_loading() as Boolean

actual fun createAddinManager(): AddinManager =
    JsAddinManager(jsMod._uapmd_addin_manager_create() as Int)
