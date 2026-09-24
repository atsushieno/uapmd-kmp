/* uapmd C API — implementation for AppModel bindings */

#include "c-api/uapmd-c-app.h"
#include "c-api-internal.h"
#include <uapmd-app-model/uapmd-app-model.hpp>
#include <uapmd-addin-core/uapmd-addin-core.hpp>
#include <uapmd-midi-service/uapmd-midi-service.hpp>
#include <uapmd-plugin-hosting/uapmd-plugin-hosting.hpp>
#include <algorithm>
#include <cstring>
#include <deque>
#include <mutex>
#include <unordered_map>
#include <filesystem>
#include <future>
#include <string>
#include <vector>

/* ── Cast helpers ─────────────────────────────────────────────────────────── */

static uapmd_app::AppModel*             AM(uapmd_app_model_t h)            { return reinterpret_cast<uapmd_app::AppModel*>(h); }
static uapmd_app::TransportController*   TC(uapmd_transport_controller_t h) { return reinterpret_cast<uapmd_app::TransportController*>(h); }

static size_t copy_string(const std::string& src, char* buf, size_t buf_size) {
    size_t required = src.size() + 1;
    if (!buf || buf_size == 0)
        return required;
    size_t to_copy = (src.size() < buf_size) ? src.size() : (buf_size - 1);
    std::memcpy(buf, src.data(), to_copy);
    buf[to_copy] = '\0';
    return to_copy;
}

static uapmd::TimelinePosition to_cpp(uapmd_timeline_position_t p) {
    uapmd::TimelinePosition pos;
    pos.samples = p.samples;
    pos.legacy_beats = p.legacy_beats;
    return pos;
}

static uapmd_timeline_position_t to_c(const uapmd::TimelinePosition& p) {
    return { p.samples, p.legacy_beats };
}

static thread_local std::string tl_error;
static thread_local std::string tl_error2;

/* ═══════════════════════════════════════════════════════════════════════════
 *  Lifecycle
 * ═══════════════════════════════════════════════════════════════════════════ */

void uapmd_app_instantiate() { uapmd_app::AppModel::instantiate(); }

uapmd_app_model_t uapmd_app_instance() {
    return reinterpret_cast<uapmd_app_model_t>(&uapmd_app::AppModel::instance());
}

void uapmd_app_cleanup() { uapmd_app::AppModel::cleanupInstance(); }

/* ═══════════════════════════════════════════════════════════════════════════
 *  Accessors
 * ═══════════════════════════════════════════════════════════════════════════ */

uapmd_realtime_sequencer_t uapmd_app_sequencer(uapmd_app_model_t app) {
    return reinterpret_cast<uapmd_realtime_sequencer_t>(&AM(app)->sequencer());
}

uapmd_transport_controller_t uapmd_app_transport(uapmd_app_model_t app) {
    return reinterpret_cast<uapmd_transport_controller_t>(&AM(app)->transport());
}

uapmd_document_provider_t uapmd_app_document_provider(uapmd_app_model_t app) {
    return reinterpret_cast<uapmd_document_provider_t>(AM(app)->documentProvider());
}

/*
 * The error string must outlive the callback, which fires after this returns; the
 * caller only borrows it for the duration of the call, as everywhere else here.
 */
void uapmd_app_save_project_to_document(uapmd_app_model_t app,
                                          const uapmd_document_handle_t* handle,
                                          void* user_data,
                                          uapmd_write_callback_t callback) {
    uapmd::DocumentHandle h;
    if (handle) {
        h.id = handle->id ? handle->id : "";
        h.display_name = handle->display_name ? handle->display_name : "";
        h.mime_type = handle->mime_type ? handle->mime_type : "";
    }
    AM(app)->saveProjectToDocument(
        std::move(h),
        [user_data, callback](uapmd::DocumentIOResult result) {
            if (!callback)
                return;
            thread_local std::string error;
            error = result.error;
            uapmd_document_io_result_t c{ result.success, error.empty() ? nullptr : error.c_str() };
            callback(c, user_data);
        });
}

void uapmd_set_remote_scanner_executable(const char* path) {
    uapmd_plugin_hosting::setRemoteScannerExecutable(path && *path ? std::filesystem::path(path)
                                                                   : std::filesystem::path{});
}

static thread_local std::string tl_current_bundle;

uapmd_slow_scan_progress_t uapmd_app_slow_scan_progress(uapmd_app_model_t app) {
    auto state = AM(app)->slowScanProgress();
    tl_current_bundle = state.currentBundle;
    return {
        state.running,
        state.processedBundles,
        state.totalBundles,
        tl_current_bundle.c_str()
    };
}

size_t uapmd_app_last_plugin_scan_error(uapmd_app_model_t app, char* buf, size_t buf_size) {
    return copy_string(AM(app)->lastPluginScanError(), buf, buf_size);
}

int32_t uapmd_app_sample_rate(uapmd_app_model_t app) { return AM(app)->sampleRate(); }
uint32_t uapmd_app_track_count(uapmd_app_model_t app) { return static_cast<uint32_t>(AM(app)->trackCount()); }

/* ═══════════════════════════════════════════════════════════════════════════
 *  Audio engine control
 * ═══════════════════════════════════════════════════════════════════════════ */

bool uapmd_app_is_scanning(uapmd_app_model_t app)                  { return AM(app)->isScanning(); }
bool uapmd_app_is_audio_engine_enabled(uapmd_app_model_t app)      { return AM(app)->isAudioEngineEnabled(); }
void uapmd_app_set_audio_engine_enabled(uapmd_app_model_t app, bool en) { AM(app)->setAudioEngineEnabled(en); }
void uapmd_app_toggle_audio_engine(uapmd_app_model_t app)          { AM(app)->toggleAudioEngine(); }
void uapmd_app_update_audio_device_settings(uapmd_app_model_t app, int32_t sr, uint32_t bs) { AM(app)->updateAudioDeviceSettings(sr, bs); }
void uapmd_app_set_auto_buffer_size_enabled(uapmd_app_model_t app, bool en) { AM(app)->setAutoBufferSizeEnabled(en); }
bool uapmd_app_auto_buffer_size_enabled(uapmd_app_model_t app)     { return AM(app)->autoBufferSizeEnabled(); }

/* ═══════════════════════════════════════════════════════════════════════════
 *  Plugin scanning
 * ═══════════════════════════════════════════════════════════════════════════ */

void uapmd_app_perform_plugin_scanning(uapmd_app_model_t app,
                                        bool force_rescan,
                                        uapmd_plugin_scan_request_t request,
                                        double remote_timeout_seconds,
                                        bool require_fast_scanning) {
    AM(app)->performPluginScanning(force_rescan,
        static_cast<uapmd_app::AppModel::PluginScanRequest>(request),
        remote_timeout_seconds, require_fast_scanning);
}

void uapmd_app_cancel_plugin_scanning(uapmd_app_model_t app) { AM(app)->cancelPluginScanning(); }
void uapmd_app_stop_plugin_scanning(uapmd_app_model_t app) { AM(app)->stopPluginScanning(); }

size_t uapmd_app_generate_scan_report(uapmd_app_model_t app, char* buf, size_t buf_size) {
    auto report = AM(app)->generateScanReport();
    return copy_string(report, buf, buf_size);
}

/*
 * The blocklist strings are owned by uapmd, so a snapshot is cached here and the
 * returned pointers stay valid until the next call on the same model - the same
 * lifetime rule the other list accessors in this file use.
 */
namespace {
std::vector<uapmd_plugin_hosting::BlocklistEntry> s_blocklist_snapshot;
std::mutex s_blocklist_mutex;
}

namespace {
uapmd_app::AppModel::MasterTrackSnapshot s_master_snapshot;
std::mutex s_master_snapshot_mutex;
}

double uapmd_app_refresh_master_tempo_map(uapmd_app_model_t app) {
    if (!app) return 0.0;
    std::lock_guard lock(s_master_snapshot_mutex);
    s_master_snapshot = AM(app)->buildMasterTrackSnapshot();
    return s_master_snapshot.maxTimeSeconds;
}

uint32_t uapmd_app_master_tempo_point_count(uapmd_app_model_t) {
    std::lock_guard lock(s_master_snapshot_mutex);
    return static_cast<uint32_t>(s_master_snapshot.tempoPoints.size());
}

bool uapmd_app_get_master_tempo_point(uapmd_app_model_t, uint32_t index, uapmd_tempo_point_t* out) {
    if (!out) return false;
    std::lock_guard lock(s_master_snapshot_mutex);
    if (index >= s_master_snapshot.tempoPoints.size()) return false;
    const auto& p = s_master_snapshot.tempoPoints[index];
    out->time_seconds = p.timeSeconds;
    out->tick_position = p.tickPosition;
    out->bpm = p.bpm;
    return true;
}

uint32_t uapmd_app_master_time_signature_count(uapmd_app_model_t) {
    std::lock_guard lock(s_master_snapshot_mutex);
    return static_cast<uint32_t>(s_master_snapshot.timeSignaturePoints.size());
}

bool uapmd_app_get_master_time_signature(uapmd_app_model_t, uint32_t index,
                                         uapmd_time_signature_point_t* out) {
    if (!out) return false;
    std::lock_guard lock(s_master_snapshot_mutex);
    if (index >= s_master_snapshot.timeSignaturePoints.size()) return false;
    const auto& p = s_master_snapshot.timeSignaturePoints[index];
    out->time_seconds = p.timeSeconds;
    out->tick_position = p.tickPosition;
    out->numerator = p.signature.numerator;
    out->denominator = p.signature.denominator;
    return true;
}

