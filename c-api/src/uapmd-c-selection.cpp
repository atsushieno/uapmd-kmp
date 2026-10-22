/* uapmd C API — implementation for the project selection service bindings */

#include "c-api/uapmd-c-selection.h"
#include <uapmd-engine/uapmd-engine.hpp>
#include <uapmd-data/uapmd-data.hpp>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <utility>
#include <variant>
#include <vector>

namespace {

using uapmd::MidiAutomationSelectionRegion;
using uapmd::MidiNoteSelectionRegion;
using uapmd::ProjectSelection;
using uapmd::ProjectSelectionRegion;
using uapmd::TimelineSelectionRegion;

struct SelectionBox {
    ProjectSelection selection;
};

struct ProjectionBox {
    uapmd::ProjectSelectionProjection projection;
};

uapmd::TimelineFacade* TF(uapmd_timeline_facade_t h) { return reinterpret_cast<uapmd::TimelineFacade*>(h); }
uapmd::ProjectSelectionService* SVC(uapmd_selection_service_t h) { return reinterpret_cast<uapmd::ProjectSelectionService*>(h); }
SelectionBox* SEL(uapmd_selection_t h) { return reinterpret_cast<SelectionBox*>(h); }
ProjectionBox* PRJ(uapmd_selection_projection_t h) { return reinterpret_cast<ProjectionBox*>(h); }

uapmd_selection_t wrap(ProjectSelection selection) {
    return reinterpret_cast<uapmd_selection_t>(new SelectionBox{std::move(selection)});
}

thread_local std::string tl_selection_error;

const ProjectSelectionRegion* region_at(uapmd_selection_t h, uint32_t index) {
    if (!h || index >= SEL(h)->selection.regions.size())
        return nullptr;
    return &SEL(h)->selection.regions[index];
}

template <typename Region>
const Region* region_as(uapmd_selection_t h, uint32_t index) {
    auto* r = region_at(h, index);
    return r ? std::get_if<Region>(r) : nullptr;
}

/* The clip and lane accessors are shared by the two MIDI region kinds. */
const std::string* clip_of(uapmd_selection_t h, uint32_t index) {
    if (auto* n = region_as<MidiNoteSelectionRegion>(h, index))
        return &n->clip_id;
    if (auto* a = region_as<MidiAutomationSelectionRegion>(h, index))
        return &a->lane.clip_id;
    return nullptr;
}

int64_t ctx_out(std::optional<uapmd::SelectionContextId> c) {
    return c ? static_cast<int64_t>(*c) : UAPMD_SELECTION_NO_CONTEXT;
}

/* Forwards service notifications to C callbacks. */
class CListener final : public uapmd::ProjectSelectionListener {
    void* user_data_;
    uapmd_selection_changed_cb_t selection_changed_;
    uapmd_selection_active_context_changed_cb_t active_changed_;
    uapmd_selection_context_removed_cb_t removed_;

public:
    CListener(void* user_data, uapmd_selection_changed_cb_t sc,
              uapmd_selection_active_context_changed_cb_t ac, uapmd_selection_context_removed_cb_t cr)
        : user_data_(user_data), selection_changed_(sc), active_changed_(ac), removed_(cr) {}

    void selectionChanged(uapmd::SelectionContextId context, const ProjectSelection& previous,
                          const ProjectSelection& current) override {
        if (!selection_changed_)
            return;
        // Borrowed views: copies that live only for the call.
        SelectionBox prev{previous}, cur{current};
        selection_changed_(static_cast<int64_t>(context),
                           reinterpret_cast<uapmd_selection_t>(&prev),
                           reinterpret_cast<uapmd_selection_t>(&cur), user_data_);
    }
    void activeContextChanged(std::optional<uapmd::SelectionContextId> previous,
                              std::optional<uapmd::SelectionContextId> current) override {
        if (active_changed_)
            active_changed_(ctx_out(previous), ctx_out(current), user_data_);
    }
    void selectionContextRemoved(uapmd::SelectionContextId context) override {
        if (removed_)
            removed_(static_cast<int64_t>(context), user_data_);
    }
};

struct ListenerKey {
    uapmd::ProjectSelectionService* service;
    uapmd::SelectionListenerToken token;
    bool operator<(const ListenerKey& o) const {
        return std::tie(service, token) < std::tie(o.service, o.token);
    }
};
std::mutex g_listener_mutex;
std::map<ListenerKey, std::unique_ptr<CListener>> g_listeners;

} // namespace

