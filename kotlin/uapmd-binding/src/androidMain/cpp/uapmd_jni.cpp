/**
 * uapmd JNI bridge
 *
 * Wraps every function in the uapmd C API so it can be called from Kotlin
 * via external fun declarations in JniBridge.kt.
 *
 * Handle convention: opaque C pointers are cast to/from jlong (int64_t).
 * Callbacks: stored as jobject global refs; dispatched via cached jmethodIDs.
 */

#include <jni.h>
#include <android/log.h>
#include <functional>
#include <string>
#include <vector>
#include <mutex>
#include <unordered_map>
#include <atomic>

#include "c-api/uapmd-c-common.h"
#include "c-api/uapmd-c-api.h"
#include "c-api/uapmd-c-data.h"
#include "c-api/uapmd-c-engine.h"
#include "c-api/uapmd-c-tooling.h"

#define LOG_TAG "uapmd-jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─── Global JVM reference ────────────────────────────────────────────────────

static JavaVM* g_jvm = nullptr;

jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

static JNIEnv* jni_env() {
    JNIEnv* env = nullptr;
    jint rc = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    }
    return env;
}

// ─── Handle helpers ───────────────────────────────────────────────────────────

template<typename T>
static inline jlong p2j(T ptr) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ptr));
}

template<typename T>
static inline T j2p(jlong handle) {
    return reinterpret_cast<T>(static_cast<uintptr_t>(handle));
}

// ─── String helpers ───────────────────────────────────────────────────────────

// Call a C two-call string getter and return a Java string.
template<typename Fn>
static jstring cstr(JNIEnv* env, Fn fn) {
    size_t n = fn(nullptr, 0);
    if (!n) return env->NewStringUTF("");
    std::string buf(n, '\0');
    fn(buf.data(), n);
    if (!buf.empty() && buf.back() == '\0') buf.pop_back();
    return env->NewStringUTF(buf.c_str());
}

static const char* jstr(JNIEnv* env, jstring s, const char* fallback = "") {
    return s ? env->GetStringUTFChars(s, nullptr) : fallback;
}

static void jstr_release(JNIEnv* env, jstring s, const char* c) {
    if (s) env->ReleaseStringUTFChars(s, c);
}

// ─── Async callback context ───────────────────────────────────────────────────

struct AsyncCtx {
    jobject obj;
    jmethodID mid;

    AsyncCtx(JNIEnv* env, jobject o, jmethodID m)
        : obj(env->NewGlobalRef(o)), mid(m) {}

    ~AsyncCtx() {
        if (obj) {
            JNIEnv* e = jni_env();
            if (e) e->DeleteGlobalRef(obj);
        }
    }

    JNIEnv* env() { return jni_env(); }
};

// ─── MIDI input handler map (supports multiple handlers per IO device) ────────

struct MidiHandlerCtx {
    jobject obj;  // global ref to Kotlin UmpReceiver
    jmethodID mid;
    bool active = true;
};

static std::mutex g_midi_mu;
static std::unordered_map<int64_t, MidiHandlerCtx*> g_midi_handlers;
static std::atomic<int64_t> g_midi_counter{0};

// ─── Android EventLoop bridge ─────────────────────────────────────────────────
// remidy::EventLoop::runTaskOnMainThread() enqueues a task on the "native main"
// thread and blocks the caller until it completes.  We redirect this to the
// Android main looper so that plugins that require the UI thread work correctly.

struct AndroidElCtx {
    jobject  dispatcher;      // global ref: AndroidEventLoopDispatcher Kotlin obj
    jmethodID dispatch_method; // dispatchTask(J)V
};

static AndroidElCtx*            g_android_el = nullptr;
static std::mutex               g_el_task_mutex;
static std::atomic<jlong>       g_el_next_id{1};
static std::unordered_map<jlong, std::function<void()>> g_el_tasks;

static bool android_is_main_thread(void*) {
    JNIEnv* env = jni_env();
    if (!env) return false;
    jclass cls = env->FindClass("android/os/Looper");
    if (!cls) return false;
    jmethodID my_lp   = env->GetStaticMethodID(cls, "myLooper",   "()Landroid/os/Looper;");
    jmethodID main_lp = env->GetStaticMethodID(cls, "getMainLooper", "()Landroid/os/Looper;");
    if (!my_lp || !main_lp) { env->DeleteLocalRef(cls); return false; }
    jobject cur  = env->CallStaticObjectMethod(cls, my_lp);
    jobject main = env->CallStaticObjectMethod(cls, main_lp);
    bool on_main = cur && main && env->IsSameObject(cur, main);
    if (cur)  env->DeleteLocalRef(cur);
    if (main) env->DeleteLocalRef(main);
    env->DeleteLocalRef(cls);
    return on_main;
}

static void android_enqueue_task(uapmd_event_loop_task_fn_t task_fn, void* task_ctx, void*) {
    auto* ctx = g_android_el;
    if (!ctx) { task_fn(task_ctx); return; } // safe fallback before init
    jlong token = g_el_next_id++;
    {
        std::lock_guard<std::mutex> lock(g_el_task_mutex);
        g_el_tasks[token] = [task_fn, task_ctx]() { task_fn(task_ctx); };
    }
    JNIEnv* env = jni_env();
    if (env)
        env->CallVoidMethod(ctx->dispatcher, ctx->dispatch_method, token);
}

// Single C trampoline for all JNI-backed MIDI handlers.
static void midi_ump_trampoline(void* context, uapmd_ump_t* ump, size_t size, uapmd_timestamp_t ts) {
    auto id = reinterpret_cast<int64_t>(context);
    JNIEnv* env = jni_env();
    if (!env) return;

    MidiHandlerCtx* ctx = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_midi_mu);
        auto it = g_midi_handlers.find(id);
        if (it == g_midi_handlers.end() || !it->second->active) return;
        ctx = it->second;
    }

    jint count = static_cast<jint>(size / sizeof(uint32_t));
    jintArray arr = env->NewIntArray(count);
    env->SetIntArrayRegion(arr, 0, count, reinterpret_cast<const jint*>(ump));
    env->CallVoidMethod(ctx->obj, ctx->mid, arr, static_cast<jlong>(ts));
    env->DeleteLocalRef(arr);
    if (env->ExceptionCheck()) env->ExceptionDescribe();
}

#define PKG "dev/atsushieno/uapmd/"
#define JNI_BRIDGE PKG "JniBridge"

// ─── PluginInstance ───────────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jstring JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceDisplayName(
        JNIEnv* env, jclass, jlong h) {
    auto inst = j2p<uapmd_plugin_instance_t>(h);
    return cstr(env, [&](char* b, size_t n){ return uapmd_instance_display_name(inst, b, n); });
}
JNIEXPORT jstring JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceFormatName(
        JNIEnv* env, jclass, jlong h) {
    auto inst = j2p<uapmd_plugin_instance_t>(h);
    return cstr(env, [&](char* b, size_t n){ return uapmd_instance_format_name(inst, b, n); });
}
JNIEXPORT jstring JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstancePluginId(
        JNIEnv* env, jclass, jlong h) {
    auto inst = j2p<uapmd_plugin_instance_t>(h);
    return cstr(env, [&](char* b, size_t n){ return uapmd_instance_plugin_id(inst, b, n); });
}

JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceGetBypassed(
        JNIEnv*, jclass, jlong h) {
    return uapmd_instance_get_bypassed(j2p<uapmd_plugin_instance_t>(h));
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceSetBypassed(
        JNIEnv*, jclass, jlong h, jboolean v) {
    uapmd_instance_set_bypassed(j2p<uapmd_plugin_instance_t>(h), v);
}
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceStartProcessing(
        JNIEnv*, jclass, jlong h) {
    return uapmd_instance_start_processing(j2p<uapmd_plugin_instance_t>(h));
}
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceStopProcessing(
        JNIEnv*, jclass, jlong h) {
    return uapmd_instance_stop_processing(j2p<uapmd_plugin_instance_t>(h));
}
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceLatencyInSamples(
        JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_instance_latency_in_samples(j2p<uapmd_plugin_instance_t>(h)));
}
JNIEXPORT jdouble JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceTailLengthInSeconds(
        JNIEnv*, jclass, jlong h) {
    return uapmd_instance_tail_length_in_seconds(j2p<uapmd_plugin_instance_t>(h));
}
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceRequiresReplacingProcess(
        JNIEnv*, jclass, jlong h) {
    return uapmd_instance_requires_replacing_process(j2p<uapmd_plugin_instance_t>(h));
}
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceParameterCount(
        JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_instance_parameter_count(j2p<uapmd_plugin_instance_t>(h)));
}
JNIEXPORT jdouble JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceGetParameterValue(
        JNIEnv*, jclass, jlong h, jint idx) {
    return uapmd_instance_get_parameter_value(j2p<uapmd_plugin_instance_t>(h), idx);
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceSetParameterValue(
        JNIEnv*, jclass, jlong h, jint idx, jdouble v) {
    uapmd_instance_set_parameter_value(j2p<uapmd_plugin_instance_t>(h), idx, v);
}
JNIEXPORT jstring JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceGetParameterValueString(
        JNIEnv* env, jclass, jlong h, jint idx, jdouble v) {
    auto inst = j2p<uapmd_plugin_instance_t>(h);
    return cstr(env, [&](char* b, size_t n){
        return uapmd_instance_get_parameter_value_string(inst, idx, v, b, n);
    });
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceSetPerNoteControllerValue(
        JNIEnv*, jclass, jlong h, jbyte note, jbyte index, jdouble v) {
    uapmd_instance_set_per_note_controller_value(j2p<uapmd_plugin_instance_t>(h), note, index, v);
}
JNIEXPORT jstring JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceGetPerNoteControllerValueString(
        JNIEnv* env, jclass, jlong h, jbyte note, jbyte index, jdouble v) {
    auto inst = j2p<uapmd_plugin_instance_t>(h);
    return cstr(env, [&](char* b, size_t n){
        return uapmd_instance_get_per_note_controller_value_string(inst, note, index, v, b, n);
    });
}
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstancePresetCount(
        JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_instance_preset_count(j2p<uapmd_plugin_instance_t>(h)));
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceLoadPreset(
        JNIEnv*, jclass, jlong h, jint idx) {
    uapmd_instance_load_preset(j2p<uapmd_plugin_instance_t>(h), idx);
}

// Parameter metadata – returns a flat double[] {index, stable_id_hash?, ...}
// For simplicity, each field is returned via dedicated JNI functions.
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceGetParameterMetadata(
        JNIEnv* env, jclass, jlong h, jint idx,
        jintArray outIndex,
        jobjectArray outStrings,   // [stableId, name, path]
        jdoubleArray outDoubles,   // [defaultVal, minVal, maxVal]
        jbooleanArray outBools,    // [automatable, hidden, discrete]
        jintArray outNamedCount) {
    uapmd_parameter_metadata_t meta{};
    if (!uapmd_instance_get_parameter_metadata(j2p<uapmd_plugin_instance_t>(h), idx, &meta))
        return false;

    jint iidx = static_cast<jint>(meta.index);
    env->SetIntArrayRegion(outIndex, 0, 1, &iidx);

    env->SetObjectArrayElement(outStrings, 0, env->NewStringUTF(meta.stable_id ? meta.stable_id : ""));
    env->SetObjectArrayElement(outStrings, 1, env->NewStringUTF(meta.name ? meta.name : ""));
    env->SetObjectArrayElement(outStrings, 2, env->NewStringUTF(meta.path ? meta.path : ""));

    jdouble dvals[3] = {meta.default_plain_value, meta.min_plain_value, meta.max_plain_value};
    env->SetDoubleArrayRegion(outDoubles, 0, 3, dvals);

    jboolean bvals[3] = {(jboolean)meta.automatable, (jboolean)meta.hidden, (jboolean)meta.discrete};
    env->SetBooleanArrayRegion(outBools, 0, 3, bvals);

    jint nc = static_cast<jint>(meta.named_values_count);
    env->SetIntArrayRegion(outNamedCount, 0, 1, &nc);
    return true;
}

JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceGetPresetMetadata(
        JNIEnv* env, jclass, jlong h, jint idx,
        jbyteArray outBank, jintArray outIndex,
        jobjectArray outStrings) {  // [stableId, name, path]
    uapmd_preset_metadata_t meta{};
    if (!uapmd_instance_get_preset_metadata(j2p<uapmd_plugin_instance_t>(h), idx, &meta))
        return false;
    jbyte bank = static_cast<jbyte>(meta.bank);
    env->SetByteArrayRegion(outBank, 0, 1, &bank);
    jint midx = static_cast<jint>(meta.index);
    env->SetIntArrayRegion(outIndex, 0, 1, &midx);
    env->SetObjectArrayElement(outStrings, 0, env->NewStringUTF(meta.stable_id ? meta.stable_id : ""));
    env->SetObjectArrayElement(outStrings, 1, env->NewStringUTF(meta.name ? meta.name : ""));
    env->SetObjectArrayElement(outStrings, 2, env->NewStringUTF(meta.path ? meta.path : ""));
    return true;
}

JNIEXPORT jbyteArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceSaveStateSync(
        JNIEnv* env, jclass, jlong h) {
    auto inst = j2p<uapmd_plugin_instance_t>(h);
    size_t n = uapmd_instance_save_state_sync(inst, nullptr, 0);
    if (!n) return env->NewByteArray(0);
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(n));
    jbyte* buf = env->GetByteArrayElements(arr, nullptr);
    uapmd_instance_save_state_sync(inst, reinterpret_cast<uint8_t*>(buf), n);
    env->ReleaseByteArrayElements(arr, buf, 0);
    return arr;
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceLoadStateSync(
        JNIEnv* env, jclass, jlong h, jbyteArray data) {
    jsize n = env->GetArrayLength(data);
    jbyte* buf = env->GetByteArrayElements(data, nullptr);
    uapmd_instance_load_state_sync(j2p<uapmd_plugin_instance_t>(h),
                                   reinterpret_cast<const uint8_t*>(buf), n);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
}

// requestState(handle, ctx, includeUiState, callback: RequestStateCb)
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceRequestState(
        JNIEnv* env, jclass, jlong h, jint ctx, jboolean includeUi, jobject cb) {
    jmethodID mid = env->GetMethodID(env->GetObjectClass(cb), "invoke", "([BLjava/lang/String;)V");
    auto* actx = new AsyncCtx(env, cb, mid);
    uapmd_instance_request_state(
        j2p<uapmd_plugin_instance_t>(h),
        static_cast<uapmd_state_context_type_t>(ctx), includeUi, actx,
        [](const uint8_t* state, size_t stateSize, const char* error, void* ud) {
            auto* ctx = static_cast<AsyncCtx*>(ud);
            JNIEnv* e = ctx->env();
            jbyteArray data = nullptr;
            if (state && stateSize) {
                data = e->NewByteArray(static_cast<jsize>(stateSize));
                e->SetByteArrayRegion(data, 0, static_cast<jsize>(stateSize),
                                      reinterpret_cast<const jbyte*>(state));
            }
            jstring err = error ? e->NewStringUTF(error) : nullptr;
            e->CallVoidMethod(ctx->obj, ctx->mid, data, err);
            delete ctx;
        });
}

JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceLoadState(
        JNIEnv* env, jclass, jlong h, jbyteArray data, jint ctx, jboolean includeUi, jobject cb) {
    jmethodID mid = env->GetMethodID(env->GetObjectClass(cb), "invoke", "(Ljava/lang/String;)V");
    auto* actx = new AsyncCtx(env, cb, mid);
    jsize n = env->GetArrayLength(data);
    jbyte* buf = env->GetByteArrayElements(data, nullptr);
    uapmd_instance_load_state(
        j2p<uapmd_plugin_instance_t>(h),
        reinterpret_cast<const uint8_t*>(buf), n,
        static_cast<uapmd_state_context_type_t>(ctx), includeUi, actx,
        [](const char* error, void* ud) {
            auto* ctx = static_cast<AsyncCtx*>(ud);
            JNIEnv* e = ctx->env();
            jstring err = error ? e->NewStringUTF(error) : nullptr;
            e->CallVoidMethod(ctx->obj, ctx->mid, err);
            delete ctx;
        });
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
}

JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceHasUiSupport(
        JNIEnv*, jclass, jlong h) {
    return uapmd_instance_has_ui_support(j2p<uapmd_plugin_instance_t>(h));
}
JNIEXPORT jbooleanArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceGetUiCapabilities(
        JNIEnv* env, jclass, jlong h) {
    uapmd_ui_capabilities_t caps{};
    uapmd_instance_get_ui_capabilities(j2p<uapmd_plugin_instance_t>(h), &caps);
    jbooleanArray arr = env->NewBooleanArray(4);
    jboolean vals[4] = {
        static_cast<jboolean>(caps.has_ui_support),
        static_cast<jboolean>(caps.supports_embedded_presentations),
        static_cast<jboolean>(caps.supports_floating_presentations),
        static_cast<jboolean>(caps.supports_multiple_presentations)
    };
    env->SetBooleanArrayRegion(arr, 0, 4, vals);
    return arr;
}
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceCreateUi(
        JNIEnv* env, jclass, jlong h, jboolean floating, jlong parent, jobject resizeCb) {
    struct RCtx { jobject obj; jmethodID mid; };
    RCtx* rctx = nullptr;
    uapmd_ui_resize_handler_t handler = nullptr;
    if (resizeCb) {
        rctx = new RCtx{env->NewGlobalRef(resizeCb),
                        env->GetMethodID(env->GetObjectClass(resizeCb), "invoke", "(II)Z")};
        handler = [](uint32_t w, uint32_t h, void* ud) -> bool {
            auto* r = static_cast<RCtx*>(ud);
            return jni_env()->CallBooleanMethod(r->obj, r->mid,
                                                static_cast<jint>(w), static_cast<jint>(h));
        };
    }
    return uapmd_instance_create_ui(j2p<uapmd_plugin_instance_t>(h), floating,
                                    reinterpret_cast<void*>(static_cast<uintptr_t>(parent)),
                                    rctx, handler);
}
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceCreateUiPresentation(
        JNIEnv* env, jclass, jlong h, jint hostKind, jint role, jlong parent, jstring webContainerId, jobject resizeCb) {
    struct RCtx { jobject obj; jmethodID mid; };
    RCtx* rctx = nullptr;
    uapmd_ui_resize_handler_t handler = nullptr;
    if (resizeCb) {
        rctx = new RCtx{env->NewGlobalRef(resizeCb),
                        env->GetMethodID(env->GetObjectClass(resizeCb), "invoke", "(II)Z")};
        handler = [](uint32_t w, uint32_t h, void* ud) -> bool {
            auto* r = static_cast<RCtx*>(ud);
            return jni_env()->CallBooleanMethod(r->obj, r->mid,
                                                static_cast<jint>(w), static_cast<jint>(h));
        };
    }
    uapmd_ui_presentation_request_t request{};
    request.host_kind = static_cast<uapmd_ui_host_kind_t>(hostKind);
    request.role = static_cast<uapmd_ui_presentation_role_t>(role);
    request.parent_handle = reinterpret_cast<void*>(static_cast<uintptr_t>(parent));
    const char* webId = nullptr;
    if (webContainerId)
        webId = jstr(env, webContainerId);
    request.web_container_id = webId;
    auto ret = uapmd_instance_create_ui_presentation(
        j2p<uapmd_plugin_instance_t>(h),
        &request,
        rctx,
        handler);
    if (webContainerId)
        jstr_release(env, webContainerId, webId);
    return p2j(ret);
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceDestroyUi(
        JNIEnv*, jclass, jlong h) { uapmd_instance_destroy_ui(j2p<uapmd_plugin_instance_t>(h)); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceShowUi(
        JNIEnv*, jclass, jlong h) { return uapmd_instance_show_ui(j2p<uapmd_plugin_instance_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceHideUi(
        JNIEnv*, jclass, jlong h) { uapmd_instance_hide_ui(j2p<uapmd_plugin_instance_t>(h)); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceIsUiVisible(
        JNIEnv*, jclass, jlong h) { return uapmd_instance_is_ui_visible(j2p<uapmd_plugin_instance_t>(h)); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceSetUiSize(
        JNIEnv*, jclass, jlong h, jint w, jint ht) {
    return uapmd_instance_set_ui_size(j2p<uapmd_plugin_instance_t>(h), w, ht);
}
// Returns {width, height} as int[2], or null if failed.
JNIEXPORT jintArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceGetUiSize(
        JNIEnv* env, jclass, jlong h) {
    uint32_t w = 0, ht = 0;
    if (!uapmd_instance_get_ui_size(j2p<uapmd_plugin_instance_t>(h), &w, &ht)) return nullptr;
    jintArray arr = env->NewIntArray(2);
    jint vals[2] = {static_cast<jint>(w), static_cast<jint>(ht)};
    env->SetIntArrayRegion(arr, 0, 2, vals);
    return arr;
}
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstanceCanUiResize(
        JNIEnv*, jclass, jlong h) { return uapmd_instance_can_ui_resize(j2p<uapmd_plugin_instance_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdUiPresentationDestroy(
        JNIEnv*, jclass, jlong h) { uapmd_ui_presentation_destroy(j2p<uapmd_ui_presentation_t>(h)); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdUiPresentationShow(
        JNIEnv*, jclass, jlong h) { return uapmd_ui_presentation_show(j2p<uapmd_ui_presentation_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdUiPresentationHide(
        JNIEnv*, jclass, jlong h) { uapmd_ui_presentation_hide(j2p<uapmd_ui_presentation_t>(h)); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdUiPresentationIsVisible(
        JNIEnv*, jclass, jlong h) { return uapmd_ui_presentation_is_visible(j2p<uapmd_ui_presentation_t>(h)); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdUiPresentationSetUiSize(
        JNIEnv*, jclass, jlong h, jint w, jint ht) {
    return uapmd_ui_presentation_set_size(j2p<uapmd_ui_presentation_t>(h), w, ht);
}
JNIEXPORT jintArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdUiPresentationGetUiSize(
        JNIEnv* env, jclass, jlong h) {
    uint32_t w = 0, ht = 0;
    if (!uapmd_ui_presentation_get_size(j2p<uapmd_ui_presentation_t>(h), &w, &ht)) return nullptr;
    jintArray arr = env->NewIntArray(2);
    jint vals[2] = {static_cast<jint>(w), static_cast<jint>(ht)};
    env->SetIntArrayRegion(arr, 0, 2, vals);
    return arr;
}
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdUiPresentationCanUiResize(
        JNIEnv*, jclass, jlong h) { return uapmd_ui_presentation_can_resize(j2p<uapmd_ui_presentation_t>(h)); }

// ─── PluginHost ───────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdPluginHostCreate(
        JNIEnv*, jclass) { return p2j(uapmd_plugin_host_create()); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdPluginHostDestroy(
        JNIEnv*, jclass, jlong h) { uapmd_plugin_host_destroy(j2p<uapmd_plugin_host_t>(h)); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdPluginHostCatalogEntryCount(
        JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_plugin_host_catalog_entry_count(j2p<uapmd_plugin_host_t>(h)));
}
// Returns {format, pluginId, displayName} as String[3], or null if not found.
JNIEXPORT jobjectArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdPluginHostGetCatalogEntry(
        JNIEnv* env, jclass, jlong h, jint idx) {
    char fmt[256], pid[512], name[512], vendor[256];
    if (!uapmd_plugin_host_get_catalog_entry(j2p<uapmd_plugin_host_t>(h), idx,
            fmt, sizeof(fmt), pid, sizeof(pid), name, sizeof(name), vendor, sizeof(vendor)))
        return nullptr;
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(4, sc, nullptr);
    env->SetObjectArrayElement(arr, 0, env->NewStringUTF(fmt));
    env->SetObjectArrayElement(arr, 1, env->NewStringUTF(pid));
    env->SetObjectArrayElement(arr, 2, env->NewStringUTF(name));
    env->SetObjectArrayElement(arr, 3, env->NewStringUTF(vendor));
    return arr;
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdPluginHostSaveCatalog(
        JNIEnv* env, jclass, jlong h, jstring path) {
    const char* p = jstr(env, path);
    uapmd_plugin_host_save_catalog(j2p<uapmd_plugin_host_t>(h), p);
    jstr_release(env, path, p);
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdPluginHostPerformScanning(
        JNIEnv*, jclass, jlong h, jboolean rescan) {
    uapmd_plugin_host_perform_scanning(j2p<uapmd_plugin_host_t>(h), rescan);
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdPluginHostReloadCatalogFromCache(
        JNIEnv*, jclass, jlong h) {
    uapmd_plugin_host_reload_catalog_from_cache(j2p<uapmd_plugin_host_t>(h));
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdPluginHostCreateInstance(
        JNIEnv* env, jclass, jlong h,
        jint sr, jint bs, jint inCh, jint outCh, jboolean offline,
        jstring format, jstring pluginId, jobject cb) {
    jmethodID mid = env->GetMethodID(env->GetObjectClass(cb), "invoke", "(ILjava/lang/String;)V");
    auto* actx = new AsyncCtx(env, cb, mid);
    const char* fmt = jstr(env, format);
    const char* pid = jstr(env, pluginId);
    uapmd_plugin_host_create_instance(j2p<uapmd_plugin_host_t>(h),
        sr, bs, inCh, outCh, offline, fmt, pid, actx,
        [](int32_t instanceId, const char* error, void* ud) {
            auto* ctx = static_cast<AsyncCtx*>(ud);
            JNIEnv* e = ctx->env();
            jstring err = error ? e->NewStringUTF(error) : nullptr;
            e->CallVoidMethod(ctx->obj, ctx->mid, instanceId, err);
            delete ctx;
        });
    jstr_release(env, format, fmt);
    jstr_release(env, pluginId, pid);
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdPluginHostDeleteInstance(
        JNIEnv*, jclass, jlong h, jint id) {
    uapmd_plugin_host_delete_instance(j2p<uapmd_plugin_host_t>(h), id);
}
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdPluginHostGetInstance(
        JNIEnv*, jclass, jlong h, jint id) {
    return p2j(uapmd_plugin_host_get_instance(j2p<uapmd_plugin_host_t>(h), id));
}
JNIEXPORT jintArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdPluginHostGetInstanceIds(
        JNIEnv* env, jclass, jlong h) {
    auto host = j2p<uapmd_plugin_host_t>(h);
    uint32_t count = uapmd_plugin_host_instance_id_count(host);
    jintArray arr = env->NewIntArray(static_cast<jsize>(count));
    if (count > 0) {
        jint* buf = env->GetIntArrayElements(arr, nullptr);
        uapmd_plugin_host_get_instance_ids(host, buf, count);
        env->ReleaseIntArrayElements(arr, buf, 0);
    }
    return arr;
}

// ─── PluginNode ───────────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdNodeInstanceId(
        JNIEnv*, jclass, jlong h) { return uapmd_node_instance_id(j2p<uapmd_plugin_node_t>(h)); }
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdNodeInstance(
        JNIEnv*, jclass, jlong h) { return p2j(uapmd_node_instance(j2p<uapmd_plugin_node_t>(h))); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdNodeScheduleEvents(
        JNIEnv* env, jclass, jlong h, jlong ts, jbyteArray data) {
    jsize n = env->GetArrayLength(data);
    jbyte* buf = env->GetByteArrayElements(data, nullptr);
    bool r = uapmd_node_schedule_events(j2p<uapmd_plugin_node_t>(h), ts, buf, n);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return r;
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdNodeSendAllNotesOff(
        JNIEnv*, jclass, jlong h) { uapmd_node_send_all_notes_off(j2p<uapmd_plugin_node_t>(h)); }

// ─── PluginGraph ──────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdGraphCreate(
        JNIEnv*, jclass, jlong sz) { return p2j(uapmd_graph_create(static_cast<size_t>(sz))); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdGraphDestroy(
        JNIEnv*, jclass, jlong h) { uapmd_graph_destroy(j2p<uapmd_plugin_graph_t>(h)); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdGraphAppendNode(
        JNIEnv* env, jclass, jlong g, jint id, jlong inst, jobject deleteCb) {
    AsyncCtx* actx = nullptr;
    uapmd_graph_delete_cb_t cb = nullptr;
    if (deleteCb) {
        jmethodID mid = env->GetMethodID(env->GetObjectClass(deleteCb), "invoke", "()V");
        actx = new AsyncCtx(env, deleteCb, mid);
        cb = [](void* ud) {
            auto* ctx = static_cast<AsyncCtx*>(ud);
            ctx->env()->CallVoidMethod(ctx->obj, ctx->mid);
            delete ctx;
        };
    }
    return uapmd_graph_append_node(j2p<uapmd_plugin_graph_t>(g), id,
                                   j2p<uapmd_plugin_instance_t>(inst), actx, cb);
}
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdGraphRemoveNode(
        JNIEnv*, jclass, jlong h, jint id) {
    return uapmd_graph_remove_node(j2p<uapmd_plugin_graph_t>(h), id);
}
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdGraphPluginCount(
        JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_graph_plugin_count(j2p<uapmd_plugin_graph_t>(h)));
}
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdGraphGetPluginNode(
        JNIEnv*, jclass, jlong h, jint id) {
    return p2j(uapmd_graph_get_plugin_node(j2p<uapmd_plugin_graph_t>(h), id));
}

// Persistent event-output callback — store in a global to allow clearing.
static AsyncCtx* g_graph_event_output_ctx = nullptr;

JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdGraphSetEventOutputCallback(
        JNIEnv* env, jclass, jlong h, jobject cb) {
    delete g_graph_event_output_ctx;
    g_graph_event_output_ctx = nullptr;
    if (!cb) {
        uapmd_graph_set_event_output_callback(j2p<uapmd_plugin_graph_t>(h), nullptr, nullptr);
        return;
    }
    jmethodID mid = env->GetMethodID(env->GetObjectClass(cb), "invoke", "(I[II)V");
    g_graph_event_output_ctx = new AsyncCtx(env, cb, mid);
    uapmd_graph_set_event_output_callback(
        j2p<uapmd_plugin_graph_t>(h), g_graph_event_output_ctx,
        [](int32_t instId, const uapmd_ump_t* data, size_t sz, void* ud) {
            auto* ctx = static_cast<AsyncCtx*>(ud);
            JNIEnv* e = ctx->env();
            jint count = static_cast<jint>(sz / sizeof(uint32_t));
            jintArray arr = e->NewIntArray(count);
            e->SetIntArrayRegion(arr, 0, count, reinterpret_cast<const jint*>(data));
            e->CallVoidMethod(ctx->obj, ctx->mid, instId, arr, static_cast<jint>(sz));
            e->DeleteLocalRef(arr);
        });
}
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdGraphOutputBusCount(
        JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_graph_output_bus_count(j2p<uapmd_plugin_graph_t>(h)));
}
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdGraphOutputLatencyInSamples(
        JNIEnv*, jclass, jlong h, jint bus) {
    return static_cast<jint>(uapmd_graph_output_latency_in_samples(j2p<uapmd_plugin_graph_t>(h), bus));
}
JNIEXPORT jdouble JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdGraphOutputTailLengthInSeconds(
        JNIEnv*, jclass, jlong h, jint bus) {
    return uapmd_graph_output_tail_length_in_seconds(j2p<uapmd_plugin_graph_t>(h), bus);
}
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdGraphRenderLeadInSamples(
        JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_graph_render_lead_in_samples(j2p<uapmd_plugin_graph_t>(h)));
}
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdGraphMainOutputLatencyInSamples(
        JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_graph_main_output_latency_in_samples(j2p<uapmd_plugin_graph_t>(h)));
}
JNIEXPORT jdouble JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdGraphMainOutputTailLengthInSeconds(
        JNIEnv*, jclass, jlong h) {
    return uapmd_graph_main_output_tail_length_in_seconds(j2p<uapmd_plugin_graph_t>(h));
}

// ─── MidiIO ───────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdMidiIoAddInputHandler(
        JNIEnv* env, jclass, jlong io, jobject receiver) {
    jmethodID mid = env->GetMethodID(env->GetObjectClass(receiver), "invoke", "([IJ)V");
    auto* ctx = new MidiHandlerCtx{env->NewGlobalRef(receiver), mid, true};
    int64_t id = ++g_midi_counter;
    {
        std::lock_guard<std::mutex> lk(g_midi_mu);
        g_midi_handlers[id] = ctx;
    }
    uapmd_midi_io_add_input_handler(j2p<uapmd_midi_io_t>(io),
                                    midi_ump_trampoline,
                                    reinterpret_cast<void*>(id));
    return static_cast<jlong>(id);
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdMidiIoRemoveInputHandler(
        JNIEnv*, jclass, jlong io, jlong handlerId) {
    MidiHandlerCtx* ctx = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_midi_mu);
        auto it = g_midi_handlers.find(static_cast<int64_t>(handlerId));
        if (it != g_midi_handlers.end()) {
            ctx = it->second;
            ctx->active = false;
            g_midi_handlers.erase(it);
        }
    }
    // Only call remove for the very last handler (all share the same trampoline).
    if (g_midi_handlers.empty()) {
        uapmd_midi_io_remove_input_handler(j2p<uapmd_midi_io_t>(io), midi_ump_trampoline);
    }
    if (ctx) {
        JNIEnv* e = jni_env();
        if (e) e->DeleteGlobalRef(ctx->obj);
        delete ctx;
    }
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdMidiIoSend(
        JNIEnv* env, jclass, jlong io, jintArray msgs, jlong ts) {
    jsize n = env->GetArrayLength(msgs);
    jint* buf = env->GetIntArrayElements(msgs, nullptr);
    uapmd_midi_io_send(j2p<uapmd_midi_io_t>(io),
                       reinterpret_cast<uapmd_ump_t*>(buf), n * sizeof(uint32_t), ts);
    env->ReleaseIntArrayElements(msgs, buf, JNI_ABORT);
}

// ─── FunctionBlock ────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFbMidiIo(
        JNIEnv*, jclass, jlong h) { return p2j(uapmd_fb_midi_io(j2p<uapmd_function_block_t>(h))); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFbInstanceId(
        JNIEnv*, jclass, jlong h) { return uapmd_fb_instance_id(j2p<uapmd_function_block_t>(h)); }
JNIEXPORT jbyte JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFbGetGroup(
        JNIEnv*, jclass, jlong h) { return static_cast<jbyte>(uapmd_fb_get_group(j2p<uapmd_function_block_t>(h))); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFbSetGroup(
        JNIEnv*, jclass, jlong h, jbyte g) { uapmd_fb_set_group(j2p<uapmd_function_block_t>(h), static_cast<uint8_t>(g)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFbDetachOutputMapper(
        JNIEnv*, jclass, jlong h) { uapmd_fb_detach_output_mapper(j2p<uapmd_function_block_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFbInitialize(
        JNIEnv*, jclass, jlong h) { uapmd_fb_initialize(j2p<uapmd_function_block_t>(h)); }

// ─── FunctionBlockManager ─────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFbmCount(
        JNIEnv*, jclass, jlong h) { return static_cast<jlong>(uapmd_fbm_count(j2p<uapmd_function_block_mgr_t>(h))); }
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFbmCreateDevice(
        JNIEnv*, jclass, jlong h) { return static_cast<jlong>(uapmd_fbm_create_device(j2p<uapmd_function_block_mgr_t>(h))); }
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFbmGetDeviceByIndex(
        JNIEnv*, jclass, jlong h, jint i) { return p2j(uapmd_fbm_get_device_by_index(j2p<uapmd_function_block_mgr_t>(h), i)); }
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFbmGetDeviceForInstance(
        JNIEnv*, jclass, jlong h, jint id) { return p2j(uapmd_fbm_get_device_for_instance(j2p<uapmd_function_block_mgr_t>(h), id)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFbmDeleteEmptyDevices(
        JNIEnv*, jclass, jlong h) { uapmd_fbm_delete_empty_devices(j2p<uapmd_function_block_mgr_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFbmDetachAllOutputMappers(
        JNIEnv*, jclass, jlong h) { uapmd_fbm_detach_all_output_mappers(j2p<uapmd_function_block_mgr_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFbmClearAllDevices(
        JNIEnv*, jclass, jlong h) { uapmd_fbm_clear_all_devices(j2p<uapmd_function_block_mgr_t>(h)); }

// ─── UmpMapper ────────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdUmpInSetParameterValue(
        JNIEnv*, jclass, jlong h, jint idx, jdouble v) { uapmd_ump_in_set_parameter_value(j2p<uapmd_ump_input_mapper_t>(h), idx, v); }
JNIEXPORT jdouble JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdUmpInGetParameterValue(
        JNIEnv*, jclass, jlong h, jint idx) { return uapmd_ump_in_get_parameter_value(j2p<uapmd_ump_input_mapper_t>(h), idx); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdUmpInSetPerNoteControllerValue(
        JNIEnv*, jclass, jlong h, jbyte note, jbyte idx, jdouble v) { uapmd_ump_in_set_per_note_controller_value(j2p<uapmd_ump_input_mapper_t>(h), note, idx, v); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdUmpInLoadPreset(
        JNIEnv*, jclass, jlong h, jint idx) { uapmd_ump_in_load_preset(j2p<uapmd_ump_input_mapper_t>(h), idx); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdUmpOutSendParameterValue(
        JNIEnv*, jclass, jlong h, jint idx, jdouble v) { uapmd_ump_out_send_parameter_value(j2p<uapmd_ump_output_mapper_t>(h), idx, v); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdUmpOutSendPerNoteControllerValue(
        JNIEnv*, jclass, jlong h, jbyte note, jbyte idx, jdouble v) { uapmd_ump_out_send_per_note_controller_value(j2p<uapmd_ump_output_mapper_t>(h), note, idx, v); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdUmpOutSendPresetIndexChange(
        JNIEnv*, jclass, jlong h, jint idx) { uapmd_ump_out_send_preset_index_change(j2p<uapmd_ump_output_mapper_t>(h), idx); }

// ─── SequencerEngine ──────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineCreate(
        JNIEnv*, jclass, jint sr, jint abs, jint ubs) {
    return p2j(uapmd_engine_create(sr, abs, ubs));
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineDestroy(
        JNIEnv*, jclass, jlong h) { uapmd_engine_destroy(j2p<uapmd_sequencer_engine_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineEnqueueUmp(
        JNIEnv* env, jclass, jlong h, jint instId, jintArray ump, jlong ts) {
    jsize n = env->GetArrayLength(ump);
    jint* buf = env->GetIntArrayElements(ump, nullptr);
    uapmd_engine_enqueue_ump(j2p<uapmd_sequencer_engine_t>(h), instId,
                             reinterpret_cast<uapmd_ump_t*>(buf), n * sizeof(uint32_t), ts);
    env->ReleaseIntArrayElements(ump, buf, JNI_ABORT);
}
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEnginePluginHost(
        JNIEnv*, jclass, jlong h) { return p2j(uapmd_engine_plugin_host(j2p<uapmd_sequencer_engine_t>(h))); }
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineGetPluginInstance(
        JNIEnv*, jclass, jlong h, jint id) { return p2j(uapmd_engine_get_plugin_instance(j2p<uapmd_sequencer_engine_t>(h), id)); }
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineFunctionBlockManager(
        JNIEnv*, jclass, jlong h) { return p2j(uapmd_engine_function_block_manager(j2p<uapmd_sequencer_engine_t>(h))); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineTrackCount(
        JNIEnv*, jclass, jlong h) { return static_cast<jint>(uapmd_engine_track_count(j2p<uapmd_sequencer_engine_t>(h))); }
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineGetTrack(
        JNIEnv*, jclass, jlong h, jint idx) { return p2j(uapmd_engine_get_track(j2p<uapmd_sequencer_engine_t>(h), idx)); }
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineMasterTrack(
        JNIEnv*, jclass, jlong h) { return p2j(uapmd_engine_master_track(j2p<uapmd_sequencer_engine_t>(h))); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineAddEmptyTrack(
        JNIEnv*, jclass, jlong h) { return uapmd_engine_add_empty_track(j2p<uapmd_sequencer_engine_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineAddPluginToTrack(
        JNIEnv* env, jclass, jlong h, jint trackIdx, jstring format, jstring pluginId, jobject cb) {
    jmethodID mid = env->GetMethodID(env->GetObjectClass(cb), "invoke", "(IILjava/lang/String;)V");
    auto* actx = new AsyncCtx(env, cb, mid);
    const char* fmt = jstr(env, format);
    const char* pid = jstr(env, pluginId);
    uapmd_engine_add_plugin_to_track(j2p<uapmd_sequencer_engine_t>(h), trackIdx, fmt, pid, actx,
        [](int32_t instId, int32_t tIdx, const char* error, void* ud) {
            auto* ctx = static_cast<AsyncCtx*>(ud);
            JNIEnv* e = ctx->env();
            jstring err = error ? e->NewStringUTF(error) : nullptr;
            e->CallVoidMethod(ctx->obj, ctx->mid, instId, tIdx, err);
            delete ctx;
        });
    jstr_release(env, format, fmt);
    jstr_release(env, pluginId, pid);
}
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineRemovePluginInstance(
        JNIEnv*, jclass, jlong h, jint id) { return uapmd_engine_remove_plugin_instance(j2p<uapmd_sequencer_engine_t>(h), id); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineRemoveTrack(
        JNIEnv*, jclass, jlong h, jint idx) { return uapmd_engine_remove_track(j2p<uapmd_sequencer_engine_t>(h), idx); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineCleanupEmptyTracks(
        JNIEnv*, jclass, jlong h) { uapmd_engine_cleanup_empty_tracks(j2p<uapmd_sequencer_engine_t>(h)); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineFindTrackForInstance(
        JNIEnv*, jclass, jlong h, jint id) { return uapmd_engine_find_track_for_instance(j2p<uapmd_sequencer_engine_t>(h), id); }
JNIEXPORT jbyte JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineGetInstanceGroup(
        JNIEnv*, jclass, jlong h, jint id) { return static_cast<jbyte>(uapmd_engine_get_instance_group(j2p<uapmd_sequencer_engine_t>(h), id)); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineSetInstanceGroup(
        JNIEnv*, jclass, jlong h, jint id, jbyte g) { return uapmd_engine_set_instance_group(j2p<uapmd_sequencer_engine_t>(h), id, static_cast<uint8_t>(g)); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineTrackLatency(
        JNIEnv*, jclass, jlong h, jint idx) { return static_cast<jint>(uapmd_engine_track_latency(j2p<uapmd_sequencer_engine_t>(h), idx)); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineMasterTrackLatency(
        JNIEnv*, jclass, jlong h) { return static_cast<jint>(uapmd_engine_master_track_latency(j2p<uapmd_sequencer_engine_t>(h))); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineTrackRenderLead(
        JNIEnv*, jclass, jlong h, jint idx) { return static_cast<jint>(uapmd_engine_track_render_lead(j2p<uapmd_sequencer_engine_t>(h), idx)); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineMasterTrackRenderLead(
        JNIEnv*, jclass, jlong h) { return static_cast<jint>(uapmd_engine_master_track_render_lead(j2p<uapmd_sequencer_engine_t>(h))); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineSetDefaultChannels(
        JNIEnv*, jclass, jlong h, jint in, jint out) { uapmd_engine_set_default_channels(j2p<uapmd_sequencer_engine_t>(h), in, out); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineSetSampleRate(
        JNIEnv*, jclass, jlong h, jint sr) { uapmd_engine_set_sample_rate(j2p<uapmd_sequencer_engine_t>(h), sr); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineGetOfflineRendering(
        JNIEnv*, jclass, jlong h) { return uapmd_engine_get_offline_rendering(j2p<uapmd_sequencer_engine_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineSetOfflineRendering(
        JNIEnv*, jclass, jlong h, jboolean v) { uapmd_engine_set_offline_rendering(j2p<uapmd_sequencer_engine_t>(h), v); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineSetActive(
        JNIEnv*, jclass, jlong h, jboolean v) { uapmd_engine_set_active(j2p<uapmd_sequencer_engine_t>(h), v); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineSetExternalPump(
        JNIEnv*, jclass, jlong h, jboolean v) { uapmd_engine_set_external_pump(j2p<uapmd_sequencer_engine_t>(h), v); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineIsPlaybackActive(
        JNIEnv*, jclass, jlong h) { return uapmd_engine_is_playback_active(j2p<uapmd_sequencer_engine_t>(h)); }
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineGetPlaybackPosition(
        JNIEnv*, jclass, jlong h) { return uapmd_engine_get_playback_position(j2p<uapmd_sequencer_engine_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineSetPlaybackPosition(
        JNIEnv*, jclass, jlong h, jlong v) { uapmd_engine_set_playback_position(j2p<uapmd_sequencer_engine_t>(h), v); }
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineRenderPlaybackPosition(
        JNIEnv*, jclass, jlong h) { return uapmd_engine_render_playback_position(j2p<uapmd_sequencer_engine_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineStartPlayback(
        JNIEnv*, jclass, jlong h) { uapmd_engine_start_playback(j2p<uapmd_sequencer_engine_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineStopPlayback(
        JNIEnv*, jclass, jlong h) { uapmd_engine_stop_playback(j2p<uapmd_sequencer_engine_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEnginePausePlayback(
        JNIEnv*, jclass, jlong h) { uapmd_engine_pause_playback(j2p<uapmd_sequencer_engine_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineResumePlayback(
        JNIEnv*, jclass, jlong h) { uapmd_engine_resume_playback(j2p<uapmd_sequencer_engine_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineSendNoteOn(
        JNIEnv*, jclass, jlong h, jint id, jint note) { uapmd_engine_send_note_on(j2p<uapmd_sequencer_engine_t>(h), id, note); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineSendNoteOff(
        JNIEnv*, jclass, jlong h, jint id, jint note) { uapmd_engine_send_note_off(j2p<uapmd_sequencer_engine_t>(h), id, note); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineSendPitchBend(
        JNIEnv*, jclass, jlong h, jint id, jfloat v) { uapmd_engine_send_pitch_bend(j2p<uapmd_sequencer_engine_t>(h), id, v); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineSendChannelPressure(
        JNIEnv*, jclass, jlong h, jint id, jfloat v) { uapmd_engine_send_channel_pressure(j2p<uapmd_sequencer_engine_t>(h), id, v); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineSetParameterValue(
        JNIEnv*, jclass, jlong h, jint id, jint idx, jdouble v) { uapmd_engine_set_parameter_value(j2p<uapmd_sequencer_engine_t>(h), id, idx, v); }
JNIEXPORT jfloatArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineGetInputSpectrum(
        JNIEnv* env, jclass, jlong h, jint bars) {
    jfloatArray arr = env->NewFloatArray(bars);
    jfloat* buf = env->GetFloatArrayElements(arr, nullptr);
    uapmd_engine_get_input_spectrum(j2p<uapmd_sequencer_engine_t>(h), buf, bars);
    env->ReleaseFloatArrayElements(arr, buf, 0);
    return arr;
}
JNIEXPORT jfloatArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineGetOutputSpectrum(
        JNIEnv* env, jclass, jlong h, jint bars) {
    jfloatArray arr = env->NewFloatArray(bars);
    jfloat* buf = env->GetFloatArrayElements(arr, nullptr);
    uapmd_engine_get_output_spectrum(j2p<uapmd_sequencer_engine_t>(h), buf, bars);
    env->ReleaseFloatArrayElements(arr, buf, 0);
    return arr;
}
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdEngineTimeline(
        JNIEnv*, jclass, jlong h) { return p2j(uapmd_engine_timeline(j2p<uapmd_sequencer_engine_t>(h))); }

// Offline render — returns double[4]: {success(0/1), canceled(0/1), renderedSecs, errorStr_ptr?}
// We avoid pointer gymnastics: return a String[] {success, canceled, renderedSecs, error?}
JNIEXPORT jobjectArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdRenderOffline(
        JNIEnv* env, jclass, jlong h,
        jstring outputPath,
        jdouble startSecs, jdouble endSecs, jboolean hasEndSecs,
        jboolean useContentFallback, jboolean contentBoundsValid,
        jdouble contentStartSecs, jdouble contentEndSecs,
        jdouble tailSecs, jboolean enableSilenceStop,
        jdouble silenceDurSecs, jdouble silenceThreshDb,
        jint sampleRate, jint bufferSize, jint outputChannels, jint umpBufSize,
        jobject progressCb, jobject cancelCb) {

    uapmd_offline_render_settings_t s{};
    const char* op = jstr(env, outputPath);
    s.output_path = op;
    s.start_seconds = startSecs;
    s.end_seconds = endSecs;
    s.has_end_seconds = hasEndSecs;
    s.use_content_fallback = useContentFallback;
    s.content_bounds_valid = contentBoundsValid;
    s.content_start_seconds = contentStartSecs;
    s.content_end_seconds = contentEndSecs;
    s.tail_seconds = tailSecs;
    s.enable_silence_stop = enableSilenceStop;
    s.silence_duration_seconds = silenceDurSecs;
    s.silence_threshold_db = silenceThreshDb;
    s.sample_rate = sampleRate;
    s.buffer_size = bufferSize;
    s.output_channels = outputChannels;
    s.ump_buffer_size = umpBufSize;

    struct RCtx {
        jobject pCb; jmethodID pMid;
        jobject cCb; jmethodID cMid;
    };
    RCtx rctx{};
    if (progressCb) {
        rctx.pCb = env->NewGlobalRef(progressCb);
        rctx.pMid = env->GetMethodID(env->GetObjectClass(progressCb), "invoke", "(DDDJJ)V");
    }
    if (cancelCb) {
        rctx.cCb = env->NewGlobalRef(cancelCb);
        rctx.cMid = env->GetMethodID(env->GetObjectClass(cancelCb), "invoke", "()Z");
    }

    auto result = uapmd_render_offline(
        j2p<uapmd_sequencer_engine_t>(h), &s, &rctx,
        progressCb ? [](const uapmd_offline_render_progress_t* p, void* ud) {
            auto* r = static_cast<RCtx*>(ud);
            JNIEnv* e = jni_env();
            e->CallVoidMethod(r->pCb, r->pMid,
                p->progress, p->rendered_seconds, p->total_seconds,
                p->rendered_frames, p->total_frames);
        } : (uapmd_render_progress_cb_t)nullptr,
        cancelCb ? [](void* ud) -> bool {
            auto* r = static_cast<RCtx*>(ud);
            return jni_env()->CallBooleanMethod(r->cCb, r->cMid);
        } : (uapmd_render_should_cancel_cb_t)nullptr);

    if (rctx.pCb) env->DeleteGlobalRef(rctx.pCb);
    if (rctx.cCb) env->DeleteGlobalRef(rctx.cCb);
    jstr_release(env, outputPath, op);

    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(4, sc, nullptr);
    env->SetObjectArrayElement(arr, 0, env->NewStringUTF(result.success ? "1" : "0"));
    env->SetObjectArrayElement(arr, 1, env->NewStringUTF(result.canceled ? "1" : "0"));
    char buf[64]; snprintf(buf, sizeof(buf), "%.6f", result.rendered_seconds);
    env->SetObjectArrayElement(arr, 2, env->NewStringUTF(buf));
    env->SetObjectArrayElement(arr, 3, result.error_message ? env->NewStringUTF(result.error_message) : nullptr);
    return arr;
}

// ─── SequencerTrack ───────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTrackGraph(
        JNIEnv*, jclass, jlong h) { return p2j(uapmd_track_graph(j2p<uapmd_sequencer_track_t>(h))); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTrackLatencyInSamples(
        JNIEnv*, jclass, jlong h) { return static_cast<jint>(uapmd_track_latency_in_samples(j2p<uapmd_sequencer_track_t>(h))); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTrackRenderLeadInSamples(
        JNIEnv*, jclass, jlong h) { return static_cast<jint>(uapmd_track_render_lead_in_samples(j2p<uapmd_sequencer_track_t>(h))); }
JNIEXPORT jdouble JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTrackTailLengthInSeconds(
        JNIEnv*, jclass, jlong h) { return uapmd_track_tail_length_in_seconds(j2p<uapmd_sequencer_track_t>(h)); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTrackGetBypassed(
        JNIEnv*, jclass, jlong h) { return uapmd_track_get_bypassed(j2p<uapmd_sequencer_track_t>(h)); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTrackGetFrozen(
        JNIEnv*, jclass, jlong h) { return uapmd_track_get_frozen(j2p<uapmd_sequencer_track_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTrackSetBypassed(
        JNIEnv*, jclass, jlong h, jboolean v) { uapmd_track_set_bypassed(j2p<uapmd_sequencer_track_t>(h), v); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTrackSetFrozen(
        JNIEnv*, jclass, jlong h, jboolean v) { uapmd_track_set_frozen(j2p<uapmd_sequencer_track_t>(h), v); }
JNIEXPORT jintArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTrackGetOrderedInstanceIds(
        JNIEnv* env, jclass, jlong h) {
    auto track = j2p<uapmd_sequencer_track_t>(h);
    uint32_t count = uapmd_track_ordered_instance_id_count(track);
    jintArray arr = env->NewIntArray(static_cast<jsize>(count));
    if (count > 0) {
        jint* buf = env->GetIntArrayElements(arr, nullptr);
        uapmd_track_get_ordered_instance_ids(track, buf, count);
        env->ReleaseIntArrayElements(arr, buf, 0);
    }
    return arr;
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTrackSetInstanceGroup(
        JNIEnv*, jclass, jlong h, jint id, jbyte g) { uapmd_track_set_instance_group(j2p<uapmd_sequencer_track_t>(h), id, static_cast<uint8_t>(g)); }
JNIEXPORT jbyte JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTrackGetInstanceGroup(
        JNIEnv*, jclass, jlong h, jint id) { return static_cast<jbyte>(uapmd_track_get_instance_group(j2p<uapmd_sequencer_track_t>(h), id)); }
JNIEXPORT jbyte JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTrackFindAvailableGroup(
        JNIEnv*, jclass, jlong h) { return static_cast<jbyte>(uapmd_track_find_available_group(j2p<uapmd_sequencer_track_t>(h))); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTrackRemoveInstance(
        JNIEnv*, jclass, jlong h, jint id) { uapmd_track_remove_instance(j2p<uapmd_sequencer_track_t>(h), id); }

// ─── TimelineFacade ───────────────────────────────────────────────────────────

// Returns double[9] for timeline state fields, or null if failed.
JNIEXPORT jdoubleArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTlGetState(
        JNIEnv* env, jclass, jlong h) {
    uapmd_timeline_state_t st{};
    if (!uapmd_tl_get_state(j2p<uapmd_timeline_facade_t>(h), &st)) return nullptr;
    // [playhead.samples, playhead.beats, isPlaying, loopEnabled,
    //  loopStart.samples, loopStart.beats, loopEnd.samples, loopEnd.beats,
    //  tempo, numerator, denominator, sampleRate]
    jdouble vals[12] = {
        static_cast<jdouble>(st.playhead_position.samples), st.playhead_position.legacy_beats,
        static_cast<jdouble>(st.is_playing), static_cast<jdouble>(st.loop_enabled),
        static_cast<jdouble>(st.loop_start.samples), st.loop_start.legacy_beats,
        static_cast<jdouble>(st.loop_end.samples), st.loop_end.legacy_beats,
        st.tempo,
        static_cast<jdouble>(st.time_signature_numerator),
        static_cast<jdouble>(st.time_signature_denominator),
        static_cast<jdouble>(st.sample_rate)
    };
    jdoubleArray arr = env->NewDoubleArray(12);
    env->SetDoubleArrayRegion(arr, 0, 12, vals);
    return arr;
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTlSetTempo(
        JNIEnv*, jclass, jlong h, jdouble t) { uapmd_tl_set_tempo(j2p<uapmd_timeline_facade_t>(h), t); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTlSetTimeSignature(
        JNIEnv*, jclass, jlong h, jint n, jint d) { uapmd_tl_set_time_signature(j2p<uapmd_timeline_facade_t>(h), n, d); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTlSetLoop(
        JNIEnv*, jclass, jlong h, jboolean en,
        jlong startSamples, jdouble startBeats, jlong endSamples, jdouble endBeats) {
    uapmd_timeline_position_t s{startSamples, startBeats};
    uapmd_timeline_position_t e{endSamples, endBeats};
    uapmd_tl_set_loop(j2p<uapmd_timeline_facade_t>(h), en, s, e);
}
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTlTrackCount(
        JNIEnv*, jclass, jlong h) { return static_cast<jint>(uapmd_tl_track_count(j2p<uapmd_timeline_facade_t>(h))); }
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTlGetTrack(
        JNIEnv*, jclass, jlong h, jint idx) { return p2j(uapmd_tl_get_track(j2p<uapmd_timeline_facade_t>(h), idx)); }
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTlMasterTimelineTrack(
        JNIEnv*, jclass, jlong h) { return p2j(uapmd_tl_master_timeline_track(j2p<uapmd_timeline_facade_t>(h))); }
// Returns int[4]: {clipId, sourceNodeId, success(0/1), error_idx_in_string_arr}
JNIEXPORT jintArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTlAddAudioClip(
        JNIEnv* env, jclass, jlong h, jint trackIdx,
        jlong posSamples, jdouble posBeats, jlong reader, jstring filepath) {
    uapmd_timeline_position_t pos{posSamples, posBeats};
    const char* fp = jstr(env, filepath);
    auto r = uapmd_tl_add_audio_clip(j2p<uapmd_timeline_facade_t>(h), trackIdx, pos,
                                     j2p<uapmd_audio_file_reader_t>(reader), fp);
    jstr_release(env, filepath, fp);
    jintArray arr = env->NewIntArray(3);
    jint vals[3] = {r.clip_id, r.source_node_id, r.success ? 1 : 0};
    env->SetIntArrayRegion(arr, 0, 3, vals);
    // Return error as separate string via global; for simplicity store in a thread-local
    // or just return a 4th element index — for now callers check success flag.
    return arr;
}
JNIEXPORT jintArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTlAddMidiClipFromFile(
        JNIEnv* env, jclass, jlong h, jint trackIdx,
        jlong posSamples, jdouble posBeats, jstring filepath, jboolean nrpn) {
    uapmd_timeline_position_t pos{posSamples, posBeats};
    const char* fp = jstr(env, filepath);
    auto r = uapmd_tl_add_midi_clip_from_file(j2p<uapmd_timeline_facade_t>(h), trackIdx, pos, fp, nrpn);
    jstr_release(env, filepath, fp);
    jintArray arr = env->NewIntArray(3);
    jint vals[3] = {r.clip_id, r.source_node_id, r.success ? 1 : 0};
    env->SetIntArrayRegion(arr, 0, 3, vals);
    return arr;
}
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTlRemoveClip(
        JNIEnv*, jclass, jlong h, jint tIdx, jint cId) { return uapmd_tl_remove_clip(j2p<uapmd_timeline_facade_t>(h), tIdx, cId); }
// Returns String[2]: {success("1"/"0"), error?}
JNIEXPORT jobjectArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTlLoadProject(
        JNIEnv* env, jclass, jlong h, jstring path) {
    const char* p = jstr(env, path);
    auto r = uapmd_tl_load_project(j2p<uapmd_timeline_facade_t>(h), p);
    jstr_release(env, path, p);
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(2, sc, nullptr);
    env->SetObjectArrayElement(arr, 0, env->NewStringUTF(r.success ? "1" : "0"));
    env->SetObjectArrayElement(arr, 1, r.error ? env->NewStringUTF(r.error) : nullptr);
    return arr;
}
// Returns double[5]: {hasContent, firstSample, lastSample, firstSecs, lastSecs}
JNIEXPORT jdoubleArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTlCalculateContentBounds(
        JNIEnv* env, jclass, jlong h) {
    auto r = uapmd_tl_calculate_content_bounds(j2p<uapmd_timeline_facade_t>(h));
    jdouble vals[5] = {
        r.has_content ? 1.0 : 0.0,
        static_cast<jdouble>(r.first_sample), static_cast<jdouble>(r.last_sample),
        r.first_seconds, r.last_seconds
    };
    jdoubleArray arr = env->NewDoubleArray(5);
    env->SetDoubleArrayRegion(arr, 0, 5, vals);
    return arr;
}

// ─── TimelineTrack (clip data) ────────────────────────────────────────────────

JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTtClipCount(
        JNIEnv*, jclass, jlong h) {
    auto cm = uapmd_tt_clip_manager(j2p<uapmd_timeline_track_t>(h));
    return static_cast<jint>(uapmd_cm_clip_count(cm));
}

// Returns double[count*7]: per clip [clipId, posSamples, posBeats, durSamples, gain, muted, clipType]
// Fills outStrings[count*2]: per clip [name, filepath]
JNIEXPORT jdoubleArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdTtGetAllClips(
        JNIEnv* env, jclass, jlong h, jobjectArray outStrings) {
    auto cm = uapmd_tt_clip_manager(j2p<uapmd_timeline_track_t>(h));
    auto count = static_cast<uint32_t>(uapmd_cm_clip_count(cm));
    if (count == 0) return env->NewDoubleArray(0);
    std::vector<uapmd_clip_data_t> clips(count);
    auto actual = uapmd_cm_get_all_clips(cm, clips.data(), count);
    std::vector<jdouble> numerics(actual * 7);
    for (uint32_t i = 0; i < actual; i++) {
        const auto& c = clips[i];
        numerics[i*7 + 0] = static_cast<jdouble>(c.clip_id);
        numerics[i*7 + 1] = static_cast<jdouble>(c.position.samples);
        numerics[i*7 + 2] = c.position.legacy_beats;
        numerics[i*7 + 3] = static_cast<jdouble>(c.duration_samples);
        numerics[i*7 + 4] = c.gain;
        numerics[i*7 + 5] = c.muted ? 1.0 : 0.0;
        numerics[i*7 + 6] = static_cast<jdouble>(c.clip_type);
        env->SetObjectArrayElement(outStrings, i*2,   env->NewStringUTF(c.name     ? c.name     : ""));
        env->SetObjectArrayElement(outStrings, i*2+1, env->NewStringUTF(c.filepath ? c.filepath : ""));
    }
    jdoubleArray arr = env->NewDoubleArray(actual * 7);
    env->SetDoubleArrayRegion(arr, 0, actual * 7, numerics.data());
    return arr;
}

// ─── AudioIODeviceManager ─────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdAudioDeviceMgrInstance(
        JNIEnv* env, jclass, jstring driver) {
    const char* d = jstr(env, driver, nullptr);
    jlong r = p2j(uapmd_audio_device_mgr_instance(d));
    if (driver && d) env->ReleaseStringUTFChars(driver, d);
    return r;
}
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdAudioDeviceMgrDeviceCount(
        JNIEnv*, jclass, jlong h) { return static_cast<jint>(uapmd_audio_device_mgr_device_count(j2p<uapmd_audio_io_device_mgr_t>(h))); }
// Returns double[5]: {directions, id, sampleRate, channels} + name via String array
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdAudioDeviceMgrGetDeviceInfo(
        JNIEnv* env, jclass, jlong h, jint idx,
        jintArray outInts,    // [directions, id, sampleRate, channels]
        jobjectArray outName) {
    uapmd_audio_device_info_t info{};
    if (!uapmd_audio_device_mgr_get_device_info(j2p<uapmd_audio_io_device_mgr_t>(h), idx, &info))
        return false;
    jint vals[4] = {info.directions, info.id, static_cast<jint>(info.sample_rate), static_cast<jint>(info.channels)};
    env->SetIntArrayRegion(outInts, 0, 4, vals);
    env->SetObjectArrayElement(outName, 0, env->NewStringUTF(info.name ? info.name : ""));
    return true;
}
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdAudioDeviceMgrOpen(
        JNIEnv*, jclass, jlong h, jint inIdx, jint outIdx, jint sr, jint bs) {
    return p2j(uapmd_audio_device_mgr_open(j2p<uapmd_audio_io_device_mgr_t>(h), inIdx, outIdx, sr, bs));
}

// ─── AudioIODevice ────────────────────────────────────────────────────────────

JNIEXPORT jdouble JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdAudioDeviceSampleRate(
        JNIEnv*, jclass, jlong h) { return uapmd_audio_device_sample_rate(j2p<uapmd_audio_io_device_t>(h)); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdAudioDeviceChannels(
        JNIEnv*, jclass, jlong h) { return static_cast<jint>(uapmd_audio_device_channels(j2p<uapmd_audio_io_device_t>(h))); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdAudioDeviceInputChannels(
        JNIEnv*, jclass, jlong h) { return static_cast<jint>(uapmd_audio_device_input_channels(j2p<uapmd_audio_io_device_t>(h))); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdAudioDeviceOutputChannels(
        JNIEnv*, jclass, jlong h) { return static_cast<jint>(uapmd_audio_device_output_channels(j2p<uapmd_audio_io_device_t>(h))); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdAudioDeviceStart(
        JNIEnv*, jclass, jlong h) { return uapmd_audio_device_start(j2p<uapmd_audio_io_device_t>(h)); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdAudioDeviceStop(
        JNIEnv*, jclass, jlong h) { return uapmd_audio_device_stop(j2p<uapmd_audio_io_device_t>(h)); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdAudioDeviceIsPlaying(
        JNIEnv*, jclass, jlong h) { return uapmd_audio_device_is_playing(j2p<uapmd_audio_io_device_t>(h)); }

// ─── MidiIODevice + DeviceIODispatcher + RealtimeSequencer ───────────────────

JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdMidiDeviceInstance(
        JNIEnv* env, jclass, jstring driver) {
    const char* d = jstr(env, driver, nullptr);
    jlong r = p2j(uapmd_midi_device_instance(d));
    if (driver && d) env->ReleaseStringUTFChars(driver, d);
    return r;
}
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdDefaultDeviceIoDispatcher(
        JNIEnv*, jclass) { return p2j(uapmd_default_device_io_dispatcher()); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdDispatcherStart(
        JNIEnv*, jclass, jlong h) { return uapmd_dispatcher_start(j2p<uapmd_device_io_dispatcher_t>(h)); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdDispatcherStop(
        JNIEnv*, jclass, jlong h) { return uapmd_dispatcher_stop(j2p<uapmd_device_io_dispatcher_t>(h)); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdDispatcherIsPlaying(
        JNIEnv*, jclass, jlong h) { return uapmd_dispatcher_is_playing(j2p<uapmd_device_io_dispatcher_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdDispatcherClearOutputBuffers(
        JNIEnv*, jclass, jlong h) { uapmd_dispatcher_clear_output_buffers(j2p<uapmd_device_io_dispatcher_t>(h)); }
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdRtSequencerCreate(
        JNIEnv*, jclass, jint bs, jint ubs, jint sr, jlong disp) {
    return p2j(uapmd_rt_sequencer_create(bs, ubs, sr, j2p<uapmd_device_io_dispatcher_t>(disp)));
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdRtSequencerDestroy(
        JNIEnv*, jclass, jlong h) { uapmd_rt_sequencer_destroy(j2p<uapmd_realtime_sequencer_t>(h)); }
JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdRtSequencerEngine(
        JNIEnv*, jclass, jlong h) { return p2j(uapmd_rt_sequencer_engine(j2p<uapmd_realtime_sequencer_t>(h))); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdRtSequencerStartAudio(
        JNIEnv*, jclass, jlong h) { return uapmd_rt_sequencer_start_audio(j2p<uapmd_realtime_sequencer_t>(h)); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdRtSequencerStopAudio(
        JNIEnv*, jclass, jlong h) { return uapmd_rt_sequencer_stop_audio(j2p<uapmd_realtime_sequencer_t>(h)); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdRtSequencerIsAudioPlaying(
        JNIEnv*, jclass, jlong h) { return uapmd_rt_sequencer_is_audio_playing(j2p<uapmd_realtime_sequencer_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdRtSequencerClearOutputBuffers(
        JNIEnv*, jclass, jlong h) { uapmd_rt_sequencer_clear_output_buffers(j2p<uapmd_realtime_sequencer_t>(h)); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdRtSequencerSampleRate(
        JNIEnv*, jclass, jlong h) { return uapmd_rt_sequencer_sample_rate(j2p<uapmd_realtime_sequencer_t>(h)); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdRtSequencerSetSampleRate(
        JNIEnv*, jclass, jlong h, jint sr) { return uapmd_rt_sequencer_set_sample_rate(j2p<uapmd_realtime_sequencer_t>(h), sr); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdRtSequencerReconfigureAudioDevice(
        JNIEnv*, jclass, jlong h, jint inIdx, jint outIdx, jint sr, jint bs) {
    return uapmd_rt_sequencer_reconfigure_audio_device(j2p<uapmd_realtime_sequencer_t>(h), inIdx, outIdx, sr, bs);
}

// ─── AudioFileReader ──────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdAudioFileReaderCreate(
        JNIEnv* env, jclass, jstring path) {
    const char* p = jstr(env, path);
    jlong r = p2j(uapmd_audio_file_reader_create(p));
    jstr_release(env, path, p);
    return r;
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdAudioFileReaderDestroy(
        JNIEnv*, jclass, jlong h) { uapmd_audio_file_reader_destroy(j2p<uapmd_audio_file_reader_t>(h)); }
// Returns long[3]: {numFrames, numChannels, sampleRate}, or null.
JNIEXPORT jlongArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdAudioFileReaderGetProperties(
        JNIEnv* env, jclass, jlong h) {
    uapmd_audio_file_properties_t props{};
    if (!uapmd_audio_file_reader_get_properties(j2p<uapmd_audio_file_reader_t>(h), &props)) return nullptr;
    jlongArray arr = env->NewLongArray(3);
    jlong vals[3] = {static_cast<jlong>(props.num_frames), static_cast<jlong>(props.num_channels), static_cast<jlong>(props.sample_rate)};
    env->SetLongArrayRegion(arr, 0, 3, vals);
    return arr;
}
// readFrames: fills existing float[][] (one array per channel).
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdAudioFileReaderReadFrames(
        JNIEnv* env, jclass, jlong h, jlong startFrame, jlong nFrames, jobjectArray dest) {
    jint nCh = env->GetArrayLength(dest);
    std::vector<float*> ptrs(nCh);
    std::vector<jfloatArray> arrays(nCh);
    for (jint i = 0; i < nCh; ++i) {
        arrays[i] = static_cast<jfloatArray>(env->GetObjectArrayElement(dest, i));
        ptrs[i] = env->GetFloatArrayElements(arrays[i], nullptr);
    }
    uapmd_audio_file_reader_read_frames(j2p<uapmd_audio_file_reader_t>(h),
                                        startFrame, nFrames,
                                        const_cast<float* const*>(ptrs.data()), nCh);
    for (jint i = 0; i < nCh; ++i) {
        env->ReleaseFloatArrayElements(arrays[i], ptrs[i], 0);
        env->DeleteLocalRef(arrays[i]);
    }
}

// ─── ScanTool ─────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolCreate(
        JNIEnv*, jclass) { return p2j(uapmd_scan_tool_create()); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolDestroy(
        JNIEnv*, jclass, jlong h) { uapmd_scan_tool_destroy(j2p<uapmd_scan_tool_t>(h)); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolCatalogEntryCount(
        JNIEnv*, jclass, jlong h) { return static_cast<jint>(uapmd_scan_tool_catalog_entry_count(j2p<uapmd_scan_tool_t>(h))); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolFormatCount(
        JNIEnv*, jclass, jlong h) { return static_cast<jint>(uapmd_scan_tool_format_count(j2p<uapmd_scan_tool_t>(h))); }
JNIEXPORT jstring JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolGetFormatName(
        JNIEnv* env, jclass, jlong h, jint idx) {
    auto t = j2p<uapmd_scan_tool_t>(h);
    return cstr(env, [&](char* b, size_t n){ return uapmd_scan_tool_get_format_name(t, idx, b, n); });
}
JNIEXPORT jstring JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolGetCacheFile(
        JNIEnv* env, jclass, jlong h) {
    auto t = j2p<uapmd_scan_tool_t>(h);
    return cstr(env, [&](char* b, size_t n){ return uapmd_scan_tool_get_cache_file(t, b, n); });
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolSetCacheFile(
        JNIEnv* env, jclass, jlong h, jstring path) {
    const char* p = jstr(env, path);
    uapmd_scan_tool_set_cache_file(j2p<uapmd_scan_tool_t>(h), p);
    jstr_release(env, path, p);
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolSaveCache(
        JNIEnv*, jclass, jlong h) { uapmd_scan_tool_save_cache(j2p<uapmd_scan_tool_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolSaveCacheTo(
        JNIEnv* env, jclass, jlong h, jstring path) {
    const char* p = jstr(env, path);
    uapmd_scan_tool_save_cache_to(j2p<uapmd_scan_tool_t>(h), p);
    jstr_release(env, path, p);
}

// Scanning with individual callback objects (avoids struct pointer complexity).
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolPerformScanning(
        JNIEnv* env, jclass, jlong h, jboolean fast,
        jobject slowStartCb, jobject bundleStartCb, jobject bundleCompleteCb,
        jobject slowCompleteCb, jobject errorCb, jobject cancelCb) {

    struct ScanCtx {
        jobject slowStart, bundleStart, bundleComplete, slowComplete, err, cancel;
        jmethodID mSlowStart, mBundleStart, mBundleComplete, mSlowComplete, mErr, mCancel;
    };

    auto getM = [&](jobject o, const char* sig) -> jmethodID {
        return o ? env->GetMethodID(env->GetObjectClass(o), "invoke", sig) : nullptr;
    };

    ScanCtx ctx{};
    if (slowStartCb)    { ctx.slowStart    = env->NewGlobalRef(slowStartCb);    ctx.mSlowStart    = getM(slowStartCb, "(I)V"); }
    if (bundleStartCb)  { ctx.bundleStart  = env->NewGlobalRef(bundleStartCb);  ctx.mBundleStart  = getM(bundleStartCb, "(Ljava/lang/String;)V"); }
    if (bundleCompleteCb){ctx.bundleComplete=env->NewGlobalRef(bundleCompleteCb);ctx.mBundleComplete=getM(bundleCompleteCb,"(Ljava/lang/String;)V");}
    if (slowCompleteCb) { ctx.slowComplete = env->NewGlobalRef(slowCompleteCb); ctx.mSlowComplete = getM(slowCompleteCb, "()V"); }
    if (errorCb)        { ctx.err          = env->NewGlobalRef(errorCb);        ctx.mErr          = getM(errorCb, "(Ljava/lang/String;)V"); }
    if (cancelCb)       { ctx.cancel       = env->NewGlobalRef(cancelCb);       ctx.mCancel       = getM(cancelCb, "()Z"); }

    uapmd_scan_observer_t obs{};
    obs.user_data = &ctx;
    if (ctx.slowStart)    obs.slow_scan_started    = [](uint32_t total, void* ud) { auto* c=(ScanCtx*)ud; jni_env()->CallVoidMethod(c->slowStart, c->mSlowStart, (jint)total); };
    if (ctx.bundleStart)  obs.bundle_scan_started  = [](const char* p, void* ud) { auto* c=(ScanCtx*)ud; JNIEnv* e=jni_env(); e->CallVoidMethod(c->bundleStart, c->mBundleStart, p?e->NewStringUTF(p):nullptr); };
    if (ctx.bundleComplete) obs.bundle_scan_completed=[](const char* p,void* ud){ auto* c=(ScanCtx*)ud; JNIEnv* e=jni_env(); e->CallVoidMethod(c->bundleComplete,c->mBundleComplete,p?e->NewStringUTF(p):nullptr); };
    if (ctx.slowComplete) obs.slow_scan_completed   = [](void* ud) { auto* c=(ScanCtx*)ud; jni_env()->CallVoidMethod(c->slowComplete, c->mSlowComplete); };
    if (ctx.err)          obs.error_occurred        = [](const char* msg, void* ud) { auto* c=(ScanCtx*)ud; JNIEnv* e=jni_env(); e->CallVoidMethod(c->err, c->mErr, msg?e->NewStringUTF(msg):nullptr); };
    if (ctx.cancel)       obs.should_cancel         = [](void* ud) -> bool { auto* c=(ScanCtx*)ud; return jni_env()->CallBooleanMethod(c->cancel, c->mCancel); };

    uapmd_scan_tool_perform_scanning(j2p<uapmd_scan_tool_t>(h), fast, &obs);

    // Scanning is synchronous, release refs immediately.
    auto del = [&](jobject o){ if(o) env->DeleteGlobalRef(o); };
    del(ctx.slowStart); del(ctx.bundleStart); del(ctx.bundleComplete);
    del(ctx.slowComplete); del(ctx.err); del(ctx.cancel);
}

JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolBlocklistCount(
        JNIEnv*, jclass, jlong h) { return static_cast<jint>(uapmd_scan_tool_blocklist_count(j2p<uapmd_scan_tool_t>(h))); }
// Returns String[4]: {id, format, pluginId, reason}, or null.
JNIEXPORT jobjectArray JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolGetBlocklistEntry(
        JNIEnv* env, jclass, jlong h, jint idx) {
    uapmd_blocklist_entry_t entry{};
    if (!uapmd_scan_tool_get_blocklist_entry(j2p<uapmd_scan_tool_t>(h), idx, &entry)) return nullptr;
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(4, sc, nullptr);
    env->SetObjectArrayElement(arr, 0, env->NewStringUTF(entry.id ? entry.id : ""));
    env->SetObjectArrayElement(arr, 1, env->NewStringUTF(entry.format ? entry.format : ""));
    env->SetObjectArrayElement(arr, 2, env->NewStringUTF(entry.plugin_id ? entry.plugin_id : ""));
    env->SetObjectArrayElement(arr, 3, env->NewStringUTF(entry.reason ? entry.reason : ""));
    return arr;
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolFlushBlocklist(
        JNIEnv*, jclass, jlong h) { uapmd_scan_tool_flush_blocklist(j2p<uapmd_scan_tool_t>(h)); }
JNIEXPORT jboolean JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolUnblockBundle(
        JNIEnv* env, jclass, jlong h, jstring id) {
    const char* s = jstr(env, id);
    bool r = uapmd_scan_tool_unblock_bundle(j2p<uapmd_scan_tool_t>(h), s);
    jstr_release(env, id, s);
    return r;
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolClearBlocklist(
        JNIEnv*, jclass, jlong h) { uapmd_scan_tool_clear_blocklist(j2p<uapmd_scan_tool_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolAddToBlocklist(
        JNIEnv* env, jclass, jlong h, jstring fmt, jstring pid, jstring reason) {
    const char* f = jstr(env, fmt);
    const char* p = jstr(env, pid);
    const char* r = jstr(env, reason);
    uapmd_scan_tool_add_to_blocklist(j2p<uapmd_scan_tool_t>(h), f, p, r);
    jstr_release(env, fmt, f); jstr_release(env, pid, p); jstr_release(env, reason, r);
}
JNIEXPORT jstring JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdScanToolLastScanError(
        JNIEnv* env, jclass, jlong h) {
    auto t = j2p<uapmd_scan_tool_t>(h);
    return cstr(env, [&](char* b, size_t n){ return uapmd_scan_tool_last_scan_error(t, b, n); });
}

// ─── PluginInstancing ─────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstancingCreate(
        JNIEnv* env, jclass, jlong tool, jstring format, jstring pluginId) {
    const char* f = jstr(env, format);
    const char* p = jstr(env, pluginId);
    jlong r = p2j(uapmd_instancing_create(j2p<uapmd_scan_tool_t>(tool), f, p));
    jstr_release(env, format, f); jstr_release(env, pluginId, p);
    return r;
}
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstancingDestroy(
        JNIEnv*, jclass, jlong h) { uapmd_instancing_destroy(j2p<uapmd_plugin_instancing_t>(h)); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstancingMakeAlive(
        JNIEnv* env, jclass, jlong h, jobject cb) {
    jmethodID mid = env->GetMethodID(env->GetObjectClass(cb), "invoke", "(Ljava/lang/String;)V");
    auto* actx = new AsyncCtx(env, cb, mid);
    uapmd_instancing_make_alive(j2p<uapmd_plugin_instancing_t>(h), actx,
        [](const char* error, void* ud) {
            auto* ctx = static_cast<AsyncCtx*>(ud);
            JNIEnv* e = ctx->env();
            jstring err = error ? e->NewStringUTF(error) : nullptr;
            e->CallVoidMethod(ctx->obj, ctx->mid, err);
            delete ctx;
        });
}
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdInstancingState(
        JNIEnv*, jclass, jlong h) { return static_cast<jint>(uapmd_instancing_state(j2p<uapmd_plugin_instancing_t>(h))); }

// ─── FormatManager ────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFormatManagerCreate(
        JNIEnv*, jclass) { return p2j(uapmd_format_manager_create()); }
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFormatManagerDestroy(
        JNIEnv*, jclass, jlong h) { uapmd_format_manager_destroy(j2p<uapmd_format_manager_t>(h)); }
JNIEXPORT jint JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFormatManagerFormatCount(
        JNIEnv*, jclass, jlong h) { return static_cast<jint>(uapmd_format_manager_format_count(j2p<uapmd_format_manager_t>(h))); }
JNIEXPORT jstring JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdFormatManagerGetFormatName(
        JNIEnv* env, jclass, jlong h, jint idx) {
    auto m = j2p<uapmd_format_manager_t>(h);
    return cstr(env, [&](char* b, size_t n){ return uapmd_format_manager_get_format_name(m, idx, b, n); });
}

// ─── Android EventLoop JNI entry points ──────────────────────────────────────

// Called once from the Android main thread before creating any engine.
// dispatcher: Kotlin AndroidEventLoopDispatcher with fun dispatchTask(Long).
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdSetupAndroidEventLoop(
        JNIEnv* env, jclass, jobject dispatcher) {
    auto* ctx = new AndroidElCtx();
    ctx->dispatcher = env->NewGlobalRef(dispatcher);
    jclass cls = env->GetObjectClass(dispatcher);
    ctx->dispatch_method = env->GetMethodID(cls, "dispatchTask", "(J)V");
    env->DeleteLocalRef(cls);
    g_android_el = ctx;
    uapmd_set_event_loop(ctx, nullptr, android_is_main_thread, android_enqueue_task);
}

// Called from the Android main thread (via Handler) to execute a queued task.
JNIEXPORT void JNICALL Java_dev_atsushieno_uapmd_JniBridge_uapmdRunEventLoopTask(
        JNIEnv*, jclass, jlong token) {
    std::function<void()> fn;
    {
        std::lock_guard<std::mutex> lock(g_el_task_mutex);
        auto it = g_el_tasks.find(token);
        if (it != g_el_tasks.end()) {
            fn = std::move(it->second);
            g_el_tasks.erase(it);
        }
    }
    if (fn) fn();
}

} // extern "C"
