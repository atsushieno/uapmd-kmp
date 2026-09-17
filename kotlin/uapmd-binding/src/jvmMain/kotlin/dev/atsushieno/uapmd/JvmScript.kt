package dev.atsushieno.uapmd

import com.sun.jna.Pointer
import dev.atsushieno.uapmd.jna.JsModuleResolver

class JvmJsRuntime internal constructor(internal val handle: Pointer) : JsRuntime {
    override fun ensureApiBootstrapped() = lib.uapmd_js_runtime_ensure_api_bootstrapped(handle)
    override fun reinitialize() = lib.uapmd_js_runtime_reinitialize(handle)

    override fun evaluate(code: String, moduleResolver: ((String) -> String?)?): JsResult {
        // Held for the duration of the call: JNA must not collect the callback
        // while native code still holds the pointer.
        val resolver = moduleResolver?.let { fn ->
            object : JsModuleResolver {
                override fun invoke(modulePath: String?, userData: Pointer?) = fn(modulePath ?: "")
            }
        }
        val r = lib.uapmd_js_runtime_evaluate(handle, code, null, resolver)
        return JsResult(r.success != 0.toByte(), r.json, r.error)
    }

    override fun registerParameterListener(instanceId: Int) =
        lib.uapmd_js_runtime_register_parameter_listener(handle, instanceId)
    override fun unregisterParameterListener(instanceId: Int) =
        lib.uapmd_js_runtime_unregister_parameter_listener(handle, instanceId)
    override fun registerAllParameterListeners() =
        lib.uapmd_js_runtime_register_all_parameter_listeners(handle)
    override fun unregisterAllParameterListeners() =
        lib.uapmd_js_runtime_unregister_all_parameter_listeners(handle)
    override fun registerMetadataListener(instanceId: Int) =
        lib.uapmd_js_runtime_register_metadata_listener(handle, instanceId)
    override fun unregisterMetadataListener(instanceId: Int) =
        lib.uapmd_js_runtime_unregister_metadata_listener(handle, instanceId)
    override fun registerAllMetadataListeners() =
        lib.uapmd_js_runtime_register_all_metadata_listeners(handle)
    override fun unregisterAllMetadataListeners() =
        lib.uapmd_js_runtime_unregister_all_metadata_listeners(handle)

    override fun close() = lib.uapmd_js_runtime_destroy(handle)
}

class JvmMcpServer internal constructor(internal val handle: Pointer) : McpServer {
    override fun start() = lib.uapmd_mcp_server_start(handle)
    override fun stop() = lib.uapmd_mcp_server_stop(handle)
    override val mode: McpMode
        get() = if (lib.uapmd_mcp_server_mode(handle) == 0) McpMode.Server else McpMode.Client
    override val connectionState: McpState
        get() = mcpStateOf(lib.uapmd_mcp_server_connection_state(handle))
    override val port: Int get() = lib.uapmd_mcp_server_port(handle)
    override val statusMessage: String get() = lib.uapmd_mcp_server_status_message(handle) ?: ""
    override fun processMainThreadQueue() = lib.uapmd_mcp_server_process_main_thread_queue(handle)
    override fun close() = lib.uapmd_mcp_server_destroy(handle)
}

internal fun mcpStateOf(value: Int) = when (value) {
    1 -> McpState.Connecting
    2 -> McpState.Connected
    3 -> McpState.Error
    else -> McpState.Idle
}

internal actual fun createJsRuntime(): JsRuntime? =
    lib.uapmd_js_runtime_create()?.let { JvmJsRuntime(it) }

internal actual fun mcpIsSupported(): Boolean = lib.uapmd_mcp_is_supported()
internal actual fun mcpHasHttpServer(): Boolean = lib.uapmd_mcp_has_http_server()

internal actual fun createMcpServer(port: Int): McpServer? =
    lib.uapmd_mcp_server_create(port)?.let { JvmMcpServer(it) }

internal actual fun createMcpClient(relayUrl: String, autoReconnect: Boolean): McpServer? =
    lib.uapmd_mcp_client_create(relayUrl, autoReconnect)?.let { JvmMcpServer(it) }
