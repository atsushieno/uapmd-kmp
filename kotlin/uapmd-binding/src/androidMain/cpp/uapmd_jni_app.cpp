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
#include <string>

#include "c-api/uapmd-c-common.h"
#include "c-api/uapmd-c-app.h"
#include "c-api/uapmd-c-data.h"
#include "c-api/uapmd-c-undo.h"

/* Provided by uapmd_jni.cpp (C++ linkage, matching uapmd_jni_history.cpp). */
extern JNIEnv* uapmd_jni_env();

namespace {

template <typename T>
inline jlong p2j(T ptr) { return static_cast<jlong>(reinterpret_cast<uintptr_t>(ptr)); }

/* Clip targets cross as a flat int[] of {trackIndex, clipId} pairs. */
inline std::vector<uapmd_timeline_clip_target_t> decode_targets(JNIEnv* env, jintArray pairs) {
    std::vector<uapmd_timeline_clip_target_t> result;
    if (!pairs) return result;
    const jsize n = env->GetArrayLength(pairs) / 2;
    if (n <= 0) return result;
    std::vector<jint> flat(static_cast<size_t>(n) * 2);
    env->GetIntArrayRegion(pairs, 0, n * 2, flat.data());
    result.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++)
        result.push_back({flat[i * 2], flat[i * 2 + 1]});
    return result;
}

template <typename T>
inline T j2p(jlong handle) { return reinterpret_cast<T>(static_cast<uintptr_t>(handle)); }

