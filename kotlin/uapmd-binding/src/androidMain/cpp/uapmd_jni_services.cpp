/**
 * uapmd JNI bridge — audio workers, the Virtual MIDI Devices addin, addin panels
 * and Augene2 (uapmd f5d490d).
 */

#include <jni.h>
#include <cstdint>
#include <string>

#include "c-api/uapmd-c-common.h"
#include "c-api/uapmd-c-engine.h"
#include "c-api/uapmd-c-app.h"
#include "c-api/uapmd-c-addin.h"
#include "c-api/uapmd-c-augene2.h"

/* Provided by uapmd_jni.cpp (C++ linkage, matching uapmd_jni_history.cpp). */
extern JNIEnv* uapmd_jni_env();

namespace {

template <typename T>
inline jlong p2j(T ptr) { return static_cast<jlong>(reinterpret_cast<uintptr_t>(ptr)); }

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

template <typename Fn>
jstring cstr(JNIEnv* env, Fn fn) {
    size_t n = fn(nullptr, 0);
    if (!n) return env->NewStringUTF("");
    std::string buf(n, '\0');
    fn(buf.data(), n);
    if (!buf.empty() && buf.back() == '\0') buf.pop_back();
    return env->NewStringUTF(buf.c_str());
}

inline uapmd_sequencer_engine_t E(jlong h) { return j2p<uapmd_sequencer_engine_t>(h); }
inline uapmd_app_model_t AM(jlong h) { return j2p<uapmd_app_model_t>(h); }
inline uapmd_augene2_integration_t AI(jlong h) { return j2p<uapmd_augene2_integration_t>(h); }

/* AppModel::showVirtualMidiDevices holds one handler; so does this. */
jobject g_show_virtual_midi_devices = nullptr;

void show_virtual_midi_devices_trampoline(void*) {
    JNIEnv* env = uapmd_jni_env();
    if (!env || !g_show_virtual_midi_devices) return;
    jclass cls = env->GetObjectClass(g_show_virtual_midi_devices);
    jmethodID run = env->GetMethodID(cls, "run", "()V");
    env->DeleteLocalRef(cls);
    if (run) env->CallVoidMethod(g_show_virtual_midi_devices, run);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

} // namespace

extern "C" {

#define JNI_FN(ret, name) JNIEXPORT ret JNICALL Java_dev_atsushieno_uapmd_JniBridge_##name

JNI_FN(jboolean, uapmdMidiApiSupportsDynamicUmpEndpoints)(JNIEnv* env, jclass, jstring apiName) {
    JStr a(env, apiName);
    return uapmd_midi_api_supports_dynamic_ump_endpoints(a.c_str());
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Audio workers
 * ═══════════════════════════════════════════════════════════════════════════ */

JNI_FN(jboolean, uapmdEngineAudioWorkersConfigure)(JNIEnv*, jclass, jlong engine, jint count) {
    return uapmd_engine_audio_workers_configure(E(engine), static_cast<uint32_t>(count));
}
JNI_FN(jint, uapmdEngineAudioWorkersCount)(JNIEnv*, jclass, jlong engine) {
    return static_cast<jint>(uapmd_engine_audio_workers_count(E(engine)));
}
JNI_FN(jboolean, uapmdEngineAudioWorkersStopOnDeadline)(JNIEnv*, jclass, jlong engine) {
    return uapmd_engine_audio_workers_stop_on_deadline(E(engine));
}
JNI_FN(void, uapmdEngineAudioWorkersSetStopOnDeadline)(JNIEnv*, jclass, jlong engine, jboolean enabled) {
    uapmd_engine_audio_workers_set_stop_on_deadline(E(engine), enabled);
}
JNI_FN(void, uapmdEngineAudioWorkersWait)(JNIEnv*, jclass, jlong engine) {
    uapmd_engine_audio_workers_wait(E(engine));
}
JNI_FN(jint, uapmdEngineAudioWorkersFault)(JNIEnv*, jclass, jlong engine) {
    return static_cast<jint>(uapmd_engine_audio_workers_fault(E(engine)));
}
JNI_FN(void, uapmdEngineAudioWorkersResetFault)(JNIEnv*, jclass, jlong engine) {
    uapmd_engine_audio_workers_reset_fault(E(engine));
}
JNI_FN(jint, uapmdEngineDroppedPluginParameterNotificationCount)(JNIEnv*, jclass, jlong engine) {
    return static_cast<jint>(uapmd_engine_dropped_plugin_parameter_notification_count(E(engine)));
}
JNI_FN(jint, uapmdEngineDroppedPluginPresetRequestCount)(JNIEnv*, jclass, jlong engine) {
    return static_cast<jint>(uapmd_engine_dropped_plugin_preset_request_count(E(engine)));
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Virtual MIDI Devices addin, document provider
 * ═══════════════════════════════════════════════════════════════════════════ */

JNI_FN(void, uapmdAppRegisterVirtualMidiDevicesAddin)(JNIEnv*, jclass) {
    uapmd_app_register_virtual_midi_devices_addin();
}
JNI_FN(void, uapmdAddinManagerRegisterAppModel)(JNIEnv*, jclass, jlong mgr, jlong app) {
    uapmd_addin_manager_register_app_model(j2p<uapmd_addin_manager_t>(mgr), AM(app));
}
JNI_FN(jboolean, uapmdAppVirtualMidiDevicesEnabled)(JNIEnv*, jclass, jlong app) {
    return uapmd_app_virtual_midi_devices_enabled(AM(app));
}
JNI_FN(jboolean, uapmdAppAutoCreateVirtualMidiDevices)(JNIEnv*, jclass, jlong app) {
    return uapmd_app_auto_create_virtual_midi_devices(AM(app));
}
JNI_FN(void, uapmdAppSetAutoCreateVirtualMidiDevices)(JNIEnv*, jclass, jlong app, jboolean enabled) {
    uapmd_app_set_auto_create_virtual_midi_devices(AM(app), enabled);
}
/** callback: a java.lang.Runnable, or null to clear. */
JNI_FN(void, uapmdAppSetShowVirtualMidiDevicesCallback)(JNIEnv* env, jclass, jlong app, jobject callback) {
    uapmd_app_set_show_virtual_midi_devices_callback(AM(app), nullptr, nullptr);
    if (g_show_virtual_midi_devices) {
        env->DeleteGlobalRef(g_show_virtual_midi_devices);
        g_show_virtual_midi_devices = nullptr;
    }
    if (!callback) return;
    g_show_virtual_midi_devices = env->NewGlobalRef(callback);
    uapmd_app_set_show_virtual_midi_devices_callback(AM(app), nullptr, show_virtual_midi_devices_trampoline);
}
JNI_FN(jlong, uapmdAppDocumentProvider)(JNIEnv*, jclass, jlong app) {
    return p2j(uapmd_app_document_provider(AM(app)));
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Project commands, panels
 * ═══════════════════════════════════════════════════════════════════════════ */

JNI_FN(void, uapmdAddinManagerRegisterProjectCommandRegistry)(JNIEnv*, jclass, jlong mgr, jlong reg) {
    uapmd_addin_manager_register_project_command_registry(j2p<uapmd_addin_manager_t>(mgr),
                                                           j2p<uapmd_command_registry_t>(reg));
}
JNI_FN(jlong, uapmdPanelRegistryCreate)(JNIEnv*, jclass) {
    return p2j(uapmd_panel_registry_create());
}
JNI_FN(void, uapmdPanelRegistryDestroy)(JNIEnv*, jclass, jlong reg) {
    uapmd_panel_registry_destroy(j2p<uapmd_panel_registry_t>(reg));
}
JNI_FN(void, uapmdAddinManagerRegisterPanelRegistry)(JNIEnv*, jclass, jlong mgr, jlong reg) {
    uapmd_addin_manager_register_panel_registry(j2p<uapmd_addin_manager_t>(mgr),
                                                j2p<uapmd_panel_registry_t>(reg));
}
JNI_FN(void, uapmdPanelRegistryUpdate)(JNIEnv*, jclass, jlong reg) {
    uapmd_panel_registry_update(j2p<uapmd_panel_registry_t>(reg));
}
JNI_FN(void, uapmdPanelRegistryClearRetainedPanels)(JNIEnv*, jclass, jlong reg) {
    uapmd_panel_registry_clear_retained_panels(j2p<uapmd_panel_registry_t>(reg));
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Augene2
 * ═══════════════════════════════════════════════════════════════════════════ */

JNI_FN(jboolean, uapmdAugene2Available)(JNIEnv*, jclass) {
    return uapmd_augene2_available();
}
JNI_FN(void, uapmdAugene2RegisterProjectService)(JNIEnv*, jclass, jlong timeline, jlong panels) {
    uapmd_augene2_register_project_service(j2p<uapmd_timeline_facade_t>(timeline),
                                           j2p<uapmd_panel_registry_t>(panels));
}
JNI_FN(jlong, uapmdAugene2Integration)(JNIEnv*, jclass) {
    return p2j(uapmd_augene2_integration());
}
JNI_FN(void, uapmdAugene2IntegrationRelease)(JNIEnv*, jclass, jlong h) {
    uapmd_augene2_integration_release(AI(h));
}
JNI_FN(jboolean, uapmdAugene2IntegrationIsOpen)(JNIEnv*, jclass, jlong h) {
    return uapmd_augene2_integration_is_open(AI(h));
}
JNI_FN(void, uapmdAugene2IntegrationSetOpen)(JNIEnv*, jclass, jlong h, jboolean open) {
    uapmd_augene2_integration_set_open(AI(h), open);
}
JNI_FN(jboolean, uapmdAugene2IntegrationBusy)(JNIEnv*, jclass, jlong h) {
    return uapmd_augene2_integration_busy(AI(h));
}
JNI_FN(jboolean, uapmdAugene2IntegrationCompiling)(JNIEnv*, jclass, jlong h) {
    return uapmd_augene2_integration_compiling(AI(h));
}
JNI_FN(jint, uapmdAugene2IntegrationSourceCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_augene2_integration_source_count(AI(h)));
}
/** Fills outStrings[0..1] = {path, externalPath}; returns {compile} or null. */
JNI_FN(jintArray, uapmdAugene2IntegrationGetSource)(JNIEnv* env, jclass, jlong h, jint index, jobjectArray outStrings) {
    uapmd_augene2_source_t source{};
    if (!uapmd_augene2_integration_get_source(AI(h), static_cast<uint32_t>(index), &source))
        return nullptr;
    env->SetObjectArrayElement(outStrings, 0, env->NewStringUTF(source.path ? source.path : ""));
    env->SetObjectArrayElement(outStrings, 1, env->NewStringUTF(source.external_path ? source.external_path : ""));
    jint flags[1] = {source.compile ? 1 : 0};
    jintArray result = env->NewIntArray(1);
    env->SetIntArrayRegion(result, 0, 1, flags);
    return result;
}
JNI_FN(jint, uapmdAugene2IntegrationTrackMappingCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_augene2_integration_track_mapping_count(AI(h)));
}
/** Fills outStrings[0] = key; returns {trackIndex} or null. */
JNI_FN(jintArray, uapmdAugene2IntegrationGetTrackMapping)(JNIEnv* env, jclass, jlong h, jint index, jobjectArray outStrings) {
    uapmd_augene2_track_mapping_t mapping{};
    if (!uapmd_augene2_integration_get_track_mapping(AI(h), static_cast<uint32_t>(index), &mapping))
        return nullptr;
    env->SetObjectArrayElement(outStrings, 0, env->NewStringUTF(mapping.key ? mapping.key : ""));
    jint values[1] = {mapping.track_index};
    jintArray result = env->NewIntArray(1);
    env->SetIntArrayRegion(result, 0, 1, values);
    return result;
}
JNI_FN(jstring, uapmdAugene2IntegrationStatus)(JNIEnv* env, jclass, jlong h) {
    return cstr(env, [&](char* buf, size_t size) { return uapmd_augene2_integration_status(AI(h), buf, size); });
}
JNI_FN(jint, uapmdAugene2IntegrationDiagnosticCount)(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(uapmd_augene2_integration_diagnostic_count(AI(h)));
}
JNI_FN(jstring, uapmdAugene2IntegrationGetDiagnostic)(JNIEnv* env, jclass, jlong h, jint index) {
    return cstr(env, [&](char* buf, size_t size) {
        return uapmd_augene2_integration_get_diagnostic(AI(h), static_cast<uint32_t>(index), buf, size);
    });
}
JNI_FN(jstring, uapmdAugene2IntegrationResourceFolder)(JNIEnv* env, jclass, jlong h) {
    return cstr(env, [&](char* buf, size_t size) { return uapmd_augene2_integration_resource_folder(AI(h), buf, size); });
}
JNI_FN(void, uapmdAugene2IntegrationSetResourceFolder)(JNIEnv* env, jclass, jlong h, jstring folder) {
    JStr f(env, folder);
    uapmd_augene2_integration_set_resource_folder(AI(h), f.c_str());
}
JNI_FN(void, uapmdAugene2IntegrationImportSources)(JNIEnv*, jclass, jlong h, jboolean compile) {
    uapmd_augene2_integration_import_sources(AI(h), compile);
}
JNI_FN(void, uapmdAugene2IntegrationRelinkSource)(JNIEnv* env, jclass, jlong h, jstring path) {
    JStr p(env, path);
    uapmd_augene2_integration_relink_source(AI(h), p.c_str());
}
JNI_FN(void, uapmdAugene2IntegrationRemoveSource)(JNIEnv* env, jclass, jlong h, jstring path) {
    JStr p(env, path);
    uapmd_augene2_integration_remove_source(AI(h), p.c_str());
}
JNI_FN(void, uapmdAugene2IntegrationCompile)(JNIEnv*, jclass, jlong h) {
    uapmd_augene2_integration_compile(AI(h));
}

} // extern "C"
