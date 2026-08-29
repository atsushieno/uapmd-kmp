/**
 * uapmd JNI bridge — project history and addins (uapmd 0.5.6)
 *
 * Split out from uapmd_jni.cpp because the history surface is large and
 * self-contained. Same conventions apply: opaque C pointers travel as jlong,
 * callbacks arrive as jobjects whose `invoke` method is resolved on the spot,
 * and struct-valued results are packed into primitive arrays plus a
 * caller-allocated String[] rather than mirrored as Java classes.
 */

#include <jni.h>
#include <string>
#include <vector>

#include "c-api/uapmd-c-common.h"
#include "c-api/uapmd-c-data.h"
#include "c-api/uapmd-c-engine.h"
#include "c-api/uapmd-c-undo.h"
#include "c-api/uapmd-c-addin.h"

// Shared with uapmd_jni.cpp.
extern JNIEnv* uapmd_jni_env();

namespace {

template<typename T>
inline jlong p2j(T ptr) { return static_cast<jlong>(reinterpret_cast<uintptr_t>(ptr)); }

template<typename T>
inline T j2p(jlong handle) { return reinterpret_cast<T>(static_cast<uintptr_t>(handle)); }

const char* jstr(JNIEnv* env, jstring s, const char* fallback = "") {
    return s ? env->GetStringUTFChars(s, nullptr) : fallback;
}

void jstr_release(JNIEnv* env, jstring s, const char* c) {
    if (s) env->ReleaseStringUTFChars(s, c);
}

template<typename Fn>
jstring cstr(JNIEnv* env, Fn fn) {
    size_t n = fn(nullptr, 0);
    if (!n) return env->NewStringUTF("");
    std::string buf(n, '\0');
    fn(buf.data(), n);
    if (!buf.empty() && buf.back() == '\0') buf.pop_back();
    return env->NewStringUTF(buf.c_str());
}

template<typename Fn>
jbyteArray cbytes(JNIEnv* env, Fn fn) {
    size_t n = fn(nullptr, 0);
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(n));
    if (n == 0) return arr;
    std::vector<uint8_t> buf(n);
    fn(buf.data(), n);
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte*>(buf.data()));
    return arr;
}

/**
 * Marker/warp lists cross the boundary as parallel arrays, because their only
 * non-numeric content is a fixed number of strings per element:
 *   markers: strings[i*4] = {markerId, referenceClipId, referenceMarkerId, name}
 *            numbers[i]   = clipPositionOffset,  refTypes[i] = referenceType
 *   warps:   strings[i*2] = {referenceClipId, referenceMarkerId}
 *            numbers[i*2] = {clipPositionOffset, speedRatio}, refTypes[i] = referenceType
 *
 * The C structs hold borrowed `const char*`, so the decoded std::strings must
 * outlive the call; MarkerBuffer owns both halves.
 */
struct MarkerBuffer {
    std::vector<std::string> storage;
    std::vector<uapmd_clip_marker_t> markers;

    const uapmd_clip_marker_t* data() const { return markers.empty() ? nullptr : markers.data(); }
    uint32_t size() const { return static_cast<uint32_t>(markers.size()); }
};

MarkerBuffer decode_markers(JNIEnv* env, jobjectArray strings, jdoubleArray numbers, jintArray refTypes) {
    MarkerBuffer out;
    if (!numbers) return out;
    jsize count = env->GetArrayLength(numbers);
    if (count == 0) return out;

    out.storage.reserve(static_cast<size_t>(count) * 4);
    std::vector<jdouble> offsets(count);
    env->GetDoubleArrayRegion(numbers, 0, count, offsets.data());
    std::vector<jint> types(count);
    if (refTypes) env->GetIntArrayRegion(refTypes, 0, count, types.data());

    for (jsize i = 0; i < count; ++i) {
        for (int f = 0; f < 4; ++f) {
            auto s = static_cast<jstring>(env->GetObjectArrayElement(strings, i * 4 + f));
            const char* c = jstr(env, s);
            out.storage.emplace_back(c ? c : "");
            jstr_release(env, s, c);
            if (s) env->DeleteLocalRef(s);
        }
    }
    // Only fill the C structs once storage has stopped reallocating.
    out.markers.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        uapmd_clip_marker_t m{};
        m.marker_id = out.storage[i * 4 + 0].c_str();
        m.clip_position_offset = offsets[i];
        m.reference_type = static_cast<uapmd_audio_warp_reference_type_t>(types[i]);
        m.reference_clip_id = out.storage[i * 4 + 1].c_str();
        m.reference_marker_id = out.storage[i * 4 + 2].c_str();
        m.name = out.storage[i * 4 + 3].c_str();
        out.markers.push_back(m);
    }
    return out;
}

struct WarpBuffer {
    std::vector<std::string> storage;
    std::vector<uapmd_audio_warp_point_t> warps;

    const uapmd_audio_warp_point_t* data() const { return warps.empty() ? nullptr : warps.data(); }
    uint32_t size() const { return static_cast<uint32_t>(warps.size()); }
};

WarpBuffer decode_warps(JNIEnv* env, jobjectArray strings, jdoubleArray numbers, jintArray refTypes) {
    WarpBuffer out;
    if (!refTypes) return out;
    jsize count = env->GetArrayLength(refTypes);
    if (count == 0) return out;

    std::vector<jdouble> values(count * 2);
    env->GetDoubleArrayRegion(numbers, 0, count * 2, values.data());
    std::vector<jint> types(count);
    env->GetIntArrayRegion(refTypes, 0, count, types.data());

    out.storage.reserve(static_cast<size_t>(count) * 2);
    for (jsize i = 0; i < count; ++i) {
        for (int f = 0; f < 2; ++f) {
            auto s = static_cast<jstring>(env->GetObjectArrayElement(strings, i * 2 + f));
            const char* c = jstr(env, s);
            out.storage.emplace_back(c ? c : "");
            jstr_release(env, s, c);
            if (s) env->DeleteLocalRef(s);
        }
    }
    out.warps.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        uapmd_audio_warp_point_t w{};
        w.clip_position_offset = values[i * 2 + 0];
        w.speed_ratio = values[i * 2 + 1];
        w.reference_type = static_cast<uapmd_audio_warp_reference_type_t>(types[i]);
        w.reference_clip_id = out.storage[i * 2 + 0].c_str();
        w.reference_marker_id = out.storage[i * 2 + 1].c_str();
        out.warps.push_back(w);
    }
    return out;
}

