/*
 * uapmd JNI bridge — JS runtime and MCP transport
 *
 * The module resolver crosses as a Kotlin object with an invoke(String): String?
 * method, the same shape the other callbacks in this bridge use. Its result is
 * copied into a std::string that outlives the JNI local ref, because the C API
 * only promises the pointer for the duration of the call.
 */
#include <jni.h>
#include <string>

#include "c-api/uapmd-c-script.h"

extern JNIEnv* uapmd_jni_env();

namespace {

template <typename T> T j2p(jlong h) { return reinterpret_cast<T>(static_cast<uintptr_t>(h)); }

inline uapmd_js_runtime_t RT(jlong h) { return j2p<uapmd_js_runtime_t>(h); }
inline uapmd_mcp_server_t MCP(jlong h) { return j2p<uapmd_mcp_server_t>(h); }

struct JStr {
    JNIEnv* env; jstring str; const char* chars;
    JStr(JNIEnv* e, jstring s) : env(e), str(s), chars(s ? e->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~JStr() { if (str && chars) env->ReleaseStringUTFChars(str, chars); }
    const char* c_str() const { return chars ? chars : ""; }
};

/* Kept alive across the resolver's return; see the file comment. */
thread_local std::string tl_resolved;

struct ResolverCtx {
    jobject obj{nullptr};
    jmethodID mid{nullptr};
};

const char* resolver_trampoline(const char* module_path, void* ud) {
    auto* ctx = static_cast<ResolverCtx*>(ud);
    if (!ctx || !ctx->mid) return nullptr;
    JNIEnv* e = uapmd_jni_env();
    if (!e) return nullptr;
    jstring path = e->NewStringUTF(module_path ? module_path : "");
    auto found = reinterpret_cast<jstring>(e->CallObjectMethod(ctx->obj, ctx->mid, path));
    e->DeleteLocalRef(path);
    if (!found) return nullptr;
    JStr s(e, found);
    tl_resolved.assign(s.c_str());
    e->DeleteLocalRef(found);
    return tl_resolved.c_str();
}

} // namespace

extern "C" {

#define JNI_FN(ret, name) JNIEXPORT ret JNICALL Java_dev_atsushieno_uapmd_JniBridge_##name

JNI_FN(jlong, uapmdJsRuntimeCreate)(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(uapmd_js_runtime_create());
}
JNI_FN(void, uapmdJsRuntimeDestroy)(JNIEnv*, jclass, jlong rt) {
    uapmd_js_runtime_destroy(RT(rt));
}
JNI_FN(jboolean, uapmdJsRuntimeEnsureApiBootstrapped)(JNIEnv*, jclass, jlong rt) {
    return uapmd_js_runtime_ensure_api_bootstrapped(RT(rt));
}
JNI_FN(void, uapmdJsRuntimeReinitialize)(JNIEnv*, jclass, jlong rt) {
    uapmd_js_runtime_reinitialize(RT(rt));
}

/** Object[]{ boolean[1] success, String? json, String? error }. */
JNI_FN(jobjectArray, uapmdJsRuntimeEvaluate)(JNIEnv* env, jclass, jlong rt, jstring code, jobject resolver) {
    JStr c(env, code);
    ResolverCtx ctx;
    if (resolver) {
        ctx.obj = resolver;
        ctx.mid = env->GetMethodID(env->GetObjectClass(resolver), "invoke",
                                   "(Ljava/lang/String;)Ljava/lang/String;");
        if (!ctx.mid)
            env->ExceptionClear();
    }
    auto r = uapmd_js_runtime_evaluate(RT(rt), c.c_str(),
                                       ctx.mid ? &ctx : nullptr,
                                       ctx.mid ? resolver_trampoline : nullptr);

    jboolean ok = r.success ? JNI_TRUE : JNI_FALSE;
    jbooleanArray okArr = env->NewBooleanArray(1);
    env->SetBooleanArrayRegion(okArr, 0, 1, &ok);
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray out = env->NewObjectArray(3, objectClass, nullptr);
    env->SetObjectArrayElement(out, 0, okArr);
    if (r.json)  env->SetObjectArrayElement(out, 1, env->NewStringUTF(r.json));
    if (r.error) env->SetObjectArrayElement(out, 2, env->NewStringUTF(r.error));
    return out;
}

JNI_FN(void, uapmdJsRuntimeRegisterParameterListener)(JNIEnv*, jclass, jlong rt, jint id) {
    uapmd_js_runtime_register_parameter_listener(RT(rt), id);
}
JNI_FN(void, uapmdJsRuntimeUnregisterParameterListener)(JNIEnv*, jclass, jlong rt, jint id) {
    uapmd_js_runtime_unregister_parameter_listener(RT(rt), id);
}
JNI_FN(void, uapmdJsRuntimeRegisterAllParameterListeners)(JNIEnv*, jclass, jlong rt) {
    uapmd_js_runtime_register_all_parameter_listeners(RT(rt));
}
JNI_FN(void, uapmdJsRuntimeUnregisterAllParameterListeners)(JNIEnv*, jclass, jlong rt) {
    uapmd_js_runtime_unregister_all_parameter_listeners(RT(rt));
}
JNI_FN(void, uapmdJsRuntimeRegisterMetadataListener)(JNIEnv*, jclass, jlong rt, jint id) {
    uapmd_js_runtime_register_metadata_listener(RT(rt), id);
}
JNI_FN(void, uapmdJsRuntimeUnregisterMetadataListener)(JNIEnv*, jclass, jlong rt, jint id) {
    uapmd_js_runtime_unregister_metadata_listener(RT(rt), id);
}
JNI_FN(void, uapmdJsRuntimeRegisterAllMetadataListeners)(JNIEnv*, jclass, jlong rt) {
    uapmd_js_runtime_register_all_metadata_listeners(RT(rt));
}
JNI_FN(void, uapmdJsRuntimeUnregisterAllMetadataListeners)(JNIEnv*, jclass, jlong rt) {
    uapmd_js_runtime_unregister_all_metadata_listeners(RT(rt));
}

JNI_FN(jboolean, uapmdMcpIsSupported)(JNIEnv*, jclass) { return uapmd_mcp_is_supported(); }
JNI_FN(jboolean, uapmdMcpHasHttpServer)(JNIEnv*, jclass) { return uapmd_mcp_has_http_server(); }

JNI_FN(jlong, uapmdMcpServerCreate)(JNIEnv*, jclass, jint port) {
    return reinterpret_cast<jlong>(uapmd_mcp_server_create(port));
}
JNI_FN(jlong, uapmdMcpClientCreate)(JNIEnv* env, jclass, jstring url, jboolean autoReconnect) {
    JStr u(env, url);
    return reinterpret_cast<jlong>(uapmd_mcp_client_create(u.c_str(), autoReconnect));
}
JNI_FN(void, uapmdMcpServerDestroy)(JNIEnv*, jclass, jlong mcp) { uapmd_mcp_server_destroy(MCP(mcp)); }
JNI_FN(void, uapmdMcpServerStart)(JNIEnv*, jclass, jlong mcp) { uapmd_mcp_server_start(MCP(mcp)); }
JNI_FN(void, uapmdMcpServerStop)(JNIEnv*, jclass, jlong mcp) { uapmd_mcp_server_stop(MCP(mcp)); }
JNI_FN(jint, uapmdMcpServerMode)(JNIEnv*, jclass, jlong mcp) {
    return static_cast<jint>(uapmd_mcp_server_mode(MCP(mcp)));
}
JNI_FN(jint, uapmdMcpServerConnectionState)(JNIEnv*, jclass, jlong mcp) {
    return static_cast<jint>(uapmd_mcp_server_connection_state(MCP(mcp)));
}
JNI_FN(jint, uapmdMcpServerPort)(JNIEnv*, jclass, jlong mcp) {
    return uapmd_mcp_server_port(MCP(mcp));
}
JNI_FN(jstring, uapmdMcpServerStatusMessage)(JNIEnv* env, jclass, jlong mcp) {
    const char* s = uapmd_mcp_server_status_message(MCP(mcp));
    return env->NewStringUTF(s ? s : "");
}
JNI_FN(void, uapmdMcpServerProcessMainThreadQueue)(JNIEnv*, jclass, jlong mcp) {
    uapmd_mcp_server_process_main_thread_queue(MCP(mcp));
}

#undef JNI_FN

} // extern "C"