/** Borrows a jstring's UTF-8 for the duration of a call; null becomes "". */
struct JStr {
    JNIEnv* env;
    jstring str;
    const char* chars;
    JStr(JNIEnv* e, jstring s)
        : env(e), str(s), chars(s ? e->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~JStr() { if (str && chars) env->ReleaseStringUTFChars(str, chars); }
    JStr(const JStr&) = delete;
    JStr& operator=(const JStr&) = delete;
    const char* c_str() const { return chars ? chars : ""; }
};

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
          mid(env->GetMethodID(env->GetObjectClass(o), "invoke", signature)) {
        if (!mid) {
            /*
             * The callback object does not declare invoke() with this exact
             * descriptor - the usual cause is a bare Kotlin lambda, which only
             * carries the erased FunctionN.invoke(Object...)Object. Leaving the
             * pending NoSuchMethodError in place would abort the whole process
             * at the next unrelated JNI call ("FindClass called with pending
             * exception"), so rethrow it here where the signature is known.
             */
            env->ExceptionClear();
            if (jclass err = env->FindClass("java/lang/NoSuchMethodError"))
                env->ThrowNew(err, signature);
        }
    }

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

/** cb signature: (success: Boolean, error: String?, importedTrackCount: Int) -> Unit */
void app_midi_tracks_import_trampoline(bool success, const char* error,
                                       uint32_t importedTrackCount, void* ud) {
    auto* ctx = static_cast<AppCtx*>(ud);
    if (!ctx) return;
    JNIEnv* e = uapmd_jni_env();
    if (e && ctx->mid) {
        jstring err = error ? e->NewStringUTF(error) : nullptr;
        e->CallVoidMethod(ctx->obj, ctx->mid, static_cast<jboolean>(success), err,
                          static_cast<jint>(importedTrackCount));
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

/** cb signature: (instanceId: Int, pluginName: String?, error: String?) -> Unit */
void app_instance_created_trampoline(uapmd_plugin_instance_result_t result, void* ud) {
    auto* ctx = static_cast<AppCtx*>(ud);
    if (!ctx) return;
    JNIEnv* e = uapmd_jni_env();
    if (e && ctx->mid) {
        jstring name = result.plugin_name ? e->NewStringUTF(result.plugin_name) : nullptr;
        jstring err = result.error ? e->NewStringUTF(result.error) : nullptr;
        e->CallVoidMethod(ctx->obj, ctx->mid, static_cast<jint>(result.instance_id), name, err);
    }
    delete ctx;
}

/** cb signature: (instanceId: Int, success: Boolean, error: String?, filepath: String?) -> Unit */
void app_plugin_state_trampoline(uapmd_plugin_state_result_t r, void* ud) {
    auto* ctx = static_cast<AppCtx*>(ud);
    if (!ctx) return;
    JNIEnv* e = uapmd_jni_env();
    if (e && ctx->mid) {
        jstring err = r.error ? e->NewStringUTF(r.error) : nullptr;
        jstring path = r.filepath ? e->NewStringUTF(r.filepath) : nullptr;
        e->CallVoidMethod(ctx->obj, ctx->mid, static_cast<jint>(r.instance_id),
                          static_cast<jboolean>(r.success), err, path);
    }
    delete ctx;
}

/** Packs uapmd_app_project_result_t as Object[]{ long[1] success, String? error }. */
jobjectArray pack_project_result(JNIEnv* env, uapmd_app_project_result_t r) {
    jlong ok = r.success ? 1 : 0;
    jlongArray okArr = env->NewLongArray(1);
    env->SetLongArrayRegion(okArr, 0, 1, &ok);

    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(2, objectClass, nullptr);
    env->SetObjectArrayElement(result, 0, okArr);
    if (r.error) env->SetObjectArrayElement(result, 1, env->NewStringUTF(r.error));
    return result;
}

/** cb signature: (success: Boolean, error: String?) -> Unit */
void app_project_save_trampoline(uapmd_app_project_result_t r, void* ud) {
    auto* ctx = static_cast<AppCtx*>(ud);
    if (!ctx) return;
    JNIEnv* e = uapmd_jni_env();
    if (e && ctx->mid) {
        jstring err = r.error ? e->NewStringUTF(r.error) : nullptr;
        e->CallVoidMethod(ctx->obj, ctx->mid, static_cast<jboolean>(r.success), err);
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

JNI_FN(jobjectArray, uapmdAppSlowScanProgress)(JNIEnv* env, jclass, jlong app) {
    auto p = uapmd_app_slow_scan_progress(AM(app));
    jint counts[3] = {
        p.running ? 1 : 0,
        static_cast<jint>(p.processed_bundles),
        static_cast<jint>(p.total_bundles)
    };
    jintArray countArr = env->NewIntArray(3);
    env->SetIntArrayRegion(countArr, 0, 3, counts);

    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(2, objectClass, nullptr);
    env->SetObjectArrayElement(result, 0, countArr);
    env->SetObjectArrayElement(result, 1,
                               env->NewStringUTF(p.current_bundle ? p.current_bundle : ""));
    return result;
}

JNI_FN(jstring, uapmdAppLastPluginScanError)(JNIEnv* env, jclass, jlong app) {
    size_t needed = uapmd_app_last_plugin_scan_error(AM(app), nullptr, 0);
    std::vector<char> buf(needed + 1, 0);
    uapmd_app_last_plugin_scan_error(AM(app), buf.data(), buf.size());
    return env->NewStringUTF(buf.data());
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

JNI_FN(jdouble, uapmdAppRefreshMasterTempoMap)(JNIEnv*, jclass, jlong app) {
    return uapmd_app_refresh_master_tempo_map(AM(app));
}

/* Packed [seconds, tick, bpm] per point; flat arrays avoid struct marshalling. */
JNI_FN(jdoubleArray, uapmdAppGetMasterTempoPoints)(JNIEnv* env, jclass, jlong app) {
    auto n = uapmd_app_master_tempo_point_count(AM(app));
    std::vector<jdouble> v(n * 3);
    for (uint32_t i = 0; i < n; i++) {
        uapmd_tempo_point_t p{};
        if (!uapmd_app_get_master_tempo_point(AM(app), i, &p)) continue;
        v[i*3 + 0] = p.time_seconds;
        v[i*3 + 1] = static_cast<jdouble>(p.tick_position);
        v[i*3 + 2] = p.bpm;
    }
    jdoubleArray arr = env->NewDoubleArray(static_cast<jsize>(v.size()));
    if (!v.empty()) env->SetDoubleArrayRegion(arr, 0, static_cast<jsize>(v.size()), v.data());
    return arr;
}

/* Packed [seconds, tick, numerator, denominator] per point. */
JNI_FN(jdoubleArray, uapmdAppGetMasterTimeSignatures)(JNIEnv* env, jclass, jlong app) {
    auto n = uapmd_app_master_time_signature_count(AM(app));
    std::vector<jdouble> v(n * 4);
    for (uint32_t i = 0; i < n; i++) {
        uapmd_time_signature_point_t p{};
        if (!uapmd_app_get_master_time_signature(AM(app), i, &p)) continue;
        v[i*4 + 0] = p.time_seconds;
        v[i*4 + 1] = static_cast<jdouble>(p.tick_position);
        v[i*4 + 2] = p.numerator;
        v[i*4 + 3] = p.denominator;
    }
    jdoubleArray arr = env->NewDoubleArray(static_cast<jsize>(v.size()));
    if (!v.empty()) env->SetDoubleArrayRegion(arr, 0, static_cast<jsize>(v.size()), v.data());
    return arr;
}

JNI_FN(jint, uapmdAppBlocklistCount)(JNIEnv*, jclass, jlong app) {
    return static_cast<jint>(uapmd_app_blocklist_count(AM(app)));
}

JNI_FN(jobjectArray, uapmdAppGetBlocklistEntry)(JNIEnv* env, jclass, jlong app, jint index) {
    uapmd_blocklist_entry_t e{};
    if (!uapmd_app_get_blocklist_entry(AM(app), static_cast<uint32_t>(index), &e))
        return nullptr;
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(4, strCls, nullptr);
    const char* fields[4] = {e.id, e.format, e.plugin_id, e.reason};
    for (int i = 0; i < 4; i++)
        env->SetObjectArrayElement(arr, i, env->NewStringUTF(fields[i] ? fields[i] : ""));
    env->DeleteLocalRef(strCls);
    return arr;
}

JNI_FN(jboolean, uapmdAppUnblockPlugin)(JNIEnv* env, jclass, jlong app, jstring entryId) {
    const char* id = env->GetStringUTFChars(entryId, nullptr);
    bool ok = uapmd_app_unblock_plugin_from_blocklist(AM(app), id);
    env->ReleaseStringUTFChars(entryId, id);
    return ok;
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
    return p2j(uapmd_app_get_master_timeline_track(AM(app)));
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
    if (!uapmd_app_history_state(AM(app), &s)) return nullptr;
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

/* ── Plugin instances ──────────────────────────────────────────────────────── */

JNI_FN(void, uapmdAppCreatePluginInstance)(JNIEnv* env, jclass, jlong app,
                                             jstring format, jstring pluginId, jint trackIndex,
                                             jstring apiName, jstring deviceName, jstring manufacturer,
                                             jstring version, jstring stateFile, jobject cb) {
    const char* f   = format     ? env->GetStringUTFChars(format, nullptr)     : nullptr;
    const char* p   = pluginId   ? env->GetStringUTFChars(pluginId, nullptr)   : nullptr;
    const char* an  = apiName    ? env->GetStringUTFChars(apiName, nullptr)    : nullptr;
    const char* dn  = deviceName ? env->GetStringUTFChars(deviceName, nullptr) : nullptr;
    const char* mf  = manufacturer ? env->GetStringUTFChars(manufacturer, nullptr) : nullptr;
    const char* ver = version    ? env->GetStringUTFChars(version, nullptr)    : nullptr;
    const char* sf  = stateFile  ? env->GetStringUTFChars(stateFile, nullptr)  : nullptr;

    uapmd_plugin_instance_config_t cfg{};
    cfg.api_name = an; cfg.device_name = dn; cfg.manufacturer = mf;
    cfg.version = ver; cfg.state_file = sf;

    auto* ctx = cb ? new AppCtx(env, cb, "(ILjava/lang/String;Ljava/lang/String;)V") : nullptr;
    uapmd_app_create_plugin_instance(AM(app), f, p, trackIndex, &cfg, ctx,
                                       app_instance_created_trampoline);

    if (format) env->ReleaseStringUTFChars(format, f);
    if (pluginId) env->ReleaseStringUTFChars(pluginId, p);
    if (apiName) env->ReleaseStringUTFChars(apiName, an);
    if (deviceName) env->ReleaseStringUTFChars(deviceName, dn);
    if (manufacturer) env->ReleaseStringUTFChars(manufacturer, mf);
    if (version) env->ReleaseStringUTFChars(version, ver);
    if (stateFile) env->ReleaseStringUTFChars(stateFile, sf);
}

JNI_FN(void, uapmdAppRemovePluginInstance)(JNIEnv*, jclass, jlong app, jint instanceId) {
    uapmd_app_remove_plugin_instance(AM(app), instanceId);
}

JNI_FN(jint, uapmdAppGetInstanceGroup)(JNIEnv*, jclass, jlong app, jint instanceId) {
    return uapmd_app_get_instance_group(AM(app), instanceId);
}

JNI_FN(jboolean, uapmdAppSetInstanceGroup)(JNIEnv*, jclass, jlong app, jint instanceId, jint group) {
    return uapmd_app_set_instance_group(AM(app), instanceId, static_cast<uint8_t>(group));
}

JNI_FN(void, uapmdAppEnableUmpDevice)(JNIEnv* env, jclass, jlong app, jint instanceId, jstring deviceName) {
    const char* d = deviceName ? env->GetStringUTFChars(deviceName, nullptr) : nullptr;
    uapmd_app_enable_ump_device(AM(app), instanceId, d);
    if (deviceName) env->ReleaseStringUTFChars(deviceName, d);
}

JNI_FN(void, uapmdAppDisableUmpDevice)(JNIEnv*, jclass, jlong app, jint instanceId) {
    uapmd_app_disable_ump_device(AM(app), instanceId);
}

JNI_FN(void, uapmdAppRequestShowInstanceDetails)(JNIEnv*, jclass, jlong app, jint instanceId) {
    uapmd_app_request_show_instance_details(AM(app), instanceId);
}

JNI_FN(void, uapmdAppRequestShowPluginUi)(JNIEnv*, jclass, jlong app, jint instanceId) {
    uapmd_app_request_show_plugin_ui(AM(app), instanceId);
}

JNI_FN(void, uapmdAppHidePluginUi)(JNIEnv*, jclass, jlong app, jint instanceId) {
    uapmd_app_hide_plugin_ui(AM(app), instanceId);
}

/* ── Project I/O ───────────────────────────────────────────────────────────── */

JNI_FN(jobjectArray, uapmdAppLoadProject)(JNIEnv* env, jclass, jlong app, jstring path) {
    const char* p = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    auto r = uapmd_app_load_project(AM(app), p);
    if (path) env->ReleaseStringUTFChars(path, p);
    return pack_project_result(env, r);
}

JNI_FN(jobjectArray, uapmdAppSaveProjectSync)(JNIEnv* env, jclass, jlong app, jstring path) {
    const char* p = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    auto r = uapmd_app_save_project_sync(AM(app), p);
    if (path) env->ReleaseStringUTFChars(path, p);
    return pack_project_result(env, r);
}

JNI_FN(void, uapmdAppImportMidiTracksFromFile)(JNIEnv* env, jclass, jlong app,
                                                jstring filepath, jobject cb) {
    if (!cb) return;
    auto* ctx = new AppCtx(env, cb, "(ZLjava/lang/String;I)V");
    const char* p = filepath ? env->GetStringUTFChars(filepath, nullptr) : nullptr;
    uapmd_app_import_midi_tracks_from_file(AM(app), p, ctx, app_midi_tracks_import_trampoline);
    if (filepath) env->ReleaseStringUTFChars(filepath, p);
}

JNI_FN(void, uapmdAppSaveProject)(JNIEnv* env, jclass, jlong app, jstring path, jobject cb) {
    const char* p = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    auto* ctx = cb ? new AppCtx(env, cb, "(ZLjava/lang/String;)V") : nullptr;
    uapmd_app_save_project(AM(app), p, ctx, app_project_save_trampoline);
    if (path) env->ReleaseStringUTFChars(path, p);
}

JNI_FN(jobjectArray, uapmdAppLoadProjectFromHandleToken)(JNIEnv* env, jclass, jlong app, jstring token) {
    const char* t = token ? env->GetStringUTFChars(token, nullptr) : nullptr;
    auto r = uapmd_app_load_project_from_handle_token(AM(app), t);
    if (token) env->ReleaseStringUTFChars(token, t);
    return pack_project_result(env, r);
}

JNI_FN(jobjectArray, uapmdAppNewProject)(JNIEnv* env, jclass, jlong app) {
    return pack_project_result(env, uapmd_app_new_project(AM(app)));
}

JNI_FN(jlong, uapmdAppMasterTempoMap)(JNIEnv*, jclass, jlong app) {
    return p2j(uapmd_app_master_tempo_map(AM(app)));
}

/* ── Timeline clip selection and clipboard ─────────────────────────────────
 *
 * Targets cross as a flat int[] of {trackIndex, clipId} pairs, the same way
 * markers cross as parallel arrays: two ints per entry, no struct mirroring.
 */

JNI_FN(jboolean, uapmdAppIsTimelineClipSelected)(JNIEnv*, jclass, jlong app, jint t, jint c) {
    return uapmd_app_is_timeline_clip_selected(AM(app), t, c);
}

/** Returns int[2n] {trackIndex, clipId, ...}. */
JNI_FN(jintArray, uapmdAppSelectedTimelineClips)(JNIEnv* env, jclass, jlong app) {
    const uint32_t count = uapmd_app_selected_timeline_clips(AM(app), nullptr, 0);
    jintArray arr = env->NewIntArray(static_cast<jsize>(count) * 2);
    if (count == 0) return arr;
    std::vector<uapmd_timeline_clip_target_t> buf(count);
    const uint32_t filled = uapmd_app_selected_timeline_clips(AM(app), buf.data(), count);
    std::vector<jint> flat;
    flat.reserve(static_cast<size_t>(filled) * 2);
    for (uint32_t i = 0; i < filled; i++) {
        flat.push_back(buf[i].track_index);
        flat.push_back(buf[i].clip_id);
    }
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(flat.size()), flat.data());
    return arr;
}

JNI_FN(void, uapmdAppSelectTimelineClips)(JNIEnv* env, jclass, jlong app, jintArray pairs,
                                           jboolean additive, jboolean toggle) {
    auto targets = decode_targets(env, pairs);
    uapmd_app_select_timeline_clips(AM(app), targets.empty() ? nullptr : targets.data(),
                                    static_cast<uint32_t>(targets.size()), additive, toggle);
}

JNI_FN(void, uapmdAppClearTimelineClipSelection)(JNIEnv*, jclass, jlong app) {
    uapmd_app_clear_timeline_clip_selection(AM(app));
}

JNI_FN(jboolean, uapmdAppSelectTimelineMidiClip)(JNIEnv*, jclass, jlong app, jint t, jint c) {
    return uapmd_app_select_timeline_midi_clip(AM(app), t, c);
}

/** Returns int[2] {trackIndex, clipId}, or null when nothing is selected. */
JNI_FN(jintArray, uapmdAppSelectedTimelineMidiClip)(JNIEnv* env, jclass, jlong app) {
    uapmd_timeline_clip_target_t out{};
    if (!uapmd_app_selected_timeline_midi_clip(AM(app), &out)) return nullptr;
    jint vals[2] = {out.track_index, out.clip_id};
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, vals);
    return arr;
}

JNI_FN(jint, uapmdAppTimelineClipboardCount)(JNIEnv*, jclass, jlong app) {
    return static_cast<jint>(uapmd_app_timeline_clipboard_count(AM(app)));
}

JNI_FN(void, uapmdAppClearTimelineClipboard)(JNIEnv*, jclass, jlong app) {
    uapmd_app_clear_timeline_clipboard(AM(app));
}

JNI_FN(jboolean, uapmdAppCopySelectedTimelineClips)(JNIEnv*, jclass, jlong app) {
    return uapmd_app_copy_selected_timeline_clips(AM(app));
}

/**
 * Deletes and reports in one call -- calling twice would delete twice. The
 * changed-track indexes come back in `outTracks`, whose length is the caller's
 * capacity; the count written is returned.
 */
JNI_FN(jint, uapmdAppDeleteSelectedTimelineClips)(JNIEnv* env, jclass, jlong app, jboolean cut,
                                                   jintArray outTracks) {
    uint32_t capacity = outTracks ? static_cast<uint32_t>(env->GetArrayLength(outTracks)) : 0;
    std::vector<int32_t> buf(capacity);
    uint32_t count = capacity;
    const bool ok = uapmd_app_delete_selected_timeline_clips(
        AM(app), cut, capacity ? buf.data() : nullptr, &count);
    if (outTracks && count)
        env->SetIntArrayRegion(outTracks, 0, static_cast<jsize>(count < capacity ? count : capacity), buf.data());
    return ok ? static_cast<jint>(count) : -1;
}

JNI_FN(jintArray, uapmdAppTimelinePasteDestinations)(JNIEnv* env, jclass, jlong app, jint t,
                                                      jboolean originalTracks) {
    const uint32_t count = uapmd_app_timeline_paste_destinations(AM(app), t, originalTracks, nullptr, 0);
    jintArray arr = env->NewIntArray(static_cast<jsize>(count));
    if (count == 0) return arr;
    std::vector<int32_t> buf(count);
    const uint32_t filled = uapmd_app_timeline_paste_destinations(AM(app), t, originalTracks, buf.data(), count);
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(filled), buf.data());
    return arr;
}

/**
 * Returns int[1 + 2n]: success flag, then {trackIndex, clipId} per pasted clip.
 * A paste creates at most one clip per clipboard entry, which bounds it.
 */
JNI_FN(jintArray, uapmdAppPasteTimelineClips)(JNIEnv* env, jclass, jlong app, jint t,
                                               jdouble positionSeconds, jboolean originalTracks) {
    uint32_t capacity = uapmd_app_timeline_clipboard_count(AM(app));
    std::vector<uapmd_timeline_clip_target_t> buf(capacity);
    uint32_t count = capacity;
    const bool ok = uapmd_app_paste_timeline_clips(
        AM(app), t, positionSeconds, originalTracks, capacity ? buf.data() : nullptr, &count);
    if (count > capacity) count = capacity;
    std::vector<jint> flat;
    flat.reserve(1 + static_cast<size_t>(count) * 2);
    flat.push_back(ok ? 1 : 0);
    for (uint32_t i = 0; i < count; i++) {
        flat.push_back(buf[i].track_index);
        flat.push_back(buf[i].clip_id);
    }
    jintArray arr = env->NewIntArray(static_cast<jsize>(flat.size()));
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(flat.size()), flat.data());
    return arr;
}

JNI_FN(jstring, uapmdAppLastTimelineClipError)(JNIEnv* env, jclass) {
    const char* e = uapmd_app_last_timeline_clip_error();
    return env->NewStringUTF(e ? e : "");
}

/* ── Piano roll editing session ────────────────────────────────────────────
 *
 * A note crosses as one double[6] and one long[6] rather than as a mirrored
 * Java class: the struct is a fixed set of scalars, which is the same shape the
 * rest of this bridge packs into primitive arrays.
 *   doubles: {startSeconds, durationSeconds, velocity}
 *   longs:   {note, channel, deleted, editId, umpGroup, releaseVelocity,
 *             attributeType, attributeValue, automationEventCount}
 */

JNI_FN(jlong, uapmdAppPianoRollClipSnapshot)(JNIEnv*, jclass, jlong app, jint t, jint c, jdouble fallback) {
    return p2j(uapmd_app_piano_roll_clip_snapshot(AM(app), t, c, fallback));
}

JNI_FN(void, uapmdPianoRollSnapshotDestroy)(JNIEnv*, jclass, jlong h) {
    uapmd_piano_roll_snapshot_destroy(j2p<uapmd_piano_roll_snapshot_t>(h));
}

JNI_FN(jboolean, uapmdPianoRollSnapshotReady)(JNIEnv*, jclass, jlong h) {
    return uapmd_piano_roll_snapshot_ready(j2p<uapmd_piano_roll_snapshot_t>(h));
}

JNI_FN(jstring, uapmdPianoRollSnapshotError)(JNIEnv* env, jclass, jlong h) {
    const char* e = uapmd_piano_roll_snapshot_error(j2p<uapmd_piano_roll_snapshot_t>(h));
    return env->NewStringUTF(e ? e : "");
}

JNI_FN(jdouble, uapmdPianoRollSnapshotDurationSeconds)(JNIEnv*, jclass, jlong h) {
    return uapmd_piano_roll_snapshot_duration_seconds(j2p<uapmd_piano_roll_snapshot_t>(h));
}

JNI_FN(jint, uapmdPianoRollSnapshotMinNote)(JNIEnv*, jclass, jlong h) {
    return uapmd_piano_roll_snapshot_min_note(j2p<uapmd_piano_roll_snapshot_t>(h));
}

JNI_FN(jint, uapmdPianoRollSnapshotMaxNote)(JNIEnv*, jclass, jlong h) {
    return uapmd_piano_roll_snapshot_max_note(j2p<uapmd_piano_roll_snapshot_t>(h));
}

JNI_FN(jint, uapmdPianoRollSnapshotNoteCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_piano_roll_snapshot_note_count(j2p<uapmd_piano_roll_snapshot_t>(h)));
}

namespace {
void pack_piano_roll_note(JNIEnv* env, const uapmd_piano_roll_note_t& n,
                          jdoubleArray outDoubles, jlongArray outLongs) {
    if (outDoubles && env->GetArrayLength(outDoubles) >= 3) {
        jdouble d[3] = {n.start_seconds, n.duration_seconds, static_cast<jdouble>(n.velocity)};
        env->SetDoubleArrayRegion(outDoubles, 0, 3, d);
    }
    if (outLongs && env->GetArrayLength(outLongs) >= 9) {
        jlong l[9] = {
            n.note, n.channel, n.deleted ? 1 : 0, static_cast<jlong>(n.edit_id),
            n.ump_group, n.release_velocity, n.attribute_type, n.attribute_value,
            n.automation_event_count
        };
        env->SetLongArrayRegion(outLongs, 0, 9, l);
    }
}
} // namespace

JNI_FN(jboolean, uapmdPianoRollSnapshotGetNote)(JNIEnv* env, jclass, jlong h, jint index,
                                                 jdoubleArray outDoubles, jlongArray outLongs) {
    uapmd_piano_roll_note_t n{};
    if (!uapmd_piano_roll_snapshot_get_note(j2p<uapmd_piano_roll_snapshot_t>(h),
                                            static_cast<uint32_t>(index), &n))
        return false;
    pack_piano_roll_note(env, n, outDoubles, outLongs);
    return true;
}

JNI_FN(jlong, uapmdAppOpenPianoRollSession)(JNIEnv*, jclass, jlong app, jint t, jint c) {
    return p2j(uapmd_app_open_piano_roll_session(AM(app), t, c));
}

JNI_FN(jlong, uapmdAppFindPianoRollSession)(JNIEnv*, jclass, jlong app, jint t, jint c) {
    return p2j(uapmd_app_find_piano_roll_session(AM(app), t, c));
}

JNI_FN(void, uapmdAppClosePianoRollSession)(JNIEnv*, jclass, jlong app, jint t, jint c) {
    uapmd_app_close_piano_roll_session(AM(app), t, c);
}

JNI_FN(jboolean, uapmdPianoRollSessionMatchesSource)(JNIEnv*, jclass, jlong session, jlong snapshot) {
    return uapmd_piano_roll_session_matches_source(
        j2p<uapmd_piano_roll_session_t>(session), j2p<uapmd_piano_roll_snapshot_t>(snapshot));
}

JNI_FN(void, uapmdPianoRollSessionLoadNotes)(JNIEnv*, jclass, jlong session, jlong snapshot) {
    uapmd_piano_roll_session_load_notes(
        j2p<uapmd_piano_roll_session_t>(session), j2p<uapmd_piano_roll_snapshot_t>(snapshot));
}

JNI_FN(jint, uapmdPianoRollSessionNoteCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_piano_roll_session_note_count(j2p<uapmd_piano_roll_session_t>(h)));
}

JNI_FN(jboolean, uapmdPianoRollSessionGetNote)(JNIEnv* env, jclass, jlong h, jint index,
                                                jdoubleArray outDoubles, jlongArray outLongs) {
    uapmd_piano_roll_note_t n{};
    if (!uapmd_piano_roll_session_get_note(j2p<uapmd_piano_roll_session_t>(h),
                                           static_cast<uint32_t>(index), &n))
        return false;
    pack_piano_roll_note(env, n, outDoubles, outLongs);
    return true;
}

JNI_FN(jboolean, uapmdPianoRollSessionIsNoteSelected)(JNIEnv*, jclass, jlong h, jint index) {
    return uapmd_piano_roll_session_is_note_selected(j2p<uapmd_piano_roll_session_t>(h), static_cast<uint32_t>(index));
}

JNI_FN(jint, uapmdPianoRollSessionSelectedNoteCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_piano_roll_session_selected_note_count(j2p<uapmd_piano_roll_session_t>(h)));
}

