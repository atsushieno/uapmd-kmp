package dev.atsushieno.uapmd_kmp

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
    initUapmdWasm(uapmdCApiFactory, uapmdCApiWasmUrl)
    ComposeViewport {
        App()
    }
}
