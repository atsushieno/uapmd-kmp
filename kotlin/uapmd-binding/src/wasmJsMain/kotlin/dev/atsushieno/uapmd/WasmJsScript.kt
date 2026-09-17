package dev.atsushieno.uapmd

/**
 * uapmd_js_result_t: bool @0, char* json @4, char* error @8 (12 bytes),
 * returned through a hidden first argument (Emscripten sret).
 */
private const val JsResultSize = 12

class WasmJsJsRuntime internal constructor(internal val handle: Int) : JsRuntime {
    override fun ensureApiBootstrapped() = wasmMod.uapmdJsRuntimeEnsureApiBootstrapped(handle)
    override fun reinitialize() = wasmMod.uapmdJsRuntimeReinitialize(handle)

    override fun evaluate(code: String, moduleResolver: ((String) -> String?)?): JsResult {
        val mod = wasmMod
        // Whatever the resolver hands back has to stay alive until evaluation
        // ends, so the allocations are released only after the call returns.
        val owned = mutableListOf<Int>()
        var cbId = 0
        var resolverPtr = 0
        if (moduleResolver != null) {
            cbId = nextCallbackId()
            pendingJsModuleResolvers[cbId] = { path ->
                val found = moduleResolver(path)
                if (found == null) 0 else {
                    val size = mod.lengthBytesUTF8(found) + 1
                    val p = mod.malloc(size)
                    mod.stringToUTF8(found, p, size)
                    owned += p
                    p
                }
            }
            resolverPtr = makeCFunctionPtr(cbId, "uapmdDispatchJsModuleResolver", "iii")
        }

        try {
            return withWasmStruct(JsResultSize) { out ->
                withCStringKt(code) { codePtr ->
                    mod.uapmdJsRuntimeEvaluate(out, handle, codePtr, 0, resolverPtr)
                }
                val ok = mod.getValue(out, "i8").toInt() != 0
                JsResult(
                    ok,
                    if (ok) wasmStrOrNull(out + 4) else null,
                    if (ok) null else wasmStrOrNull(out + 8)
                )
            }
        } finally {
            owned.forEach { mod.free(it) }
            if (cbId != 0) pendingJsModuleResolvers.remove(cbId)
        }
    }

    override fun registerParameterListener(instanceId: Int) =
        wasmMod.uapmdJsRuntimeRegisterParameterListener(handle, instanceId)
    override fun unregisterParameterListener(instanceId: Int) =
        wasmMod.uapmdJsRuntimeUnregisterParameterListener(handle, instanceId)
    override fun registerAllParameterListeners() =
        wasmMod.uapmdJsRuntimeRegisterAllParameterListeners(handle)
    override fun unregisterAllParameterListeners() =
        wasmMod.uapmdJsRuntimeUnregisterAllParameterListeners(handle)
    override fun registerMetadataListener(instanceId: Int) =
        wasmMod.uapmdJsRuntimeRegisterMetadataListener(handle, instanceId)
    override fun unregisterMetadataListener(instanceId: Int) =
        wasmMod.uapmdJsRuntimeUnregisterMetadataListener(handle, instanceId)
    override fun registerAllMetadataListeners() =
        wasmMod.uapmdJsRuntimeRegisterAllMetadataListeners(handle)
    override fun unregisterAllMetadataListeners() =
        wasmMod.uapmdJsRuntimeUnregisterAllMetadataListeners(handle)

    override fun close() = wasmMod.uapmdJsRuntimeDestroy(handle)
}

private fun wasmStrOrNull(ptr: Int): String? {
    val p = wasmMod.getValue(ptr, "i32").toInt()
    return if (p != 0) wasmMod.utf8ToString(p) else null
}

class WasmJsMcpServer internal constructor(internal val handle: Int) : McpServer {
    override fun start() = wasmMod.uapmdMcpServerStart(handle)
    override fun stop() = wasmMod.uapmdMcpServerStop(handle)
    override val mode: McpMode
        get() = if (wasmMod.uapmdMcpServerMode(handle) == 0) McpMode.Server else McpMode.Client
    override val connectionState: McpState
        get() = when (wasmMod.uapmdMcpServerConnectionState(handle)) {
            1 -> McpState.Connecting
            2 -> McpState.Connected
            3 -> McpState.Error
            else -> McpState.Idle
        }
    override val port: Int get() = wasmMod.uapmdMcpServerPort(handle)
    override val statusMessage: String
        get() = wasmMod.uapmdMcpServerStatusMessage(handle)
            .let { if (it != 0) wasmMod.utf8ToString(it) else "" }
    override fun processMainThreadQueue() = wasmMod.uapmdMcpServerProcessMainThreadQueue(handle)
    override fun close() = wasmMod.uapmdMcpServerDestroy(handle)
}

internal actual fun createJsRuntime(): JsRuntime? =
    wasmMod.uapmdJsRuntimeCreate().takeIf { it != 0 }?.let { WasmJsJsRuntime(it) }

internal actual fun mcpIsSupported(): Boolean = wasmMod.uapmdMcpIsSupported()
internal actual fun mcpHasHttpServer(): Boolean = wasmMod.uapmdMcpHasHttpServer()

internal actual fun createMcpServer(port: Int): McpServer? =
    wasmMod.uapmdMcpServerCreate(port).takeIf { it != 0 }?.let { WasmJsMcpServer(it) }

internal actual fun createMcpClient(relayUrl: String, autoReconnect: Boolean): McpServer? =
    withCStringKt(relayUrl) { p ->
        wasmMod.uapmdMcpClientCreate(p, autoReconnect).takeIf { it != 0 }
    }?.let { WasmJsMcpServer(it) }