JNI_FN(jint, uapmdPianoRollSessionFocusedNote)(JNIEnv*, jclass, jlong h) {
    return uapmd_piano_roll_session_focused_note(j2p<uapmd_piano_roll_session_t>(h));
}

JNI_FN(void, uapmdPianoRollSessionSetFocusedNote)(JNIEnv*, jclass, jlong h, jint index) {
    uapmd_piano_roll_session_set_focused_note(j2p<uapmd_piano_roll_session_t>(h), index);
}

JNI_FN(jdouble, uapmdPianoRollSessionDurationSeconds)(JNIEnv*, jclass, jlong h) {
    return uapmd_piano_roll_session_duration_seconds(j2p<uapmd_piano_roll_session_t>(h));
}

JNI_FN(jint, uapmdPianoRollSessionMinNote)(JNIEnv*, jclass, jlong h) {
    return uapmd_piano_roll_session_min_note(j2p<uapmd_piano_roll_session_t>(h));
}

JNI_FN(jint, uapmdPianoRollSessionMaxNote)(JNIEnv*, jclass, jlong h) {
    return uapmd_piano_roll_session_max_note(j2p<uapmd_piano_roll_session_t>(h));
}

JNI_FN(jint, uapmdPianoRollSessionClipboardCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_piano_roll_session_clipboard_count(j2p<uapmd_piano_roll_session_t>(h)));
}

