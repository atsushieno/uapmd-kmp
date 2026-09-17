package dev.atsushieno.uapmd

/** What [JsRuntime.evaluate] produced: JSON on success, a message on failure. */
data class JsResult(
    val success: Boolean,
    /** `"undefined"` when the script produced no value. Null on failure. */
    val json: String?,
    val error: String?
)

interface JsRuntime : AutoCloseable {
    fun ensureApiBootstrapped(): Boolean
    fun reinitialize()

    /**
     * [moduleResolver] is consulted for an ES module the embedded bundle does
     * not carry; return null for "not found". It is called during the
     * evaluation, on the calling thread.
     */
    fun evaluate(code: String, moduleResolver: ((String) -> String?)? = null): JsResult

    fun registerParameterListener(instanceId: Int)
    fun unregisterParameterListener(instanceId: Int)
    fun registerAllParameterListeners()
    fun unregisterAllParameterListeners()
    fun registerMetadataListener(instanceId: Int)
    fun unregisterMetadataListener(instanceId: Int)
    fun registerAllMetadataListeners()
    fun unregisterAllMetadataListeners()

    companion object {
        /** Null when the runtime could not be constructed. */
        fun create(): JsRuntime? = createJsRuntime()
    }
}

enum class McpMode { Server, Client }

enum class McpState { Idle, Connecting, Connected, Error }

interface McpServer : AutoCloseable {
    fun start()
    fun stop()

    val mode: McpMode
    val connectionState: McpState
    /** Server mode only. */
    val port: Int
    val statusMessage: String

    /**
     * Dispatches queued tool calls on the model thread. Nothing arrives until
     * this is called, and a caller waiting on a tool result waits exactly as
     * long as the gap between calls.
     */
    fun processMainThreadQueue()

    companion object {
        val isSupported: Boolean get() = mcpIsSupported()

        /** False on wasm, iOS and Android: those builds have client mode only. */
        val hasHttpServer: Boolean get() = mcpHasHttpServer()

        /** Null when [hasHttpServer] is false. */
        fun server(port: Int): McpServer? = createMcpServer(port)
        fun client(relayUrl: String, autoReconnect: Boolean = true): McpServer? =
            createMcpClient(relayUrl, autoReconnect)
    }
}

internal expect fun createJsRuntime(): JsRuntime?
internal expect fun mcpIsSupported(): Boolean
internal expect fun mcpHasHttpServer(): Boolean
internal expect fun createMcpServer(port: Int): McpServer?
internal expect fun createMcpClient(relayUrl: String, autoReconnect: Boolean): McpServer?