/* ── The service ─────────────────────────────────────────────────────────── */

uapmd_selection_service_t uapmd_tl_selections(uapmd_timeline_facade_t tl) {
    return reinterpret_cast<uapmd_selection_service_t>(&TF(tl)->document().selections());
}

int64_t uapmd_selection_service_default_context(uapmd_selection_service_t svc) {
    return static_cast<int64_t>(SVC(svc)->defaultContext());
}

int64_t uapmd_selection_service_create_context(uapmd_selection_service_t svc, const char* owner_object_id) {
    std::optional<uapmd::ProjectObjectId> owner;
    if (owner_object_id)
        owner = owner_object_id;
    return static_cast<int64_t>(SVC(svc)->createContext(std::move(owner)));
}

bool uapmd_selection_service_has_context(uapmd_selection_service_t svc, int64_t context) {
    return context >= 0 && SVC(svc)->hasContext(static_cast<uapmd::SelectionContextId>(context));
}

void uapmd_selection_service_remove_context(uapmd_selection_service_t svc, int64_t context) {
    if (context >= 0)
        SVC(svc)->removeContext(static_cast<uapmd::SelectionContextId>(context));
}

uapmd_selection_t uapmd_selection_service_snapshot(uapmd_selection_service_t svc, int64_t context) {
    if (context < 0)
        return nullptr;
    auto snapshot = SVC(svc)->snapshot(static_cast<uapmd::SelectionContextId>(context));
    return snapshot ? wrap(std::move(*snapshot)) : nullptr;
}

uapmd_selection_t uapmd_selection_service_active_selection(uapmd_selection_service_t svc) {
    return wrap(SVC(svc)->activeSelection());
}

bool uapmd_selection_service_replace(uapmd_selection_service_t svc, int64_t context, uapmd_selection_t selection) {
    tl_selection_error.clear();
    if (context < 0 || !selection) {
        tl_selection_error = "Invalid selection context or selection.";
        return false;
    }
    auto result = SVC(svc)->replace(static_cast<uapmd::SelectionContextId>(context), SEL(selection)->selection);
    if (!result)
        tl_selection_error = result.error;
    return result.success;
}

void uapmd_selection_service_clear(uapmd_selection_service_t svc, int64_t context) {
    if (context >= 0)
        SVC(svc)->clear(static_cast<uapmd::SelectionContextId>(context));
}

const char* uapmd_selection_service_last_error(void) { return tl_selection_error.c_str(); }

int64_t uapmd_selection_service_active_context(uapmd_selection_service_t svc) {
    return ctx_out(SVC(svc)->activeContext());
}

bool uapmd_selection_service_set_active_context(uapmd_selection_service_t svc, int64_t context) {
    if (context < 0)
        return SVC(svc)->setActiveContext(std::nullopt);
    return SVC(svc)->setActiveContext(static_cast<uapmd::SelectionContextId>(context));
}

int64_t uapmd_selection_service_add_listener(uapmd_selection_service_t svc, void* user_data,
                                              uapmd_selection_changed_cb_t selection_changed,
                                              uapmd_selection_active_context_changed_cb_t active_context_changed,
                                              uapmd_selection_context_removed_cb_t context_removed) {
    auto listener = std::make_unique<CListener>(user_data, selection_changed, active_context_changed, context_removed);
    auto token = SVC(svc)->addListener(*listener);
    std::lock_guard lock(g_listener_mutex);
    g_listeners[{SVC(svc), token}] = std::move(listener);
    return static_cast<int64_t>(token);
}