JNI_FN(jboolean, uapmdPianoRollSessionDirty)(JNIEnv*, jclass, jlong h) {
    return uapmd_piano_roll_session_dirty(j2p<uapmd_piano_roll_session_t>(h));
}

JNI_FN(jstring, uapmdPianoRollSessionError)(JNIEnv* env, jclass, jlong h) {
    const char* e = uapmd_piano_roll_session_error(j2p<uapmd_piano_roll_session_t>(h));
    return env->NewStringUTF(e ? e : "");
}

JNI_FN(void, uapmdPianoRollSessionSelectNote)(JNIEnv*, jclass, jlong h, jint index, jboolean additive, jboolean toggle) {
    uapmd_piano_roll_session_select_note(j2p<uapmd_piano_roll_session_t>(h), index, additive, toggle);
}

JNI_FN(void, uapmdPianoRollSessionPerformAction)(JNIEnv*, jclass, jlong h, jint action, jdouble pasteSeconds) {
    uapmd_piano_roll_session_perform_action(j2p<uapmd_piano_roll_session_t>(h),
        static_cast<uapmd_piano_roll_action_t>(action), pasteSeconds);
}

JNI_FN(void, uapmdPianoRollSessionCreateNote)(JNIEnv*, jclass, jlong h, jdouble start, jdouble duration, jint note, jfloat velocity) {
    uapmd_piano_roll_session_create_note(j2p<uapmd_piano_roll_session_t>(h), start, duration,
        static_cast<uint8_t>(note), velocity);
}

JNI_FN(void, uapmdPianoRollSessionDeleteNote)(JNIEnv*, jclass, jlong h, jint index) {
    uapmd_piano_roll_session_delete_note(j2p<uapmd_piano_roll_session_t>(h), static_cast<uint32_t>(index));
}

JNI_FN(void, uapmdPianoRollSessionResizeNote)(JNIEnv*, jclass, jlong h, jint index, jdouble start, jdouble duration, jint note) {
    uapmd_piano_roll_session_resize_note(j2p<uapmd_piano_roll_session_t>(h), static_cast<uint32_t>(index),
        start, duration, static_cast<uint8_t>(note));
}

JNI_FN(void, uapmdPianoRollSessionBeginDrag)(JNIEnv*, jclass, jlong h) {
    uapmd_piano_roll_session_begin_drag(j2p<uapmd_piano_roll_session_t>(h));
}

JNI_FN(void, uapmdPianoRollSessionMoveSelection)(JNIEnv*, jclass, jlong h, jdouble timeDelta, jint pitchDelta) {
    uapmd_piano_roll_session_move_selection(j2p<uapmd_piano_roll_session_t>(h), timeDelta, pitchDelta);
}

JNI_FN(void, uapmdPianoRollSessionCancelDrag)(JNIEnv*, jclass, jlong h) {
    uapmd_piano_roll_session_cancel_drag(j2p<uapmd_piano_roll_session_t>(h));
}

JNI_FN(void, uapmdPianoRollSessionFinishDrag)(JNIEnv*, jclass, jlong h, jint index, jdouble origStart, jdouble origEnd, jint origNote) {
    uapmd_piano_roll_session_finish_drag(j2p<uapmd_piano_roll_session_t>(h), static_cast<uint32_t>(index),
        origStart, origEnd, static_cast<uint8_t>(origNote));
}

JNI_FN(jboolean, uapmdPianoRollSessionCommit)(JNIEnv*, jclass, jlong h, jlong app) {
    return uapmd_piano_roll_session_commit(j2p<uapmd_piano_roll_session_t>(h), AM(app));
}

JNI_FN(void, uapmdAppRecordPianoRollCommitSource)(JNIEnv*, jclass, jlong app, jint t, jint c) {
    uapmd_app_record_piano_roll_commit_source(AM(app), t, c);
}

JNI_FN(jboolean, uapmdAppPianoRollSourceMatchesLastEdit)(JNIEnv*, jclass, jlong app) {
    return uapmd_app_piano_roll_source_matches_last_edit(AM(app));
}

JNI_FN(void, uapmdAppClearPianoRollCommitSource)(JNIEnv*, jclass, jlong app) {
    uapmd_app_clear_piano_roll_commit_source(AM(app));
}

JNI_FN(void, uapmdTransportJump)(JNIEnv*, jclass, jlong tc, jdouble positionSeconds) {
    uapmd_transport_jump(j2p<uapmd_transport_controller_t>(tc), positionSeconds);
}

/* ── Assorted AppModel accessors ──────────────────────────────────────────── */

namespace {
/* Ports and devices come back as flat String[]s, two and four entries each. */
jobjectArray ports_to_strings(JNIEnv* env, const std::vector<uapmd_midi_port_info_t>& ports) {
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(ports.size()) * 2, sc, nullptr);
    for (size_t i = 0; i < ports.size(); i++) {
        env->SetObjectArrayElement(arr, static_cast<jsize>(i * 2),
            env->NewStringUTF(ports[i].id ? ports[i].id : ""));
        env->SetObjectArrayElement(arr, static_cast<jsize>(i * 2 + 1),
            env->NewStringUTF(ports[i].display_name ? ports[i].display_name : ""));
    }
    return arr;
}
} // namespace

JNI_FN(jobjectArray, uapmdAppGetMidiInputPorts)(JNIEnv* env, jclass, jlong app) {
    const uint32_t n = uapmd_app_get_midi_input_ports(AM(app), nullptr, 0);
    std::vector<uapmd_midi_port_info_t> ports(n);
    if (n) uapmd_app_get_midi_input_ports(AM(app), ports.data(), n);
    return ports_to_strings(env, ports);
}

JNI_FN(jobjectArray, uapmdAppGetMidiOutputPorts)(JNIEnv* env, jclass, jlong app) {
    const uint32_t n = uapmd_app_get_midi_output_ports(AM(app), nullptr, 0);
    std::vector<uapmd_midi_port_info_t> ports(n);
    if (n) uapmd_app_get_midi_output_ports(AM(app), ports.data(), n);
    return ports_to_strings(env, ports);
}

JNI_FN(jboolean, uapmdAppIsTrackHidden)(JNIEnv*, jclass, jlong app, jint t) {
    return uapmd_app_is_track_hidden(AM(app), t);
}

/** double[4] {hasContent, startSeconds, endSeconds, durationSeconds}. */
JNI_FN(jdoubleArray, uapmdAppTimelineContentBounds)(JNIEnv* env, jclass, jlong app) {
    const auto b = uapmd_app_timeline_content_bounds(AM(app));
    jdouble vals[4] = {b.has_content ? 1.0 : 0.0, b.start_seconds, b.end_seconds, b.duration_seconds};
    jdoubleArray arr = env->NewDoubleArray(4);
    env->SetDoubleArrayRegion(arr, 0, 4, vals);
    return arr;
}

/**
 * Devices cross as a flat String[3n] {label, apiName, statusMessage} plus an
 * int[4n] {id, running, instantiating, hasError}.
 */
JNI_FN(jobjectArray, uapmdAppGetDevices)(JNIEnv* env, jclass, jlong app, jintArray outInts) {
    const uint32_t n = uapmd_app_get_devices(AM(app), nullptr, 0);
    std::vector<uapmd_device_entry_t> devices(n);
    if (n) uapmd_app_get_devices(AM(app), devices.data(), n);
    if (outInts && env->GetArrayLength(outInts) >= static_cast<jsize>(n) * 4) {
        std::vector<jint> ints;
        ints.reserve(static_cast<size_t>(n) * 4);
        for (const auto& d : devices) {
            ints.push_back(d.id);
            ints.push_back(d.running ? 1 : 0);
            ints.push_back(d.instantiating ? 1 : 0);
            ints.push_back(d.has_error ? 1 : 0);
        }
        if (!ints.empty())
            env->SetIntArrayRegion(outInts, 0, static_cast<jsize>(ints.size()), ints.data());
    }
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(n) * 3, sc, nullptr);
    for (uint32_t i = 0; i < n; i++) {
        env->SetObjectArrayElement(arr, i * 3, env->NewStringUTF(devices[i].label ? devices[i].label : ""));
        env->SetObjectArrayElement(arr, i * 3 + 1, env->NewStringUTF(devices[i].api_name ? devices[i].api_name : ""));
        env->SetObjectArrayElement(arr, i * 3 + 2, env->NewStringUTF(devices[i].status_message ? devices[i].status_message : ""));
    }
    return arr;
}