uint32_t uapmd_app_blocklist_count(uapmd_app_model_t app) {
    if (!app) return 0;
    std::lock_guard lock(s_blocklist_mutex);
    s_blocklist_snapshot = AM(app)->pluginBlocklist();
    return static_cast<uint32_t>(s_blocklist_snapshot.size());
}

bool uapmd_app_get_blocklist_entry(uapmd_app_model_t app, uint32_t index,
                                   uapmd_blocklist_entry_t* out) {
    if (!app || !out) return false;
    std::lock_guard lock(s_blocklist_mutex);
    if (index >= s_blocklist_snapshot.size()) return false;
    const auto& e = s_blocklist_snapshot[index];
    out->id = e.id.c_str();
    out->format = e.format.c_str();
    out->plugin_id = e.pluginId.c_str();
    out->reason = e.reason.c_str();
    return true;
}

bool uapmd_app_unblock_plugin_from_blocklist(uapmd_app_model_t app, const char* entry_id) {
    if (!app || !entry_id) return false;
    return AM(app)->unblockPluginFromBlocklist(entry_id);
}

void uapmd_app_clear_plugin_blocklist(uapmd_app_model_t app) { AM(app)->clearPluginBlocklist(); }

/* ═══════════════════════════════════════════════════════════════════════════
 *  Plugin instance management
 * ═══════════════════════════════════════════════════════════════════════════ */

void uapmd_app_create_plugin_instance(uapmd_app_model_t app,
                                       const char* format,
                                       const char* plugin_id,
                                       int32_t track_index,
                                       const uapmd_plugin_instance_config_t* config,
                                       void* user_data,
                                       uapmd_instance_created_cb_t callback) {
    uapmd_app::AppModel::PluginInstanceConfig cfg;
    if (config) {
        if (config->api_name) cfg.apiName = config->api_name;
        if (config->device_name) cfg.deviceName = config->device_name;
        if (config->manufacturer) cfg.manufacturer = config->manufacturer;
        if (config->version) cfg.version = config->version;
        if (config->state_file) cfg.stateFile = config->state_file;
    }

    AM(app)->createPluginInstanceAsync(format, plugin_id, track_index, cfg,
        [callback, user_data](const uapmd_app::AppModel::PluginInstanceResult& r) {
            if (!callback) return;
            uapmd_plugin_instance_result_t cr;
            cr.instance_id = r.instanceId;
            cr.plugin_name = r.pluginName.c_str();
            cr.error = r.error.empty() ? nullptr : r.error.c_str();
            callback(cr, user_data);
        });
}

void uapmd_app_remove_plugin_instance(uapmd_app_model_t app, int32_t instance_id) {
    AM(app)->removePluginInstance(instance_id);
}

uint8_t uapmd_app_get_instance_group(uapmd_app_model_t app, int32_t instance_id) {
    return AM(app)->getInstanceGroup(instance_id);
}

bool uapmd_app_set_instance_group(uapmd_app_model_t app, int32_t instance_id, uint8_t group) {
    return AM(app)->setInstanceGroup(instance_id, group);
}

void uapmd_app_enable_ump_device(uapmd_app_model_t app, int32_t instance_id, const char* device_name) {
    AM(app)->enableUmpDevice(instance_id, device_name ? device_name : "");
}

void uapmd_app_disable_ump_device(uapmd_app_model_t app, int32_t instance_id) {
    AM(app)->disableUmpDevice(instance_id);
}

void uapmd_app_register_virtual_midi_devices_addin(void) {
    uapmd_app::registerVirtualMidiDevicesAddin();
}

void uapmd_addin_manager_register_app_model(uapmd_addin_manager_t mgr, uapmd_app_model_t app) {
    if (!mgr || !app)
        return;
    reinterpret_cast<uapmd_addin::AddinManager*>(mgr)->registerExtensionPoint("/uapmd/app/model/v1", AM(app));
}

bool uapmd_app_virtual_midi_devices_enabled(uapmd_app_model_t app) {
    return AM(app)->virtualMidiDevicesEnabled();
}

bool uapmd_app_auto_create_virtual_midi_devices(uapmd_app_model_t app) {
    return AM(app)->autoCreateVirtualMidiDevices();
}

void uapmd_app_set_auto_create_virtual_midi_devices(uapmd_app_model_t app, bool enabled) {
    AM(app)->setAutoCreateVirtualMidiDevices(enabled);
}

