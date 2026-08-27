/**
 * uapmd JNI bridge — AppModel and TransportController (c-api/uapmd-c-app.h).
 *
 * Same conventions as uapmd_jni.cpp / uapmd_jni_history.cpp: opaque C pointers
 * travel as jlong. This surface is all scalars, so no struct marshalling is
 * needed here.
 */

#include <jni.h>
#include <cstdint>
#include <vector>

#include "c-api/uapmd-c-common.h"
#include "c-api/uapmd-c-app.h"
#include "c-api/uapmd-c-data.h"
#include "c-api/uapmd-c-undo.h"

/* Provided by uapmd_jni.cpp (C++ linkage, matching uapmd_jni_history.cpp). */
extern JNIEnv* uapmd_jni_env();

namespace {

template <typename T>
inline jlong p2j(T ptr) { return static_cast<jlong>(reinterpret_cast<uintptr_t>(ptr)); }

template <typename T>
inline T j2p(jlong handle) { return reinterpret_cast<T>(static_cast<uintptr_t>(handle)); }

inline uapmd_app_model_t AM(jlong h) { return j2p<uapmd_app_model_t>(h); }
inline uapmd_transport_controller_t TC(jlong h) { return j2p<uapmd_transport_controller_t>(h); }

/**
 * Async callback context. Each C completion fires exactly once, so the context
 * deletes itself from inside the trampoline. Mirrors HistoryCtx in
 * uapmd_jni_history.cpp, which lives in that file's anonymous namespace.
 */
struct AppCtx {
    jobject obj{nullptr};
    jmethodID mid{nullptr};

    AppCtx(JNIEnv* env, jobject o, const char* signature)
        : obj(env->NewGlobalRef(o)),
          mid(env->GetMethodID(env->GetObjectClass(o), "invoke", signature)) {}

    ~AppCtx() {
        if (obj) {
            JNIEnv* e = uapmd_jni_env();
            if (e) e->DeleteGlobalRef(obj);
        }
    }
};

/** cb signature: (trackIndex: Int, error: String?) -> Unit */
void app_track_mutation_trampoline(int32_t trackIndex, const char* error, void* ud) {
    auto* ctx = static_cast<AppCtx*>(ud);
    if (!ctx) return;
    JNIEnv* e = uapmd_jni_env();
    if (e && ctx->mid) {
        jstring err = error ? e->NewStringUTF(error) : nullptr;
        e->CallVoidMethod(ctx->obj, ctx->mid, static_cast<jint>(trackIndex), err);
    }
    delete ctx;
}

/** cb signature: (error: String?) -> Unit */
void app_error_only_trampoline(const char* error, void* ud) {
    auto* ctx = static_cast<AppCtx*>(ud);
    if (!ctx) return;
    JNIEnv* e = uapmd_jni_env();
    if (e && ctx->mid) {
        jstring err = error ? e->NewStringUTF(error) : nullptr;
        e->CallVoidMethod(ctx->obj, ctx->mid, err);
    }
    delete ctx;
}

/** Packs uapmd_undo_state_t as Object[]{ long[10], String, String, String }. */
jobjectArray pack_app_undo_state(JNIEnv* env, const uapmd_undo_state_t& s) {
    jlong nums[10] = {
        s.busy ? 1 : 0, s.compound_open ? 1 : 0, s.gesture_open ? 1 : 0,
        s.can_undo ? 1 : 0, s.can_redo ? 1 : 0, s.dirty ? 1 : 0,
        static_cast<jlong>(s.history_size_in_bytes),
        static_cast<jlong>(s.maximum_history_size_in_bytes),
        static_cast<jlong>(s.current_state_id),
        static_cast<jlong>(s.saved_state_id)
    };
    jlongArray numArr = env->NewLongArray(10);
    env->SetLongArrayRegion(numArr, 0, 10, nums);

    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(4, objectClass, nullptr);
    env->SetObjectArrayElement(result, 0, numArr);
    env->SetObjectArrayElement(result, 1, env->NewStringUTF(s.compound_description ? s.compound_description : ""));
    env->SetObjectArrayElement(result, 2, env->NewStringUTF(s.undo_description ? s.undo_description : ""));
    env->SetObjectArrayElement(result, 3, env->NewStringUTF(s.redo_description ? s.redo_description : ""));
    return result;
}

} // namespace