JNI_FN(jint, uapmdAppGetDeviceCount)(JNIEnv*, jclass, jlong app) {
    return static_cast<jint>(uapmd_app_get_devices(AM(app), nullptr, 0));
}

/** Same encoding as one device row; null when the instance has no device. */
JNI_FN(jobjectArray, uapmdAppGetDeviceForInstance)(JNIEnv* env, jclass, jlong app, jint instanceId, jintArray outInts) {
    uapmd_device_entry_t d{};
    if (!uapmd_app_get_device_for_instance(AM(app), instanceId, &d)) return nullptr;
    if (outInts && env->GetArrayLength(outInts) >= 4) {
        jint ints[4] = {d.id, d.running ? 1 : 0, d.instantiating ? 1 : 0, d.has_error ? 1 : 0};
        env->SetIntArrayRegion(outInts, 0, 4, ints);
    }
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(3, sc, nullptr);
    env->SetObjectArrayElement(arr, 0, env->NewStringUTF(d.label ? d.label : ""));
    env->SetObjectArrayElement(arr, 1, env->NewStringUTF(d.api_name ? d.api_name : ""));
    env->SetObjectArrayElement(arr, 2, env->NewStringUTF(d.status_message ? d.status_message : ""));
    return arr;
}

JNI_FN(void, uapmdAppUpdateDeviceLabel)(JNIEnv* env, jclass, jlong app, jint instanceId, jstring label) {
    JStr l(env, label);
    uapmd_app_update_device_label(AM(app), instanceId, l.c_str());
}

namespace {
/** int[2] {instanceId, success} plus String[2] {error, filepath}. */
jintArray pack_state_result(JNIEnv* env, const uapmd_plugin_state_result_t& r, jobjectArray outStrings) {
    if (outStrings && env->GetArrayLength(outStrings) >= 2) {
        env->SetObjectArrayElement(outStrings, 0, env->NewStringUTF(r.error ? r.error : ""));
        env->SetObjectArrayElement(outStrings, 1, env->NewStringUTF(r.filepath ? r.filepath : ""));
    }
    jint vals[2] = {r.instance_id, r.success ? 1 : 0};
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, vals);
    return arr;
}
} // namespace

/*
 * The asynchronous pair. uapmd-app-model completes these on its own worker, so
 * the callback lands on whichever thread finished the I/O - the same contract
 * the JVM and Native bindings document.
 */
JNI_FN(void, uapmdAppLoadPluginState)(JNIEnv* env, jclass, jlong app, jint instanceId,
                                       jstring filepath, jobject cb) {
    JStr f(env, filepath);
    uapmd_app_load_plugin_state(AM(app), instanceId, f.c_str(),
                                new AppCtx(env, cb, "(IZLjava/lang/String;Ljava/lang/String;)V"),
                                app_plugin_state_trampoline);
}

JNI_FN(void, uapmdAppSavePluginState)(JNIEnv* env, jclass, jlong app, jint instanceId,
                                       jstring filepath, jobject cb) {
    JStr f(env, filepath);
    uapmd_app_save_plugin_state(AM(app), instanceId, f.c_str(),
                                new AppCtx(env, cb, "(IZLjava/lang/String;Ljava/lang/String;)V"),
                                app_plugin_state_trampoline);
}

JNI_FN(jintArray, uapmdAppLoadPluginStateSync)(JNIEnv* env, jclass, jlong app, jint instanceId,
                                                jstring filepath, jobjectArray outStrings) {
    JStr f(env, filepath);
    auto r = uapmd_app_load_plugin_state_sync(AM(app), instanceId, f.c_str());
    return pack_state_result(env, r, outStrings);
}

JNI_FN(jintArray, uapmdAppSavePluginStateSync)(JNIEnv* env, jclass, jlong app, jint instanceId,
                                                jstring filepath, jobjectArray outStrings) {
    JStr f(env, filepath);
    auto r = uapmd_app_save_plugin_state_sync(AM(app), instanceId, f.c_str());
    return pack_state_result(env, r, outStrings);
}

JNI_FN(void, uapmdAppMarkPluginInstanceTrackDirty)(JNIEnv*, jclass, jlong app, jint instanceId) {
    uapmd_app_mark_plugin_instance_track_dirty(AM(app), instanceId);
}

/* ── MIDI clip UMP events ──────────────────────────────────────────────────── */

/** Returns Object[]{ long[1] success, String? error, long[] ticks, int[][] words } or null. */
JNI_FN(jobjectArray, uapmdAppGetMidiClipUmpEvents)(JNIEnv* env, jclass, jlong app,
                                                     jint trackIndex, jint clipId) {
    auto r = uapmd_app_get_midi_clip_ump_events(AM(app), trackIndex, clipId);

    /* long[2] = { success, tickResolution }; the tempo rides in its own double[1]. */
    jlong head[2] = { r.success ? 1 : 0, static_cast<jlong>(r.tick_resolution) };
    jlongArray okArr = env->NewLongArray(2);
    env->SetLongArrayRegion(okArr, 0, 2, head);
    jdouble tempo = r.clip_tempo;
    jdoubleArray tempoArr = env->NewDoubleArray(1);
    env->SetDoubleArrayRegion(tempoArr, 0, 1, &tempo);

    const jsize n = r.success && r.events ? static_cast<jsize>(r.event_count) : 0;
    jlongArray ticks = env->NewLongArray(n);
    jclass intArrayClass = env->FindClass("[I");
    jobjectArray words = env->NewObjectArray(n, intArrayClass, nullptr);
    for (jsize i = 0; i < n; i++) {
        const auto& e = r.events[i];
        jlong tick = static_cast<jlong>(e.tick);
        env->SetLongArrayRegion(ticks, i, 1, &tick);
        jintArray w = env->NewIntArray(static_cast<jsize>(e.word_count));
        if (e.words && e.word_count)
            env->SetIntArrayRegion(w, 0, static_cast<jsize>(e.word_count),
                                     reinterpret_cast<const jint*>(e.words));
        env->SetObjectArrayElement(words, i, w);
        env->DeleteLocalRef(w);
    }

    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(5, objectClass, nullptr);
    env->SetObjectArrayElement(result, 0, okArr);
    if (r.error) env->SetObjectArrayElement(result, 1, env->NewStringUTF(r.error));
    env->SetObjectArrayElement(result, 2, ticks);
    env->SetObjectArrayElement(result, 3, words);
    env->SetObjectArrayElement(result, 4, tempoArr);
    return result;
}

JNI_FN(jboolean, uapmdAppAddUmpEventToClip)(JNIEnv* env, jclass, jlong app, jint trackIndex,
                                              jint clipId, jlong tick, jintArray words) {
    jsize n = words ? env->GetArrayLength(words) : 0;
    jint* w = words ? env->GetIntArrayElements(words, nullptr) : nullptr;
    bool ok = uapmd_app_add_ump_event_to_clip(AM(app), trackIndex, clipId,
                                                static_cast<uint64_t>(tick),
                                                reinterpret_cast<const uint32_t*>(w),
                                                static_cast<uint32_t>(n));
    if (words) env->ReleaseIntArrayElements(words, w, JNI_ABORT);
    return ok;
}

JNI_FN(jboolean, uapmdAppRemoveUmpEventFromClip)(JNIEnv*, jclass, jlong app, jint trackIndex,
                                                   jint clipId, jint eventIndex) {
    return uapmd_app_remove_ump_event_from_clip(AM(app), trackIndex, clipId, eventIndex);
}

JNI_FN(jboolean, uapmdAppRemoveClipFromTrack)(JNIEnv*, jclass, jlong app, jint trackIndex, jint clipId) {
    return uapmd_app_remove_clip_from_track(AM(app), trackIndex, clipId);
}

/* ── Track graph ───────────────────────────────────────────────────────────── */

JNI_FN(jboolean, uapmdAppEnsureTrackUsesEditorGraph)(JNIEnv*, jclass, jlong app, jint t) {
    return uapmd_app_ensure_track_uses_editor_graph(AM(app), t);
}

JNI_FN(jboolean, uapmdAppRevertTrackToSimpleGraph)(JNIEnv*, jclass, jlong app, jint t) {
    return uapmd_app_revert_track_to_simple_graph(AM(app), t);
}

/** Object[]{ long[1] success, String? error, long[] ids, int[] flat } where flat is
 *  7 ints per connection: busType, srcType, srcInstance, srcBus, tgtType, tgtInstance, tgtBus. */
JNI_FN(jobjectArray, uapmdAppGetTrackGraphConnections)(JNIEnv* env, jclass, jlong app, jint t) {
    auto r = uapmd_app_get_track_graph_connections(AM(app), t);

    jlong ok = r.success ? 1 : 0;
    jlongArray okArr = env->NewLongArray(1);
    env->SetLongArrayRegion(okArr, 0, 1, &ok);

    const jsize n = r.success && r.connections ? static_cast<jsize>(r.count) : 0;
    jlongArray ids = env->NewLongArray(n);
    jintArray flat = env->NewIntArray(n * 7);
    // Two node ids per connection, source first: they are the field a graph editor
    // keys its pins by, since instance_id is -1 for every non-plugin endpoint.
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray nodeIds = env->NewObjectArray(n * 2, stringClass, nullptr);
    for (jsize i = 0; i < n; i++) {
        const auto& c = r.connections[i];
        jlong id = static_cast<jlong>(c.id);
        env->SetLongArrayRegion(ids, i, 1, &id);
        jint vals[7] = {
            static_cast<jint>(c.bus_type),
            static_cast<jint>(c.source.type), c.source.instance_id, static_cast<jint>(c.source.bus_index),
            static_cast<jint>(c.target.type), c.target.instance_id, static_cast<jint>(c.target.bus_index)
        };
        env->SetIntArrayRegion(flat, i * 7, 7, vals);
        env->SetObjectArrayElement(nodeIds, i * 2,
                                   env->NewStringUTF(c.source.node_id ? c.source.node_id : ""));
        env->SetObjectArrayElement(nodeIds, i * 2 + 1,
                                   env->NewStringUTF(c.target.node_id ? c.target.node_id : ""));
    }

    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(5, objectClass, nullptr);
    env->SetObjectArrayElement(result, 0, okArr);
    if (r.error) env->SetObjectArrayElement(result, 1, env->NewStringUTF(r.error));
    env->SetObjectArrayElement(result, 2, ids);
    env->SetObjectArrayElement(result, 3, flat);
    env->SetObjectArrayElement(result, 4, nodeIds);
    return result;
}

