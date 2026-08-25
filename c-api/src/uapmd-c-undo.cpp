/* uapmd C API — implementation for the uapmd 0.5.6 project history bindings */

#include "c-api/uapmd-c-undo.h"
#include "c-api-internal.h"
#include <uapmd-engine/uapmd-engine.hpp>
#include <uapmd-data/uapmd-data.hpp>
#include <cstring>
#include <iterator>
#include <memory>
#include <string>
#include <utility>
#include <vector>

/* ── Cast helpers ─────────────────────────────────────────────────────────── */

static uapmd::TimelineFacade*        TF(uapmd_timeline_facade_t h)   { return reinterpret_cast<uapmd::TimelineFacade*>(h); }
static uapmd::ProjectUndoEngine*     UE(uapmd_undo_engine_t h)       { return reinterpret_cast<uapmd::ProjectUndoEngine*>(h); }
static uapmd::ProjectCommandManager* CM(uapmd_command_manager_t h)   { return reinterpret_cast<uapmd::ProjectCommandManager*>(h); }
static uapmd::ProjectCommands*       PC(uapmd_project_commands_t h)  { return reinterpret_cast<uapmd::ProjectCommands*>(h); }
static uapmd::ProjectAddressBook*    AB(uapmd_address_book_t h)      { return reinterpret_cast<uapmd::ProjectAddressBook*>(h); }

static uapmd::ProjectMutationOrigin to_cpp_origin(uapmd_mutation_origin_t o) {
    return static_cast<uapmd::ProjectMutationOrigin>(o);
}

/* ── Per-thread storage for returned strings ─────────────────────────────── */

static thread_local std::string tl_undo_error;
static thread_local std::string tl_compound_desc;
static thread_local std::string tl_undo_desc;
static thread_local std::string tl_redo_desc;
static thread_local std::string tl_addr_track_ref;
static thread_local std::string tl_addr_clip_ref;
static thread_local std::string tl_addr_node_id;

static size_t copy_string(const std::string& src, char* buf, size_t buf_size) {
    size_t required = src.size() + 1;
    if (!buf || buf_size == 0)
        return required;
    size_t to_copy = (src.size() < buf_size) ? src.size() : (buf_size - 1);
    std::memcpy(buf, src.data(), to_copy);
    buf[to_copy] = '\0';
    return to_copy;
}

static size_t copy_bytes(const std::vector<uint8_t>& src, uint8_t* buf, size_t buf_size) {
    if (!buf || buf_size == 0)
        return src.size();
    size_t to_copy = (src.size() < buf_size) ? src.size() : buf_size;
    if (to_copy)
        std::memcpy(buf, src.data(), to_copy);
    return to_copy;
}

/* ── C ↔ C++ value conversion ────────────────────────────────────────────── */

static uapmd_timeline_position_t to_c(const uapmd::TimelinePosition& pos) {
    return { pos.samples, pos.legacy_beats };
}

static uapmd::TimeReference to_cpp_time_ref(uapmd_time_reference_t ref) {
    uapmd::TimeReference tr;
    tr.type = static_cast<uapmd::TimeReferenceType>(ref.type);
    if (ref.reference_id) tr.referenceId = ref.reference_id;
    tr.offset = ref.offset;
    return tr;
}