void uapmd_selection_service_remove_listener(uapmd_selection_service_t svc, int64_t token) {
    if (token <= 0)
        return;
    SVC(svc)->removeListener(static_cast<uapmd::SelectionListenerToken>(token));
    std::lock_guard lock(g_listener_mutex);
    g_listeners.erase({SVC(svc), static_cast<uapmd::SelectionListenerToken>(token)});
}

/* ── A selection: reading ────────────────────────────────────────────────── */

void uapmd_selection_destroy(uapmd_selection_t selection) { delete SEL(selection); }

uint32_t uapmd_selection_object_count(uapmd_selection_t selection) {
    return selection ? static_cast<uint32_t>(SEL(selection)->selection.objects.size()) : 0;
}

const char* uapmd_selection_object_at(uapmd_selection_t selection, uint32_t index) {
    if (!selection || index >= SEL(selection)->selection.objects.size())
        return nullptr;
    return SEL(selection)->selection.objects[index].c_str();
}

uint32_t uapmd_selection_region_count(uapmd_selection_t selection) {
    return selection ? static_cast<uint32_t>(SEL(selection)->selection.regions.size()) : 0;
}

int32_t uapmd_selection_region_kind(uapmd_selection_t selection, uint32_t region) {
    auto* r = region_at(selection, region);
    return r ? static_cast<int32_t>(r->index()) : -1;
}

double uapmd_selection_region_start_seconds(uapmd_selection_t selection, uint32_t region) {
    auto* r = region_as<TimelineSelectionRegion>(selection, region);
    return r ? r->start_seconds : 0.0;
}

double uapmd_selection_region_end_seconds(uapmd_selection_t selection, uint32_t region) {
    auto* r = region_as<TimelineSelectionRegion>(selection, region);
    return r ? r->end_seconds : 0.0;
}

bool uapmd_selection_region_tracks_restricted(uapmd_selection_t selection, uint32_t region) {
    auto* r = region_as<TimelineSelectionRegion>(selection, region);
    return r && r->track_ids.has_value();
}

uint32_t uapmd_selection_region_track_count(uapmd_selection_t selection, uint32_t region) {
    auto* r = region_as<TimelineSelectionRegion>(selection, region);
    return r && r->track_ids ? static_cast<uint32_t>(r->track_ids->size()) : 0;
}

const char* uapmd_selection_region_track_at(uapmd_selection_t selection, uint32_t region, uint32_t index) {
    auto* r = region_as<TimelineSelectionRegion>(selection, region);
    if (!r || !r->track_ids || index >= r->track_ids->size())
        return nullptr;
    return (*r->track_ids)[index].c_str();
}

const char* uapmd_selection_region_clip_id(uapmd_selection_t selection, uint32_t region) {
    auto* clip = clip_of(selection, region);
    return clip ? clip->c_str() : nullptr;
}

int64_t uapmd_selection_region_start_tick(uapmd_selection_t selection, uint32_t region) {
    if (auto* n = region_as<MidiNoteSelectionRegion>(selection, region))
        return static_cast<int64_t>(n->start_tick);
    if (auto* a = region_as<MidiAutomationSelectionRegion>(selection, region))
        return static_cast<int64_t>(a->start_tick);
    return 0;
}

int64_t uapmd_selection_region_end_tick(uapmd_selection_t selection, uint32_t region) {
    if (auto* n = region_as<MidiNoteSelectionRegion>(selection, region))
        return static_cast<int64_t>(n->end_tick);
    if (auto* a = region_as<MidiAutomationSelectionRegion>(selection, region))
        return static_cast<int64_t>(a->end_tick);
    return 0;
}

int32_t uapmd_selection_region_pitch_min(uapmd_selection_t selection, uint32_t region) {
    auto* n = region_as<MidiNoteSelectionRegion>(selection, region);
    return n && n->pitches ? n->pitches->first : -1;
}

int32_t uapmd_selection_region_pitch_max(uapmd_selection_t selection, uint32_t region) {
    auto* n = region_as<MidiNoteSelectionRegion>(selection, region);
    return n && n->pitches ? n->pitches->second : -1;
}