/**
 * Returns Object[]{
 *   long[1] success, String? error,
 *   String[3n] node strings (id / type / display name),
 *   int[9n]    node ints (instance, bypassed, latency, hasBuses, hasEventIn,
 *              hasEventOut, busOffset, audioInCount, audioOutCount),
 *   double[n]  tail lengths,
 *   int[2n]    main bus indices (input, output),
 *   String[2b] bus strings (name / channel layout name),
 *   int[3b]    bus ints (role, enabled, channel count),
 *   int[4]     graph buses layout
 * }, where n is the node count and b the total bus count.
 */
JNI_FN(jobjectArray, uapmdAppGetTrackGraphNodes)(JNIEnv* env, jclass, jlong app, jint t) {
    auto r = uapmd_app_get_track_graph_nodes(AM(app), t);

    jlong ok = r.success ? 1 : 0;
    jlongArray okArr = env->NewLongArray(1);
    env->SetLongArrayRegion(okArr, 0, 1, &ok);

    const jsize n = r.success && r.nodes ? static_cast<jsize>(r.count) : 0;
    const jsize b = r.success && r.audio_buses ? static_cast<jsize>(r.audio_bus_count) : 0;
    jclass stringClass = env->FindClass("java/lang/String");

    jobjectArray nodeStrings = env->NewObjectArray(n * 3, stringClass, nullptr);
    jintArray nodeInts = env->NewIntArray(n * 9);
    jdoubleArray tails = env->NewDoubleArray(n);
    jintArray mainBuses = env->NewIntArray(n * 2);
    for (jsize i = 0; i < n; i++) {
        const auto& node = r.nodes[i];
        env->SetObjectArrayElement(nodeStrings, i * 3,
                                   env->NewStringUTF(node.node_id ? node.node_id : ""));
        env->SetObjectArrayElement(nodeStrings, i * 3 + 1,
                                   env->NewStringUTF(node.node_type ? node.node_type : ""));
        env->SetObjectArrayElement(nodeStrings, i * 3 + 2,
                                   env->NewStringUTF(node.display_name ? node.display_name : ""));
        jint vals[9] = {
            node.instance_id,
            node.bypassed ? 1 : 0,
            static_cast<jint>(node.latency_in_samples),
            node.has_audio_buses ? 1 : 0,
            node.has_event_inputs ? 1 : 0,
            node.has_event_outputs ? 1 : 0,
            static_cast<jint>(node.audio_bus_offset),
            static_cast<jint>(node.audio_input_bus_count),
            static_cast<jint>(node.audio_output_bus_count)
        };
        env->SetIntArrayRegion(nodeInts, i * 9, 9, vals);
        jdouble tail = node.tail_length_in_seconds;
        env->SetDoubleArrayRegion(tails, i, 1, &tail);
        jint mains[2] = { node.main_input_bus_index, node.main_output_bus_index };
        env->SetIntArrayRegion(mainBuses, i * 2, 2, mains);
    }

    jobjectArray busStrings = env->NewObjectArray(b * 2, stringClass, nullptr);
    jintArray busInts = env->NewIntArray(b * 3);
    for (jsize i = 0; i < b; i++) {
        const auto& bus = r.audio_buses[i];
        env->SetObjectArrayElement(busStrings, i * 2,
                                   env->NewStringUTF(bus.name ? bus.name : ""));
        env->SetObjectArrayElement(busStrings, i * 2 + 1,
                                   env->NewStringUTF(bus.channel_layout_name ? bus.channel_layout_name : ""));
        jint vals[3] = {
            static_cast<jint>(bus.role),
            bus.enabled ? 1 : 0,
            static_cast<jint>(bus.channel_count)
        };
        env->SetIntArrayRegion(busInts, i * 3, 3, vals);
    }

    jint graphCounts[4] = {
        static_cast<jint>(r.graph_audio_input_bus_count),
        static_cast<jint>(r.graph_audio_output_bus_count),
        static_cast<jint>(r.graph_event_input_bus_count),
        static_cast<jint>(r.graph_event_output_bus_count)
    };
    jintArray graphArr = env->NewIntArray(4);
    env->SetIntArrayRegion(graphArr, 0, 4, graphCounts);

    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(9, objectClass, nullptr);
    env->SetObjectArrayElement(result, 0, okArr);
    if (r.error) env->SetObjectArrayElement(result, 1, env->NewStringUTF(r.error));
    env->SetObjectArrayElement(result, 2, nodeStrings);
    env->SetObjectArrayElement(result, 3, nodeInts);
    env->SetObjectArrayElement(result, 4, tails);
    env->SetObjectArrayElement(result, 5, mainBuses);
    env->SetObjectArrayElement(result, 6, busStrings);
    env->SetObjectArrayElement(result, 7, busInts);
    env->SetObjectArrayElement(result, 8, graphArr);
    return result;
}

/** Returns Object[]{ long[1] success, String? error }. */
static jobjectArray pack_op_result(JNIEnv* env, uapmd_op_result_t r) {
    jlong ok = r.success ? 1 : 0;
    jlongArray okArr = env->NewLongArray(1);
    env->SetLongArrayRegion(okArr, 0, 1, &ok);
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(2, objectClass, nullptr);
    env->SetObjectArrayElement(result, 0, okArr);
    if (r.error) env->SetObjectArrayElement(result, 1, env->NewStringUTF(r.error));
    return result;
}

JNI_FN(jobjectArray, uapmdAppConnectTrackGraph)(JNIEnv* env, jclass, jlong app, jint t,
                                                  jlong id, jint busType,
                                                  jint srcType, jstring srcNodeId, jint srcInstance, jint srcBus,
                                                  jint tgtType, jstring tgtNodeId, jint tgtInstance, jint tgtBus) {
    JStr src{env, srcNodeId};
    JStr tgt{env, tgtNodeId};
    uapmd_graph_connection_t c{};
    c.id = static_cast<int64_t>(id);
    c.bus_type = static_cast<uapmd_graph_bus_type_t>(busType);
    c.source.type = static_cast<uapmd_graph_endpoint_type_t>(srcType);
    c.source.node_id = src.c_str();
    c.source.instance_id = srcInstance;
    c.source.bus_index = static_cast<uint32_t>(srcBus);
    c.target.type = static_cast<uapmd_graph_endpoint_type_t>(tgtType);
    c.target.node_id = tgt.c_str();
    c.target.instance_id = tgtInstance;
    c.target.bus_index = static_cast<uint32_t>(tgtBus);
    return pack_op_result(env, uapmd_app_connect_track_graph(AM(app), t, &c));
}

JNI_FN(jobjectArray, uapmdAppDisconnectTrackGraphConnection)(JNIEnv* env, jclass, jlong app,
                                                               jint t, jlong connectionId) {
    return pack_op_result(env, uapmd_app_disconnect_track_graph_connection(AM(app), t, connectionId));
}

/* ── Clip audio events ─────────────────────────────────────────────────────── */

/** Object[]{ long[1] ok, String? error, String[] markerStrings(4/marker), double[] markerNums(1/marker),
 *            int[] markerRefTypes, double[] warpNums(2/warp), int[] warpRefTypes, String[] warpStrings(2/warp) } */
JNI_FN(jobjectArray, uapmdAppGetClipAudioEvents)(JNIEnv* env, jclass, jlong app, jint t, jint clipId) {
    auto r = uapmd_app_get_clip_audio_events(AM(app), t, clipId);

    jlong ok = r.success ? 1 : 0;
    jlongArray okArr = env->NewLongArray(1);
    env->SetLongArrayRegion(okArr, 0, 1, &ok);

    jclass stringClass = env->FindClass("java/lang/String");
    const jsize mn = r.success ? static_cast<jsize>(r.marker_count) : 0;
    const jsize wn = r.success ? static_cast<jsize>(r.audio_warp_count) : 0;

    jobjectArray mStr = env->NewObjectArray(mn * 4, stringClass, nullptr);
    jdoubleArray mNum = env->NewDoubleArray(mn);
    jintArray mRef = env->NewIntArray(mn);
    for (jsize i = 0; i < mn; i++) {
        const auto& m = r.markers[i];
        const char* strs[4] = { m.marker_id, m.reference_clip_id, m.reference_marker_id, m.name };
        for (int k = 0; k < 4; k++)
            env->SetObjectArrayElement(mStr, i * 4 + k, env->NewStringUTF(strs[k] ? strs[k] : ""));
        jdouble off = m.clip_position_offset;
        env->SetDoubleArrayRegion(mNum, i, 1, &off);
        jint rt = static_cast<jint>(m.reference_type);
        env->SetIntArrayRegion(mRef, i, 1, &rt);
    }

    jobjectArray wStr = env->NewObjectArray(wn * 2, stringClass, nullptr);
    jdoubleArray wNum = env->NewDoubleArray(wn * 2);
    jintArray wRef = env->NewIntArray(wn);
    for (jsize i = 0; i < wn; i++) {
        const auto& w = r.audio_warps[i];
        const char* strs[2] = { w.reference_clip_id, w.reference_marker_id };
        for (int k = 0; k < 2; k++)
            env->SetObjectArrayElement(wStr, i * 2 + k, env->NewStringUTF(strs[k] ? strs[k] : ""));
        jdouble nums[2] = { w.clip_position_offset, w.speed_ratio };
        env->SetDoubleArrayRegion(wNum, i * 2, 2, nums);
        jint rt = static_cast<jint>(w.reference_type);
        env->SetIntArrayRegion(wRef, i, 1, &rt);
    }

    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(8, objectClass, nullptr);
    env->SetObjectArrayElement(result, 0, okArr);
    if (r.error) env->SetObjectArrayElement(result, 1, env->NewStringUTF(r.error));
    env->SetObjectArrayElement(result, 2, mStr);
    env->SetObjectArrayElement(result, 3, mNum);
    env->SetObjectArrayElement(result, 4, mRef);
    env->SetObjectArrayElement(result, 5, wNum);
    env->SetObjectArrayElement(result, 6, wRef);
    env->SetObjectArrayElement(result, 7, wStr);
    return result;
}

