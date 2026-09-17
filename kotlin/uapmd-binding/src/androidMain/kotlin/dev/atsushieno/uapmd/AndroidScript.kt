package dev.atsushieno.uapmd

/**
 * A bare lambda only carries the erased FunctionN.invoke(Object)Object, so the
 * native side would not find the descriptor it looks up. Wrapping it in a class
 * that declares the exact types gives it one, as the rest of this bridge does.
 */
private class JsModuleResolver(private val resolve: (String) -> String?) {
    @Suppress("unused")
    fun invoke(modulePath: String): String? = resolve(modulePath)
}

class AndroidJsRuntime internal constructor(internal val handle: Long) : JsRuntime {
    override fun ensureApiBootstrapped() = JniBridge.uapmdJsRuntimeEnsureApiBootstrapped(handle)
    override fun reinitialize() = JniBridge.uapmdJsRuntimeReinitialize(handle)

    override fun evaluate(code: String, moduleResolver: ((String) -> String?)?): JsResult {
        val packed = JniBridge.uapmdJsRuntimeEvaluate(
            handle, code, moduleResolver?.let { JsModuleResolver(it) }
        )
        val ok = (packed[0] as BooleanArray)[0]
        return JsResult(ok, packed.getOrNull(1) as? String, packed.getOrNull(2) as? String)
    }

    override fun registerParameterListener(instanceId: Int) =
        JniBridge.uapmdJsRuntimeRegisterParameterListener(handle, instanceId)
    override fun unregisterParameterListener(instanceId: Int) =
        JniBridge.uapmdJsRuntimeUnregisterParameterListener(handle, instanceId)
    override fun registerAllParameterListeners() =
        JniBridge.uapmdJsRuntimeRegisterAllParameterListeners(handle)
    override fun unregisterAllParameterListeners() =
        JniBridge.uapmdJsRuntimeUnregisterAllParameterListeners(handle)
    override fun registerMetadataListener(instanceId: Int) =
        JniBridge.uapmdJsRuntimeRegisterMetadataListener(handle, instanceId)
    override fun unregisterMetadataListener(instanceId: Int) =
        JniBridge.uapmdJsRuntimeUnregisterMetadataListener(handle, instanceId)
    override fun registerAllMetadataListeners() =
        JniBridge.uapmdJsRuntimeRegisterAllMetadataListeners(handle)
    override fun unregisterAllMetadataListeners() =
        JniBridge.uapmdJsRuntimeUnregisterAllMetadataListeners(handle)

    override fun close() = JniBridge.uapmdJsRuntimeDestroy(handle)
}

class AndroidMcpServer internal constructor(internal val handle: Long) : McpServer {
    override fun start() = JniBridge.uapmdMcpServerStart(handle)
    override fun stop() = JniBridge.uapmdMcpServerStop(handle)
    override val mode: McpMode
        get() = if (JniBridge.uapmdMcpServerMode(handle) == 0) McpMode.Server else McpMode.Client
    override val connectionState: McpState
        get() = when (JniBridge.uapmdMcpServerConnectionState(handle)) {
            1 -> McpState.Connecting
            2 -> McpState.Connected
            3 -> McpState.Error
            else -> McpState.Idle
        }
    override val port: Int get() = JniBridge.uapmdMcpServerPort(handle)
    override val statusMessage: String get() = JniBridge.uapmdMcpServerStatusMessage(handle)
    override fun processMainThreadQueue() = JniBridge.uapmdMcpServerProcessMainThreadQueue(handle)
    override fun close() = JniBridge.uapmdMcpServerDestroy(handle)
}

internal actual fun createJsRuntime(): JsRuntime? =
    JniBridge.uapmdJsRuntimeCreate().takeIf { it != 0L }?.let { AndroidJsRuntime(it) }

internal actual fun mcpIsSupported(): Boolean = JniBridge.uapmdMcpIsSupported()
internal actual fun mcpHasHttpServer(): Boolean = JniBridge.uapmdMcpHasHttpServer()

internal actual fun createMcpServer(port: Int): McpServer? =
    JniBridge.uapmdMcpServerCreate(port).takeIf { it != 0L }?.let { AndroidMcpServer(it) }

internal actual fun createMcpClient(relayUrl: String, autoReconnect: Boolean): McpServer? =
    JniBridge.uapmdMcpClientCreate(relayUrl, autoReconnect).takeIf { it != 0L }
        ?.let { AndroidMcpServer(it) }
