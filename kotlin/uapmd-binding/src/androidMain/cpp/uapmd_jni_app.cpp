/**
 * uapmd JNI bridge — AppModel and TransportController (c-api/uapmd-c-app.h).
 *
 * Same conventions as uapmd_jni.cpp / uapmd_jni_history.cpp: opaque C pointers
 * travel as jlong. This surface is all scalars, so no struct marshalling is
 * needed here.
 */

#include <jni.h>
#include <cstdint>

#include "c-api/uapmd-c-common.h"
#include "c-api/uapmd-c-app.h"

namespace {

template <typename T>
inline jlong p2j(T ptr) { return static_cast<jlong>(reinterpret_cast<uintptr_t>(ptr)); }

template <typename T>
inline T j2p(jlong handle) { return reinterpret_cast<T>(static_cast<uintptr_t>(handle)); }

inline uapmd_app_model_t AM(jlong h) { return j2p<uapmd_app_model_t>(h); }
inline uapmd_transport_controller_t TC(jlong h) { return j2p<uapmd_transport_controller_t>(h); }

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

#undef JNI_FN

} // extern "C"