/** Packs uapmd_undo_state_t as Object[]{ long[10], String, String, String }. */
jobjectArray pack_undo_state(JNIEnv* env, const uapmd_undo_state_t& s) {
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

/** Packs uapmd_undo_result_t as Object[]{ Integer-as-long[1], String? }. */
jobjectArray pack_undo_result(JNIEnv* env, uapmd_undo_result_t r) {
    jlong status = static_cast<jlong>(r.status);
    jlongArray statusArr = env->NewLongArray(1);
    env->SetLongArrayRegion(statusArr, 0, 1, &status);

    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(2, objectClass, nullptr);
    env->SetObjectArrayElement(result, 0, statusArr);
    if (r.error) env->SetObjectArrayElement(result, 1, env->NewStringUTF(r.error));
    return result;
}

/**
 * Async callback context. Each C completion fires exactly once, so the context
 * deletes itself from inside the trampoline.
 */
struct HistoryCtx {
    jobject obj;
    jmethodID mid;

    HistoryCtx(JNIEnv* env, jobject o, const char* signature)
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

    ~HistoryCtx() {
        if (obj) {
            JNIEnv* e = uapmd_jni_env();
            if (e) e->DeleteGlobalRef(obj);
        }
    }
};

/** cb signature: (statusOrdinal: Int, error: String?) -> Unit */
void undo_completion_trampoline(uapmd_undo_result_t result, void* ud) {
    auto* ctx = static_cast<HistoryCtx*>(ud);
    if (!ctx) return;  // the caller wanted no completion; the C entry point still needs a function
    JNIEnv* e = uapmd_jni_env();
    if (e && ctx->mid) {
        jstring err = result.error ? e->NewStringUTF(result.error) : nullptr;
        e->CallVoidMethod(ctx->obj, ctx->mid, static_cast<jint>(result.status), err);
    }
    delete ctx;
}

/** cb signature: (trackIndex: Int, error: String?) -> Unit */
void track_mutation_trampoline(int32_t trackIndex, const char* error, void* ud) {
    auto* ctx = static_cast<HistoryCtx*>(ud);
    if (!ctx) return;  // the caller wanted no completion; the C entry point still needs a function
    JNIEnv* e = uapmd_jni_env();
    if (e && ctx->mid) {
        jstring err = error ? e->NewStringUTF(error) : nullptr;
        e->CallVoidMethod(ctx->obj, ctx->mid, static_cast<jint>(trackIndex), err);
    }
    delete ctx;
}

/** cb signature: (fragmentHandle: Long, error: String?) -> Unit */
void track_fragment_trampoline(uapmd_track_fragment_t fragment, const char* error, void* ud) {
    auto* ctx = static_cast<HistoryCtx*>(ud);
    if (!ctx) return;  // the caller wanted no completion; the C entry point still needs a function
    JNIEnv* e = uapmd_jni_env();
    if (e && ctx->mid) {
        jstring err = error ? e->NewStringUTF(error) : nullptr;
        e->CallVoidMethod(ctx->obj, ctx->mid, p2j(fragment), err);
    }
    delete ctx;
}

HistoryCtx* undo_ctx(JNIEnv* env, jobject cb) {
    return cb ? new HistoryCtx(env, cb, "(ILjava/lang/String;)V") : nullptr;
}

} // namespace