int32_t uapmd_selection_region_note_group(uapmd_selection_t selection, uint32_t region) {
    auto* n = region_as<MidiNoteSelectionRegion>(selection, region);
    return n && n->group ? *n->group : -1;
}

int32_t uapmd_selection_region_note_channel(uapmd_selection_t selection, uint32_t region) {
    auto* n = region_as<MidiNoteSelectionRegion>(selection, region);
    return n && n->channel ? *n->channel : -1;
}

const char* uapmd_selection_region_lane_note_id(uapmd_selection_t selection, uint32_t region) {
    auto* a = region_as<MidiAutomationSelectionRegion>(selection, region);
    return a && a->lane.note_id ? a->lane.note_id->c_str() : nullptr;
}

int32_t uapmd_selection_region_lane_group(uapmd_selection_t selection, uint32_t region) {
    auto* a = region_as<MidiAutomationSelectionRegion>(selection, region);
    return a ? a->lane.group : -1;
}

int32_t uapmd_selection_region_lane_channel(uapmd_selection_t selection, uint32_t region) {
    auto* a = region_as<MidiAutomationSelectionRegion>(selection, region);
    return a ? a->lane.channel : -1;
}

int32_t uapmd_selection_region_lane_status(uapmd_selection_t selection, uint32_t region) {
    auto* a = region_as<MidiAutomationSelectionRegion>(selection, region);
    return a ? a->lane.status : -1;
}

int32_t uapmd_selection_region_lane_parameter(uapmd_selection_t selection, uint32_t region) {
    auto* a = region_as<MidiAutomationSelectionRegion>(selection, region);
    return a ? a->lane.parameter : -1;
}

bool uapmd_selection_region_has_values(uapmd_selection_t selection, uint32_t region) {
    auto* a = region_as<MidiAutomationSelectionRegion>(selection, region);
    return a && a->values.has_value();
}

double uapmd_selection_region_value_min(uapmd_selection_t selection, uint32_t region) {
    auto* a = region_as<MidiAutomationSelectionRegion>(selection, region);
    return a && a->values ? a->values->first : 0.0;
}

double uapmd_selection_region_value_max(uapmd_selection_t selection, uint32_t region) {
    auto* a = region_as<MidiAutomationSelectionRegion>(selection, region);
    return a && a->values ? a->values->second : 0.0;
}

/* ── A selection: building ───────────────────────────────────────────────── */

uapmd_selection_t uapmd_selection_create(void) { return wrap({}); }

void uapmd_selection_add_object(uapmd_selection_t selection, const char* object_id) {
    if (selection && object_id)
        SEL(selection)->selection.objects.emplace_back(object_id);
}

uint32_t uapmd_selection_add_timeline_region(uapmd_selection_t selection, double start_seconds, double end_seconds) {
    auto& regions = SEL(selection)->selection.regions;
    TimelineSelectionRegion region;
    region.start_seconds = start_seconds;
    region.end_seconds = end_seconds;
    regions.emplace_back(std::move(region));
    return static_cast<uint32_t>(regions.size() - 1);
}

void uapmd_selection_region_restrict_tracks(uapmd_selection_t selection, uint32_t region) {
    if (auto* r = const_cast<TimelineSelectionRegion*>(region_as<TimelineSelectionRegion>(selection, region)))
        if (!r->track_ids)
            r->track_ids.emplace();
}

void uapmd_selection_region_add_track(uapmd_selection_t selection, uint32_t region, const char* track_id) {
    auto* r = const_cast<TimelineSelectionRegion*>(region_as<TimelineSelectionRegion>(selection, region));
    if (!r || !track_id)
        return;
    if (!r->track_ids)
        r->track_ids.emplace();
    r->track_ids->emplace_back(track_id);
}

