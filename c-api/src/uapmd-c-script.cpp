/* uapmd C API — implementation for the JS runtime and MCP transport bindings */

#include "c-api/uapmd-c-script.h"

#include <uapmd-app-model/detail/UapmdJSRuntime.hpp>
#ifdef UAPMD_HAS_MCP_SERVER
#include <uapmd-app-model/detail/McpServer.hpp>
#endif

#include <exception>
#include <optional>
#include <string>
#include <string_view>

/* ── Cast helpers ─────────────────────────────────────────────────────────── */

static uapmd_app::UapmdJSRuntime* JS(uapmd_js_runtime_t h) {
    return reinterpret_cast<uapmd_app::UapmdJSRuntime*>(h);
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  JavaScript runtime
 * ═══════════════════════════════════════════════════════════════════════════ */

uapmd_js_runtime_t uapmd_js_runtime_create(void) {
    try {
        return reinterpret_cast<uapmd_js_runtime_t>(new uapmd_app::UapmdJSRuntime());
    } catch (...) {
        return nullptr;
    }
}

void uapmd_js_runtime_destroy(uapmd_js_runtime_t rt) {
    delete JS(rt);
}

bool uapmd_js_runtime_ensure_api_bootstrapped(uapmd_js_runtime_t rt) {
    if (!rt) return false;
    try {
        JS(rt)->ensureApiBootstrapped();
        return true;
    } catch (...) {
        /* Throws when the embedded uapmd-api.js resource is missing. */
        return false;
    }
}

void uapmd_js_runtime_reinitialize(uapmd_js_runtime_t rt) {
    if (rt) JS(rt)->reinitialize();
}

static thread_local std::string tl_js_json;
static thread_local std::string tl_js_error;
/* Holds whatever the resolver hands back for the duration of the evaluate:
 * the C callback's own storage is not ours to keep. */
static thread_local std::string tl_js_module;

uapmd_js_result_t uapmd_js_runtime_evaluate(uapmd_js_runtime_t rt,
                                            const char* code,
                                            void* user_data,
                                            uapmd_js_module_resolver_t resolver) {
    tl_js_json.clear();
    tl_js_error.clear();
    if (!rt) {
        tl_js_error = "no JavaScript runtime";
        return { false, nullptr, tl_js_error.c_str() };
    }

    std::function<std::optional<std::string>(std::string_view)> fallback;
    if (resolver) {
        fallback = [resolver, user_data](std::string_view path) -> std::optional<std::string> {
            tl_js_module.assign(path);
            const char* found = resolver(tl_js_module.c_str(), user_data);
            if (!found)
                return std::nullopt;
            return std::string(found);
        };
    }

    try {
        tl_js_json = JS(rt)->evaluateScript(code ? code : "", std::move(fallback));
        return { true, tl_js_json.c_str(), nullptr };
    } catch (const std::exception& e) {
        tl_js_error = e.what();
        return { false, nullptr, tl_js_error.c_str() };
    } catch (...) {
        tl_js_error = "unknown JavaScript error";
        return { false, nullptr, tl_js_error.c_str() };
    }
}

void uapmd_js_runtime_register_parameter_listener(uapmd_js_runtime_t rt, int32_t instance_id) {
    if (rt) JS(rt)->registerParameterListener(instance_id);
}
void uapmd_js_runtime_unregister_parameter_listener(uapmd_js_runtime_t rt, int32_t instance_id) {
    if (rt) JS(rt)->unregisterParameterListener(instance_id);
}
void uapmd_js_runtime_register_all_parameter_listeners(uapmd_js_runtime_t rt) {
    if (rt) JS(rt)->registerAllParameterListeners();
}
void uapmd_js_runtime_unregister_all_parameter_listeners(uapmd_js_runtime_t rt) {
    if (rt) JS(rt)->unregisterAllParameterListeners();
}
void uapmd_js_runtime_register_metadata_listener(uapmd_js_runtime_t rt, int32_t instance_id) {
    if (rt) JS(rt)->registerMetadataListener(instance_id);
}
void uapmd_js_runtime_unregister_metadata_listener(uapmd_js_runtime_t rt, int32_t instance_id) {
    if (rt) JS(rt)->unregisterMetadataListener(instance_id);
}
void uapmd_js_runtime_register_all_metadata_listeners(uapmd_js_runtime_t rt) {
    if (rt) JS(rt)->registerAllMetadataListeners();
}
void uapmd_js_runtime_unregister_all_metadata_listeners(uapmd_js_runtime_t rt) {
    if (rt) JS(rt)->unregisterAllMetadataListeners();
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  MCP transport
 * ═══════════════════════════════════════════════════════════════════════════ */

#ifdef UAPMD_HAS_MCP_SERVER

static uapmd_app::McpServer* MCP(uapmd_mcp_server_t h) {
    return reinterpret_cast<uapmd_app::McpServer*>(h);
}

bool uapmd_mcp_is_supported(void) { return true; }

bool uapmd_mcp_has_http_server(void) {
#ifdef UAPMD_MCP_HAS_HTTP_SERVER
    return true;
#else
    return false;
#endif
}

uapmd_mcp_server_t uapmd_mcp_server_create(int32_t port) {
#ifdef UAPMD_MCP_HAS_HTTP_SERVER
    try {
        return reinterpret_cast<uapmd_mcp_server_t>(new uapmd_app::McpServer(static_cast<int>(port)));
    } catch (...) {
        return nullptr;
    }
#else
    (void) port;
    return nullptr;
#endif
}

uapmd_mcp_server_t uapmd_mcp_client_create(const char* relay_url, bool auto_reconnect) {
    try {
        return reinterpret_cast<uapmd_mcp_server_t>(
            new uapmd_app::McpServer(std::string(relay_url ? relay_url : ""), auto_reconnect));
    } catch (...) {
        return nullptr;
    }
}

void uapmd_mcp_server_destroy(uapmd_mcp_server_t mcp) { delete MCP(mcp); }

void uapmd_mcp_server_start(uapmd_mcp_server_t mcp) { if (mcp) MCP(mcp)->start(); }
void uapmd_mcp_server_stop(uapmd_mcp_server_t mcp)  { if (mcp) MCP(mcp)->stop(); }

uapmd_mcp_mode_t uapmd_mcp_server_mode(uapmd_mcp_server_t mcp) {
    if (!mcp) return UAPMD_MCP_MODE_CLIENT;
    return MCP(mcp)->mode() == uapmd_app::McpConnectionMode::Server
        ? UAPMD_MCP_MODE_SERVER : UAPMD_MCP_MODE_CLIENT;
}

uapmd_mcp_state_t uapmd_mcp_server_connection_state(uapmd_mcp_server_t mcp) {
    if (!mcp) return UAPMD_MCP_STATE_IDLE;
    switch (MCP(mcp)->connectionState()) {
        case uapmd_app::McpConnectionState::Connecting: return UAPMD_MCP_STATE_CONNECTING;
        case uapmd_app::McpConnectionState::Connected:  return UAPMD_MCP_STATE_CONNECTED;
        case uapmd_app::McpConnectionState::Error:      return UAPMD_MCP_STATE_ERROR;
        case uapmd_app::McpConnectionState::Idle:
        default:                                       return UAPMD_MCP_STATE_IDLE;
    }
}

int32_t uapmd_mcp_server_port(uapmd_mcp_server_t mcp) {
    return mcp ? static_cast<int32_t>(MCP(mcp)->port()) : 0;
}

static thread_local std::string tl_mcp_status;

const char* uapmd_mcp_server_status_message(uapmd_mcp_server_t mcp) {
    if (!mcp) return "";
    tl_mcp_status = MCP(mcp)->statusMessage();
    return tl_mcp_status.c_str();
}

void uapmd_mcp_server_process_main_thread_queue(uapmd_mcp_server_t mcp) {
    if (mcp) MCP(mcp)->processMainThreadQueue();
}

#else /* !UAPMD_HAS_MCP_SERVER */

/* The whole surface still links, so a host can ask and get a clean "no". */
bool uapmd_mcp_is_supported(void) { return false; }
bool uapmd_mcp_has_http_server(void) { return false; }
uapmd_mcp_server_t uapmd_mcp_server_create(int32_t) { return nullptr; }
uapmd_mcp_server_t uapmd_mcp_client_create(const char*, bool) { return nullptr; }
void uapmd_mcp_server_destroy(uapmd_mcp_server_t) {}
void uapmd_mcp_server_start(uapmd_mcp_server_t) {}
void uapmd_mcp_server_stop(uapmd_mcp_server_t) {}
uapmd_mcp_mode_t uapmd_mcp_server_mode(uapmd_mcp_server_t) { return UAPMD_MCP_MODE_CLIENT; }
uapmd_mcp_state_t uapmd_mcp_server_connection_state(uapmd_mcp_server_t) { return UAPMD_MCP_STATE_IDLE; }
int32_t uapmd_mcp_server_port(uapmd_mcp_server_t) { return 0; }
const char* uapmd_mcp_server_status_message(uapmd_mcp_server_t) { return ""; }
void uapmd_mcp_server_process_main_thread_queue(uapmd_mcp_server_t) {}

#endif /* UAPMD_HAS_MCP_SERVER */