JNI_FN(jobjectArray, uapmdAppSetClipAudioEvents)(JNIEnv* env, jclass, jlong app, jint t, jint clipId,
                                                   jobjectArray mStr, jdoubleArray mNum, jintArray mRef,
                                                   jdoubleArray wNum, jintArray wRef, jobjectArray wStr) {
    const jsize mn = mNum ? env->GetArrayLength(mNum) : 0;
    const jsize wn = wRef ? env->GetArrayLength(wRef) : 0;

    std::vector<std::string> keep;
    keep.reserve(static_cast<size_t>(mn) * 4 + static_cast<size_t>(wn) * 2);
    auto take = [&](jobjectArray arr, jsize idx) -> const char* {
        auto js = reinterpret_cast<jstring>(env->GetObjectArrayElement(arr, idx));
        const char* c = js ? env->GetStringUTFChars(js, nullptr) : "";
        keep.emplace_back(c ? c : "");
        if (js) env->ReleaseStringUTFChars(js, c);
        return keep.back().c_str();
    };

    std::vector<uapmd_clip_marker_t> markers(static_cast<size_t>(mn));
    if (mn) {
        jdouble* nums = env->GetDoubleArrayElements(mNum, nullptr);
        jint* refs = env->GetIntArrayElements(mRef, nullptr);
        for (jsize i = 0; i < mn; i++) {
            markers[i].marker_id = take(mStr, i * 4);
            markers[i].clip_position_offset = nums[i];
            markers[i].reference_type = static_cast<uapmd_audio_warp_reference_type_t>(refs[i]);
            markers[i].reference_clip_id = take(mStr, i * 4 + 1);
            markers[i].reference_marker_id = take(mStr, i * 4 + 2);
            markers[i].name = take(mStr, i * 4 + 3);
        }
        env->ReleaseDoubleArrayElements(mNum, nums, JNI_ABORT);
        env->ReleaseIntArrayElements(mRef, refs, JNI_ABORT);
    }

    std::vector<uapmd_audio_warp_point_t> warps(static_cast<size_t>(wn));
    if (wn) {
        jdouble* nums = env->GetDoubleArrayElements(wNum, nullptr);
        jint* refs = env->GetIntArrayElements(wRef, nullptr);
        for (jsize i = 0; i < wn; i++) {
            warps[i].clip_position_offset = nums[i * 2];
            warps[i].speed_ratio = nums[i * 2 + 1];
            warps[i].reference_type = static_cast<uapmd_audio_warp_reference_type_t>(refs[i]);
            warps[i].reference_clip_id = take(wStr, i * 2);
            warps[i].reference_marker_id = take(wStr, i * 2 + 1);
        }
        env->ReleaseDoubleArrayElements(wNum, nums, JNI_ABORT);
        env->ReleaseIntArrayElements(wRef, refs, JNI_ABORT);
    }

    return pack_op_result(env, uapmd_app_set_clip_audio_events(
        AM(app), t, clipId,
        mn ? markers.data() : nullptr, static_cast<uint32_t>(mn),
        wn ? warps.data() : nullptr, static_cast<uint32_t>(wn)));
}

/** Object[]{ long[3] clipId/sourceNodeId/success, String? error } */
JNI_FN(jobjectArray, uapmdAppCreateEmptyMidiClip)(JNIEnv* env, jclass, jlong app, jint t,
                                                    jlong positionSamples, jint tickResolution, jdouble bpm) {
    auto r = uapmd_app_create_empty_midi_clip(AM(app), t, positionSamples,
                                                static_cast<uint32_t>(tickResolution), bpm);
    jlong nums[3] = { r.clip_id, r.source_node_id, r.success ? 1 : 0 };
    jlongArray arr = env->NewLongArray(3);
    env->SetLongArrayRegion(arr, 0, 3, nums);
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(2, objectClass, nullptr);
    env->SetObjectArrayElement(result, 0, arr);
    if (r.error) env->SetObjectArrayElement(result, 1, env->NewStringUTF(r.error));
    return result;
}


/* ── Clip adding, master markers, render-to-file ──────────────────────────────
 *
 * Positions cross as (samples, legacyBeats), as everywhere else in this file.
 * A clip-add result comes back as Object[]{ long[3] clipId/sourceNodeId/success,
 * String? error }, matching uapmdAppCreateEmptyMidiClip above.
 */

namespace {
jobjectArray pack_clip_add(JNIEnv* env, const uapmd_clip_add_result_t& r) {
    jlong nums[3] = { r.clip_id, r.source_node_id, r.success ? 1 : 0 };
    jlongArray arr = env->NewLongArray(3);
    env->SetLongArrayRegion(arr, 0, 3, nums);
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(2, objectClass, nullptr);
    env->SetObjectArrayElement(result, 0, arr);
    if (r.error) env->SetObjectArrayElement(result, 1, env->NewStringUTF(r.error));
    return result;
}

uapmd_timeline_position_t make_pos(jlong samples, jdouble beats) {
    uapmd_timeline_position_t p{};
    p.samples = samples;
    p.legacy_beats = beats;
    return p;
}
} // namespace

JNI_FN(jobjectArray, uapmdAppAddClipToTrack)(JNIEnv* env, jclass, jlong app, jint t,
                                              jlong posSamples, jdouble posBeats,
                                              jlong reader, jstring filepath) {
    JStr f(env, filepath);
    return pack_clip_add(env, uapmd_app_add_clip_to_track(
        AM(app), t, make_pos(posSamples, posBeats),
        j2p<uapmd_audio_file_reader_t>(reader), f.c_str()));
}

JNI_FN(jobjectArray, uapmdAppAddMidiClipToTrack)(JNIEnv* env, jclass, jlong app, jint t,
                                                  jlong posSamples, jdouble posBeats, jstring filepath) {
    JStr f(env, filepath);
    return pack_clip_add(env, uapmd_app_add_midi_clip_to_track(
        AM(app), t, make_pos(posSamples, posBeats), f.c_str()));
}

/*
 * Tempo changes cross as double[2n] {tickPosition, bpm} and meter changes as
 * double[tickPosition] + int[4n] {num, den, clocksPerClick, 32ndsPerQuarter} -
 * a tick position is a uint64 that no jdouble loses at any musical length.
 */
JNI_FN(jobjectArray, uapmdAppAddMidiClipFromData)(JNIEnv* env, jclass, jlong app, jint t,
                                                   jlong posSamples, jdouble posBeats,
                                                   jintArray umpEvents, jlongArray tickTimestamps,
                                                   jint tickResolution, jdouble clipTempo,
                                                   jdoubleArray tempoNums,
                                                   jlongArray sigTicks, jintArray sigNums,
                                                   jstring clipName, jboolean needsFileSave) {
    const jsize un = umpEvents ? env->GetArrayLength(umpEvents) : 0;
    const jsize tn = tickTimestamps ? env->GetArrayLength(tickTimestamps) : 0;
    const jsize tcn = tempoNums ? env->GetArrayLength(tempoNums) / 2 : 0;
    const jsize scn = sigTicks ? env->GetArrayLength(sigTicks) : 0;

    std::vector<uapmd_ump_t> ump(static_cast<size_t>(un));
    if (un) {
        jint* p = env->GetIntArrayElements(umpEvents, nullptr);
        for (jsize i = 0; i < un; i++) ump[i] = static_cast<uapmd_ump_t>(p[i]);
        env->ReleaseIntArrayElements(umpEvents, p, JNI_ABORT);
    }

    std::vector<uint64_t> ticks(static_cast<size_t>(tn));
    if (tn) {
        jlong* p = env->GetLongArrayElements(tickTimestamps, nullptr);
        for (jsize i = 0; i < tn; i++) ticks[i] = static_cast<uint64_t>(p[i]);
        env->ReleaseLongArrayElements(tickTimestamps, p, JNI_ABORT);
    }

    std::vector<uapmd_midi_tempo_change_t> tempos(static_cast<size_t>(tcn));
    if (tcn) {
        jdouble* p = env->GetDoubleArrayElements(tempoNums, nullptr);
        for (jsize i = 0; i < tcn; i++) {
            tempos[i].tick_position = static_cast<uint64_t>(p[i * 2]);
            tempos[i].bpm = p[i * 2 + 1];
        }
        env->ReleaseDoubleArrayElements(tempoNums, p, JNI_ABORT);
    }

    std::vector<uapmd_midi_time_sig_change_t> sigs(static_cast<size_t>(scn));
    if (scn) {
        jlong* tp = env->GetLongArrayElements(sigTicks, nullptr);
        jint* np = env->GetIntArrayElements(sigNums, nullptr);
        for (jsize i = 0; i < scn; i++) {
            sigs[i].tick_position = static_cast<uint64_t>(tp[i]);
            sigs[i].numerator = static_cast<uint8_t>(np[i * 4]);
            sigs[i].denominator = static_cast<uint8_t>(np[i * 4 + 1]);
            sigs[i].clocks_per_click = static_cast<uint8_t>(np[i * 4 + 2]);
            sigs[i].thirty_seconds_per_quarter = static_cast<uint8_t>(np[i * 4 + 3]);
        }
        env->ReleaseLongArrayElements(sigTicks, tp, JNI_ABORT);
        env->ReleaseIntArrayElements(sigNums, np, JNI_ABORT);
    }

    JStr name(env, clipName);
    return pack_clip_add(env, uapmd_app_add_midi_clip_from_data(
        AM(app), t, make_pos(posSamples, posBeats),
        un ? ump.data() : nullptr, static_cast<uint32_t>(un),
        tn ? ticks.data() : nullptr, static_cast<uint32_t>(tn),
        static_cast<uint32_t>(tickResolution), clipTempo,
        tcn ? tempos.data() : nullptr, static_cast<uint32_t>(tcn),
        scn ? sigs.data() : nullptr, static_cast<uint32_t>(scn),
        name.c_str(), needsFileSave));
}

