package dev.atsushieno.uapmd.cmp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.atsushieno.uapmd.initUapmdWasm
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise
import kotlinx.coroutines.await

@OptIn(ExperimentalWasmJsInterop::class)
@JsModule("uapmd-c-api")
@JsName("default")
private external val uapmdCApiFactory: JsAny

@JsModule("uapmd-c-api.wasm")
@JsName("default")
private external val uapmdCApiWasmUrl: String

@JsFun("() => import('uapmd-wasm-adapter')")
private external fun importUapmdWasmAdapter(): Promise<JsAny>

@OptIn(ExperimentalComposeUiApi::class, ExperimentalWasmJsInterop::class)
@Suppress("unused")
suspend fun main() {
    val adapterModule: JsAny = importUapmdWasmAdapter().await()
    // Also creates /browser/{uploads,remidy-tooling} and mounts IDBFS; the plugin
    // list cache lives there. See docs/uapmd-cmp-plan.md §2.5.
    initUapmdWasm(uapmdCApiFactory, uapmdCApiWasmUrl)
    ComposeViewport {
        App()
    }
}
