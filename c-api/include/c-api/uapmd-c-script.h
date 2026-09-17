/* uapmd C API — bindings for UapmdJSRuntime and McpServer */
#ifndef UAPMD_C_SCRIPT_H
#define UAPMD_C_SCRIPT_H

#include "uapmd-c-common.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ═══════════════════════════════════════════════════════════════════════════
 *  JavaScript runtime
 *
 *  Model thread only.
 * ═══════════════════════════════════════════════════════════════════════════ */

typedef struct uapmd_js_runtime* uapmd_js_runtime_t;

UAPMD_C_EXPORT uapmd_js_runtime_t uapmd_js_runtime_create(void);
UAPMD_C_EXPORT void uapmd_js_runtime_destroy(uapmd_js_runtime_t rt);

/* Answers false when the embedded API bundle could not be loaded, which is the
 * one failure `ensureApiBootstrapped` reports. Idempotent. */
UAPMD_C_EXPORT bool uapmd_js_runtime_ensure_api_bootstrapped(uapmd_js_runtime_t rt);
UAPMD_C_EXPORT void uapmd_js_runtime_reinitialize(uapmd_js_runtime_t rt);

/* Resolves an ES module the embedded bundle does not carry. Return null for
 * "not found"; the returned string is copied before the call returns. */
typedef const char* (*uapmd_js_module_resolver_t)(const char* module_path, void* user_data);

typedef struct uapmd_js_result {
    bool success;
    /* The result as JSON, or "undefined". Points into per-thread storage that
     * the next evaluate on this thread overwrites. */
    const char* json;
    /* Null when `success`. Same storage rule. */
    const char* error;
} uapmd_js_result_t;

/* Evaluates `code`: as an ES module when it contains an import, otherwise as an
 * expression. `resolver` may be null. The C++ call throws on a script error;
 * that is caught here and reported through `error`. */
UAPMD_C_EXPORT uapmd_js_result_t uapmd_js_runtime_evaluate(uapmd_js_runtime_t rt,
                                                              const char* code,
                                                              void* user_data,
                                                              uapmd_js_module_resolver_t resolver);

/* Parameter and metadata change listeners, per plug-in instance. A script polls
 * for what these queue, so a runtime that never registers sees nothing. */
UAPMD_C_EXPORT void uapmd_js_runtime_register_parameter_listener(uapmd_js_runtime_t rt, int32_t instance_id);
UAPMD_C_EXPORT void uapmd_js_runtime_unregister_parameter_listener(uapmd_js_runtime_t rt, int32_t instance_id);
UAPMD_C_EXPORT void uapmd_js_runtime_register_all_parameter_listeners(uapmd_js_runtime_t rt);
UAPMD_C_EXPORT void uapmd_js_runtime_unregister_all_parameter_listeners(uapmd_js_runtime_t rt);
UAPMD_C_EXPORT void uapmd_js_runtime_register_metadata_listener(uapmd_js_runtime_t rt, int32_t instance_id);
UAPMD_C_EXPORT void uapmd_js_runtime_unregister_metadata_listener(uapmd_js_runtime_t rt, int32_t instance_id);
UAPMD_C_EXPORT void uapmd_js_runtime_register_all_metadata_listeners(uapmd_js_runtime_t rt);
UAPMD_C_EXPORT void uapmd_js_runtime_unregister_all_metadata_listeners(uapmd_js_runtime_t rt);

/* ═══════════════════════════════════════════════════════════════════════════
 *  MCP transport
 * ═══════════════════════════════════════════════════════════════════════════ */

typedef struct uapmd_mcp_server* uapmd_mcp_server_t;

typedef enum uapmd_mcp_mode {
    UAPMD_MCP_MODE_SERVER = 0,
    UAPMD_MCP_MODE_CLIENT = 1
} uapmd_mcp_mode_t;

typedef enum uapmd_mcp_state {
    UAPMD_MCP_STATE_IDLE = 0,
    UAPMD_MCP_STATE_CONNECTING = 1,
    UAPMD_MCP_STATE_CONNECTED = 2,
    UAPMD_MCP_STATE_ERROR = 3
} uapmd_mcp_state_t;

/* Whether this build has MCP at all, and whether it can run the embedded HTTP
 * server. Server mode is desktop-only; wasm, iOS and Android have client mode
 * only, so a host must ask before offering the choice. */
UAPMD_C_EXPORT bool uapmd_mcp_is_supported(void);
UAPMD_C_EXPORT bool uapmd_mcp_has_http_server(void);

/* Server mode: listens on localhost:port. Null when this build has no embedded
 * HTTP server. */
UAPMD_C_EXPORT uapmd_mcp_server_t uapmd_mcp_server_create(int32_t port);
/* Client mode: connects out to an MCP relay, e.g. "ws://host:8765/mcp". */
UAPMD_C_EXPORT uapmd_mcp_server_t uapmd_mcp_client_create(const char* relay_url, bool auto_reconnect);
UAPMD_C_EXPORT void uapmd_mcp_server_destroy(uapmd_mcp_server_t mcp);

UAPMD_C_EXPORT void uapmd_mcp_server_start(uapmd_mcp_server_t mcp);
UAPMD_C_EXPORT void uapmd_mcp_server_stop(uapmd_mcp_server_t mcp);

UAPMD_C_EXPORT uapmd_mcp_mode_t  uapmd_mcp_server_mode(uapmd_mcp_server_t mcp);
UAPMD_C_EXPORT uapmd_mcp_state_t uapmd_mcp_server_connection_state(uapmd_mcp_server_t mcp);
UAPMD_C_EXPORT int32_t           uapmd_mcp_server_port(uapmd_mcp_server_t mcp);
/* Points into per-thread storage that the next call on this thread overwrites. */
UAPMD_C_EXPORT const char*       uapmd_mcp_server_status_message(uapmd_mcp_server_t mcp);

/* Dispatches queued tool calls. Must be called from the model thread, and often
 * enough that a caller waiting on a tool result is not left waiting: uapmd-app
 * calls it once per rendered frame. */
UAPMD_C_EXPORT void uapmd_mcp_server_process_main_thread_queue(uapmd_mcp_server_t mcp);

#ifdef __cplusplus
}
#endif

#endif /* UAPMD_C_SCRIPT_H */