static std::vector<uapmd::ClipMarker> markers_from_c(const uapmd_clip_marker_t* markers, uint32_t count) {
    std::vector<uapmd::ClipMarker> result;
    if (!markers) return result;
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
    if (!warps) return result;
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

static uapmd_undo_result_t to_c_result(const uapmd::ProjectUndoResult& r) {
    tl_undo_error = r.error;
    return {
        static_cast<uapmd_undo_status_t>(r.status),
        tl_undo_error.empty() ? nullptr : tl_undo_error.c_str()
    };
}

static void fill_state(const uapmd::ProjectUndoState& s, uapmd_undo_state_t* out) {
    tl_compound_desc = s.compoundDescription;
    tl_undo_desc = s.undoDescription;
    tl_redo_desc = s.redoDescription;
    out->busy = s.busy;
    out->compound_open = s.compoundOpen;
    out->gesture_open = s.gestureOpen;
    out->can_undo = s.canUndo;
    out->can_redo = s.canRedo;
    out->dirty = s.dirty;
    out->compound_description = tl_compound_desc.c_str();
    out->undo_description = tl_undo_desc.c_str();
    out->redo_description = tl_redo_desc.c_str();
    out->history_size_in_bytes = static_cast<uint64_t>(s.historySizeInBytes);
    out->maximum_history_size_in_bytes = static_cast<uint64_t>(s.maximumHistorySizeInBytes);
    out->current_state_id = s.currentStateId;
    out->saved_state_id = s.savedStateId;
}

/* Wraps a C completion pair as a ProjectUndoCompletion. A null callback means
 * "fire and forget", which the C++ side already models as an empty function. */
static uapmd::ProjectUndoCompletion wrap_completion(void* user_data, uapmd_undo_completion_cb_t callback) {
    if (!callback)
        return {};
    return [user_data, callback](uapmd::ProjectUndoResult result) {
        std::string error = result.error;
        uapmd_undo_result_t c{
            static_cast<uapmd_undo_status_t>(result.status),
            error.empty() ? nullptr : error.c_str()
        };
        callback(c, user_data);
    };
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  ProjectUndoEngine
 * ═══════════════════════════════════════════════════════════════════════════ */

bool uapmd_undo_engine_get_state(uapmd_undo_engine_t eng, uapmd_undo_state_t* out) {
    if (!eng || !out) return false;
    fill_state(UE(eng)->state(), out);
    return true;
}

void uapmd_undo_engine_undo(uapmd_undo_engine_t eng, void* user_data, uapmd_undo_completion_cb_t callback) {
    UE(eng)->undo(wrap_completion(user_data, callback));
}

void uapmd_undo_engine_redo(uapmd_undo_engine_t eng, void* user_data, uapmd_undo_completion_cb_t callback) {
    UE(eng)->redo(wrap_completion(user_data, callback));
}

uapmd_undo_result_t uapmd_undo_engine_begin_compound(uapmd_undo_engine_t eng,
                                                       const char* description,
                                                       uapmd_mutation_origin_t origin) {
    return to_c_result(UE(eng)->beginCompound(description ? description : "", to_cpp_origin(origin)));
}

void uapmd_undo_engine_end_compound(uapmd_undo_engine_t eng, void* user_data, uapmd_undo_completion_cb_t callback) {
    UE(eng)->endCompound(wrap_completion(user_data, callback));
}

void uapmd_undo_engine_cancel_compound(uapmd_undo_engine_t eng, void* user_data, uapmd_undo_completion_cb_t callback) {
    UE(eng)->cancelCompound(wrap_completion(user_data, callback));
}

uapmd_undo_result_t uapmd_undo_engine_begin_gesture(uapmd_undo_engine_t eng,
                                                      const char* description,
                                                      uapmd_mutation_origin_t origin) {
    return to_c_result(UE(eng)->beginGesture(description ? description : "", to_cpp_origin(origin)));
}

void uapmd_undo_engine_end_gesture(uapmd_undo_engine_t eng, void* user_data, uapmd_undo_completion_cb_t callback) {
    UE(eng)->endGesture(wrap_completion(user_data, callback));
}

void uapmd_undo_engine_cancel_gesture(uapmd_undo_engine_t eng, void* user_data, uapmd_undo_completion_cb_t callback) {
    UE(eng)->cancelGesture(wrap_completion(user_data, callback));
}

bool uapmd_undo_engine_clear(uapmd_undo_engine_t eng, bool mark_current_state_saved) {
    return UE(eng)->clear(mark_current_state_saved);
}

bool uapmd_undo_engine_mark_saved(uapmd_undo_engine_t eng) { return UE(eng)->markSaved(); }

bool uapmd_undo_engine_mark_state_saved(uapmd_undo_engine_t eng, uint64_t state_id) {
    return UE(eng)->markStateSaved(state_id);
}

bool uapmd_undo_engine_set_maximum_history_size(uapmd_undo_engine_t eng, uint64_t bytes) {
    return UE(eng)->setMaximumHistorySizeInBytes(static_cast<size_t>(bytes));
}

void uapmd_undo_engine_shutdown(uapmd_undo_engine_t eng) { UE(eng)->shutdown(); }

/* ═══════════════════════════════════════════════════════════════════════════
 *  ProjectCommandManager
 * ═══════════════════════════════════════════════════════════════════════════ */

bool uapmd_command_manager_get_state(uapmd_command_manager_t cm, uapmd_undo_state_t* out) {
    if (!cm || !out) return false;
    fill_state(CM(cm)->state(), out);
    return true;
}

uapmd_undo_engine_t uapmd_command_manager_history(uapmd_command_manager_t cm) {
    return reinterpret_cast<uapmd_undo_engine_t>(&CM(cm)->history());
}

void uapmd_command_manager_undo(uapmd_command_manager_t cm, void* user_data, uapmd_undo_completion_cb_t callback) {
    CM(cm)->undo(wrap_completion(user_data, callback));
}

void uapmd_command_manager_redo(uapmd_command_manager_t cm, void* user_data, uapmd_undo_completion_cb_t callback) {
    CM(cm)->redo(wrap_completion(user_data, callback));
}

uapmd_undo_result_t uapmd_command_manager_begin_step(uapmd_command_manager_t cm,
                                                       const char* description,
                                                       uapmd_mutation_origin_t origin) {
    return to_c_result(CM(cm)->beginStep(description ? description : "", to_cpp_origin(origin)));
}

void uapmd_command_manager_end_step(uapmd_command_manager_t cm, void* user_data, uapmd_undo_completion_cb_t callback) {
    CM(cm)->endStep(wrap_completion(user_data, callback));
}

void uapmd_command_manager_cancel_step(uapmd_command_manager_t cm, void* user_data, uapmd_undo_completion_cb_t callback) {
    CM(cm)->cancelStep(wrap_completion(user_data, callback));
}

uapmd_undo_result_t uapmd_command_manager_begin_gesture(uapmd_command_manager_t cm,
                                                          const char* description,
                                                          uapmd_mutation_origin_t origin) {
    return to_c_result(CM(cm)->beginGesture(description ? description : "", to_cpp_origin(origin)));
}

void uapmd_command_manager_end_gesture(uapmd_command_manager_t cm, void* user_data, uapmd_undo_completion_cb_t callback) {
    CM(cm)->endGesture(wrap_completion(user_data, callback));
}

void uapmd_command_manager_cancel_gesture(uapmd_command_manager_t cm, void* user_data, uapmd_undo_completion_cb_t callback) {
    CM(cm)->cancelGesture(wrap_completion(user_data, callback));
}

void uapmd_command_manager_shutdown(uapmd_command_manager_t cm) { CM(cm)->shutdown(); }

/* ═══════════════════════════════════════════════════════════════════════════
 *  ProjectCommands
 * ═══════════════════════════════════════════════════════════════════════════ */

uapmd_command_manager_t uapmd_commands_history(uapmd_project_commands_t cmd) {
    return reinterpret_cast<uapmd_command_manager_t>(&PC(cmd)->history());
}

bool uapmd_commands_set_clip_enabled(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, bool enabled, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setClipEnabled(track_index, clip_id, enabled, to_cpp_origin(origin));
}

bool uapmd_commands_set_clip_anchor(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, uapmd_time_reference_t anchor, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setClipAnchor(track_index, clip_id, to_cpp_time_ref(anchor), to_cpp_origin(origin));
}

bool uapmd_commands_set_clip_gain(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, double gain, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setClipGain(track_index, clip_id, gain, to_cpp_origin(origin));
}

bool uapmd_commands_set_clip_muted(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, bool muted, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setClipMuted(track_index, clip_id, muted, to_cpp_origin(origin));
}

bool uapmd_commands_resize_clip(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, int64_t new_duration_samples, uapmd_mutation_origin_t origin) {
    return PC(cmd)->resizeClip(track_index, clip_id, new_duration_samples, to_cpp_origin(origin));
}

bool uapmd_commands_set_clip_name(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, const char* name, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setClipName(track_index, clip_id, name ? name : "", to_cpp_origin(origin));
}

bool uapmd_commands_set_clip_filepath(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, const char* filepath, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setClipFilepath(track_index, clip_id, filepath ? filepath : "", to_cpp_origin(origin));
}

bool uapmd_commands_set_clip_needs_file_save(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, bool needs_save, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setClipNeedsFileSave(track_index, clip_id, needs_save, to_cpp_origin(origin));
}

bool uapmd_commands_set_clip_markers(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, const uapmd_clip_marker_t* markers, uint32_t marker_count, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setClipMarkers(track_index, clip_id, markers_from_c(markers, marker_count), to_cpp_origin(origin));
}

bool uapmd_commands_set_clip_audio_warps(uapmd_project_commands_t cmd, int32_t track_index, int32_t clip_id, const uapmd_audio_warp_point_t* warps, uint32_t warp_count, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setClipAudioWarps(track_index, clip_id, warps_from_c(warps, warp_count), to_cpp_origin(origin));
}

bool uapmd_commands_set_track_gain(uapmd_project_commands_t cmd, int32_t track_index, double gain, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setTrackGain(track_index, gain, to_cpp_origin(origin));
}

bool uapmd_commands_set_track_muted(uapmd_project_commands_t cmd, int32_t track_index, bool muted, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setTrackMuted(track_index, muted, to_cpp_origin(origin));
}

bool uapmd_commands_set_track_solo(uapmd_project_commands_t cmd, int32_t track_index, bool solo, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setTrackSolo(track_index, solo, to_cpp_origin(origin));
}

bool uapmd_commands_set_track_bypassed(uapmd_project_commands_t cmd, int32_t track_index, bool bypassed, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setTrackBypassed(track_index, bypassed, to_cpp_origin(origin));
}

bool uapmd_commands_set_track_freeze_policy_enabled(uapmd_project_commands_t cmd, int32_t track_index, bool enabled, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setTrackFreezePolicyEnabled(track_index, enabled, to_cpp_origin(origin));
}

bool uapmd_commands_set_plugin_bypassed(uapmd_project_commands_t cmd, int32_t instance_id, bool bypassed, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setPluginBypassed(instance_id, bypassed, to_cpp_origin(origin));
}

bool uapmd_commands_set_plugin_parameter_value(uapmd_project_commands_t cmd, int32_t instance_id, int32_t parameter_index, double value, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setPluginParameterValue(instance_id, parameter_index, value, to_cpp_origin(origin));
}

bool uapmd_commands_set_plugin_per_note_controller_value(uapmd_project_commands_t cmd,
                                                           int32_t instance_id,
                                                           uint32_t context_type,
                                                           uint32_t note,
                                                           uint32_t channel,
                                                           uint32_t group,
                                                           uint32_t extra,
                                                           int32_t parameter_index,
                                                           double value,
                                                           uapmd_mutation_origin_t origin) {
    remidy::PerNoteControllerContext ctx{note, channel, group, extra};
    return PC(cmd)->setPluginPerNoteControllerValue(
        instance_id,
        static_cast<remidy::PerNoteControllerContextTypes>(context_type),
        ctx,
        parameter_index,
        value,
        to_cpp_origin(origin));
}

bool uapmd_commands_set_plugin_group(uapmd_project_commands_t cmd, int32_t instance_id, uint8_t group, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setPluginGroup(instance_id, group, to_cpp_origin(origin));
}

bool uapmd_commands_set_master_track_markers(uapmd_project_commands_t cmd, const uapmd_clip_marker_t* markers, uint32_t marker_count, uapmd_mutation_origin_t origin) {
    return PC(cmd)->setMasterTrackMarkers(markers_from_c(markers, marker_count), to_cpp_origin(origin));
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  ProjectAddressBook
 * ═══════════════════════════════════════════════════════════════════════════ */

uapmd_timeline_track_t uapmd_addresses_timeline_track(uapmd_address_book_t ab, const char* track_reference_id) {
    return reinterpret_cast<uapmd_timeline_track_t>(AB(ab)->timelineTrack(track_reference_id ? track_reference_id : ""));
}

uapmd_sequencer_track_t uapmd_addresses_sequencer_track(uapmd_address_book_t ab, const char* track_reference_id) {
    return reinterpret_cast<uapmd_sequencer_track_t>(AB(ab)->sequencerTrack(track_reference_id ? track_reference_id : ""));
}

int32_t uapmd_addresses_track_index(uapmd_address_book_t ab, const char* track_reference_id) {
    return AB(ab)->trackIndex(track_reference_id ? track_reference_id : "");
}

int32_t uapmd_addresses_clip_id(uapmd_address_book_t ab, uapmd_clip_address_t address) {
    uapmd::ClipAddress a{
        address.track_reference_id ? address.track_reference_id : "",
        address.clip_reference_id ? address.clip_reference_id : ""
    };
    return AB(ab)->clipId(a);
}

int32_t uapmd_addresses_plugin_instance_id(uapmd_address_book_t ab, uapmd_plugin_address_t address) {
    uapmd::PluginAddress a{
        address.track_reference_id ? address.track_reference_id : "",
        address.node_id ? address.node_id : ""
    };
    return AB(ab)->pluginInstanceId(a);
}

const char* uapmd_addresses_track_reference_id(uapmd_address_book_t ab, int32_t track_index) {
    auto id = AB(ab)->trackReferenceId(track_index);
    if (!id) return nullptr;
    tl_addr_track_ref = *id;
    return tl_addr_track_ref.c_str();
}

bool uapmd_addresses_clip_address(uapmd_address_book_t ab, int32_t track_index, int32_t clip_id, uapmd_clip_address_t* out) {
    if (!out) return false;
    auto address = AB(ab)->clipAddress(track_index, clip_id);
    if (!address) return false;
    tl_addr_track_ref = address->trackReferenceId;
    tl_addr_clip_ref = address->clipReferenceId;
    out->track_reference_id = tl_addr_track_ref.c_str();
    out->clip_reference_id = tl_addr_clip_ref.c_str();
    return true;
}

bool uapmd_addresses_plugin_address(uapmd_address_book_t ab, int32_t instance_id, uapmd_plugin_address_t* out) {
    if (!out) return false;
    auto address = AB(ab)->pluginAddress(instance_id);
    if (!address) return false;
    tl_addr_track_ref = address->trackReferenceId;
    tl_addr_node_id = address->nodeId;
    out->track_reference_id = tl_addr_track_ref.c_str();
    out->node_id = tl_addr_node_id.c_str();
    return true;
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Fragments
 *
 *  A clip fragment handle is either owned by the caller (from
 *  uapmd_tl_capture_clip_fragment) or borrowed from a track fragment. Borrowed
 *  boxes ignore _destroy so that a mistaken call cannot corrupt the owner.
 * ═══════════════════════════════════════════════════════════════════════════ */

namespace {

struct ClipFragmentBox {
    uapmd::ProjectClipFragment owned{};
    const uapmd::ProjectClipFragment* ref{nullptr};
    bool ownsFragment{true};
    /* C views of the clip's markers/warps, kept alive for the box's lifetime. */
    std::vector<uapmd_clip_marker_t> markerViews;
    std::vector<uapmd_audio_warp_point_t> warpViews;

    const uapmd::ProjectClipFragment& get() const { return ref ? *ref : owned; }
};

struct TrackFragmentBox {
    uapmd::ProjectTrackFragment fragment{};
    /* One borrowed box per contained clip, built eagerly so that
     * uapmd_track_fragment_get_clip() can hand out a stable handle. */
    std::vector<std::unique_ptr<ClipFragmentBox>> clipBoxes;
};

ClipFragmentBox* CF(uapmd_clip_fragment_t h) { return reinterpret_cast<ClipFragmentBox*>(h); }
TrackFragmentBox* TFr(uapmd_track_fragment_t h) { return reinterpret_cast<TrackFragmentBox*>(h); }

void build_clip_views(ClipFragmentBox& box) {
    const auto& clip = box.get().clip;
    box.markerViews.clear();
    box.warpViews.clear();
    box.markerViews.reserve(clip.markers.size());
    for (const auto& m : clip.markers)
        box.markerViews.push_back({
            m.markerId.c_str(), m.clipPositionOffset,
            static_cast<uapmd_audio_warp_reference_type_t>(m.referenceType),
            m.referenceClipId.c_str(), m.referenceMarkerId.c_str(), m.name.c_str()
        });
    box.warpViews.reserve(clip.audioWarps.size());
    for (const auto& w : clip.audioWarps)
        box.warpViews.push_back({
            w.clipPositionOffset, w.speedRatio,
            static_cast<uapmd_audio_warp_reference_type_t>(w.referenceType),
            w.referenceClipId.c_str(), w.referenceMarkerId.c_str()
        });
}

std::unique_ptr<TrackFragmentBox> make_track_box(uapmd::ProjectTrackFragment fragment) {
    auto box = std::make_unique<TrackFragmentBox>();
    box->fragment = std::move(fragment);
    box->clipBoxes.reserve(box->fragment.clips.size());
    for (const auto& clip : box->fragment.clips) {
        auto clipBox = std::make_unique<ClipFragmentBox>();
        clipBox->ref = &clip;
        clipBox->ownsFragment = false;
        build_clip_views(*clipBox);
        box->clipBoxes.push_back(std::move(clipBox));
    }
    return box;
}

} // namespace

void uapmd_clip_fragment_destroy(uapmd_clip_fragment_t fragment) {
    if (!fragment) return;
    auto* box = CF(fragment);
    if (!box->ownsFragment) return;  /* borrowed from a track fragment */
    delete box;
}

bool uapmd_clip_fragment_is_midi(uapmd_clip_fragment_t fragment) {
    return fragment && CF(fragment)->get().isMidi();
}

bool uapmd_clip_fragment_get_clip(uapmd_clip_fragment_t fragment, uapmd_clip_data_t* out) {
    if (!fragment || !out) return false;
    auto* box = CF(fragment);
    const auto& src = box->get().clip;
    out->clip_id = src.clipId;
    out->reference_id = src.referenceId.c_str();
    out->position = to_c(src.position);
    out->duration_samples = src.durationSamples;
    out->source_node_instance_id = src.sourceNodeInstanceId;
    out->gain = src.gain;
    out->muted = src.muted;
    out->name = src.name.c_str();
    out->filepath = src.filepath.c_str();
    out->needs_file_save = src.needsFileSave;
    out->clip_type = static_cast<uapmd_clip_type_t>(src.clipType);
    out->tick_resolution = src.tickResolution;
    out->clip_tempo = src.clipTempo;
    out->nrpn_to_parameter_mapping = src.nrpnToParameterMapping;
    out->anchor_reference_id = src.anchorReferenceId.c_str();
    out->anchor_origin = static_cast<uapmd_anchor_origin_t>(src.anchorOrigin);
    out->anchor_offset = to_c(src.anchorOffset);
    out->marker_count = static_cast<uint32_t>(box->markerViews.size());
    out->markers = box->markerViews.empty() ? nullptr : box->markerViews.data();
    out->audio_warp_count = static_cast<uint32_t>(box->warpViews.size());
    out->audio_warps = box->warpViews.empty() ? nullptr : box->warpViews.data();
    return true;
}

uint32_t uapmd_clip_fragment_ump_event_count(uapmd_clip_fragment_t fragment) {
    return fragment ? static_cast<uint32_t>(CF(fragment)->get().umpEvents.size()) : 0;
}

uint32_t uapmd_clip_fragment_get_ump_events(uapmd_clip_fragment_t fragment, uapmd_ump_t* out, uint32_t out_count) {
    if (!fragment) return 0;
    const auto& events = CF(fragment)->get().umpEvents;
    if (!out || out_count == 0) return static_cast<uint32_t>(events.size());
    uint32_t n = static_cast<uint32_t>(events.size()) < out_count ? static_cast<uint32_t>(events.size()) : out_count;
    for (uint32_t i = 0; i < n; ++i) out[i] = events[i];
    return n;
}

uint32_t uapmd_clip_fragment_get_ump_tick_timestamps(uapmd_clip_fragment_t fragment, uint64_t* out, uint32_t out_count) {
    if (!fragment) return 0;
    const auto& ticks = CF(fragment)->get().umpTickTimestamps;
    if (!out || out_count == 0) return static_cast<uint32_t>(ticks.size());
    uint32_t n = static_cast<uint32_t>(ticks.size()) < out_count ? static_cast<uint32_t>(ticks.size()) : out_count;
    for (uint32_t i = 0; i < n; ++i) out[i] = ticks[i];
    return n;
}

uint32_t uapmd_clip_fragment_extension_state_count(uapmd_clip_fragment_t fragment) {
    return fragment ? static_cast<uint32_t>(CF(fragment)->get().extensionState.size()) : 0;
}

size_t uapmd_clip_fragment_extension_state_key(uapmd_clip_fragment_t fragment, uint32_t index, char* buf, size_t buf_size) {
    if (!fragment) return 0;
    const auto& map = CF(fragment)->get().extensionState;
    if (index >= map.size()) return 0;
    return copy_string(std::next(map.begin(), index)->first, buf, buf_size);
}

size_t uapmd_clip_fragment_extension_state_data(uapmd_clip_fragment_t fragment, uint32_t index, uint8_t* buf, size_t buf_size) {
    if (!fragment) return 0;
    const auto& map = CF(fragment)->get().extensionState;
    if (index >= map.size()) return 0;
    return copy_bytes(std::next(map.begin(), index)->second, buf, buf_size);
}

void uapmd_track_fragment_destroy(uapmd_track_fragment_t fragment) {
    delete TFr(fragment);
}

size_t uapmd_track_fragment_reference_id(uapmd_track_fragment_t fragment, char* buf, size_t buf_size) {
    if (!fragment) return 0;
    return copy_string(TFr(fragment)->fragment.referenceId, buf, buf_size);
}

double uapmd_track_fragment_volume(uapmd_track_fragment_t fragment) {
    return fragment ? TFr(fragment)->fragment.volume : 0.0;
}

bool uapmd_track_fragment_muted(uapmd_track_fragment_t fragment) {
    return fragment && TFr(fragment)->fragment.muted;
}

bool uapmd_track_fragment_solo(uapmd_track_fragment_t fragment) {
    return fragment && TFr(fragment)->fragment.solo;
}

size_t uapmd_track_fragment_graph_type(uapmd_track_fragment_t fragment, char* buf, size_t buf_size) {
    if (!fragment) return 0;
    return copy_string(TFr(fragment)->fragment.graphType, buf, buf_size);
}

size_t uapmd_track_fragment_graph_bytes(uapmd_track_fragment_t fragment, uint8_t* buf, size_t buf_size) {
    if (!fragment) return 0;
    return copy_bytes(TFr(fragment)->fragment.graphBytes, buf, buf_size);
}

uint32_t uapmd_track_fragment_clip_count(uapmd_track_fragment_t fragment) {
    return fragment ? static_cast<uint32_t>(TFr(fragment)->clipBoxes.size()) : 0;
}

uapmd_clip_fragment_t uapmd_track_fragment_get_clip(uapmd_track_fragment_t fragment, uint32_t index) {
    if (!fragment) return nullptr;
    auto* box = TFr(fragment);
    if (index >= box->clipBoxes.size()) return nullptr;
    return reinterpret_cast<uapmd_clip_fragment_t>(box->clipBoxes[index].get());
}

uint32_t uapmd_track_fragment_plugin_count(uapmd_track_fragment_t fragment) {
    return fragment ? static_cast<uint32_t>(TFr(fragment)->fragment.plugins.size()) : 0;
}

bool uapmd_track_fragment_get_plugin(uapmd_track_fragment_t fragment, uint32_t index, uapmd_track_plugin_fragment_t* out) {
    if (!fragment || !out) return false;
    const auto& plugins = TFr(fragment)->fragment.plugins;
    if (index >= plugins.size()) return false;
    const auto& p = plugins[index];
    out->node_id = p.nodeId.c_str();
    out->plugin_id = p.pluginId.c_str();
    out->format = p.format.c_str();
    out->display_name = p.displayName.c_str();
    out->group_index = p.groupIndex;
    out->state_size = static_cast<uint32_t>(p.state.size());
    out->state = p.state.empty() ? nullptr : p.state.data();
    return true;
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  TimelineFacade — history accessors and undoable mutations
 * ═══════════════════════════════════════════════════════════════════════════ */

uapmd_undo_engine_t uapmd_tl_undo_engine(uapmd_timeline_facade_t tl) {
    return reinterpret_cast<uapmd_undo_engine_t>(&TF(tl)->undoEngine());
}

uapmd_project_commands_t uapmd_tl_commands(uapmd_timeline_facade_t tl) {
    return reinterpret_cast<uapmd_project_commands_t>(&TF(tl)->commands());
}

uapmd_address_book_t uapmd_tl_addresses(uapmd_timeline_facade_t tl) {
    return reinterpret_cast<uapmd_address_book_t>(&TF(tl)->addresses());
}

void uapmd_tl_begin_document_transaction(uapmd_timeline_facade_t tl) { TF(tl)->beginDocumentTransaction(); }
void uapmd_tl_end_document_transaction(uapmd_timeline_facade_t tl)   { TF(tl)->endDocumentTransaction(); }

bool uapmd_tl_remove_clip_with_origin(uapmd_timeline_facade_t tl, int32_t track_index, int32_t clip_id, uapmd_mutation_origin_t origin) {
    return TF(tl)->removeClipFromTrack(track_index, clip_id, to_cpp_origin(origin));
}

bool uapmd_tl_clear_clips_from_track(uapmd_timeline_facade_t tl, int32_t track_index, uapmd_mutation_origin_t origin) {
    return TF(tl)->clearClipsFromTrack(track_index, to_cpp_origin(origin));
}

bool uapmd_tl_clip_enabled(uapmd_timeline_facade_t tl, int32_t track_index, int32_t clip_id) {
    return TF(tl)->clipEnabled(track_index, clip_id);
}

bool uapmd_tl_replace_midi_clip_content(uapmd_timeline_facade_t tl,
                                          int32_t track_index,
                                          int32_t clip_id,
                                          const uapmd_ump_t* ump_events,
                                          uint32_t ump_event_count,
                                          const uint64_t* tick_timestamps,
                                          uint32_t tick_count,
                                          uapmd_mutation_origin_t origin) {
    std::vector<uapmd_ump_t> events(ump_events, ump_events ? ump_events + ump_event_count : nullptr);
    std::vector<uint64_t> ticks(tick_timestamps, tick_timestamps ? tick_timestamps + tick_count : nullptr);
    return TF(tl)->replaceMidiClipContent(track_index, clip_id, std::move(events), std::move(ticks), to_cpp_origin(origin));
}

bool uapmd_tl_replace_audio_clip_content(uapmd_timeline_facade_t tl,
                                           int32_t track_index,
                                           int32_t clip_id,
                                           const char* filepath,
                                           const uapmd_clip_marker_t* markers,
                                           uint32_t marker_count,
                                           const uapmd_audio_warp_point_t* warps,
                                           uint32_t warp_count,
                                           const uapmd_clip_marker_t* master_markers,
                                           uint32_t master_marker_count,
                                           uapmd_mutation_origin_t origin) {
    auto masters = markers_from_c(master_markers, master_marker_count);
    return TF(tl)->replaceAudioClipContent(
        track_index, clip_id,
        filepath ? filepath : "",
        markers_from_c(markers, marker_count),
        warps_from_c(warps, warp_count),
        masters,
        to_cpp_origin(origin));
}

uapmd_clip_fragment_t uapmd_tl_capture_clip_fragment(uapmd_timeline_facade_t tl, int32_t track_index, int32_t clip_id) {
    auto captured = TF(tl)->captureClipFragment(track_index, clip_id);
    if (!captured) return nullptr;
    auto box = std::make_unique<ClipFragmentBox>();
    box->owned = std::move(*captured);
    box->ref = nullptr;
    box->ownsFragment = true;
    build_clip_views(*box);
    return reinterpret_cast<uapmd_clip_fragment_t>(box.release());
}

uapmd_clip_add_result_t uapmd_tl_attach_clip_fragment(uapmd_timeline_facade_t tl,
                                                        int32_t track_index,
                                                        uapmd_clip_fragment_t fragment,
                                                        uapmd_object_id_policy_t id_policy) {
    if (!fragment) {
        tl_undo_error = "null clip fragment";
        return { -1, -1, false, tl_undo_error.c_str() };
    }
    auto r = TF(tl)->attachClipFragment(
        track_index,
        CF(fragment)->get(),
        static_cast<uapmd::ProjectObjectIdPolicy>(id_policy));
    tl_undo_error = r.error;
    return { r.clipId, r.sourceNodeId, r.success, tl_undo_error.empty() ? nullptr : tl_undo_error.c_str() };
}

void uapmd_tl_capture_track_fragment(uapmd_timeline_facade_t tl,
                                       int32_t track_index,
                                       void* user_data,
                                       uapmd_track_fragment_cb_t callback) {
    if (!callback) return;
    TF(tl)->captureTrackFragment(
        track_index,
        [user_data, callback](std::optional<uapmd::ProjectTrackFragment> fragment, std::string error) {
            if (!fragment) {
                callback(nullptr, error.empty() ? "capture failed" : error.c_str(), user_data);
                return;
            }
            auto box = make_track_box(std::move(*fragment));
            callback(reinterpret_cast<uapmd_track_fragment_t>(box.release()),
                     error.empty() ? nullptr : error.c_str(),
                     user_data);
        });
}

void uapmd_tl_attach_track_fragment(uapmd_timeline_facade_t tl,
                                      uapmd_track_fragment_t fragment,
                                      uapmd_track_attach_options_t options,
                                      void* user_data,
                                      uapmd_track_mutation_cb_t callback) {
    if (!callback) return;
    if (!fragment) {
        callback(-1, "null track fragment", user_data);
        return;
    }
    uapmd::ProjectTrackAttachOptions opts;
    opts.idPolicy = static_cast<uapmd::ProjectObjectIdPolicy>(options.id_policy);
    opts.insertionIndex = options.insertion_index;
    opts.includePlugins = options.include_plugins;
    opts.includePluginState = options.include_plugin_state;
    opts.includeClips = options.include_clips;
    TF(tl)->attachTrackFragment(
        TFr(fragment)->fragment,
        opts,
        [user_data, callback](int32_t trackIndex, std::string error) {
            callback(trackIndex, error.empty() ? nullptr : error.c_str(), user_data);
        });
}

/* Shared shape for the asynchronous track mutations below. */
static uapmd::TimelineFacade::TrackAttachCallback wrap_track_callback(void* user_data, uapmd_track_mutation_cb_t callback) {
    return [user_data, callback](int32_t trackIndex, std::string error) {
        callback(trackIndex, error.empty() ? nullptr : error.c_str(), user_data);
    };
}

void uapmd_tl_add_empty_track(uapmd_timeline_facade_t tl, uapmd_mutation_origin_t origin, void* user_data, uapmd_track_mutation_cb_t callback) {
    if (!callback) return;
    TF(tl)->addEmptyTrack(to_cpp_origin(origin), wrap_track_callback(user_data, callback));
}

void uapmd_tl_remove_track(uapmd_timeline_facade_t tl, int32_t track_index, uapmd_mutation_origin_t origin, void* user_data, uapmd_track_mutation_cb_t callback) {
    if (!callback) return;
    TF(tl)->removeTrack(track_index, to_cpp_origin(origin), wrap_track_callback(user_data, callback));
}

void uapmd_tl_record_track_addition(uapmd_timeline_facade_t tl, int32_t track_index, uapmd_mutation_origin_t origin, void* user_data, uapmd_track_mutation_cb_t callback) {
    if (!callback) return;
    TF(tl)->recordTrackAddition(track_index, to_cpp_origin(origin), wrap_track_callback(user_data, callback));
}

void uapmd_tl_set_plugin_state(uapmd_timeline_facade_t tl, int32_t instance_id, const uint8_t* state, size_t state_size, uapmd_mutation_origin_t origin, void* user_data, uapmd_undo_completion_cb_t callback) {
    std::vector<uint8_t> bytes(state, state ? state + state_size : nullptr);
    TF(tl)->setPluginState(instance_id, std::move(bytes), to_cpp_origin(origin), wrap_completion(user_data, callback));
}

void uapmd_tl_load_plugin_preset(uapmd_timeline_facade_t tl, int32_t instance_id, int32_t preset_index, uapmd_mutation_origin_t origin, void* user_data, uapmd_undo_completion_cb_t callback) {
    TF(tl)->loadPluginPreset(instance_id, preset_index, to_cpp_origin(origin), wrap_completion(user_data, callback));
}

void uapmd_tl_record_plugin_instance_addition(uapmd_timeline_facade_t tl, int32_t instance_id, uapmd_mutation_origin_t origin, void* user_data, uapmd_undo_completion_cb_t callback) {
    TF(tl)->recordPluginInstanceAddition(instance_id, to_cpp_origin(origin), wrap_completion(user_data, callback));
}

void uapmd_tl_remove_plugin_instance(uapmd_timeline_facade_t tl, int32_t instance_id, uapmd_mutation_origin_t origin, void* user_data, uapmd_undo_completion_cb_t callback) {
    TF(tl)->removePluginInstance(instance_id, to_cpp_origin(origin), wrap_completion(user_data, callback));
}

bool uapmd_tl_has_pending_plugin_mutations(uapmd_timeline_facade_t tl) {
    return TF(tl)->hasPendingPluginMutations();
}
