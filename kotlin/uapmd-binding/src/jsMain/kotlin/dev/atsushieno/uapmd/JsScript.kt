package dev.atsushieno.uapmd

/**
 * uapmd_js_result_t: bool @0, char* json @4, char* error @8 (12 bytes),
 * returned through a hidden first argument (Emscripten sret).
 */
private const val JsResultSize = 12

class JsJsRuntime internal constructor(internal val handle: Int) : JsRuntime {
    override fun ensureApiBootstrapped() =
        jsMod._uapmd_js_runtime_ensure_api_bootstrapped(handle) as Boolean

    override fun reinitialize() {
        jsMod._uapmd_js_runtime_reinitialize(handle)
    }

    override fun evaluate(code: String, moduleResolver: ((String) -> String?)?): JsResult {
        // Whatever the resolver returns must stay alive until the evaluation
        // ends, so the allocations are released only after the call returns.
        val owned = mutableListOf<Int>()
        var slot = 0
        val callback = moduleResolver?.let { fn ->
            val f: (dynamic, dynamic) -> Int = { pathPtr, _ ->
                val path = if ((pathPtr as Int) != 0) jsMod.UTF8ToString(pathPtr) as String else ""
                val found = fn(path)
                if (found == null) 0 else {
                    val size = jsMod.lengthBytesUTF8(found) as Int + 1
                    val p = jsMod._malloc(size) as Int
                    jsMod.stringToUTF8(found, p, size)
                    owned += p
                    p
                }
            }
            slot = addJsCallback(f.asDynamic(), "iii")
            slot
        } ?: 0

        try {
            return withWasmMem(JsResultSize) { out ->
                withJsCString(code) { codePtr ->
                    jsMod._uapmd_js_runtime_evaluate(out, handle, codePtr, 0, callback)
                }
                val ok = jsGetBool(out)
                JsResult(
                    ok,
                    if (ok) jsStrOrNull(out + 4) else null,
                    if (ok) null else jsStrOrNull(out + 8)
                )
            }
        } finally {
            owned.forEach { jsMod._free(it) }
            if (callback != 0) removeJsCallback(slot)
        }
    }

    override fun registerParameterListener(instanceId: Int) {
        jsMod._uapmd_js_runtime_register_parameter_listener(handle, instanceId)
    }
    override fun unregisterParameterListener(instanceId: Int) {
        jsMod._uapmd_js_runtime_unregister_parameter_listener(handle, instanceId)
    }
    override fun registerAllParameterListeners() {
        jsMod._uapmd_js_runtime_register_all_parameter_listeners(handle)
    }
    override fun unregisterAllParameterListeners() {
        jsMod._uapmd_js_runtime_unregister_all_parameter_listeners(handle)
    }
    override fun registerMetadataListener(instanceId: Int) {
        jsMod._uapmd_js_runtime_register_metadata_listener(handle, instanceId)
    }
    override fun unregisterMetadataListener(instanceId: Int) {
        jsMod._uapmd_js_runtime_unregister_metadata_listener(handle, instanceId)
    }
    override fun registerAllMetadataListeners() {
        jsMod._uapmd_js_runtime_register_all_metadata_listeners(handle)
    }
    override fun unregisterAllMetadataListeners() {
        jsMod._uapmd_js_runtime_unregister_all_metadata_listeners(handle)
    }

    override fun close() {
        jsMod._uapmd_js_runtime_destroy(handle)
    }
}

private fun jsStrOrNull(ptr: Int): String? {
    val p = jsMod.getValue(ptr, "i32") as Int
    return if (p != 0) jsMod.UTF8ToString(p) as String else null
}

class JsMcpServer internal constructor(internal val handle: Int) : McpServer {
    override fun start() { jsMod._uapmd_mcp_server_start(handle) }
    override fun stop() { jsMod._uapmd_mcp_server_stop(handle) }
    override val mode: McpMode
        get() = if ((jsMod._uapmd_mcp_server_mode(handle) as Int) == 0) McpMode.Server else McpMode.Client
    override val connectionState: McpState
        get() = when (jsMod._uapmd_mcp_server_connection_state(handle) as Int) {
            1 -> McpState.Connecting
            2 -> McpState.Connected
            3 -> McpState.Error
            else -> McpState.Idle
        }
    override val port: Int get() = jsMod._uapmd_mcp_server_port(handle) as Int
    override val statusMessage: String
        get() = (jsMod._uapmd_mcp_server_status_message(handle) as Int)
            .let { if (it != 0) jsMod.UTF8ToString(it) as String else "" }
    override fun processMainThreadQueue() {
        jsMod._uapmd_mcp_server_process_main_thread_queue(handle)
    }
    override fun close() { jsMod._uapmd_mcp_server_destroy(handle) }
}

internal actual fun createJsRuntime(): JsRuntime? =
    (jsMod._uapmd_js_runtime_create() as Int).takeIf { it != 0 }?.let { JsJsRuntime(it) }

internal actual fun mcpIsSupported(): Boolean = jsMod._uapmd_mcp_is_supported() as Boolean
internal actual fun mcpHasHttpServer(): Boolean = jsMod._uapmd_mcp_has_http_server() as Boolean

internal actual fun createMcpServer(port: Int): McpServer? =
    (jsMod._uapmd_mcp_server_create(port) as Int).takeIf { it != 0 }?.let { JsMcpServer(it) }

internal actual fun createMcpClient(relayUrl: String, autoReconnect: Boolean): McpServer? =
    withJsCString(relayUrl) { p ->
        (jsMod._uapmd_mcp_client_create(p, autoReconnect) as Int).takeIf { it != 0 }
    }?.let { JsMcpServer(it) }