void uapmd_app_set_show_virtual_midi_devices_callback(uapmd_app_model_t app,
                                                       void* user_data,
                                                       uapmd_app_show_virtual_midi_devices_cb_t callback) {
    if (!callback) {
        AM(app)->showVirtualMidiDevices = {};
        return;
    }
    AM(app)->showVirtualMidiDevices = [callback, user_data]() { callback(user_data); };
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Plugin UI
 * ═══════════════════════════════════════════════════════════════════════════ */

void uapmd_app_request_show_plugin_ui(uapmd_app_model_t app, int32_t instance_id) {
    AM(app)->requestShowPluginUI(instance_id);
}

void uapmd_app_show_plugin_ui(uapmd_app_model_t app,
                                int32_t instance_id,
                                bool needs_create,
                                bool is_floating,
                                void* parent_handle,
                                void* resize_user_data,
                                uapmd_ui_resize_handler_t resize_handler) {
    AM(app)->showPluginUI(instance_id, needs_create, is_floating, parent_handle,
        [resize_handler, resize_user_data](uint32_t w, uint32_t h) -> bool {
            if (resize_handler)
                return resize_handler(w, h, resize_user_data);
            return true;
        });
}

void uapmd_app_hide_plugin_ui(uapmd_app_model_t app, int32_t instance_id) {
    AM(app)->hidePluginUI(instance_id);
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Plugin state save/load
 * ═══════════════════════════════════════════════════════════════════════════ */

void uapmd_app_load_plugin_state(uapmd_app_model_t app,
                                   int32_t instance_id,
                                   const char* filepath,
                                   void* user_data,
                                   uapmd_plugin_state_cb_t callback) {
    AM(app)->loadPluginState(instance_id, filepath,
        [callback, user_data](uapmd_app::AppModel::PluginStateResult r) {
            if (!callback) return;
            uapmd_plugin_state_result_t cr;
            cr.instance_id = r.instanceId;
            cr.success = r.success;
            cr.error = r.error.empty() ? nullptr : r.error.c_str();
            cr.filepath = r.filepath.empty() ? nullptr : r.filepath.c_str();
            callback(cr, user_data);
        });
}

void uapmd_app_save_plugin_state(uapmd_app_model_t app,
                                   int32_t instance_id,
                                   const char* filepath,
                                   void* user_data,
                                   uapmd_plugin_state_cb_t callback) {
    AM(app)->savePluginState(instance_id, filepath,
        [callback, user_data](uapmd_app::AppModel::PluginStateResult r) {
            if (!callback) return;
            uapmd_plugin_state_result_t cr;
            cr.instance_id = r.instanceId;
            cr.success = r.success;
            cr.error = r.error.empty() ? nullptr : r.error.c_str();
            cr.filepath = r.filepath.empty() ? nullptr : r.filepath.c_str();
            callback(cr, user_data);
        });
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Clip management
 * ═══════════════════════════════════════════════════════════════════════════ */

static uapmd_clip_add_result_t to_c_clip_result(const uapmd_app::AppModel::ClipAddResult& r) {
    tl_error = r.error;
    return { r.clipId, r.sourceNodeId, r.success, tl_error.empty() ? nullptr : tl_error.c_str() };
}

uapmd_clip_add_result_t uapmd_app_add_clip_to_track(uapmd_app_model_t app,
                                                      int32_t track_index,
                                                      uapmd_timeline_position_t position,
                                                      uapmd_audio_file_reader_t reader,
                                                      const char* filepath) {
    /* Transfer ownership from the C registry */

    auto* raw = reinterpret_cast<uapmd::AudioFileReader*>(reader);
    std::unique_ptr<uapmd::AudioFileReader> owned;
    {
        std::lock_guard lock(s_reader_mutex);
        auto it = s_owned_readers.find(raw);
        if (it != s_owned_readers.end()) {
            owned = std::move(it->second);
            s_owned_readers.erase(it);
        }
    }
    if (!owned)
        return { -1, -1, false, "reader not found or already consumed" };

    auto r = AM(app)->addClipToTrack(track_index, to_cpp(position), std::move(owned), filepath ? filepath : "");
    return to_c_clip_result(r);
}

void uapmd_app_import_midi_tracks_from_file(uapmd_app_model_t app,
                                            const char* filepath,
                                            void* user_data,
                                            uapmd_midi_tracks_import_cb_t callback) {
    if (!app || !callback) return;
    AM(app)->importMidiTracksFromFile(
        filepath ? filepath : "",
        [callback, user_data](uapmd_app::AppModel::MidiTracksImportResult result) {
            callback(result.success,
                     result.error.empty() ? nullptr : result.error.c_str(),
                     static_cast<uint32_t>(result.importedTracks.size()),
                     user_data);
        });
}

uapmd_clip_add_result_t uapmd_app_add_midi_clip_to_track(uapmd_app_model_t app,
                                                           int32_t track_index,
                                                           uapmd_timeline_position_t position,
                                                           const char* filepath) {
    auto r = AM(app)->addMidiClipToTrack(track_index, to_cpp(position), filepath);
    return to_c_clip_result(r);
}

uapmd_clip_add_result_t uapmd_app_add_midi_clip_from_data(uapmd_app_model_t app,
                                                            int32_t track_index,
                                                            uapmd_timeline_position_t position,
                                                            const uapmd_ump_t* ump_events,
                                                            uint32_t ump_event_count,
                                                            const uint64_t* tick_timestamps,
                                                            uint32_t tick_count,
                                                            uint32_t tick_resolution,
                                                            double clip_tempo,
                                                            const uapmd_midi_tempo_change_t* tempo_changes,
                                                            uint32_t tempo_change_count,
                                                            const uapmd_midi_time_sig_change_t* time_sig_changes,
                                                            uint32_t time_sig_change_count,
                                                            const char* clip_name,
                                                            bool needs_file_save) {
    std::vector<uapmd_ump_t> ump(ump_events, ump_events + ump_event_count);
    std::vector<uint64_t> ticks(tick_timestamps, tick_timestamps + tick_count);
    std::vector<uapmd::MidiTempoChange> tc(tempo_change_count);
    for (uint32_t i = 0; i < tempo_change_count; ++i) {
        tc[i].tickPosition = tempo_changes[i].tick_position;
        tc[i].bpm = tempo_changes[i].bpm;
    }
    std::vector<uapmd::MidiTimeSignatureChange> tsc(time_sig_change_count);
    for (uint32_t i = 0; i < time_sig_change_count; ++i) {
        tsc[i].tickPosition = time_sig_changes[i].tick_position;
        tsc[i].numerator = time_sig_changes[i].numerator;
        tsc[i].denominator = time_sig_changes[i].denominator;
        tsc[i].clocksPerClick = time_sig_changes[i].clocks_per_click;
        tsc[i].thirtySecondsPerQuarter = time_sig_changes[i].thirty_seconds_per_quarter;
    }

    auto r = AM(app)->addMidiClipToTrack(track_index, to_cpp(position),
        std::move(ump), std::move(ticks), tick_resolution, clip_tempo,
        std::move(tc), std::move(tsc),
        clip_name ? clip_name : "", needs_file_save);
    return to_c_clip_result(r);
}

uapmd_clip_add_result_t uapmd_app_create_empty_midi_clip(uapmd_app_model_t app,
                                                           int32_t track_index,
                                                           int64_t position_samples,
                                                           uint32_t tick_resolution,
                                                           double bpm) {
    auto r = AM(app)->createEmptyMidiClip(track_index, position_samples, tick_resolution, bpm);
    return to_c_clip_result(r);
}

bool uapmd_app_remove_clip_from_track(uapmd_app_model_t app, int32_t track_index, int32_t clip_id) {
    return AM(app)->removeClipFromTrack(track_index, clip_id);
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Track management
 * ═══════════════════════════════════════════════════════════════════════════ */

bool uapmd_app_is_track_muted(uapmd_app_model_t app, int32_t track_index) {
    return app && AM(app)->isTrackMuted(track_index);
}

bool uapmd_app_is_track_solo(uapmd_app_model_t app, int32_t track_index) {
    return app && AM(app)->isTrackSolo(track_index);
}

bool uapmd_app_set_track_muted(uapmd_app_model_t app, int32_t track_index, bool muted) {
    return app && AM(app)->setTrackMuted(track_index, muted);
}

bool uapmd_app_set_track_solo(uapmd_app_model_t app, int32_t track_index, bool solo) {
    return app && AM(app)->setTrackSolo(track_index, solo);
}

void uapmd_app_add_track(uapmd_app_model_t app, void* user_data, uapmd_track_mutation_cb_t callback) {
    if (!callback) return;
    AM(app)->addTrack([user_data, callback](int32_t track_index, std::string error) {
        callback(track_index, error.empty() ? nullptr : error.c_str(), user_data);
    });
}

void uapmd_app_remove_track(uapmd_app_model_t app, int32_t track_index,
                            void* user_data, uapmd_track_mutation_cb_t callback) {
    if (!callback) return;
    AM(app)->removeTrack(track_index, [user_data, callback](int32_t removed_index, std::string error) {
        callback(removed_index, error.empty() ? nullptr : error.c_str(), user_data);
    });
}

void uapmd_app_remove_all_tracks(uapmd_app_model_t app, void* user_data, uapmd_track_clear_cb_t callback) {
    if (!callback) return;
    AM(app)->removeAllTracks([user_data, callback](std::string error) {
        callback(error.empty() ? nullptr : error.c_str(), user_data);
    });
}

int32_t uapmd_app_add_device_input_to_track(uapmd_app_model_t app,
                                              int32_t track_index,
                                              const uint32_t* channel_indices,
                                              uint32_t channel_count) {
    std::vector<uint32_t> indices(channel_indices, channel_indices + channel_count);
    return AM(app)->addDeviceInputToTrack(track_index, indices);
}

uint32_t uapmd_app_timeline_track_count(uapmd_app_model_t app) {
    return static_cast<uint32_t>(AM(app)->getTimelineTracks().size());
}

uapmd_timeline_track_t uapmd_app_get_timeline_track(uapmd_app_model_t app, uint32_t index) {
    auto tracks = AM(app)->getTimelineTracks();
    if (index >= tracks.size()) return nullptr;
    return reinterpret_cast<uapmd_timeline_track_t>(tracks[index]);
}

uapmd_timeline_track_t uapmd_app_get_master_timeline_track(uapmd_app_model_t app) {
    return reinterpret_cast<uapmd_timeline_track_t>(AM(app)->getMasterTimelineTrack());
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Timeline state
 * ═══════════════════════════════════════════════════════════════════════════ */

/* ═══════════════════════════════════════════════════════════════════════════
 *  Assorted AppModel accessors
 * ═══════════════════════════════════════════════════════════════════════════ */

namespace {
/* Strings handed back by pointer need somewhere to live that outlives the call. */
thread_local std::deque<std::string> tl_misc_strings;
thread_local std::vector<uapmd_midi_port_info_t> tl_midi_ports;
thread_local std::vector<uapmd_device_entry_t> tl_device_entries;
thread_local std::string tl_state_error;
thread_local std::string tl_state_path;

const char* intern_misc(const std::string& v) {
    tl_misc_strings.push_back(v);
    return tl_misc_strings.back().c_str();
}

uint32_t copy_ports(const std::vector<uapmd::MidiPortInfo>& ports,
                    uapmd_midi_port_info_t* out, uint32_t out_count) {
    tl_misc_strings.clear();
    tl_midi_ports.clear();
    for (const auto& p : ports)
        tl_midi_ports.push_back({intern_misc(p.id), intern_misc(p.displayName)});
    if (!out || out_count == 0)
        return static_cast<uint32_t>(tl_midi_ports.size());
    const uint32_t n = static_cast<uint32_t>(
        tl_midi_ports.size() < out_count ? tl_midi_ports.size() : out_count);
    for (uint32_t i = 0; i < n; i++) out[i] = tl_midi_ports[i];
    return n;
}

uapmd_device_entry_t device_entry_from(int32_t id, const uapmd_app::AppModel::DeviceState& st) {
    return {
        id,
        intern_misc(st.label),
        intern_misc(st.apiName),
        intern_misc(st.statusMessage),
        st.running,
        st.instantiating,
        st.hasError
    };
}
} // namespace

uint32_t uapmd_app_get_midi_input_ports(uapmd_app_model_t app, uapmd_midi_port_info_t* out, uint32_t out_count) {
    if (!app) return 0;
    return copy_ports(AM(app)->getMidiInputPorts(), out, out_count);
}

uint32_t uapmd_app_get_midi_output_ports(uapmd_app_model_t app, uapmd_midi_port_info_t* out, uint32_t out_count) {
    if (!app) return 0;
    return copy_ports(AM(app)->getMidiOutputPorts(), out, out_count);
}

bool uapmd_app_is_track_hidden(uapmd_app_model_t app, int32_t track_index) {
    return app && AM(app)->isTrackHidden(track_index);
}

void uapmd_transport_jump(uapmd_transport_controller_t tc, double position_seconds) {
    if (tc) TC(tc)->jump(position_seconds);
}

uapmd_timeline_content_bounds_t uapmd_app_timeline_content_bounds(uapmd_app_model_t app) {
    if (!app) return {false, 0.0, 0.0, 0.0};
    const auto b = AM(app)->timelineContentBounds();
    return {b.hasContent, b.startSeconds, b.endSeconds, b.durationSeconds};
}

uint32_t uapmd_app_get_devices(uapmd_app_model_t app, uapmd_device_entry_t* out, uint32_t out_count) {
    if (!app) return 0;
    tl_misc_strings.clear();
    tl_device_entries.clear();
    for (const auto& entry : AM(app)->getDevices()) {
        if (!entry.state) continue;
        /* DeviceState carries a mutex guarding these fields. */
        std::scoped_lock lock(entry.state->mutex);
        tl_device_entries.push_back(device_entry_from(entry.id, *entry.state));
    }
    if (!out || out_count == 0)
        return static_cast<uint32_t>(tl_device_entries.size());
    const uint32_t n = static_cast<uint32_t>(
        tl_device_entries.size() < out_count ? tl_device_entries.size() : out_count);
    for (uint32_t i = 0; i < n; i++) out[i] = tl_device_entries[i];
    return n;
}

bool uapmd_app_get_device_for_instance(uapmd_app_model_t app, int32_t instance_id, uapmd_device_entry_t* out) {
    if (!app || !out) return false;
    const auto found = AM(app)->getDeviceForInstance(instance_id);
    if (!found.has_value() || !*found) return false;
    tl_misc_strings.clear();
    auto state = *found;
    std::scoped_lock lock(state->mutex);
    /* The entry id is not carried on the state, so it is looked up by identity. */
    int32_t id = -1;
    for (const auto& entry : AM(app)->getDevices())
        if (entry.state == state) { id = entry.id; break; }
    *out = device_entry_from(id, *state);
    return true;
}

void uapmd_app_update_device_label(uapmd_app_model_t app, int32_t instance_id, const char* label) {
    if (app) AM(app)->updateDeviceLabel(instance_id, label ? label : "");
}

namespace {
uapmd_plugin_state_result_t to_c_state_result(const uapmd_app::AppModel::PluginStateResult& r) {
    tl_state_error = r.error;
    tl_state_path = r.filepath;
    return {r.instanceId, r.success, tl_state_error.c_str(), tl_state_path.c_str()};
}
} // namespace

uapmd_plugin_state_result_t uapmd_app_load_plugin_state_sync(uapmd_app_model_t app, int32_t instance_id, const char* filepath) {
    if (!app) return {instance_id, false, "", ""};
    return to_c_state_result(AM(app)->loadPluginStateSync(instance_id, filepath ? filepath : ""));
}

uapmd_plugin_state_result_t uapmd_app_save_plugin_state_sync(uapmd_app_model_t app, int32_t instance_id, const char* filepath) {
    if (!app) return {instance_id, false, "", ""};
    return to_c_state_result(AM(app)->savePluginStateSync(instance_id, filepath ? filepath : ""));
}

void uapmd_app_mark_plugin_instance_track_dirty(uapmd_app_model_t app, int32_t instance_id) {
    if (app) AM(app)->markPluginInstanceTrackDirty(instance_id);
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Piano roll editing session
 * ═══════════════════════════════════════════════════════════════════════════ */

namespace {

/* The snapshot is handed out by value from AppModel, so the C side owns a copy
 * and keeps it alive for as long as the caller holds the handle. */
struct PianoRollSnapshotBox {
    uapmd_app::PianoRollClipSnapshot snapshot;
};

/* The drag originals moveNotes()/restoreNotes() replay from. uapmd-app keeps
 * these in its own window state; a C caller has nowhere to put them, so they
 * are held here, keyed by session. */
std::mutex s_piano_roll_drag_mutex;
std::unordered_map<uapmd_app::PianoRollSession*,
                   std::vector<std::pair<int, uapmd_app::PianoRollEditNote>>> s_piano_roll_drags;

uapmd_app::PianoRollSession* PRS(uapmd_piano_roll_session_t h) {
    return reinterpret_cast<uapmd_app::PianoRollSession*>(h);
}
PianoRollSnapshotBox* PRB(uapmd_piano_roll_snapshot_t h) {
    return reinterpret_cast<PianoRollSnapshotBox*>(h);
}

thread_local std::string tl_piano_roll_error;

void fill_note(const uapmd_app::PianoRollMidiNote& note, uapmd_piano_roll_note_t* out) {
    out->start_seconds = note.startSeconds;
    out->duration_seconds = note.durationSeconds;
    out->velocity = note.velocity;
    out->note = note.note;
    out->channel = note.channel;
    out->deleted = note.deleted;
    out->edit_id = 0;
    out->ump_group = 0;
    out->release_velocity = 0;
    out->attribute_type = 0;
    out->attribute_value = 0;
    out->automation_event_count = 0;
}

void fill_edit_note(const uapmd_app::PianoRollEditNote& note, uapmd_piano_roll_note_t* out) {
    fill_note(note, out);
    out->edit_id = note.edit_id;
    out->ump_group = note.ump_group;
    out->release_velocity = note.release_velocity;
    out->attribute_type = note.attributeType;
    out->attribute_value = note.attributeValue;
    out->automation_event_count = static_cast<uint32_t>(note.automationEvents.size());
}

} // namespace

uapmd_piano_roll_snapshot_t uapmd_app_piano_roll_clip_snapshot(uapmd_app_model_t app,
                                                                 int32_t track_index,
                                                                 int32_t clip_id,
                                                                 double fallback_duration_seconds) {
    if (!app) return nullptr;
    /* The snapshot is taken against the clip as the document holds it, so the
     * clip has to be looked up rather than passed in: a caller holding a stale
     * copy would parse notes that are no longer there. */
    auto tracks = AM(app)->getTimelineTracks();
    /* The master track is addressed by a sentinel index, not by position. */
    auto* track = track_index == UAPMD_MASTER_TRACK_INDEX
        ? AM(app)->getMasterTimelineTrack()
        : (track_index >= 0 && static_cast<size_t>(track_index) < tracks.size()
            ? tracks[static_cast<size_t>(track_index)] : nullptr);
    if (!track) return nullptr;
    const auto clips = track->clipManager().getAllClips();
    const auto it = std::find_if(clips.begin(), clips.end(),
        [clip_id](const auto& c) { return c.clipId == clip_id; });
    if (it == clips.end()) return nullptr;
    auto* box = new PianoRollSnapshotBox{
        AM(app)->pianoRollClipSnapshot(track_index, *it, fallback_duration_seconds)};
    return reinterpret_cast<uapmd_piano_roll_snapshot_t>(box);
}

void uapmd_piano_roll_snapshot_destroy(uapmd_piano_roll_snapshot_t snapshot) {
    delete PRB(snapshot);
}

bool uapmd_piano_roll_snapshot_ready(uapmd_piano_roll_snapshot_t snapshot) {
    return snapshot && PRB(snapshot)->snapshot.ready;
}

const char* uapmd_piano_roll_snapshot_error(uapmd_piano_roll_snapshot_t snapshot) {
    if (!snapshot) return "";
    tl_piano_roll_error = PRB(snapshot)->snapshot.error;
    return tl_piano_roll_error.c_str();
}

double uapmd_piano_roll_snapshot_duration_seconds(uapmd_piano_roll_snapshot_t snapshot) {
    return snapshot ? PRB(snapshot)->snapshot.durationSeconds : 0.0;
}

uint8_t uapmd_piano_roll_snapshot_min_note(uapmd_piano_roll_snapshot_t snapshot) {
    return snapshot ? PRB(snapshot)->snapshot.minNote : 0;
}

uint8_t uapmd_piano_roll_snapshot_max_note(uapmd_piano_roll_snapshot_t snapshot) {
    return snapshot ? PRB(snapshot)->snapshot.maxNote : 0;
}

uint32_t uapmd_piano_roll_snapshot_note_count(uapmd_piano_roll_snapshot_t snapshot) {
    return snapshot ? static_cast<uint32_t>(PRB(snapshot)->snapshot.notes.size()) : 0;
}

bool uapmd_piano_roll_snapshot_get_note(uapmd_piano_roll_snapshot_t snapshot, uint32_t index, uapmd_piano_roll_note_t* out) {
    if (!snapshot || !out) return false;
    const auto& notes = PRB(snapshot)->snapshot.notes;
    if (index >= notes.size()) return false;
    fill_note(notes[index], out);
    return true;
}

uapmd_piano_roll_session_t uapmd_app_open_piano_roll_session(uapmd_app_model_t app, int32_t track_index, int32_t clip_id) {
    if (!app) return nullptr;
    /* The model owns the session and keeps the shared_ptr alive; the handle is
     * the raw pointer into it. */
    return reinterpret_cast<uapmd_piano_roll_session_t>(
        AM(app)->openPianoRollSession(track_index, clip_id).get());
}

uapmd_piano_roll_session_t uapmd_app_find_piano_roll_session(uapmd_app_model_t app, int32_t track_index, int32_t clip_id) {
    if (!app) return nullptr;
    return reinterpret_cast<uapmd_piano_roll_session_t>(
        AM(app)->findPianoRollSession(track_index, clip_id));
}

void uapmd_app_close_piano_roll_session(uapmd_app_model_t app, int32_t track_index, int32_t clip_id) {
    if (!app) return;
    if (auto* session = AM(app)->findPianoRollSession(track_index, clip_id)) {
        std::scoped_lock lock(s_piano_roll_drag_mutex);
        s_piano_roll_drags.erase(session);
    }
    AM(app)->closePianoRollSession(track_index, clip_id);
}

bool uapmd_piano_roll_session_matches_source(uapmd_piano_roll_session_t session,
                                               uapmd_piano_roll_snapshot_t snapshot) {
    if (!session || !snapshot) return false;
    const auto& raw = PRB(snapshot)->snapshot.rawMidiData;
    return raw && PRS(session)->matchesSource(*raw);
}

void uapmd_piano_roll_session_load_notes(uapmd_piano_roll_session_t session,
                                            uapmd_piano_roll_snapshot_t snapshot) {
    if (!session) return;
    if (!snapshot) {
        PRS(session)->loadNotes({}, nullptr, 0.01);
        return;
    }
    const auto& s = PRB(snapshot)->snapshot;
    PRS(session)->loadNotes(s.notes, s.rawMidiData, s.durationSeconds);
}

uint32_t uapmd_piano_roll_session_note_count(uapmd_piano_roll_session_t session) {
    return session ? static_cast<uint32_t>(PRS(session)->editNotes.size()) : 0;
}

bool uapmd_piano_roll_session_get_note(uapmd_piano_roll_session_t session, uint32_t index, uapmd_piano_roll_note_t* out) {
    if (!session || !out) return false;
    const auto& notes = PRS(session)->editNotes;
    if (index >= notes.size()) return false;
    fill_edit_note(notes[index], out);
    return true;
}

bool uapmd_piano_roll_session_is_note_selected(uapmd_piano_roll_session_t session, uint32_t index) {
    if (!session) return false;
    const auto& notes = PRS(session)->editNotes;
    if (index >= notes.size()) return false;
    return PRS(session)->selected_notes.contains(notes[index].edit_id);
}

uint32_t uapmd_piano_roll_session_selected_note_count(uapmd_piano_roll_session_t session) {
    return session ? static_cast<uint32_t>(PRS(session)->selected_notes.size()) : 0;
}

int32_t uapmd_piano_roll_session_focused_note(uapmd_piano_roll_session_t session) {
    return session ? PRS(session)->selectedNoteIdx : -1;
}

void uapmd_piano_roll_session_set_focused_note(uapmd_piano_roll_session_t session, int32_t index) {
    if (session) PRS(session)->selectedNoteIdx = index;
}

double uapmd_piano_roll_session_duration_seconds(uapmd_piano_roll_session_t session) {
    return session ? PRS(session)->clipDurationSeconds : 0.0;
}

uint8_t uapmd_piano_roll_session_min_note(uapmd_piano_roll_session_t session) {
    return session ? PRS(session)->minNote : 0;
}

uint8_t uapmd_piano_roll_session_max_note(uapmd_piano_roll_session_t session) {
    return session ? PRS(session)->maxNote : 0;
}

uint32_t uapmd_piano_roll_session_clipboard_count(uapmd_piano_roll_session_t session) {
    return session ? static_cast<uint32_t>(PRS(session)->clipboard.size()) : 0;
}

bool uapmd_piano_roll_session_dirty(uapmd_piano_roll_session_t session) {
    return session && PRS(session)->dirtyAfterEdit;
}

const char* uapmd_piano_roll_session_error(uapmd_piano_roll_session_t session) {
    if (!session) return "";
    tl_piano_roll_error = PRS(session)->edit_error;
    return tl_piano_roll_error.c_str();
}

void uapmd_piano_roll_session_select_note(uapmd_piano_roll_session_t session, int32_t index, bool additive, bool toggle) {
    if (session) PRS(session)->selectNote(index, additive, toggle);
}

void uapmd_piano_roll_session_perform_action(uapmd_piano_roll_session_t session, uapmd_piano_roll_action_t action, double paste_seconds) {
    if (session)
        PRS(session)->performAction(static_cast<uapmd_app::PianoRollSession::Action>(action), paste_seconds);
}

void uapmd_piano_roll_session_create_note(uapmd_piano_roll_session_t session, double start_seconds, double duration_seconds, uint8_t note, float velocity) {
    if (session) PRS(session)->createNote(start_seconds, duration_seconds, note, velocity);
}

void uapmd_piano_roll_session_delete_note(uapmd_piano_roll_session_t session, uint32_t index) {
    if (session) PRS(session)->deleteNote(static_cast<int>(index));
}

void uapmd_piano_roll_session_resize_note(uapmd_piano_roll_session_t session, uint32_t index, double start_seconds, double duration_seconds, uint8_t note) {
    if (session) PRS(session)->resizeNote(static_cast<int>(index), start_seconds, duration_seconds, note);
}

void uapmd_piano_roll_session_begin_drag(uapmd_piano_roll_session_t session) {
    if (!session) return;
    auto* s = PRS(session);
    std::vector<std::pair<int, uapmd_app::PianoRollEditNote>> originals;
    for (int i = 0; i < static_cast<int>(s->editNotes.size()); i++)
        if (!s->editNotes[i].deleted && s->selected_notes.contains(s->editNotes[i].edit_id))
            originals.emplace_back(i, s->editNotes[i]);
    std::scoped_lock lock(s_piano_roll_drag_mutex);
    s_piano_roll_drags[s] = std::move(originals);
}

void uapmd_piano_roll_session_move_selection(uapmd_piano_roll_session_t session, double time_delta_seconds, int32_t pitch_delta) {
    if (!session) return;
    std::scoped_lock lock(s_piano_roll_drag_mutex);
    const auto it = s_piano_roll_drags.find(PRS(session));
    if (it == s_piano_roll_drags.end()) return;
    PRS(session)->moveNotes(it->second, time_delta_seconds, pitch_delta);
}

void uapmd_piano_roll_session_cancel_drag(uapmd_piano_roll_session_t session) {
    if (!session) return;
    std::scoped_lock lock(s_piano_roll_drag_mutex);
    const auto it = s_piano_roll_drags.find(PRS(session));
    if (it == s_piano_roll_drags.end()) return;
    PRS(session)->restoreNotes(it->second);
    s_piano_roll_drags.erase(it);
}

void uapmd_piano_roll_session_finish_drag(uapmd_piano_roll_session_t session, uint32_t index, double original_start, double original_end, uint8_t original_note) {
    if (!session) return;
    PRS(session)->finishNoteDrag(static_cast<int>(index), original_start, original_end, original_note);
    std::scoped_lock lock(s_piano_roll_drag_mutex);
    s_piano_roll_drags.erase(PRS(session));
}

bool uapmd_piano_roll_session_commit(uapmd_piano_roll_session_t session, uapmd_app_model_t app) {
    if (!session || !app) return false;
    return PRS(session)->commit(*AM(app));
}

void uapmd_app_record_piano_roll_commit_source(uapmd_app_model_t app, int32_t track_index, int32_t clip_id) {
    if (app) AM(app)->recordPianoRollCommitSource(track_index, clip_id);
}

bool uapmd_app_piano_roll_source_matches_last_edit(uapmd_app_model_t app) {
    return app && AM(app)->pianoRollSourceMatchesLastEdit();
}

void uapmd_app_clear_piano_roll_commit_source(uapmd_app_model_t app) {
    if (app) AM(app)->clearPianoRollCommitSource();
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Timeline clip selection and clipboard
 * ═══════════════════════════════════════════════════════════════════════════ */

namespace {
/* Per-thread, like every other string this API hands back by pointer. */
thread_local std::string tl_clip_selection_error;

std::vector<uapmd_app::AppModel::TimelineClipTarget> targets_from_c(
        const uapmd_timeline_clip_target_t* clips, uint32_t count) {
    std::vector<uapmd_app::AppModel::TimelineClipTarget> result;
    if (!clips) return result;
    result.reserve(count);
    for (uint32_t i = 0; i < count; i++)
        result.push_back({clips[i].track_index, clips[i].clip_id});
    return result;
}

/* Copies out under the usual convention: a null buffer asks for the count. */
template<typename T, typename Convert>
uint32_t copy_out_vector(const std::vector<T>& source, Convert convert,
                         decltype(convert(source.front()))* out, uint32_t out_count) {
    if (!out || out_count == 0)
        return static_cast<uint32_t>(source.size());
    const uint32_t n = static_cast<uint32_t>(
        source.size() < out_count ? source.size() : out_count);
    for (uint32_t i = 0; i < n; i++)
        out[i] = convert(source[i]);
    return n;
}
} // namespace

bool uapmd_app_is_timeline_clip_selected(uapmd_app_model_t app, int32_t track_index, int32_t clip_id) {
    if (!app) return false;
    return AM(app)->isTimelineClipSelected(track_index, clip_id);
}

uint32_t uapmd_app_selected_timeline_clips(uapmd_app_model_t app,
                                             uapmd_timeline_clip_target_t* out,
                                             uint32_t out_count) {
    if (!app) return 0;
    const auto selected = AM(app)->selectedTimelineClips();
    return copy_out_vector(selected,
        [](const uapmd_app::AppModel::TimelineClipTarget& t) {
            return uapmd_timeline_clip_target_t{t.track_index, t.clip_id};
        },
        out, out_count);
}

void uapmd_app_select_timeline_clips(uapmd_app_model_t app,
                                       const uapmd_timeline_clip_target_t* clips,
                                       uint32_t clip_count,
                                       bool additive,
                                       bool toggle) {
    if (!app) return;
    AM(app)->selectTimelineClips(targets_from_c(clips, clip_count), additive, toggle);
}

void uapmd_app_clear_timeline_clip_selection(uapmd_app_model_t app) {
    if (app) AM(app)->clearTimelineClipSelection();
}

bool uapmd_app_select_timeline_midi_clip(uapmd_app_model_t app, int32_t track_index, int32_t clip_id) {
    if (!app) return false;
    return AM(app)->selectTimelineMidiClip(track_index, clip_id);
}

bool uapmd_app_selected_timeline_midi_clip(uapmd_app_model_t app, uapmd_timeline_clip_target_t* out) {
    if (!app || !out) return false;
    const auto selected = AM(app)->selectedTimelineMidiClip();
    if (!selected.has_value()) return false;
    out->track_index = selected->first;
    out->clip_id = selected->second;
    return true;
}

uint32_t uapmd_app_timeline_clipboard_count(uapmd_app_model_t app) {
    return app ? static_cast<uint32_t>(AM(app)->timelineClipboard().size()) : 0;
}

void uapmd_app_clear_timeline_clipboard(uapmd_app_model_t app) {
    if (app) AM(app)->clearTimelineClipboard();
}

bool uapmd_app_copy_selected_timeline_clips(uapmd_app_model_t app) {
    tl_clip_selection_error.clear();
    if (!app) return false;
    return AM(app)->copySelectedTimelineClips(tl_clip_selection_error);
}

bool uapmd_app_delete_selected_timeline_clips(uapmd_app_model_t app,
                                                bool cut,
                                                int32_t* changed_tracks,
                                                uint32_t* changed_track_count) {
    tl_clip_selection_error.clear();
    if (!app) return false;
    std::vector<int32_t> changed;
    const bool ok = AM(app)->deleteSelectedTimelineClips(cut, tl_clip_selection_error, changed);
    if (changed_track_count) {
        const uint32_t capacity = changed_tracks ? *changed_track_count : 0;
        *changed_track_count = copy_out_vector(changed,
            [](int32_t v) { return v; }, changed_tracks, capacity);
    }
    return ok;
}

uint32_t uapmd_app_timeline_paste_destinations(uapmd_app_model_t app,
                                                 int32_t track_index,
                                                 bool original_tracks,
                                                 int32_t* out,
                                                 uint32_t out_count) {
    tl_clip_selection_error.clear();
    if (!app) return 0;
    const auto destinations =
        AM(app)->timelinePasteDestinations(track_index, original_tracks, tl_clip_selection_error);
    return copy_out_vector(destinations, [](int32_t v) { return v; }, out, out_count);
}

bool uapmd_app_paste_timeline_clips(uapmd_app_model_t app,
                                      int32_t track_index,
                                      double position_seconds,
                                      bool original_tracks,
                                      uapmd_timeline_clip_target_t* pasted,
                                      uint32_t* pasted_count) {
    tl_clip_selection_error.clear();
    if (!app) return false;
    std::vector<uapmd_app::AppModel::TimelineClipTarget> created;
    const bool ok = AM(app)->pasteTimelineClips(
        track_index, position_seconds, original_tracks, created, tl_clip_selection_error);
    if (pasted_count) {
        const uint32_t capacity = pasted ? *pasted_count : 0;
        *pasted_count = copy_out_vector(created,
            [](const uapmd_app::AppModel::TimelineClipTarget& t) {
                return uapmd_timeline_clip_target_t{t.track_index, t.clip_id};
            },
            pasted, capacity);
    }
    return ok;
}

const char* uapmd_app_last_timeline_clip_error(void) { return tl_clip_selection_error.c_str(); }

bool uapmd_app_get_timeline_state(uapmd_app_model_t app, uapmd_timeline_state_t* out) {
    auto& st = AM(app)->timeline();
    out->playhead_position = to_c(st.playheadPosition);
    out->is_playing = st.isPlaying;
    out->loop_enabled = st.loopEnabled;
    out->loop_start = to_c(st.loopStart);
    out->loop_end = to_c(st.loopEnd);
    out->tempo = st.tempo;
    out->time_signature_numerator = st.timeSignatureNumerator;
    out->time_signature_denominator = st.timeSignatureDenominator;
    out->sample_rate = st.sample_rate;
    return true;
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Instance details
 * ═══════════════════════════════════════════════════════════════════════════ */

void uapmd_app_request_show_instance_details(uapmd_app_model_t app, int32_t instance_id) {
    AM(app)->requestShowInstanceDetails(instance_id);
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Track graph editing (DAG)
 * ═══════════════════════════════════════════════════════════════════════════ */

/*
 * Endpoint node ids are returned as pointers, so their storage has to outlive the
 * call. A deque, not a vector: the connection array holds pointers into these
 * strings, and a vector would invalidate every one of them when it grew.
 */
static thread_local std::deque<std::string> tl_endpoint_node_ids;

static uapmd_graph_endpoint_t to_c_endpoint(const uapmd_graph::AudioPluginGraphEndpoint& e) {
    tl_endpoint_node_ids.push_back(e.node_id);
    return {
        static_cast<uapmd_graph_endpoint_type_t>(e.type),
        tl_endpoint_node_ids.back().c_str(),
        e.instance_id,
        e.bus_index
    };
}

static uapmd_graph::AudioPluginGraphEndpoint to_cpp_endpoint(const uapmd_graph_endpoint_t& e) {
    uapmd_graph::AudioPluginGraphEndpoint r;
    r.type = static_cast<uapmd_graph::AudioPluginGraphEndpointType>(e.type);
    r.node_id = e.node_id ? e.node_id : "";
    r.instance_id = e.instance_id;
    r.bus_index = e.bus_index;
    return r;
}

bool uapmd_app_ensure_track_uses_editor_graph(uapmd_app_model_t app, int32_t track_index) {
    return AM(app)->ensureTrackUsesEditorGraph(track_index);
}

void uapmd_app_request_show_track_graph(uapmd_app_model_t app, int32_t track_index) {
    AM(app)->requestShowTrackGraph(track_index);
}

bool uapmd_app_revert_track_to_simple_graph(uapmd_app_model_t app, int32_t track_index) {
    return AM(app)->revertTrackToSimpleGraph(track_index);
}

static thread_local std::vector<uapmd_graph_connection_t> tl_connections;
static thread_local std::string tl_conn_error;

uapmd_graph_connections_result_t uapmd_app_get_track_graph_connections(uapmd_app_model_t app, int32_t track_index) {
    std::vector<uapmd_graph::AudioPluginGraphConnection> conns;
    tl_conn_error.clear();
    bool ok = AM(app)->getTrackGraphConnections(track_index, conns, tl_conn_error);
    tl_connections.clear();
    tl_endpoint_node_ids.clear();
    tl_connections.reserve(conns.size());
    for (auto& c : conns)
        tl_connections.push_back({c.id, static_cast<uapmd_graph_bus_type_t>(c.bus_type),
                                  to_c_endpoint(c.source), to_c_endpoint(c.target)});
    return {
        ok,
        tl_conn_error.empty() ? nullptr : tl_conn_error.c_str(),
        static_cast<uint32_t>(tl_connections.size()),
        tl_connections.data()
    };
}

/*
 * The nodes of a track's graph.
 *
 * This mirrors the C++ surface — `AudioGraphNode` plus the `remidy::PluginAudioBuses`
 * facade it exposes — and applies no presentation policy of its own. uapmd-app's
 * graph editor draws a pin only for *enabled* audio buses and at most one event pin
 * per direction (PluginGraphEditor.cpp:353-450); that belongs to the editor, so
 * every bus is reported here with its `enabled` flag and the caller decides.
 */
static thread_local std::vector<uapmd_graph_node_t> tl_graph_nodes;
static thread_local std::vector<uapmd_graph_audio_bus_t> tl_graph_buses;
static thread_local std::deque<std::string> tl_graph_node_strings;
static thread_local std::string tl_graph_nodes_error;

static const char* keep_node_string(const std::string& s) {
    tl_graph_node_strings.push_back(s);
    return tl_graph_node_strings.back().c_str();
}

static void append_bus(remidy::AudioBusConfiguration* bus) {
    if (!bus) {
        tl_graph_buses.push_back({ "", UAPMD_AUDIO_BUS_ROLE_MAIN, false, "", 0 });
        return;
    }
    auto& definition = const_cast<remidy::AudioBusDefinition&>(bus->definition());
    auto& layout = bus->channelLayout();
    tl_graph_buses.push_back({
        keep_node_string(definition.name()),
        static_cast<uapmd_audio_bus_role_t>(bus->role()),
        bus->enabled(),
        keep_node_string(layout.name()),
        layout.channels()
    });
}

uapmd_graph_nodes_result_t uapmd_app_get_track_graph_nodes(uapmd_app_model_t app, int32_t track_index) {
    tl_graph_nodes.clear();
    tl_graph_buses.clear();
    tl_graph_node_strings.clear();
    tl_graph_nodes_error.clear();

    auto& sequencer = AM(app)->sequencer();
    auto* engine = sequencer.engine();
    uapmd::SequencerTrack* track = nullptr;
    if (!engine) {
        tl_graph_nodes_error = "the audio engine is not running";
    } else if (track_index == uapmd::kMasterTrackIndex) {
        track = engine->masterTrack();
    } else {
        auto tracks = engine->tracks();
        if (track_index < 0 || track_index >= static_cast<int32_t>(tracks.size()))
            tl_graph_nodes_error = "no such track";
        else
            track = tracks[static_cast<size_t>(track_index)];
    }
    if (!track) {
        if (tl_graph_nodes_error.empty())
            tl_graph_nodes_error = "no such track";
        return { false, tl_graph_nodes_error.c_str(), 0, nullptr, 0, nullptr, 0, 0, 0, 0 };
    }

    auto& graph = track->graph();
    auto* layoutExtension = graph.getExtension<uapmd_graph::AudioBusesLayoutExtension>();
    auto layout = layoutExtension ? layoutExtension->busesLayout() : uapmd_graph::AudioGraphBusesLayout{};

    for (const auto& [key, node] : graph.nodes()) {
        if (!node)
            continue;
        auto* pluginNode = dynamic_cast<uapmd_graph::AudioPluginNode*>(node);
        auto* buses = node->audioBuses();

        const auto busOffset = static_cast<uint32_t>(tl_graph_buses.size());
        uint32_t audioIn = 0, audioOut = 0;
        if (buses) {
            for (auto* b : buses->audioInputBuses()) {
                append_bus(b);
                ++audioIn;
            }
            for (auto* b : buses->audioOutputBuses()) {
                append_bus(b);
                ++audioOut;
            }
        }

        tl_graph_nodes.push_back({
            keep_node_string(node->nodeId()),
            keep_node_string(node->nodeType()),
            keep_node_string(node->displayName()),
            pluginNode ? pluginNode->instanceId() : -1,
            node->bypassed(),
            node->latencyInSamples(),
            node->tailLengthInSeconds(),
            buses != nullptr,
            buses ? buses->hasEventInputs() : false,
            buses ? buses->hasEventOutputs() : false,
            busOffset,
            audioIn,
            audioOut,
            buses ? buses->mainInputBusIndex() : -1,
            buses ? buses->mainOutputBusIndex() : -1
        });
    }

    return {
        true,
        nullptr,
        static_cast<uint32_t>(tl_graph_nodes.size()),
        tl_graph_nodes.data(),
        static_cast<uint32_t>(tl_graph_buses.size()),
        tl_graph_buses.data(),
        layout.audio_input_bus_count,
        layout.audio_output_bus_count,
        layout.event_input_bus_count,
        layout.event_output_bus_count
    };
}

uapmd_op_result_t uapmd_app_connect_track_graph(uapmd_app_model_t app,
                                                  int32_t track_index,
                                                  const uapmd_graph_connection_t* connection) {
    uapmd_graph::AudioPluginGraphConnection c;
    c.id = connection->id;
    c.bus_type = static_cast<uapmd_graph::AudioPluginGraphBusType>(connection->bus_type);
    c.source = to_cpp_endpoint(connection->source);
    c.target = to_cpp_endpoint(connection->target);
    tl_conn_error.clear();
    bool ok = AM(app)->connectTrackGraph(track_index, c, tl_conn_error);
    return { ok, tl_conn_error.empty() ? nullptr : tl_conn_error.c_str() };
}

uapmd_op_result_t uapmd_app_disconnect_track_graph_connection(uapmd_app_model_t app,
                                                               int32_t track_index,
                                                               int64_t connection_id) {
    tl_conn_error.clear();
    bool ok = AM(app)->disconnectTrackGraphConnection(track_index, connection_id, tl_conn_error);
    return { ok, tl_conn_error.empty() ? nullptr : tl_conn_error.c_str() };
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Clip audio events (markers + warps)
 * ═══════════════════════════════════════════════════════════════════════════ */

static thread_local std::vector<uapmd::ClipMarker> tl_clip_markers;
static thread_local std::vector<uapmd::AudioWarpPoint> tl_clip_warps;
static thread_local std::vector<uapmd_clip_marker_t> tl_clip_markers_c;
static thread_local std::vector<uapmd_audio_warp_point_t> tl_clip_warps_c;
static thread_local std::string tl_clip_events_error;

uapmd_clip_audio_events_result_t uapmd_app_get_clip_audio_events(uapmd_app_model_t app,
                                                                   int32_t track_index,
                                                                   int32_t clip_id) {
    tl_clip_markers.clear();
    tl_clip_warps.clear();
    tl_clip_events_error.clear();
    bool ok = AM(app)->getClipAudioEvents(track_index, clip_id, tl_clip_markers, tl_clip_warps, tl_clip_events_error);

    tl_clip_markers_c.clear();
    for (auto& m : tl_clip_markers)
        tl_clip_markers_c.push_back({
            m.markerId.c_str(), m.clipPositionOffset,
            static_cast<uapmd_audio_warp_reference_type_t>(m.referenceType),
            m.referenceClipId.c_str(), m.referenceMarkerId.c_str(), m.name.c_str()
        });

    tl_clip_warps_c.clear();
    for (auto& w : tl_clip_warps)
        tl_clip_warps_c.push_back({
            w.clipPositionOffset, w.speedRatio,
            static_cast<uapmd_audio_warp_reference_type_t>(w.referenceType),
            w.referenceClipId.c_str(), w.referenceMarkerId.c_str()
        });

    return {
        ok, tl_clip_events_error.empty() ? nullptr : tl_clip_events_error.c_str(),
        static_cast<uint32_t>(tl_clip_markers_c.size()), tl_clip_markers_c.data(),
        static_cast<uint32_t>(tl_clip_warps_c.size()), tl_clip_warps_c.data()
    };
}

static std::vector<uapmd::ClipMarker> markers_from_c(const uapmd_clip_marker_t* markers, uint32_t count) {
    std::vector<uapmd::ClipMarker> result;
    result.reserve(count);
    for (uint32_t i = 0; i < count; ++i) {
        uapmd::ClipMarker m;
        if (markers[i].marker_id) m.markerId = markers[i].marker_id;
        m.clipPositionOffset = markers[i].clip_position_offset;
        m.referenceType = static_cast<uapmd::AudioWarpReferenceType>(markers[i].reference_type);
        if (markers[i].reference_clip_id) m.referenceClipId = markers[i].reference_clip_id;
        if (markers[i].reference_marker_id) m.referenceMarkerId = markers[i].reference_marker_id;
        if (markers[i].name) m.name = markers[i].name;
        result.push_back(std::move(m));
    }
    return result;
}

static std::vector<uapmd::AudioWarpPoint> warps_from_c(const uapmd_audio_warp_point_t* warps, uint32_t count) {
    std::vector<uapmd::AudioWarpPoint> result;
    result.reserve(count);
    for (uint32_t i = 0; i < count; ++i) {
        uapmd::AudioWarpPoint w;
        w.clipPositionOffset = warps[i].clip_position_offset;
        w.speedRatio = warps[i].speed_ratio;
        w.referenceType = static_cast<uapmd::AudioWarpReferenceType>(warps[i].reference_type);
        if (warps[i].reference_clip_id) w.referenceClipId = warps[i].reference_clip_id;
        if (warps[i].reference_marker_id) w.referenceMarkerId = warps[i].reference_marker_id;
        result.push_back(std::move(w));
    }
    return result;
}

uapmd_op_result_t uapmd_app_set_clip_audio_events(uapmd_app_model_t app,
                                                     int32_t track_index,
                                                     int32_t clip_id,
                                                     const uapmd_clip_marker_t* markers,
                                                     uint32_t marker_count,
                                                     const uapmd_audio_warp_point_t* warps,
                                                     uint32_t warp_count) {
    tl_clip_events_error.clear();
    bool ok = AM(app)->setClipAudioEvents(track_index, clip_id,
        markers_from_c(markers, marker_count),
        warps_from_c(warps, warp_count),
        tl_clip_events_error);
    return { ok, tl_clip_events_error.empty() ? nullptr : tl_clip_events_error.c_str() };
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Master track markers
 * ═══════════════════════════════════════════════════════════════════════════ */

static thread_local std::vector<uapmd_clip_marker_t> tl_master_markers_c;

uint32_t uapmd_app_master_marker_count(uapmd_app_model_t app) {
    auto& markers = AM(app)->sequencer().engine()->masterTrackMarkers();
    tl_master_markers_c.clear();
    for (auto& m : markers)
        tl_master_markers_c.push_back({
            m.markerId.c_str(), m.clipPositionOffset,
            static_cast<uapmd_audio_warp_reference_type_t>(m.referenceType),
            m.referenceClipId.c_str(), m.referenceMarkerId.c_str(), m.name.c_str()
        });
    return static_cast<uint32_t>(tl_master_markers_c.size());
}

bool uapmd_app_get_master_marker(uapmd_app_model_t app, uint32_t index, uapmd_clip_marker_t* out) {
    (void) app; /* data already cached from master_marker_count call */
    if (index >= tl_master_markers_c.size()) return false;
    *out = tl_master_markers_c[index];
    return true;
}

uapmd_op_result_t uapmd_app_set_master_track_markers_with_validation(uapmd_app_model_t app,
                                                 const uapmd_clip_marker_t* markers,
                                                 uint32_t count) {
    tl_clip_events_error.clear();
    bool ok = AM(app)->setMasterTrackMarkersWithValidation(markers_from_c(markers, count), tl_clip_events_error);
    return { ok, tl_clip_events_error.empty() ? nullptr : tl_clip_events_error.c_str() };
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  MIDI clip UMP event editing
 * ═══════════════════════════════════════════════════════════════════════════ */

/*
 * A straight copy of AppModel::MidiClipUmpEvents into the flat
 * uapmd_ump_event_t array the C surface exposes. The event's own event_index
 * just counts events, so it is not carried across: it is the array position.
 */
static thread_local std::vector<uapmd_ump_event_t> tl_ump_events;
static thread_local std::vector<std::vector<uint32_t>> tl_ump_words_storage;
static thread_local std::string tl_ump_error;

uapmd_ump_events_result_t uapmd_app_get_midi_clip_ump_events(uapmd_app_model_t app,
                                                               int32_t track_index,
                                                               int32_t clip_id) {
    tl_ump_events.clear();
    tl_ump_words_storage.clear();
    tl_ump_error.clear();

    uapmd_app::AppModel::MidiClipUmpEvents val;
    try {
        val = AM(app)->getMidiClipUmpEvents(track_index, clip_id);
    } catch (const std::exception& e) {
        /* The app model throws for a missing track, a missing clip, or a clip
         * that is not MIDI; none of those may cross the C boundary. */
        tl_ump_error = e.what();
        return { false, tl_ump_error.c_str(), 0, nullptr, 0, 0.0 };
    } catch (...) {
        tl_ump_error = "failed to read MIDI clip events";
        return { false, tl_ump_error.c_str(), 0, nullptr, 0, 0.0 };
    }

    tl_ump_events.reserve(val.events.size());
    tl_ump_words_storage.reserve(val.events.size());
    for (const auto& event : val.events) {
        tl_ump_words_storage.push_back(event.words);
        auto& stored = tl_ump_words_storage.back();
        tl_ump_events.push_back({ event.tick, static_cast<uint32_t>(stored.size()), stored.data() });
    }

    return {
        true, nullptr,
        static_cast<uint32_t>(tl_ump_events.size()),
        tl_ump_events.data(),
        val.tick_resolution,
        val.bpm
    };
}

bool uapmd_app_add_ump_event_to_clip(uapmd_app_model_t app,
                                       int32_t track_index,
                                       int32_t clip_id,
                                       uint64_t tick,
                                       const uint32_t* words,
                                       uint32_t word_count) {
    tl_ump_error.clear();
    std::vector<uint32_t> w(words, words + word_count);
    return AM(app)->addUmpEventToClip(track_index, clip_id, tick, std::move(w), tl_ump_error);
}

bool uapmd_app_remove_ump_event_from_clip(uapmd_app_model_t app,
                                            int32_t track_index,
                                            int32_t clip_id,
                                            int32_t event_index) {
    tl_ump_error.clear();
    return AM(app)->removeUmpEventFromClip(track_index, clip_id, event_index, tl_ump_error);
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Undo history
 * ═══════════════════════════════════════════════════════════════════════════ */

static thread_local std::string tl_history_compound_desc;
static thread_local std::string tl_history_undo_desc;
static thread_local std::string tl_history_redo_desc;

bool uapmd_app_history_state(uapmd_app_model_t app, uapmd_undo_state_t* out) {
    if (!app || !out) return false;
    auto s = AM(app)->historyState();
    tl_history_compound_desc = s.compoundDescription;
    tl_history_undo_desc = s.undoDescription;
    tl_history_redo_desc = s.redoDescription;
    out->busy = s.busy;
    out->compound_open = s.compoundOpen;
    out->gesture_open = s.gestureOpen;
    out->can_undo = s.canUndo;
    out->can_redo = s.canRedo;
    out->dirty = s.dirty;
    out->compound_description = tl_history_compound_desc.c_str();
    out->undo_description = tl_history_undo_desc.c_str();
    out->redo_description = tl_history_redo_desc.c_str();
    out->history_size_in_bytes = static_cast<uint64_t>(s.historySizeInBytes);
    out->maximum_history_size_in_bytes = static_cast<uint64_t>(s.maximumHistorySizeInBytes);
    out->current_state_id = s.currentStateId;
    out->saved_state_id = s.savedStateId;
    return true;
}

void uapmd_app_undo(uapmd_app_model_t app, void* user_data, uapmd_history_mutation_cb_t callback) {
    if (!callback) return;
    AM(app)->undo([user_data, callback](std::string error) {
        callback(error.empty() ? nullptr : error.c_str(), user_data);
    });
}

void uapmd_app_redo(uapmd_app_model_t app, void* user_data, uapmd_history_mutation_cb_t callback) {
    if (!callback) return;
    AM(app)->redo([user_data, callback](std::string error) {
        callback(error.empty() ? nullptr : error.c_str(), user_data);
    });
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Project save/load
 * ═══════════════════════════════════════════════════════════════════════════ */

void uapmd_app_save_project(uapmd_app_model_t app, const char* file_path, void* user_data, uapmd_project_save_cb_t callback) {
    AM(app)->saveProject(file_path, [callback, user_data](uapmd_app::AppModel::ProjectResult r) {
        if (!callback) return;
        tl_error = r.error;
        uapmd_app_project_result_t cr = { r.success, tl_error.empty() ? nullptr : tl_error.c_str() };
        callback(cr, user_data);
    });
}

uapmd_app_project_result_t uapmd_app_save_project_sync(uapmd_app_model_t app, const char* file_path) {
    auto r = AM(app)->saveProjectSync(file_path);
    tl_error = r.error;
    return { r.success, tl_error.empty() ? nullptr : tl_error.c_str() };
}

uapmd_app_project_result_t uapmd_app_new_project(uapmd_app_model_t app) {
    auto promise = std::make_shared<std::promise<uapmd_app::AppModel::ProjectResult>>();
    AM(app)->newProject([promise](uapmd_app::AppModel::ProjectResult r) mutable {
        promise->set_value(std::move(r));
    });
    auto r = promise->get_future().get();
    tl_error = r.error;
    return { r.success, tl_error.empty() ? nullptr : tl_error.c_str() };
}

uapmd_tempo_map_t uapmd_app_master_tempo_map(uapmd_app_model_t app) {
    if (!app) return nullptr;
    return reinterpret_cast<uapmd_tempo_map_t>(
        const_cast<uapmd::TempoMap*>(&AM(app)->masterTempoMap()));
}

uapmd_app_project_result_t uapmd_app_load_project(uapmd_app_model_t app, const char* file_path) {
    auto promise = std::make_shared<std::promise<uapmd_app::AppModel::ProjectResult>>();
    AM(app)->loadProject(file_path, [promise](uapmd_app::AppModel::ProjectResult r) mutable {
        promise->set_value(std::move(r));
    });
    auto r = promise->get_future().get();
    tl_error = r.error;
    return { r.success, tl_error.empty() ? nullptr : tl_error.c_str() };
}

uapmd_app_project_result_t uapmd_app_load_project_from_handle_token(uapmd_app_model_t app, const char* token) {
    auto r = AM(app)->loadProjectFromHandleToken(token ? token : "");
    tl_error = r.error;
    return { r.success, tl_error.empty() ? nullptr : tl_error.c_str() };
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Offline rendering
 * ═══════════════════════════════════════════════════════════════════════════ */

bool uapmd_app_start_render_to_file(uapmd_app_model_t app, const uapmd_app_render_settings_t* settings) {
    uapmd_app::AppModel::RenderToFileSettings s;
    if (settings->output_path) s.outputPath = settings->output_path;
    s.startSeconds = settings->start_seconds;
    if (settings->has_end_seconds)
        s.endSeconds = settings->end_seconds;
    s.useContentFallback = settings->use_content_fallback;
    s.contentBoundsValid = settings->content_bounds_valid;
    s.contentStartSeconds = settings->content_start_seconds;
    s.contentEndSeconds = settings->content_end_seconds;
    s.tailSeconds = settings->tail_seconds;
    s.enableSilenceStop = settings->enable_silence_stop;
    s.silenceDurationSeconds = settings->silence_duration_seconds;
    s.silenceThresholdDb = settings->silence_threshold_db;
    return AM(app)->startRenderToFile(s);
}

void uapmd_app_cancel_render_to_file(uapmd_app_model_t app) { AM(app)->cancelRenderToFile(); }

static thread_local std::string tl_render_msg;
static thread_local std::string tl_render_path;

uapmd_app_render_status_t uapmd_app_get_render_to_file_status(uapmd_app_model_t app) {
    auto st = AM(app)->getRenderToFileStatus();
    tl_render_msg = st.message;
    tl_render_path = st.outputPath.string();
    return {
        st.running, st.completed, st.success, st.progress, st.renderedSeconds,
        tl_render_msg.empty() ? nullptr : tl_render_msg.c_str(),
        tl_render_path.empty() ? nullptr : tl_render_path.c_str()
    };
}

void uapmd_app_clear_completed_render_status(uapmd_app_model_t app) { AM(app)->clearCompletedRenderStatus(); }

/* ═══════════════════════════════════════════════════════════════════════════
 *  TransportController
 * ═══════════════════════════════════════════════════════════════════════════ */

bool  uapmd_transport_is_playing(uapmd_transport_controller_t tc)    { return TC(tc)->isPlaying(); }
bool  uapmd_transport_is_paused(uapmd_transport_controller_t tc)     { return TC(tc)->isPaused(); }
bool  uapmd_transport_is_recording(uapmd_transport_controller_t tc)  { return TC(tc)->isRecording(); }
float uapmd_transport_get_volume(uapmd_transport_controller_t tc)    { return TC(tc)->volume(); }
void  uapmd_transport_set_volume(uapmd_transport_controller_t tc, float v) { TC(tc)->setVolume(v); }

void uapmd_transport_play(uapmd_transport_controller_t tc)    { TC(tc)->play(); }
void uapmd_transport_stop(uapmd_transport_controller_t tc)    { TC(tc)->stop(); }
void uapmd_transport_pause(uapmd_transport_controller_t tc)   { TC(tc)->pause(); }
void uapmd_transport_resume(uapmd_transport_controller_t tc)  { TC(tc)->resume(); }
void uapmd_transport_record(uapmd_transport_controller_t tc)  { TC(tc)->record(); }

/* ═══════════════════════════════════════════════════════════════════════════
 *  Startup lifecycle
 * ═══════════════════════════════════════════════════════════════════════════ */

void uapmd_app_notify_ui_ready(uapmd_app_model_t app)                 { AM(app)->notifyUiReady(); }
void uapmd_app_notify_persistent_storage_ready(uapmd_app_model_t app) { AM(app)->notifyPersistentStorageReady(); }
