/**
 * uapmd JNI bridge — project selection service
 *
 * Opaque C pointers travel as jlong. A selection is read and built through the
 * scalar accessors of uapmd-c-selection.h, so no struct crosses the boundary.
 * Listener callbacks go to a Kotlin object whose methods are resolved on the spot.
 */

#include <jni.h>
#include <map>
#include <mutex>
#include <string>

#include "c-api/uapmd-c-selection.h"

// Shared with uapmd_jni.cpp.
extern JNIEnv* uapmd_jni_env();

namespace {

template<typename T>
inline jlong p2j(T ptr) { return static_cast<jlong>(reinterpret_cast<uintptr_t>(ptr)); }

template<typename T>
inline T j2p(jlong handle) { return reinterpret_cast<T>(static_cast<uintptr_t>(handle)); }

class JStr {
    JNIEnv* env_;
    jstring s_;
    const char* c_;
public:
    JStr(JNIEnv* env, jstring s) : env_(env), s_(s), c_(s ? env->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~JStr() { if (s_ && c_) env_->ReleaseStringUTFChars(s_, c_); }
    JStr(const JStr&) = delete;
    JStr& operator=(const JStr&) = delete;
    const char* get() const { return c_; }
};

jstring jstring_or_null(JNIEnv* env, const char* s) { return s ? env->NewStringUTF(s) : nullptr; }

/* One context per registered listener; owns a global ref to the Kotlin adapter. */
struct ListenerCtx {
    jobject obj;
    jmethodID changed;
    jmethodID activeChanged;
    jmethodID removed;
};

std::mutex g_mutex;
std::map<std::pair<jlong, jlong>, ListenerCtx*> g_listeners;

void on_selection_changed(int64_t context, uapmd_selection_t previous, uapmd_selection_t current, void* user_data) {
    auto* ctx = static_cast<ListenerCtx*>(user_data);
    JNIEnv* env = uapmd_jni_env();
    if (!env) return;
    env->CallVoidMethod(ctx->obj, ctx->changed, static_cast<jlong>(context), p2j(previous), p2j(current));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void on_active_context_changed(int64_t previous, int64_t current, void* user_data) {
    auto* ctx = static_cast<ListenerCtx*>(user_data);
    JNIEnv* env = uapmd_jni_env();
    if (!env) return;
    env->CallVoidMethod(ctx->obj, ctx->activeChanged, static_cast<jlong>(previous), static_cast<jlong>(current));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void on_context_removed(int64_t context, void* user_data) {
    auto* ctx = static_cast<ListenerCtx*>(user_data);
    JNIEnv* env = uapmd_jni_env();
    if (!env) return;
    env->CallVoidMethod(ctx->obj, ctx->removed, static_cast<jlong>(context));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

} // namespace

extern "C" {

#define JNI_FN(ret, name) JNIEXPORT ret JNICALL Java_dev_atsushieno_uapmd_JniBridge_##name
#define SVC(h) j2p<uapmd_selection_service_t>(h)
#define SEL(h) j2p<uapmd_selection_t>(h)
#define PRJ(h) j2p<uapmd_selection_projection_t>(h)

JNI_FN(jlong, uapmdTlSelections)(JNIEnv*, jclass, jlong tl) {
    return p2j(uapmd_tl_selections(j2p<uapmd_timeline_facade_t>(tl)));
}

JNI_FN(jlong, uapmdSelectionServiceDefaultContext)(JNIEnv*, jclass, jlong h) {
    return uapmd_selection_service_default_context(SVC(h));
}
JNI_FN(jlong, uapmdSelectionServiceCreateContext)(JNIEnv* env, jclass, jlong h, jstring owner) {
    JStr o(env, owner);
    return uapmd_selection_service_create_context(SVC(h), o.get());
}
JNI_FN(jboolean, uapmdSelectionServiceHasContext)(JNIEnv*, jclass, jlong h, jlong context) {
    return uapmd_selection_service_has_context(SVC(h), context);
}
JNI_FN(void, uapmdSelectionServiceRemoveContext)(JNIEnv*, jclass, jlong h, jlong context) {
    uapmd_selection_service_remove_context(SVC(h), context);
}
JNI_FN(jlong, uapmdSelectionServiceSnapshot)(JNIEnv*, jclass, jlong h, jlong context) {
    return p2j(uapmd_selection_service_snapshot(SVC(h), context));
}
JNI_FN(jlong, uapmdSelectionServiceActiveSelection)(JNIEnv*, jclass, jlong h) {
    return p2j(uapmd_selection_service_active_selection(SVC(h)));
}
JNI_FN(jboolean, uapmdSelectionServiceReplace)(JNIEnv*, jclass, jlong h, jlong context, jlong selection) {
    return uapmd_selection_service_replace(SVC(h), context, SEL(selection));
}
JNI_FN(void, uapmdSelectionServiceClear)(JNIEnv*, jclass, jlong h, jlong context) {
    uapmd_selection_service_clear(SVC(h), context);
}
JNI_FN(jstring, uapmdSelectionServiceLastError)(JNIEnv* env, jclass) {
    return env->NewStringUTF(uapmd_selection_service_last_error());
}
JNI_FN(jlong, uapmdSelectionServiceActiveContext)(JNIEnv*, jclass, jlong h) {
    return uapmd_selection_service_active_context(SVC(h));
}
JNI_FN(jboolean, uapmdSelectionServiceSetActiveContext)(JNIEnv*, jclass, jlong h, jlong context) {
    return uapmd_selection_service_set_active_context(SVC(h), context);
}

/* `listener` has onSelectionChanged(JJJ)V, onActiveContextChanged(JJ)V and onContextRemoved(J)V.
 * The selection handles passed to onSelectionChanged are valid only during the call. */
JNI_FN(jlong, uapmdSelectionServiceAddListener)(JNIEnv* env, jclass, jlong h, jobject listener) {
    if (!listener) return 0;
    jclass cls = env->GetObjectClass(listener);
    auto* ctx = new ListenerCtx{
        env->NewGlobalRef(listener),
        env->GetMethodID(cls, "onSelectionChanged", "(JJJ)V"),
        env->GetMethodID(cls, "onActiveContextChanged", "(JJ)V"),
        env->GetMethodID(cls, "onContextRemoved", "(J)V")};
    env->DeleteLocalRef(cls);
    jlong token = uapmd_selection_service_add_listener(
        SVC(h), ctx, on_selection_changed, on_active_context_changed, on_context_removed);
    if (token <= 0) {
        env->DeleteGlobalRef(ctx->obj);
        delete ctx;
        return 0;
    }
    std::lock_guard lock(g_mutex);
    g_listeners[{h, token}] = ctx;
    return token;
}
JNI_FN(void, uapmdSelectionServiceRemoveListener)(JNIEnv* env, jclass, jlong h, jlong token) {
    uapmd_selection_service_remove_listener(SVC(h), token);
    ListenerCtx* ctx = nullptr;
    {
        std::lock_guard lock(g_mutex);
        auto it = g_listeners.find({h, token});
        if (it == g_listeners.end()) return;
        ctx = it->second;
        g_listeners.erase(it);
    }
    env->DeleteGlobalRef(ctx->obj);
    delete ctx;
}

JNI_FN(void, uapmdSelectionDestroy)(JNIEnv*, jclass, jlong h) { uapmd_selection_destroy(SEL(h)); }
JNI_FN(jint, uapmdSelectionObjectCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_selection_object_count(SEL(h)));
}
JNI_FN(jstring, uapmdSelectionObjectAt)(JNIEnv* env, jclass, jlong h, jint i) {
    return jstring_or_null(env, uapmd_selection_object_at(SEL(h), static_cast<uint32_t>(i)));
}
JNI_FN(jint, uapmdSelectionRegionCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_selection_region_count(SEL(h)));
}
JNI_FN(jint, uapmdSelectionRegionKind)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_kind(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(jdouble, uapmdSelectionRegionStartSeconds)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_start_seconds(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(jdouble, uapmdSelectionRegionEndSeconds)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_end_seconds(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(jboolean, uapmdSelectionRegionTracksRestricted)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_tracks_restricted(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(jint, uapmdSelectionRegionTrackCount)(JNIEnv*, jclass, jlong h, jint r) {
    return static_cast<jint>(uapmd_selection_region_track_count(SEL(h), static_cast<uint32_t>(r)));
}
JNI_FN(jstring, uapmdSelectionRegionTrackAt)(JNIEnv* env, jclass, jlong h, jint r, jint i) {
    return jstring_or_null(env, uapmd_selection_region_track_at(SEL(h), static_cast<uint32_t>(r), static_cast<uint32_t>(i)));
}
JNI_FN(jstring, uapmdSelectionRegionClipId)(JNIEnv* env, jclass, jlong h, jint r) {
    return jstring_or_null(env, uapmd_selection_region_clip_id(SEL(h), static_cast<uint32_t>(r)));
}
JNI_FN(jlong, uapmdSelectionRegionStartTick)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_start_tick(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(jlong, uapmdSelectionRegionEndTick)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_end_tick(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(jint, uapmdSelectionRegionPitchMin)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_pitch_min(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(jint, uapmdSelectionRegionPitchMax)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_pitch_max(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(jint, uapmdSelectionRegionNoteGroup)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_note_group(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(jint, uapmdSelectionRegionNoteChannel)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_note_channel(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(jstring, uapmdSelectionRegionLaneNoteId)(JNIEnv* env, jclass, jlong h, jint r) {
    return jstring_or_null(env, uapmd_selection_region_lane_note_id(SEL(h), static_cast<uint32_t>(r)));
}
JNI_FN(jint, uapmdSelectionRegionLaneGroup)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_lane_group(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(jint, uapmdSelectionRegionLaneChannel)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_lane_channel(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(jint, uapmdSelectionRegionLaneStatus)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_lane_status(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(jint, uapmdSelectionRegionLaneParameter)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_lane_parameter(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(jboolean, uapmdSelectionRegionHasValues)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_has_values(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(jdouble, uapmdSelectionRegionValueMin)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_value_min(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(jdouble, uapmdSelectionRegionValueMax)(JNIEnv*, jclass, jlong h, jint r) {
    return uapmd_selection_region_value_max(SEL(h), static_cast<uint32_t>(r));
}

JNI_FN(jlong, uapmdSelectionCreate)(JNIEnv*, jclass) { return p2j(uapmd_selection_create()); }
JNI_FN(void, uapmdSelectionAddObject)(JNIEnv* env, jclass, jlong h, jstring id) {
    JStr s(env, id);
    uapmd_selection_add_object(SEL(h), s.get());
}
JNI_FN(jint, uapmdSelectionAddTimelineRegion)(JNIEnv*, jclass, jlong h, jdouble start, jdouble end) {
    return static_cast<jint>(uapmd_selection_add_timeline_region(SEL(h), start, end));
}
JNI_FN(void, uapmdSelectionRegionRestrictTracks)(JNIEnv*, jclass, jlong h, jint r) {
    uapmd_selection_region_restrict_tracks(SEL(h), static_cast<uint32_t>(r));
}
JNI_FN(void, uapmdSelectionRegionAddTrack)(JNIEnv* env, jclass, jlong h, jint r, jstring trackId) {
    JStr s(env, trackId);
    uapmd_selection_region_add_track(SEL(h), static_cast<uint32_t>(r), s.get());
}
JNI_FN(jint, uapmdSelectionAddMidiNoteRegion)(JNIEnv* env, jclass, jlong h, jstring clipId,
        jlong startTick, jlong endTick, jint pitchMin, jint pitchMax, jint group, jint channel) {
    JStr c(env, clipId);
    return static_cast<jint>(uapmd_selection_add_midi_note_region(
        SEL(h), c.get(), startTick, endTick, pitchMin, pitchMax, group, channel));
}
JNI_FN(jint, uapmdSelectionAddMidiAutomationRegion)(JNIEnv* env, jclass, jlong h, jstring clipId, jstring noteId,
        jint group, jint channel, jint status, jint parameter, jlong startTick, jlong endTick,
        jboolean hasValues, jdouble valueMin, jdouble valueMax) {
    JStr c(env, clipId);
    JStr n(env, noteId);
    return static_cast<jint>(uapmd_selection_add_midi_automation_region(
        SEL(h), c.get(), n.get(), group, channel, status, parameter, startTick, endTick,
        hasValues, valueMin, valueMax));
}

JNI_FN(jlong, uapmdSelectionProject)(JNIEnv*, jclass, jlong tl, jlong selection) {
    return p2j(uapmd_selection_project(j2p<uapmd_timeline_facade_t>(tl), SEL(selection)));
}
JNI_FN(void, uapmdSelectionProjectionDestroy)(JNIEnv*, jclass, jlong h) { uapmd_selection_projection_destroy(PRJ(h)); }
JNI_FN(jint, uapmdSelectionProjectionClipCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_selection_projection_clip_count(PRJ(h)));
}
JNI_FN(jstring, uapmdSelectionProjectionClipAt)(JNIEnv* env, jclass, jlong h, jint i) {
    return jstring_or_null(env, uapmd_selection_projection_clip_at(PRJ(h), static_cast<uint32_t>(i)));
}
JNI_FN(jint, uapmdSelectionProjectionTrackCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_selection_projection_track_count(PRJ(h)));
}
JNI_FN(jstring, uapmdSelectionProjectionTrackAt)(JNIEnv* env, jclass, jlong h, jint i) {
    return jstring_or_null(env, uapmd_selection_projection_track_at(PRJ(h), static_cast<uint32_t>(i)));
}
JNI_FN(jboolean, uapmdSelectionProjectionHasTimeRange)(JNIEnv*, jclass, jlong h) {
    return uapmd_selection_projection_has_time_range(PRJ(h));
}
JNI_FN(jdouble, uapmdSelectionProjectionRangeStartSeconds)(JNIEnv*, jclass, jlong h) {
    return uapmd_selection_projection_range_start_seconds(PRJ(h));
}
JNI_FN(jdouble, uapmdSelectionProjectionRangeEndSeconds)(JNIEnv*, jclass, jlong h) {
    return uapmd_selection_projection_range_end_seconds(PRJ(h));
}

} // extern "C"