uint32_t uapmd_selection_add_midi_note_region(uapmd_selection_t selection, const char* clip_id,
                                                int64_t start_tick, int64_t end_tick,
                                                int32_t pitch_min, int32_t pitch_max,
                                                int32_t group, int32_t channel) {
    auto& regions = SEL(selection)->selection.regions;
    MidiNoteSelectionRegion region;
    region.clip_id = clip_id ? clip_id : "";
    region.start_tick = static_cast<uint64_t>(start_tick < 0 ? 0 : start_tick);
    region.end_tick = static_cast<uint64_t>(end_tick < 0 ? 0 : end_tick);
    if (pitch_min >= 0 && pitch_max >= 0)
        region.pitches = std::make_pair(static_cast<uint8_t>(pitch_min), static_cast<uint8_t>(pitch_max));
    if (group >= 0)
        region.group = static_cast<uint8_t>(group);
    if (channel >= 0)
        region.channel = static_cast<uint8_t>(channel);
    regions.emplace_back(std::move(region));
    return static_cast<uint32_t>(regions.size() - 1);
}

uint32_t uapmd_selection_add_midi_automation_region(uapmd_selection_t selection, const char* clip_id,
                                                      const char* note_id,
                                                      int32_t group, int32_t channel,
                                                      int32_t status, int32_t parameter,
                                                      int64_t start_tick, int64_t end_tick,
                                                      bool has_values, double value_min, double value_max) {
    auto& regions = SEL(selection)->selection.regions;
    MidiAutomationSelectionRegion region;
    region.lane.clip_id = clip_id ? clip_id : "";
    if (note_id)
        region.lane.note_id = std::string(note_id);
    region.lane.group = static_cast<uint8_t>(group);
    region.lane.channel = static_cast<uint8_t>(channel);
    region.lane.status = static_cast<uint8_t>(status);
    region.lane.parameter = static_cast<uint16_t>(parameter);
    region.start_tick = static_cast<uint64_t>(start_tick < 0 ? 0 : start_tick);
    region.end_tick = static_cast<uint64_t>(end_tick < 0 ? 0 : end_tick);
    if (has_values)
        region.values = std::make_pair(value_min, value_max);
    regions.emplace_back(std::move(region));
    return static_cast<uint32_t>(regions.size() - 1);
}

/* ── Projection ──────────────────────────────────────────────────────────── */

uapmd_selection_projection_t uapmd_selection_project(uapmd_timeline_facade_t tl, uapmd_selection_t selection) {
    if (!selection)
        return nullptr;
    auto* box = new ProjectionBox{uapmd::projectSelection(TF(tl)->document().view(), SEL(selection)->selection)};
    return reinterpret_cast<uapmd_selection_projection_t>(box);
}

void uapmd_selection_projection_destroy(uapmd_selection_projection_t projection) { delete PRJ(projection); }

uint32_t uapmd_selection_projection_clip_count(uapmd_selection_projection_t projection) {
    return projection ? static_cast<uint32_t>(PRJ(projection)->projection.clip_ids.size()) : 0;
}

const char* uapmd_selection_projection_clip_at(uapmd_selection_projection_t projection, uint32_t index) {
    if (!projection || index >= PRJ(projection)->projection.clip_ids.size())
        return nullptr;
    return PRJ(projection)->projection.clip_ids[index].c_str();
}

uint32_t uapmd_selection_projection_track_count(uapmd_selection_projection_t projection) {
    return projection ? static_cast<uint32_t>(PRJ(projection)->projection.track_ids.size()) : 0;
}

const char* uapmd_selection_projection_track_at(uapmd_selection_projection_t projection, uint32_t index) {
    if (!projection || index >= PRJ(projection)->projection.track_ids.size())
        return nullptr;
    return PRJ(projection)->projection.track_ids[index].c_str();
}

bool uapmd_selection_projection_has_time_range(uapmd_selection_projection_t projection) {
    return projection && PRJ(projection)->projection.time_range.has_value();
}

double uapmd_selection_projection_range_start_seconds(uapmd_selection_projection_t projection) {
    return projection && PRJ(projection)->projection.time_range ? PRJ(projection)->projection.time_range->first : 0.0;
}

double uapmd_selection_projection_range_end_seconds(uapmd_selection_projection_t projection) {
    return projection && PRJ(projection)->projection.time_range ? PRJ(projection)->projection.time_range->second : 0.0;
}
