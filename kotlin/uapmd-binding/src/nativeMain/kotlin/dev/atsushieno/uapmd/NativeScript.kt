package dev.atsushieno.uapmd

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import uapmd.*

@OptIn(ExperimentalForeignApi::class)
class NativeJsRuntime internal constructor(internal val handle: uapmd_js_runtime_t) : JsRuntime {
    override fun ensureApiBootstrapped() = uapmd_js_runtime_ensure_api_bootstrapped(handle)
    override fun reinitialize() = uapmd_js_runtime_reinitialize(handle)

    override fun evaluate(code: String, moduleResolver: ((String) -> String?)?): JsResult {
        if (moduleResolver == null)
            return uapmd_js_runtime_evaluate(handle, code, null, null)
                .useContents { JsResult(success, json?.toKString(), error?.toKString()) }

        // The resolved source has to outlive the callback's return, so it is
        // kept in the box until the evaluation finishes.
        val box = ResolverBox(moduleResolver)
        val ref = StableRef.create(box)
        try {
            return uapmd_js_runtime_evaluate(
                handle, code, ref.asCPointer(),
                staticCFunction { path: CPointer<kotlinx.cinterop.ByteVar>?, ud: kotlinx.cinterop.COpaquePointer? ->
                    val b = ud!!.asStableRef<ResolverBox>().get()
                    val found = b.resolve(path?.toKString() ?: "")
                    if (found == null) null else {
                        b.last = found
                        b.lastPinned = found.cstr.getPointer(kotlinx.cinterop.nativeHeap)
                        b.lastPinned
                    }
                }
            ).useContents { JsResult(success, json?.toKString(), error?.toKString()) }
        } finally {
            box.lastPinned?.let { kotlinx.cinterop.nativeHeap.free(it) }
            ref.dispose()
        }
    }

    override fun registerParameterListener(instanceId: Int) =
        uapmd_js_runtime_register_parameter_listener(handle, instanceId)
    override fun unregisterParameterListener(instanceId: Int) =
        uapmd_js_runtime_unregister_parameter_listener(handle, instanceId)
    override fun registerAllParameterListeners() =
        uapmd_js_runtime_register_all_parameter_listeners(handle)
    override fun unregisterAllParameterListeners() =
        uapmd_js_runtime_unregister_all_parameter_listeners(handle)
    override fun registerMetadataListener(instanceId: Int) =
        uapmd_js_runtime_register_metadata_listener(handle, instanceId)
    override fun unregisterMetadataListener(instanceId: Int) =
        uapmd_js_runtime_unregister_metadata_listener(handle, instanceId)
    override fun registerAllMetadataListeners() =
        uapmd_js_runtime_register_all_metadata_listeners(handle)
    override fun unregisterAllMetadataListeners() =
        uapmd_js_runtime_unregister_all_metadata_listeners(handle)

    override fun close() = uapmd_js_runtime_destroy(handle)
}

@OptIn(ExperimentalForeignApi::class)
internal class ResolverBox(val resolve: (String) -> String?) {
    var last: String? = null
    var lastPinned: CPointer<kotlinx.cinterop.ByteVar>? = null
}

@OptIn(ExperimentalForeignApi::class)
class NativeMcpServer internal constructor(internal val handle: uapmd_mcp_server_t) : McpServer {
    override fun start() = uapmd_mcp_server_start(handle)
    override fun stop() = uapmd_mcp_server_stop(handle)
    override val mode: McpMode
        get() = if (uapmd_mcp_server_mode(handle).toInt() == 0) McpMode.Server else McpMode.Client
    override val connectionState: McpState
        get() = when (uapmd_mcp_server_connection_state(handle).toInt()) {
            1 -> McpState.Connecting
            2 -> McpState.Connected
            3 -> McpState.Error
            else -> McpState.Idle
        }
    override val port: Int get() = uapmd_mcp_server_port(handle)
    override val statusMessage: String get() = uapmd_mcp_server_status_message(handle)?.toKString() ?: ""
    override fun processMainThreadQueue() = uapmd_mcp_server_process_main_thread_queue(handle)
    override fun close() = uapmd_mcp_server_destroy(handle)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun createJsRuntime(): JsRuntime? =
    uapmd_js_runtime_create()?.let { NativeJsRuntime(it) }

@OptIn(ExperimentalForeignApi::class)
internal actual fun mcpIsSupported(): Boolean = uapmd_mcp_is_supported()

@OptIn(ExperimentalForeignApi::class)
internal actual fun mcpHasHttpServer(): Boolean = uapmd_mcp_has_http_server()

@OptIn(ExperimentalForeignApi::class)
internal actual fun createMcpServer(port: Int): McpServer? =
    uapmd_mcp_server_create(port)?.let { NativeMcpServer(it) }

@OptIn(ExperimentalForeignApi::class)
internal actual fun createMcpClient(relayUrl: String, autoReconnect: Boolean): McpServer? =
    memScoped { uapmd_mcp_client_create(relayUrl, autoReconnect) }?.let { NativeMcpServer(it) }