extern "C" {

#define JNI_FN(ret, name) JNIEXPORT ret JNICALL Java_dev_atsushieno_uapmd_JniBridge_##name

/* ═══════════════════════════════════════════════════════════════════════════
 *  ProjectUndoEngine
 * ═══════════════════════════════════════════════════════════════════════════ */

JNI_FN(jobjectArray, uapmdUndoEngineGetState)(JNIEnv* env, jclass, jlong h) {
    uapmd_undo_state_t s{};
    if (!uapmd_undo_engine_get_state(j2p<uapmd_undo_engine_t>(h), &s)) return nullptr;
    return pack_undo_state(env, s);
}

JNI_FN(void, uapmdUndoEngineUndo)(JNIEnv* env, jclass, jlong h, jobject cb) {
    uapmd_undo_engine_undo(j2p<uapmd_undo_engine_t>(h), undo_ctx(env, cb), undo_completion_trampoline);
}

JNI_FN(void, uapmdUndoEngineRedo)(JNIEnv* env, jclass, jlong h, jobject cb) {
    uapmd_undo_engine_redo(j2p<uapmd_undo_engine_t>(h), undo_ctx(env, cb), undo_completion_trampoline);
}

JNI_FN(jobjectArray, uapmdUndoEngineBeginCompound)(JNIEnv* env, jclass, jlong h, jstring desc, jint origin) {
    const char* d = jstr(env, desc);
    auto r = uapmd_undo_engine_begin_compound(j2p<uapmd_undo_engine_t>(h), d, static_cast<uapmd_mutation_origin_t>(origin));
    jstr_release(env, desc, d);
    return pack_undo_result(env, r);
}

JNI_FN(void, uapmdUndoEngineEndCompound)(JNIEnv* env, jclass, jlong h, jobject cb) {
    uapmd_undo_engine_end_compound(j2p<uapmd_undo_engine_t>(h), undo_ctx(env, cb), undo_completion_trampoline);
}

JNI_FN(void, uapmdUndoEngineCancelCompound)(JNIEnv* env, jclass, jlong h, jobject cb) {
    uapmd_undo_engine_cancel_compound(j2p<uapmd_undo_engine_t>(h), undo_ctx(env, cb), undo_completion_trampoline);
}

JNI_FN(jobjectArray, uapmdUndoEngineBeginGesture)(JNIEnv* env, jclass, jlong h, jstring desc, jint origin) {
    const char* d = jstr(env, desc);
    auto r = uapmd_undo_engine_begin_gesture(j2p<uapmd_undo_engine_t>(h), d, static_cast<uapmd_mutation_origin_t>(origin));
    jstr_release(env, desc, d);
    return pack_undo_result(env, r);
}

JNI_FN(void, uapmdUndoEngineEndGesture)(JNIEnv* env, jclass, jlong h, jobject cb) {
    uapmd_undo_engine_end_gesture(j2p<uapmd_undo_engine_t>(h), undo_ctx(env, cb), undo_completion_trampoline);
}

JNI_FN(void, uapmdUndoEngineCancelGesture)(JNIEnv* env, jclass, jlong h, jobject cb) {
    uapmd_undo_engine_cancel_gesture(j2p<uapmd_undo_engine_t>(h), undo_ctx(env, cb), undo_completion_trampoline);
}

JNI_FN(jboolean, uapmdUndoEngineClear)(JNIEnv*, jclass, jlong h, jboolean markSaved) {
    return uapmd_undo_engine_clear(j2p<uapmd_undo_engine_t>(h), markSaved);
}

JNI_FN(jboolean, uapmdUndoEngineMarkSaved)(JNIEnv*, jclass, jlong h) {
    return uapmd_undo_engine_mark_saved(j2p<uapmd_undo_engine_t>(h));
}

JNI_FN(jboolean, uapmdUndoEngineMarkStateSaved)(JNIEnv*, jclass, jlong h, jlong stateId) {
    return uapmd_undo_engine_mark_state_saved(j2p<uapmd_undo_engine_t>(h), static_cast<uint64_t>(stateId));
}

JNI_FN(jboolean, uapmdUndoEngineSetMaximumHistorySize)(JNIEnv*, jclass, jlong h, jlong bytes) {
    return uapmd_undo_engine_set_maximum_history_size(j2p<uapmd_undo_engine_t>(h), static_cast<uint64_t>(bytes));
}

JNI_FN(void, uapmdUndoEngineShutdown)(JNIEnv*, jclass, jlong h) {
    uapmd_undo_engine_shutdown(j2p<uapmd_undo_engine_t>(h));
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  ProjectCommandManager
 * ═══════════════════════════════════════════════════════════════════════════ */

JNI_FN(jobjectArray, uapmdCommandManagerGetState)(JNIEnv* env, jclass, jlong h) {
    uapmd_undo_state_t s{};
    if (!uapmd_command_manager_get_state(j2p<uapmd_command_manager_t>(h), &s)) return nullptr;
    return pack_undo_state(env, s);
}

JNI_FN(jlong, uapmdCommandManagerHistory)(JNIEnv*, jclass, jlong h) {
    return p2j(uapmd_command_manager_history(j2p<uapmd_command_manager_t>(h)));
}

JNI_FN(void, uapmdCommandManagerUndo)(JNIEnv* env, jclass, jlong h, jobject cb) {
    uapmd_command_manager_undo(j2p<uapmd_command_manager_t>(h), undo_ctx(env, cb), undo_completion_trampoline);
}

JNI_FN(void, uapmdCommandManagerRedo)(JNIEnv* env, jclass, jlong h, jobject cb) {
    uapmd_command_manager_redo(j2p<uapmd_command_manager_t>(h), undo_ctx(env, cb), undo_completion_trampoline);
}

JNI_FN(jobjectArray, uapmdCommandManagerBeginStep)(JNIEnv* env, jclass, jlong h, jstring desc, jint origin) {
    const char* d = jstr(env, desc);
    auto r = uapmd_command_manager_begin_step(j2p<uapmd_command_manager_t>(h), d, static_cast<uapmd_mutation_origin_t>(origin));
    jstr_release(env, desc, d);
    return pack_undo_result(env, r);
}

JNI_FN(void, uapmdCommandManagerEndStep)(JNIEnv* env, jclass, jlong h, jobject cb) {
    uapmd_command_manager_end_step(j2p<uapmd_command_manager_t>(h), undo_ctx(env, cb), undo_completion_trampoline);
}

JNI_FN(void, uapmdCommandManagerCancelStep)(JNIEnv* env, jclass, jlong h, jobject cb) {
    uapmd_command_manager_cancel_step(j2p<uapmd_command_manager_t>(h), undo_ctx(env, cb), undo_completion_trampoline);
}

JNI_FN(jobjectArray, uapmdCommandManagerBeginGesture)(JNIEnv* env, jclass, jlong h, jstring desc, jint origin) {
    const char* d = jstr(env, desc);
    auto r = uapmd_command_manager_begin_gesture(j2p<uapmd_command_manager_t>(h), d, static_cast<uapmd_mutation_origin_t>(origin));
    jstr_release(env, desc, d);
    return pack_undo_result(env, r);
}

JNI_FN(void, uapmdCommandManagerEndGesture)(JNIEnv* env, jclass, jlong h, jobject cb) {
    uapmd_command_manager_end_gesture(j2p<uapmd_command_manager_t>(h), undo_ctx(env, cb), undo_completion_trampoline);
}

JNI_FN(void, uapmdCommandManagerCancelGesture)(JNIEnv* env, jclass, jlong h, jobject cb) {
    uapmd_command_manager_cancel_gesture(j2p<uapmd_command_manager_t>(h), undo_ctx(env, cb), undo_completion_trampoline);
}

JNI_FN(void, uapmdCommandManagerShutdown)(JNIEnv*, jclass, jlong h) {
    uapmd_command_manager_shutdown(j2p<uapmd_command_manager_t>(h));
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  ProjectCommands
 * ═══════════════════════════════════════════════════════════════════════════ */

JNI_FN(jlong, uapmdCommandsHistory)(JNIEnv*, jclass, jlong h) {
    return p2j(uapmd_commands_history(j2p<uapmd_project_commands_t>(h)));
}

JNI_FN(jboolean, uapmdCommandsSetClipEnabled)(JNIEnv*, jclass, jlong h, jint t, jint c, jboolean v, jint o) {
    return uapmd_commands_set_clip_enabled(j2p<uapmd_project_commands_t>(h), t, c, v, static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdCommandsSetClipAnchor)(JNIEnv* env, jclass, jlong h, jint t, jint c,
                                              jint type, jstring refId, jdouble offset, jint o) {
    const char* r = jstr(env, refId);
    uapmd_time_reference_t anchor{static_cast<uapmd_time_reference_type_t>(type), r, offset};
    bool ok = uapmd_commands_set_clip_anchor(j2p<uapmd_project_commands_t>(h), t, c, anchor, static_cast<uapmd_mutation_origin_t>(o));
    jstr_release(env, refId, r);
    return ok;
}

JNI_FN(jboolean, uapmdCommandsSetClipGain)(JNIEnv*, jclass, jlong h, jint t, jint c, jdouble v, jint o) {
    return uapmd_commands_set_clip_gain(j2p<uapmd_project_commands_t>(h), t, c, v, static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdCommandsSetClipMuted)(JNIEnv*, jclass, jlong h, jint t, jint c, jboolean v, jint o) {
    return uapmd_commands_set_clip_muted(j2p<uapmd_project_commands_t>(h), t, c, v, static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdCommandsResizeClip)(JNIEnv*, jclass, jlong h, jint t, jint c, jlong v, jint o) {
    return uapmd_commands_resize_clip(j2p<uapmd_project_commands_t>(h), t, c, v, static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdCommandsSetClipName)(JNIEnv* env, jclass, jlong h, jint t, jint c, jstring v, jint o) {
    const char* s = jstr(env, v);
    bool ok = uapmd_commands_set_clip_name(j2p<uapmd_project_commands_t>(h), t, c, s, static_cast<uapmd_mutation_origin_t>(o));
    jstr_release(env, v, s);
    return ok;
}

JNI_FN(jboolean, uapmdCommandsSetClipFilepath)(JNIEnv* env, jclass, jlong h, jint t, jint c, jstring v, jint o) {
    const char* s = jstr(env, v);
    bool ok = uapmd_commands_set_clip_filepath(j2p<uapmd_project_commands_t>(h), t, c, s, static_cast<uapmd_mutation_origin_t>(o));
    jstr_release(env, v, s);
    return ok;
}

JNI_FN(jboolean, uapmdCommandsSetClipNeedsFileSave)(JNIEnv*, jclass, jlong h, jint t, jint c, jboolean v, jint o) {
    return uapmd_commands_set_clip_needs_file_save(j2p<uapmd_project_commands_t>(h), t, c, v, static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdCommandsSetClipMarkers)(JNIEnv* env, jclass, jlong h, jint t, jint c,
                                               jobjectArray strings, jdoubleArray numbers, jintArray refTypes, jint o) {
    auto buf = decode_markers(env, strings, numbers, refTypes);
    return uapmd_commands_set_clip_markers(j2p<uapmd_project_commands_t>(h), t, c,
                                           buf.data(), buf.size(), static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdCommandsSetClipAudioWarps)(JNIEnv* env, jclass, jlong h, jint t, jint c,
                                                  jobjectArray strings, jdoubleArray numbers, jintArray refTypes, jint o) {
    auto buf = decode_warps(env, strings, numbers, refTypes);
    return uapmd_commands_set_clip_audio_warps(j2p<uapmd_project_commands_t>(h), t, c,
                                               buf.data(), buf.size(), static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdCommandsSetTrackGain)(JNIEnv*, jclass, jlong h, jint t, jdouble v, jint o) {
    return uapmd_commands_set_track_gain(j2p<uapmd_project_commands_t>(h), t, v, static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdCommandsSetTrackMuted)(JNIEnv*, jclass, jlong h, jint t, jboolean v, jint o) {
    return uapmd_commands_set_track_muted(j2p<uapmd_project_commands_t>(h), t, v, static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdCommandsSetTrackSolo)(JNIEnv*, jclass, jlong h, jint t, jboolean v, jint o) {
    return uapmd_commands_set_track_solo(j2p<uapmd_project_commands_t>(h), t, v, static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdCommandsSetTrackBypassed)(JNIEnv*, jclass, jlong h, jint t, jboolean v, jint o) {
    return uapmd_commands_set_track_bypassed(j2p<uapmd_project_commands_t>(h), t, v, static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdCommandsSetTrackFreezePolicyEnabled)(JNIEnv*, jclass, jlong h, jint t, jboolean v, jint o) {
    return uapmd_commands_set_track_freeze_policy_enabled(j2p<uapmd_project_commands_t>(h), t, v, static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdCommandsSetPluginBypassed)(JNIEnv*, jclass, jlong h, jint id, jboolean v, jint o) {
    return uapmd_commands_set_plugin_bypassed(j2p<uapmd_project_commands_t>(h), id, v, static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdCommandsSetPluginParameterValue)(JNIEnv*, jclass, jlong h, jint id, jint idx, jdouble v, jint o) {
    return uapmd_commands_set_plugin_parameter_value(j2p<uapmd_project_commands_t>(h), id, idx, v, static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdCommandsSetPluginPerNoteControllerValue)(
        JNIEnv*, jclass, jlong h, jint id, jint contextType,
        jint note, jint channel, jint group, jint extra, jint idx, jdouble v, jint o) {
    return uapmd_commands_set_plugin_per_note_controller_value(
        j2p<uapmd_project_commands_t>(h), id, static_cast<uint32_t>(contextType),
        static_cast<uint32_t>(note), static_cast<uint32_t>(channel),
        static_cast<uint32_t>(group), static_cast<uint32_t>(extra),
        idx, v, static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdCommandsSetPluginGroup)(JNIEnv*, jclass, jlong h, jint id, jbyte g, jint o) {
    return uapmd_commands_set_plugin_group(j2p<uapmd_project_commands_t>(h), id, static_cast<uint8_t>(g), static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdCommandsSetMasterTrackMarkers)(JNIEnv* env, jclass, jlong h,
                                                      jobjectArray strings, jdoubleArray numbers, jintArray refTypes, jint o) {
    auto buf = decode_markers(env, strings, numbers, refTypes);
    return uapmd_commands_set_master_track_markers(j2p<uapmd_project_commands_t>(h),
                                                   buf.data(), buf.size(), static_cast<uapmd_mutation_origin_t>(o));
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  ProjectAddressBook
 * ═══════════════════════════════════════════════════════════════════════════ */

JNI_FN(jlong, uapmdAddressesTimelineTrack)(JNIEnv* env, jclass, jlong h, jstring refId) {
    const char* r = jstr(env, refId);
    jlong result = p2j(uapmd_addresses_timeline_track(j2p<uapmd_address_book_t>(h), r));
    jstr_release(env, refId, r);
    return result;
}

JNI_FN(jlong, uapmdAddressesSequencerTrack)(JNIEnv* env, jclass, jlong h, jstring refId) {
    const char* r = jstr(env, refId);
    jlong result = p2j(uapmd_addresses_sequencer_track(j2p<uapmd_address_book_t>(h), r));
    jstr_release(env, refId, r);
    return result;
}

JNI_FN(jint, uapmdAddressesTrackIndex)(JNIEnv* env, jclass, jlong h, jstring refId) {
    const char* r = jstr(env, refId);
    jint result = uapmd_addresses_track_index(j2p<uapmd_address_book_t>(h), r);
    jstr_release(env, refId, r);
    return result;
}

JNI_FN(jint, uapmdAddressesClipId)(JNIEnv* env, jclass, jlong h, jstring trackRef, jstring clipRef) {
    const char* t = jstr(env, trackRef);
    const char* c = jstr(env, clipRef);
    uapmd_clip_address_t addr{t, c};
    jint result = uapmd_addresses_clip_id(j2p<uapmd_address_book_t>(h), addr);
    jstr_release(env, trackRef, t);
    jstr_release(env, clipRef, c);
    return result;
}

JNI_FN(jint, uapmdAddressesPluginInstanceId)(JNIEnv* env, jclass, jlong h, jstring trackRef, jstring nodeId) {
    const char* t = jstr(env, trackRef);
    const char* n = jstr(env, nodeId);
    uapmd_plugin_address_t addr{t, n};
    jint result = uapmd_addresses_plugin_instance_id(j2p<uapmd_address_book_t>(h), addr);
    jstr_release(env, trackRef, t);
    jstr_release(env, nodeId, n);
    return result;
}

JNI_FN(jstring, uapmdAddressesTrackReferenceId)(JNIEnv* env, jclass, jlong h, jint trackIndex) {
    const char* id = uapmd_addresses_track_reference_id(j2p<uapmd_address_book_t>(h), trackIndex);
    return id ? env->NewStringUTF(id) : nullptr;
}

/** Returns String[2] {trackReferenceId, clipReferenceId} or null. */
JNI_FN(jobjectArray, uapmdAddressesClipAddress)(JNIEnv* env, jclass, jlong h, jint trackIndex, jint clipId) {
    uapmd_clip_address_t out{};
    if (!uapmd_addresses_clip_address(j2p<uapmd_address_book_t>(h), trackIndex, clipId, &out)) return nullptr;
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(2, stringClass, nullptr);
    env->SetObjectArrayElement(arr, 0, env->NewStringUTF(out.track_reference_id ? out.track_reference_id : ""));
    env->SetObjectArrayElement(arr, 1, env->NewStringUTF(out.clip_reference_id ? out.clip_reference_id : ""));
    return arr;
}

/** Returns String[2] {trackReferenceId, nodeId} or null. */
JNI_FN(jobjectArray, uapmdAddressesPluginAddress)(JNIEnv* env, jclass, jlong h, jint instanceId) {
    uapmd_plugin_address_t out{};
    if (!uapmd_addresses_plugin_address(j2p<uapmd_address_book_t>(h), instanceId, &out)) return nullptr;
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(2, stringClass, nullptr);
    env->SetObjectArrayElement(arr, 0, env->NewStringUTF(out.track_reference_id ? out.track_reference_id : ""));
    env->SetObjectArrayElement(arr, 1, env->NewStringUTF(out.node_id ? out.node_id : ""));
    return arr;
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Fragments
 * ═══════════════════════════════════════════════════════════════════════════ */

JNI_FN(void, uapmdClipFragmentDestroy)(JNIEnv*, jclass, jlong h) {
    uapmd_clip_fragment_destroy(j2p<uapmd_clip_fragment_t>(h));
}

JNI_FN(jboolean, uapmdClipFragmentIsMidi)(JNIEnv*, jclass, jlong h) {
    return uapmd_clip_fragment_is_midi(j2p<uapmd_clip_fragment_t>(h));
}

/**
 * Fills outStrings[0..1] = {name, filepath} and returns
 * double[7] = {clipId, positionSamples, positionBeats, durationSamples, gain, muted, clipType},
 * matching the packing uapmdTtGetAllClips already uses.
 */
JNI_FN(jdoubleArray, uapmdClipFragmentGetClip)(JNIEnv* env, jclass, jlong h, jobjectArray outStrings) {
    uapmd_clip_data_t c{};
    if (!uapmd_clip_fragment_get_clip(j2p<uapmd_clip_fragment_t>(h), &c)) return nullptr;
    if (outStrings) {
        env->SetObjectArrayElement(outStrings, 0, env->NewStringUTF(c.name ? c.name : ""));
        env->SetObjectArrayElement(outStrings, 1, env->NewStringUTF(c.filepath ? c.filepath : ""));
    }
    jdouble vals[7] = {
        static_cast<jdouble>(c.clip_id),
        static_cast<jdouble>(c.position.samples), c.position.legacy_beats,
        static_cast<jdouble>(c.duration_samples), c.gain,
        c.muted ? 1.0 : 0.0, static_cast<jdouble>(c.clip_type)
    };
    jdoubleArray arr = env->NewDoubleArray(7);
    env->SetDoubleArrayRegion(arr, 0, 7, vals);
    return arr;
}

JNI_FN(jintArray, uapmdClipFragmentGetUmpEvents)(JNIEnv* env, jclass, jlong h) {
    auto fragment = j2p<uapmd_clip_fragment_t>(h);
    uint32_t n = uapmd_clip_fragment_get_ump_events(fragment, nullptr, 0);
    jintArray arr = env->NewIntArray(static_cast<jsize>(n));
    if (n == 0) return arr;
    std::vector<uapmd_ump_t> buf(n);
    uapmd_clip_fragment_get_ump_events(fragment, buf.data(), n);
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(n), reinterpret_cast<const jint*>(buf.data()));
    return arr;
}

JNI_FN(jlongArray, uapmdClipFragmentGetUmpTickTimestamps)(JNIEnv* env, jclass, jlong h) {
    auto fragment = j2p<uapmd_clip_fragment_t>(h);
    uint32_t n = uapmd_clip_fragment_get_ump_tick_timestamps(fragment, nullptr, 0);
    jlongArray arr = env->NewLongArray(static_cast<jsize>(n));
    if (n == 0) return arr;
    std::vector<uint64_t> buf(n);
    uapmd_clip_fragment_get_ump_tick_timestamps(fragment, buf.data(), n);
    env->SetLongArrayRegion(arr, 0, static_cast<jsize>(n), reinterpret_cast<const jlong*>(buf.data()));
    return arr;
}

JNI_FN(jint, uapmdClipFragmentExtensionStateCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_clip_fragment_extension_state_count(j2p<uapmd_clip_fragment_t>(h)));
}

JNI_FN(jstring, uapmdClipFragmentExtensionStateKey)(JNIEnv* env, jclass, jlong h, jint index) {
    auto fragment = j2p<uapmd_clip_fragment_t>(h);
    return cstr(env, [&](char* buf, size_t size) {
        return uapmd_clip_fragment_extension_state_key(fragment, static_cast<uint32_t>(index), buf, size);
    });
}

JNI_FN(jbyteArray, uapmdClipFragmentExtensionStateData)(JNIEnv* env, jclass, jlong h, jint index) {
    auto fragment = j2p<uapmd_clip_fragment_t>(h);
    return cbytes(env, [&](uint8_t* buf, size_t size) {
        return uapmd_clip_fragment_extension_state_data(fragment, static_cast<uint32_t>(index), buf, size);
    });
}

JNI_FN(void, uapmdTrackFragmentDestroy)(JNIEnv*, jclass, jlong h) {
    uapmd_track_fragment_destroy(j2p<uapmd_track_fragment_t>(h));
}

JNI_FN(jstring, uapmdTrackFragmentReferenceId)(JNIEnv* env, jclass, jlong h) {
    auto fragment = j2p<uapmd_track_fragment_t>(h);
    return cstr(env, [&](char* buf, size_t size) { return uapmd_track_fragment_reference_id(fragment, buf, size); });
}

JNI_FN(jdouble, uapmdTrackFragmentVolume)(JNIEnv*, jclass, jlong h) {
    return uapmd_track_fragment_volume(j2p<uapmd_track_fragment_t>(h));
}

JNI_FN(jboolean, uapmdTrackFragmentMuted)(JNIEnv*, jclass, jlong h) {
    return uapmd_track_fragment_muted(j2p<uapmd_track_fragment_t>(h));
}

JNI_FN(jboolean, uapmdTrackFragmentSolo)(JNIEnv*, jclass, jlong h) {
    return uapmd_track_fragment_solo(j2p<uapmd_track_fragment_t>(h));
}

JNI_FN(jstring, uapmdTrackFragmentGraphType)(JNIEnv* env, jclass, jlong h) {
    auto fragment = j2p<uapmd_track_fragment_t>(h);
    return cstr(env, [&](char* buf, size_t size) { return uapmd_track_fragment_graph_type(fragment, buf, size); });
}

JNI_FN(jbyteArray, uapmdTrackFragmentGraphBytes)(JNIEnv* env, jclass, jlong h) {
    auto fragment = j2p<uapmd_track_fragment_t>(h);
    return cbytes(env, [&](uint8_t* buf, size_t size) { return uapmd_track_fragment_graph_bytes(fragment, buf, size); });
}

JNI_FN(jint, uapmdTrackFragmentClipCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_track_fragment_clip_count(j2p<uapmd_track_fragment_t>(h)));
}

JNI_FN(jlong, uapmdTrackFragmentGetClip)(JNIEnv*, jclass, jlong h, jint index) {
    return p2j(uapmd_track_fragment_get_clip(j2p<uapmd_track_fragment_t>(h), static_cast<uint32_t>(index)));
}

JNI_FN(jint, uapmdTrackFragmentPluginCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_track_fragment_plugin_count(j2p<uapmd_track_fragment_t>(h)));
}

/**
 * Fills outStrings[0..3] = {nodeId, pluginId, format, displayName} and returns
 * the plug-in's opaque state, or null when the index is out of range. The group
 * index is returned separately by uapmdTrackFragmentPluginGroupIndex.
 */
JNI_FN(jbyteArray, uapmdTrackFragmentGetPlugin)(JNIEnv* env, jclass, jlong h, jint index, jobjectArray outStrings) {
    uapmd_track_plugin_fragment_t p{};
    if (!uapmd_track_fragment_get_plugin(j2p<uapmd_track_fragment_t>(h), static_cast<uint32_t>(index), &p))
        return nullptr;
    if (outStrings) {
        env->SetObjectArrayElement(outStrings, 0, env->NewStringUTF(p.node_id ? p.node_id : ""));
        env->SetObjectArrayElement(outStrings, 1, env->NewStringUTF(p.plugin_id ? p.plugin_id : ""));
        env->SetObjectArrayElement(outStrings, 2, env->NewStringUTF(p.format ? p.format : ""));
        env->SetObjectArrayElement(outStrings, 3, env->NewStringUTF(p.display_name ? p.display_name : ""));
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(p.state_size));
    if (p.state_size && p.state)
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(p.state_size), reinterpret_cast<const jbyte*>(p.state));
    return arr;
}

JNI_FN(jint, uapmdTrackFragmentPluginGroupIndex)(JNIEnv*, jclass, jlong h, jint index) {
    uapmd_track_plugin_fragment_t p{};
    if (!uapmd_track_fragment_get_plugin(j2p<uapmd_track_fragment_t>(h), static_cast<uint32_t>(index), &p))
        return -1;
    return p.group_index;
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  TimelineFacade history accessors and undoable mutations
 * ═══════════════════════════════════════════════════════════════════════════ */

JNI_FN(jlong, uapmdTlUndoEngine)(JNIEnv*, jclass, jlong h) {
    return p2j(uapmd_tl_undo_engine(j2p<uapmd_timeline_facade_t>(h)));
}

JNI_FN(jlong, uapmdTlCommands)(JNIEnv*, jclass, jlong h) {
    return p2j(uapmd_tl_commands(j2p<uapmd_timeline_facade_t>(h)));
}

JNI_FN(jlong, uapmdTlAddresses)(JNIEnv*, jclass, jlong h) {
    return p2j(uapmd_tl_addresses(j2p<uapmd_timeline_facade_t>(h)));
}

JNI_FN(void, uapmdTlBeginDocumentTransaction)(JNIEnv*, jclass, jlong h) {
    uapmd_tl_begin_document_transaction(j2p<uapmd_timeline_facade_t>(h));
}

JNI_FN(void, uapmdTlEndDocumentTransaction)(JNIEnv*, jclass, jlong h) {
    uapmd_tl_end_document_transaction(j2p<uapmd_timeline_facade_t>(h));
}

JNI_FN(jboolean, uapmdTlRemoveClipWithOrigin)(JNIEnv*, jclass, jlong h, jint t, jint c, jint o) {
    return uapmd_tl_remove_clip_with_origin(j2p<uapmd_timeline_facade_t>(h), t, c, static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdTlClearClipsFromTrack)(JNIEnv*, jclass, jlong h, jint t, jint o) {
    return uapmd_tl_clear_clips_from_track(j2p<uapmd_timeline_facade_t>(h), t, static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdTlClipEnabled)(JNIEnv*, jclass, jlong h, jint t, jint c) {
    return uapmd_tl_clip_enabled(j2p<uapmd_timeline_facade_t>(h), t, c);
}

JNI_FN(jboolean, uapmdTlReplaceMidiClipContent)(JNIEnv* env, jclass, jlong h, jint t, jint c,
                                                 jintArray umpEvents, jlongArray tickTimestamps, jint o) {
    jsize eventCount = umpEvents ? env->GetArrayLength(umpEvents) : 0;
    jsize tickCount = tickTimestamps ? env->GetArrayLength(tickTimestamps) : 0;
    std::vector<uapmd_ump_t> events(eventCount);
    std::vector<uint64_t> ticks(tickCount);
    if (eventCount) env->GetIntArrayRegion(umpEvents, 0, eventCount, reinterpret_cast<jint*>(events.data()));
    if (tickCount) env->GetLongArrayRegion(tickTimestamps, 0, tickCount, reinterpret_cast<jlong*>(ticks.data()));
    return uapmd_tl_replace_midi_clip_content(
        j2p<uapmd_timeline_facade_t>(h), t, c,
        events.empty() ? nullptr : events.data(), static_cast<uint32_t>(eventCount),
        ticks.empty() ? nullptr : ticks.data(), static_cast<uint32_t>(tickCount),
        static_cast<uapmd_mutation_origin_t>(o));
}

JNI_FN(jboolean, uapmdTlReplaceAudioClipContent)(
        JNIEnv* env, jclass, jlong h, jint t, jint c, jstring filepath,
        jobjectArray markerStrings, jdoubleArray markerNumbers, jintArray markerTypes,
        jobjectArray warpStrings, jdoubleArray warpNumbers, jintArray warpTypes,
        jobjectArray masterStrings, jdoubleArray masterNumbers, jintArray masterTypes, jint o) {
    auto markers = decode_markers(env, markerStrings, markerNumbers, markerTypes);
    auto warps = decode_warps(env, warpStrings, warpNumbers, warpTypes);
    auto masters = decode_markers(env, masterStrings, masterNumbers, masterTypes);
    const char* fp = jstr(env, filepath);
    bool ok = uapmd_tl_replace_audio_clip_content(
        j2p<uapmd_timeline_facade_t>(h), t, c, fp,
        markers.data(), markers.size(),
        warps.data(), warps.size(),
        masters.data(), masters.size(),
        static_cast<uapmd_mutation_origin_t>(o));
    jstr_release(env, filepath, fp);
    return ok;
}

JNI_FN(jlong, uapmdTlCaptureClipFragment)(JNIEnv*, jclass, jlong h, jint t, jint c) {
    return p2j(uapmd_tl_capture_clip_fragment(j2p<uapmd_timeline_facade_t>(h), t, c));
}

/** Returns int[3] {clipId, sourceNodeId, success}; fills outStrings[0] with the error. */
JNI_FN(jintArray, uapmdTlAttachClipFragment)(JNIEnv* env, jclass, jlong h, jint t, jlong fragment,
                                              jint idPolicy, jobjectArray outStrings) {
    auto r = uapmd_tl_attach_clip_fragment(j2p<uapmd_timeline_facade_t>(h), t,
                                           j2p<uapmd_clip_fragment_t>(fragment),
                                           static_cast<uapmd_object_id_policy_t>(idPolicy));
    if (outStrings && r.error)
        env->SetObjectArrayElement(outStrings, 0, env->NewStringUTF(r.error));
    jint vals[3] = {r.clip_id, r.source_node_id, r.success ? 1 : 0};
    jintArray arr = env->NewIntArray(3);
    env->SetIntArrayRegion(arr, 0, 3, vals);
    return arr;
}

JNI_FN(void, uapmdTlCaptureTrackFragment)(JNIEnv* env, jclass, jlong h, jint t, jobject cb) {
    if (!cb) return;
    auto* ctx = new HistoryCtx(env, cb, "(JLjava/lang/String;)V");
    uapmd_tl_capture_track_fragment(j2p<uapmd_timeline_facade_t>(h), t, ctx, track_fragment_trampoline);
}

JNI_FN(void, uapmdTlAttachTrackFragment)(JNIEnv* env, jclass, jlong h, jlong fragment,
                                          jint idPolicy, jint insertionIndex,
                                          jboolean includePlugins, jboolean includePluginState,
                                          jboolean includeClips, jobject cb) {
    if (!cb) return;
    auto* ctx = new HistoryCtx(env, cb, "(ILjava/lang/String;)V");
    uapmd_track_attach_options_t options{
        static_cast<uapmd_object_id_policy_t>(idPolicy), insertionIndex,
        static_cast<bool>(includePlugins), static_cast<bool>(includePluginState),
        static_cast<bool>(includeClips)
    };
    uapmd_tl_attach_track_fragment(j2p<uapmd_timeline_facade_t>(h), j2p<uapmd_track_fragment_t>(fragment),
                                   options, ctx, track_mutation_trampoline);
}

JNI_FN(void, uapmdTlAddEmptyTrackUndoable)(JNIEnv* env, jclass, jlong h, jint o, jobject cb) {
    if (!cb) return;
    auto* ctx = new HistoryCtx(env, cb, "(ILjava/lang/String;)V");
    uapmd_tl_add_empty_track(j2p<uapmd_timeline_facade_t>(h), static_cast<uapmd_mutation_origin_t>(o),
                             ctx, track_mutation_trampoline);
}

JNI_FN(void, uapmdTlRemoveTrackUndoable)(JNIEnv* env, jclass, jlong h, jint t, jint o, jobject cb) {
    if (!cb) return;
    auto* ctx = new HistoryCtx(env, cb, "(ILjava/lang/String;)V");
    uapmd_tl_remove_track(j2p<uapmd_timeline_facade_t>(h), t, static_cast<uapmd_mutation_origin_t>(o),
                          ctx, track_mutation_trampoline);
}

JNI_FN(void, uapmdTlRecordTrackAddition)(JNIEnv* env, jclass, jlong h, jint t, jint o, jobject cb) {
    if (!cb) return;
    auto* ctx = new HistoryCtx(env, cb, "(ILjava/lang/String;)V");
    uapmd_tl_record_track_addition(j2p<uapmd_timeline_facade_t>(h), t, static_cast<uapmd_mutation_origin_t>(o),
                                   ctx, track_mutation_trampoline);
}

JNI_FN(void, uapmdTlSetPluginState)(JNIEnv* env, jclass, jlong h, jint id, jbyteArray state, jint o, jobject cb) {
    jsize n = state ? env->GetArrayLength(state) : 0;
    std::vector<uint8_t> buf(n);
    if (n) env->GetByteArrayRegion(state, 0, n, reinterpret_cast<jbyte*>(buf.data()));
    uapmd_tl_set_plugin_state(j2p<uapmd_timeline_facade_t>(h), id,
                              buf.empty() ? nullptr : buf.data(), buf.size(),
                              static_cast<uapmd_mutation_origin_t>(o),
                              undo_ctx(env, cb), undo_completion_trampoline);
}

JNI_FN(void, uapmdTlLoadPluginPreset)(JNIEnv* env, jclass, jlong h, jint id, jint presetIndex, jint o, jobject cb) {
    uapmd_tl_load_plugin_preset(j2p<uapmd_timeline_facade_t>(h), id, presetIndex,
                                static_cast<uapmd_mutation_origin_t>(o),
                                undo_ctx(env, cb), undo_completion_trampoline);
}

JNI_FN(void, uapmdTlRecordPluginInstanceAddition)(JNIEnv* env, jclass, jlong h, jint id, jint o, jobject cb) {
    uapmd_tl_record_plugin_instance_addition(j2p<uapmd_timeline_facade_t>(h), id,
                                             static_cast<uapmd_mutation_origin_t>(o),
                                             undo_ctx(env, cb), undo_completion_trampoline);
}

JNI_FN(void, uapmdTlRemovePluginInstanceUndoable)(JNIEnv* env, jclass, jlong h, jint id, jint o, jobject cb) {
    uapmd_tl_remove_plugin_instance(j2p<uapmd_timeline_facade_t>(h), id,
                                    static_cast<uapmd_mutation_origin_t>(o),
                                    undo_ctx(env, cb), undo_completion_trampoline);
}

JNI_FN(jboolean, uapmdTlHasPendingPluginMutations)(JNIEnv*, jclass, jlong h) {
    return uapmd_tl_has_pending_plugin_mutations(j2p<uapmd_timeline_facade_t>(h));
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Engine dirty state and master markers
 * ═══════════════════════════════════════════════════════════════════════════ */

JNI_FN(jboolean, uapmdEngineIsProjectDirty)(JNIEnv*, jclass, jlong h) {
    return uapmd_engine_is_project_dirty(j2p<uapmd_sequencer_engine_t>(h));
}

JNI_FN(jboolean, uapmdEngineIsTrackDirty)(JNIEnv*, jclass, jlong h, jint t) {
    return uapmd_engine_is_track_dirty(j2p<uapmd_sequencer_engine_t>(h), t);
}

JNI_FN(void, uapmdEngineMarkTrackDirty)(JNIEnv*, jclass, jlong h, jint t, jboolean dirty) {
    uapmd_engine_mark_track_dirty(j2p<uapmd_sequencer_engine_t>(h), t, dirty);
}

JNI_FN(void, uapmdEngineClearTrackDirtyState)(JNIEnv*, jclass, jlong h) {
    uapmd_engine_clear_track_dirty_state(j2p<uapmd_sequencer_engine_t>(h));
}

/**
 * Snapshots the engine's master markers into `outStrings` (4 per marker) and
 * `outTypes` (1 per marker), returning the offsets. Call
 * uapmdEngineMasterMarkerCount first to size the arrays.
 */
JNI_FN(jdoubleArray, uapmdEngineGetMasterMarkers)(JNIEnv* env, jclass, jlong h,
                                                   jobjectArray outStrings, jintArray outTypes) {
    auto engine = j2p<uapmd_sequencer_engine_t>(h);
    uint32_t count = uapmd_engine_master_marker_count(engine);
    jdoubleArray offsets = env->NewDoubleArray(static_cast<jsize>(count));
    if (count == 0) return offsets;

    std::vector<jdouble> offsetValues(count);
    std::vector<jint> typeValues(count);
    for (uint32_t i = 0; i < count; ++i) {
        uapmd_clip_marker_t m{};
        if (!uapmd_engine_get_master_marker(engine, i, &m)) continue;
        offsetValues[i] = m.clip_position_offset;
        typeValues[i] = static_cast<jint>(m.reference_type);
        if (outStrings) {
            env->SetObjectArrayElement(outStrings, i * 4 + 0, env->NewStringUTF(m.marker_id ? m.marker_id : ""));
            env->SetObjectArrayElement(outStrings, i * 4 + 1, env->NewStringUTF(m.reference_clip_id ? m.reference_clip_id : ""));
            env->SetObjectArrayElement(outStrings, i * 4 + 2, env->NewStringUTF(m.reference_marker_id ? m.reference_marker_id : ""));
            env->SetObjectArrayElement(outStrings, i * 4 + 3, env->NewStringUTF(m.name ? m.name : ""));
        }
    }
    env->SetDoubleArrayRegion(offsets, 0, static_cast<jsize>(count), offsetValues.data());
    if (outTypes) env->SetIntArrayRegion(outTypes, 0, static_cast<jsize>(count), typeValues.data());
    return offsets;
}

JNI_FN(jint, uapmdEngineMasterMarkerCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_engine_master_marker_count(j2p<uapmd_sequencer_engine_t>(h)));
}

JNI_FN(void, uapmdEngineSetMasterMarkers)(JNIEnv* env, jclass, jlong h,
                                           jobjectArray strings, jdoubleArray numbers, jintArray refTypes) {
    auto buf = decode_markers(env, strings, numbers, refTypes);
    uapmd_engine_set_master_markers(j2p<uapmd_sequencer_engine_t>(h), buf.data(), buf.size());
}

JNI_FN(void, uapmdEngineRegisterAddinExtensionPoints)(JNIEnv*, jclass, jlong engine, jlong mgr) {
    uapmd_engine_register_addin_extension_points(j2p<uapmd_sequencer_engine_t>(engine),
                                                 j2p<uapmd_addin_manager_t>(mgr));
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  AddinManager
 * ═══════════════════════════════════════════════════════════════════════════ */

JNI_FN(jlong, uapmdAddinManagerCreate)(JNIEnv*, jclass) {
    return p2j(uapmd_addin_manager_create());
}

JNI_FN(void, uapmdAddinManagerDestroy)(JNIEnv*, jclass, jlong h) {
    uapmd_addin_manager_destroy(j2p<uapmd_addin_manager_t>(h));
}

JNI_FN(void, uapmdAddinManagerInitialize)(JNIEnv*, jclass, jlong h) {
    uapmd_addin_manager_initialize(j2p<uapmd_addin_manager_t>(h));
}

JNI_FN(jboolean, uapmdAddinManagerSetEnabled)(JNIEnv* env, jclass, jlong h,
                                               jstring packageId, jstring addinId, jboolean enabled) {
    const char* p = jstr(env, packageId);
    const char* a = jstr(env, addinId);
    bool ok = uapmd_addin_manager_set_enabled(j2p<uapmd_addin_manager_t>(h), p, a, enabled);
    jstr_release(env, packageId, p);
    jstr_release(env, addinId, a);
    return ok;
}

JNI_FN(void, uapmdAddinManagerShutdown)(JNIEnv*, jclass, jlong h) {
    uapmd_addin_manager_shutdown(j2p<uapmd_addin_manager_t>(h));
}

JNI_FN(jint, uapmdAddinManagerDirectoryCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_addin_manager_directory_count(j2p<uapmd_addin_manager_t>(h)));
}

JNI_FN(jstring, uapmdAddinManagerGetDirectory)(JNIEnv* env, jclass, jlong h, jint index) {
    auto mgr = j2p<uapmd_addin_manager_t>(h);
    return cstr(env, [&](char* buf, size_t size) {
        return uapmd_addin_manager_get_directory(mgr, static_cast<uint32_t>(index), buf, size);
    });
}

JNI_FN(jint, uapmdAddinManagerAddinCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_addin_manager_addin_count(j2p<uapmd_addin_manager_t>(h)));
}

/**
 * Fills outStrings[0..5] = {packageId, addinId, name, path, libraryPath, message}
 * and returns int[2] = {builtIn, state}, or null when the index is out of range.
 */
JNI_FN(jintArray, uapmdAddinManagerGetAddin)(JNIEnv* env, jclass, jlong h, jint index, jobjectArray outStrings) {
    uapmd_addin_info_t info{};
    if (!uapmd_addin_manager_get_addin(j2p<uapmd_addin_manager_t>(h), static_cast<uint32_t>(index), &info))
        return nullptr;
    if (outStrings) {
        const char* values[6] = {
            info.package_id, info.addin_id, info.name, info.path, info.library_path, info.message
        };
        for (int i = 0; i < 6; ++i)
            env->SetObjectArrayElement(outStrings, i, env->NewStringUTF(values[i] ? values[i] : ""));
    }
    jint vals[2] = {info.built_in ? 1 : 0, static_cast<jint>(info.state)};
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, vals);
    return arr;
}

JNI_FN(jstring, uapmdAddinManagerLastError)(JNIEnv* env, jclass, jlong h) {
    auto mgr = j2p<uapmd_addin_manager_t>(h);
    return cstr(env, [&](char* buf, size_t size) { return uapmd_addin_manager_last_error(mgr, buf, size); });
}

JNI_FN(jboolean, uapmdAddinSupportsDynamicLoading)(JNIEnv*, jclass) {
    return uapmd_addin_supports_dynamic_loading();
}

#undef JNI_FN

} // extern "C"