extern "C" {

#define JNI_FN(ret, name) JNIEXPORT ret JNICALL Java_dev_atsushieno_uapmd_JniBridge_##name

/* ── Lifecycle ─────────────────────────────────────────────────────────────── */

JNI_FN(void, uapmdAppInstantiate)(JNIEnv*, jclass) {
    uapmd_app_instantiate();
}

JNI_FN(jlong, uapmdAppInstance)(JNIEnv*, jclass) {
    return p2j(uapmd_app_instance());
}

JNI_FN(void, uapmdAppCleanup)(JNIEnv*, jclass) {
    uapmd_app_cleanup();
}

/* ── Accessors ─────────────────────────────────────────────────────────────── */

JNI_FN(jlong, uapmdAppSequencer)(JNIEnv*, jclass, jlong app) {
    return p2j(uapmd_app_sequencer(AM(app)));
}

JNI_FN(jlong, uapmdAppTransport)(JNIEnv*, jclass, jlong app) {
    return p2j(uapmd_app_transport(AM(app)));
}

JNI_FN(jint, uapmdAppSampleRate)(JNIEnv*, jclass, jlong app) {
    return uapmd_app_sample_rate(AM(app));
}

JNI_FN(jint, uapmdAppTrackCount)(JNIEnv*, jclass, jlong app) {
    return static_cast<jint>(uapmd_app_track_count(AM(app)));
}

/* ── Audio engine ──────────────────────────────────────────────────────────── */

JNI_FN(jboolean, uapmdAppIsScanning)(JNIEnv*, jclass, jlong app) {
    return uapmd_app_is_scanning(AM(app));
}

JNI_FN(jboolean, uapmdAppIsAudioEngineEnabled)(JNIEnv*, jclass, jlong app) {
    return uapmd_app_is_audio_engine_enabled(AM(app));
}

JNI_FN(void, uapmdAppSetAudioEngineEnabled)(JNIEnv*, jclass, jlong app, jboolean enabled) {
    uapmd_app_set_audio_engine_enabled(AM(app), enabled);
}

JNI_FN(void, uapmdAppToggleAudioEngine)(JNIEnv*, jclass, jlong app) {
    uapmd_app_toggle_audio_engine(AM(app));
}

JNI_FN(void, uapmdAppUpdateAudioDeviceSettings)(JNIEnv*, jclass, jlong app,
                                                  jint sampleRate, jint bufferSize) {
    uapmd_app_update_audio_device_settings(AM(app), sampleRate, static_cast<uint32_t>(bufferSize));
}

JNI_FN(void, uapmdAppSetAutoBufferSizeEnabled)(JNIEnv*, jclass, jlong app, jboolean enabled) {
    uapmd_app_set_auto_buffer_size_enabled(AM(app), enabled);
}

JNI_FN(jboolean, uapmdAppAutoBufferSizeEnabled)(JNIEnv*, jclass, jlong app) {
    return uapmd_app_auto_buffer_size_enabled(AM(app));
}

/* ── Startup lifecycle ─────────────────────────────────────────────────────── */

JNI_FN(void, uapmdAppNotifyUiReady)(JNIEnv*, jclass, jlong app) {
    uapmd_app_notify_ui_ready(AM(app));
}

JNI_FN(void, uapmdAppNotifyPersistentStorageReady)(JNIEnv*, jclass, jlong app) {
    uapmd_app_notify_persistent_storage_ready(AM(app));
}

/* ── TransportController ───────────────────────────────────────────────────── */

JNI_FN(jboolean, uapmdTransportIsPlaying)(JNIEnv*, jclass, jlong tc) {
    return uapmd_transport_is_playing(TC(tc));
}

JNI_FN(jboolean, uapmdTransportIsPaused)(JNIEnv*, jclass, jlong tc) {
    return uapmd_transport_is_paused(TC(tc));
}

JNI_FN(jboolean, uapmdTransportIsRecording)(JNIEnv*, jclass, jlong tc) {
    return uapmd_transport_is_recording(TC(tc));
}

JNI_FN(jfloat, uapmdTransportGetVolume)(JNIEnv*, jclass, jlong tc) {
    return uapmd_transport_get_volume(TC(tc));
}

JNI_FN(void, uapmdTransportSetVolume)(JNIEnv*, jclass, jlong tc, jfloat volume) {
    uapmd_transport_set_volume(TC(tc), volume);
}

JNI_FN(void, uapmdTransportPlay)(JNIEnv*, jclass, jlong tc) { uapmd_transport_play(TC(tc)); }
JNI_FN(void, uapmdTransportStop)(JNIEnv*, jclass, jlong tc) { uapmd_transport_stop(TC(tc)); }
JNI_FN(void, uapmdTransportPause)(JNIEnv*, jclass, jlong tc) { uapmd_transport_pause(TC(tc)); }
JNI_FN(void, uapmdTransportResume)(JNIEnv*, jclass, jlong tc) { uapmd_transport_resume(TC(tc)); }
JNI_FN(void, uapmdTransportRecord)(JNIEnv*, jclass, jlong tc) { uapmd_transport_record(TC(tc)); }

/* ── Plugin scanning ───────────────────────────────────────────────────────── */

JNI_FN(void, uapmdAppPerformPluginScanning)(JNIEnv*, jclass, jlong app, jboolean forceRescan,
                                              jint request, jdouble remoteTimeoutSeconds,
                                              jboolean requireFastScanning) {
    uapmd_app_perform_plugin_scanning(AM(app), forceRescan,
                                        static_cast<uapmd_plugin_scan_request_t>(request),
                                        remoteTimeoutSeconds, requireFastScanning);
}

JNI_FN(void, uapmdAppCancelPluginScanning)(JNIEnv*, jclass, jlong app) {
    uapmd_app_cancel_plugin_scanning(AM(app));
}

JNI_FN(jstring, uapmdAppGenerateScanReport)(JNIEnv* env, jclass, jlong app) {
    size_t needed = uapmd_app_generate_scan_report(AM(app), nullptr, 0);
    if (needed == 0) return env->NewStringUTF("");
    std::vector<char> buf(needed);
    uapmd_app_generate_scan_report(AM(app), buf.data(), buf.size());
    return env->NewStringUTF(buf.data());
}

JNI_FN(void, uapmdAppClearPluginBlocklist)(JNIEnv*, jclass, jlong app) {
    uapmd_app_clear_plugin_blocklist(AM(app));
}

/* ── Tracks ────────────────────────────────────────────────────────────────── */

JNI_FN(void, uapmdAppAddTrack)(JNIEnv* env, jclass, jlong app, jobject cb) {
    if (!cb) return;
    auto* ctx = new AppCtx(env, cb, "(ILjava/lang/String;)V");
    uapmd_app_add_track(AM(app), ctx, app_track_mutation_trampoline);
}

JNI_FN(void, uapmdAppRemoveTrack)(JNIEnv* env, jclass, jlong app, jint trackIndex, jobject cb) {
    if (!cb) return;
    auto* ctx = new AppCtx(env, cb, "(ILjava/lang/String;)V");
    uapmd_app_remove_track(AM(app), trackIndex, ctx, app_track_mutation_trampoline);
}

JNI_FN(void, uapmdAppRemoveAllTracks)(JNIEnv* env, jclass, jlong app, jobject cb) {
    if (!cb) return;
    auto* ctx = new AppCtx(env, cb, "(Ljava/lang/String;)V");
    uapmd_app_remove_all_tracks(AM(app), ctx, app_error_only_trampoline);
}

JNI_FN(jint, uapmdAppTimelineTrackCount)(JNIEnv*, jclass, jlong app) {
    return static_cast<jint>(uapmd_app_timeline_track_count(AM(app)));
}

JNI_FN(jlong, uapmdAppGetTimelineTrack)(JNIEnv*, jclass, jlong app, jint index) {
    return p2j(uapmd_app_get_timeline_track(AM(app), static_cast<uint32_t>(index)));
}

JNI_FN(jlong, uapmdAppMasterTimelineTrack)(JNIEnv*, jclass, jlong app) {
    return p2j(uapmd_app_master_timeline_track(AM(app)));
}

/** Returns double[12] laid out as AndroidTimeline.getState() expects, or null. */
JNI_FN(jdoubleArray, uapmdAppGetTimelineState)(JNIEnv* env, jclass, jlong app) {
    uapmd_timeline_state_t s{};
    if (!uapmd_app_get_timeline_state(AM(app), &s)) return nullptr;
    jdouble vals[12] = {
        static_cast<jdouble>(s.playhead_position.samples), s.playhead_position.legacy_beats,
        s.is_playing ? 1.0 : 0.0, s.loop_enabled ? 1.0 : 0.0,
        static_cast<jdouble>(s.loop_start.samples), s.loop_start.legacy_beats,
        static_cast<jdouble>(s.loop_end.samples), s.loop_end.legacy_beats,
        s.tempo,
        static_cast<jdouble>(s.time_signature_numerator),
        static_cast<jdouble>(s.time_signature_denominator),
        static_cast<jdouble>(s.sample_rate)
    };
    jdoubleArray out = env->NewDoubleArray(12);
    env->SetDoubleArrayRegion(out, 0, 12, vals);
    return out;
}

/* ── History ───────────────────────────────────────────────────────────────── */

JNI_FN(jobjectArray, uapmdAppGetHistoryState)(JNIEnv* env, jclass, jlong app) {
    uapmd_undo_state_t s{};
    if (!uapmd_app_get_history_state(AM(app), &s)) return nullptr;
    return pack_app_undo_state(env, s);
}

JNI_FN(void, uapmdAppUndo)(JNIEnv* env, jclass, jlong app, jobject cb) {
    auto* ctx = cb ? new AppCtx(env, cb, "(Ljava/lang/String;)V") : nullptr;
    uapmd_app_undo(AM(app), ctx, app_error_only_trampoline);
}

JNI_FN(void, uapmdAppRedo)(JNIEnv* env, jclass, jlong app, jobject cb) {
    auto* ctx = cb ? new AppCtx(env, cb, "(Ljava/lang/String;)V") : nullptr;
    uapmd_app_redo(AM(app), ctx, app_error_only_trampoline);
}

#undef JNI_FN

} // extern "C"