JNI_FN(jint, uapmdAppAddDeviceInputToTrack)(JNIEnv* env, jclass, jlong app, jint t, jintArray channels) {
    const jsize n = channels ? env->GetArrayLength(channels) : 0;
    std::vector<uint32_t> ch(static_cast<size_t>(n));
    if (n) {
        jint* p = env->GetIntArrayElements(channels, nullptr);
        for (jsize i = 0; i < n; i++) ch[i] = static_cast<uint32_t>(p[i]);
        env->ReleaseIntArrayElements(channels, p, JNI_ABORT);
    }
    return uapmd_app_add_device_input_to_track(AM(app), t, n ? ch.data() : nullptr, static_cast<uint32_t>(n));
}

JNI_FN(jint, uapmdAppMasterMarkerCount)(JNIEnv*, jclass, jlong app) {
    return static_cast<jint>(uapmd_app_master_marker_count(AM(app)));
}

/** Markers come back as String[4n] {id, refClipId, refMarkerId, name} + double[n] + int[n]. */
JNI_FN(jobjectArray, uapmdAppGetMasterMarkers)(JNIEnv* env, jclass, jlong app,
                                                jdoubleArray outOffsets, jintArray outRefTypes) {
    const uint32_t n = uapmd_app_master_marker_count(AM(app));
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray strings = env->NewObjectArray(static_cast<jsize>(n) * 4, stringClass, nullptr);
    std::vector<jdouble> offsets(n);
    std::vector<jint> refTypes(n);
    for (uint32_t i = 0; i < n; i++) {
        uapmd_clip_marker_t m{};
        if (!uapmd_app_get_master_marker(AM(app), i, &m)) continue;
        auto put = [&](int slot, const char* v) {
            env->SetObjectArrayElement(strings, static_cast<jsize>(i) * 4 + slot,
                                       env->NewStringUTF(v ? v : ""));
        };
        put(0, m.marker_id);
        put(1, m.reference_clip_id);
        put(2, m.reference_marker_id);
        put(3, m.name);
        offsets[i] = m.clip_position_offset;
        refTypes[i] = static_cast<jint>(m.reference_type);
    }
    if (n && outOffsets && env->GetArrayLength(outOffsets) >= static_cast<jsize>(n))
        env->SetDoubleArrayRegion(outOffsets, 0, static_cast<jsize>(n), offsets.data());
    if (n && outRefTypes && env->GetArrayLength(outRefTypes) >= static_cast<jsize>(n))
        env->SetIntArrayRegion(outRefTypes, 0, static_cast<jsize>(n), refTypes.data());
    return strings;
}

JNI_FN(jobjectArray, uapmdAppSetMasterTrackMarkersWithValidation)(JNIEnv* env, jclass, jlong app,
                                                                   jobjectArray mStr, jdoubleArray mNum,
                                                                   jintArray mRef) {
    const jsize n = mNum ? env->GetArrayLength(mNum) : 0;
    std::vector<std::string> keep;
    keep.reserve(static_cast<size_t>(n) * 4);
    auto take = [&](jsize idx) -> const char* {
        auto js = reinterpret_cast<jstring>(env->GetObjectArrayElement(mStr, idx));
        const char* c = js ? env->GetStringUTFChars(js, nullptr) : "";
        keep.emplace_back(c ? c : "");
        if (js) env->ReleaseStringUTFChars(js, c);
        return keep.back().c_str();
    };
    std::vector<uapmd_clip_marker_t> markers(static_cast<size_t>(n));
    if (n) {
        jdouble* nums = env->GetDoubleArrayElements(mNum, nullptr);
        jint* refs = env->GetIntArrayElements(mRef, nullptr);
        for (jsize i = 0; i < n; i++) {
            markers[i].marker_id = take(i * 4);
            markers[i].clip_position_offset = nums[i];
            markers[i].reference_type = static_cast<uapmd_audio_warp_reference_type_t>(refs[i]);
            markers[i].reference_clip_id = take(i * 4 + 1);
            markers[i].reference_marker_id = take(i * 4 + 2);
            markers[i].name = take(i * 4 + 3);
        }
        env->ReleaseDoubleArrayElements(mNum, nums, JNI_ABORT);
        env->ReleaseIntArrayElements(mRef, refs, JNI_ABORT);
    }
    return pack_op_result(env, uapmd_app_set_master_track_markers_with_validation(
        AM(app), n ? markers.data() : nullptr, static_cast<uint32_t>(n)));
}

/** Settings cross as double[8] plus boolean[3], to keep the argument list short. */
JNI_FN(jboolean, uapmdAppStartRenderToFile)(JNIEnv* env, jclass, jlong app, jstring outputPath,
                                             jdoubleArray nums, jbooleanArray flags) {
    JStr path(env, outputPath);
    jdouble n[8] = {};
    if (nums && env->GetArrayLength(nums) >= 8) env->GetDoubleArrayRegion(nums, 0, 8, n);
    jboolean f[4] = {};
    if (flags && env->GetArrayLength(flags) >= 4) env->GetBooleanArrayRegion(flags, 0, 4, f);

    uapmd_app_render_settings_t s{};
    s.output_path = path.c_str();
    s.start_seconds = n[0];
    s.end_seconds = n[1];
    s.content_start_seconds = n[2];
    s.content_end_seconds = n[3];
    s.tail_seconds = n[4];
    s.silence_duration_seconds = n[5];
    s.silence_threshold_db = n[6];
    s.has_end_seconds = f[0];
    s.use_content_fallback = f[1];
    s.content_bounds_valid = f[2];
    s.enable_silence_stop = f[3];
    return uapmd_app_start_render_to_file(AM(app), &s);
}

JNI_FN(void, uapmdAppCancelRenderToFile)(JNIEnv*, jclass, jlong app) {
    uapmd_app_cancel_render_to_file(AM(app));
}

/** Object[]{ double[2] progress/renderedSeconds, boolean[3], String message, String outputPath } */
JNI_FN(jobjectArray, uapmdAppGetRenderToFileStatus)(JNIEnv* env, jclass, jlong app) {
    auto st = uapmd_app_get_render_to_file_status(AM(app));
    jdouble nums[2] = { st.progress, st.rendered_seconds };
    jdoubleArray numArr = env->NewDoubleArray(2);
    env->SetDoubleArrayRegion(numArr, 0, 2, nums);
    jboolean flags[3] = {
        static_cast<jboolean>(st.running), static_cast<jboolean>(st.completed),
        static_cast<jboolean>(st.success)
    };
    jbooleanArray flagArr = env->NewBooleanArray(3);
    env->SetBooleanArrayRegion(flagArr, 0, 3, flags);

    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(4, objectClass, nullptr);
    env->SetObjectArrayElement(result, 0, numArr);
    env->SetObjectArrayElement(result, 1, flagArr);
    env->SetObjectArrayElement(result, 2, env->NewStringUTF(st.message ? st.message : ""));
    env->SetObjectArrayElement(result, 3, env->NewStringUTF(st.output_path ? st.output_path : ""));
    return result;
}

JNI_FN(void, uapmdAppClearCompletedRenderStatus)(JNIEnv*, jclass, jlong app) {
    uapmd_app_clear_completed_render_status(AM(app));
}

JNI_FN(void, uapmdAppRequestShowTrackGraph)(JNIEnv*, jclass, jlong app, jint trackIndex) {
    uapmd_app_request_show_track_graph(AM(app), trackIndex);
}

JNI_FN(jboolean, uapmdAppIsTrackMuted)(JNIEnv*, jclass, jlong app, jint t) {
    return uapmd_app_is_track_muted(AM(app), t);
}

JNI_FN(jboolean, uapmdAppIsTrackSolo)(JNIEnv*, jclass, jlong app, jint t) {
    return uapmd_app_is_track_solo(AM(app), t);
}

JNI_FN(jboolean, uapmdAppSetTrackMuted)(JNIEnv*, jclass, jlong app, jint t, jboolean muted) {
    return uapmd_app_set_track_muted(AM(app), t, muted);
}

JNI_FN(jboolean, uapmdAppSetTrackSolo)(JNIEnv*, jclass, jlong app, jint t, jboolean solo) {
    return uapmd_app_set_track_solo(AM(app), t, solo);
}

#undef JNI_FN

} // extern "C"
