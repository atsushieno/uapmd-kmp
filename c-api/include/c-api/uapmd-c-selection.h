/* uapmd C API — bindings for the project selection service (uapmd-data ProjectSelection.hpp)
 *
 * The selection service is a model-thread service owned by the project document. A selection is
 * a list of selected objects (ordered by relevance; the first is primary) plus a list of regions
 * (a timeline range, a MIDI note region, or a MIDI automation region). Each editor works in its
 * own selection context; the document also has a default context, and exactly one context at a
 * time is the active one, whose selection the rest of the application acts on.
 *
 * Selections cross this API as opaque snapshot handles with scalar and string accessors, so the
 * bindings never marshal a variant. Every accessor takes the snapshot and an index. A snapshot
 * is a copy: it never changes after it was taken, and strings it returns stay valid until the
 * snapshot is destroyed.
 *
 * Time in regions: timeline regions use seconds, MIDI regions use ticks of the clip's content.
 * Optional constraints read as -1 (integers) when absent.
 */
#ifndef UAPMD_C_SELECTION_H
#define UAPMD_C_SELECTION_H

#include "uapmd-c-common.h"
#include "uapmd-c-engine.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct uapmd_selection_service*    uapmd_selection_service_t;
typedef struct uapmd_selection*            uapmd_selection_t;
typedef struct uapmd_selection_projection* uapmd_selection_projection_t;

/* Context ids are positive; a negative value means "no context". */
#define UAPMD_SELECTION_NO_CONTEXT ((int64_t)-1)

/* ProjectSelectionRegion alternatives. */
typedef enum uapmd_selection_region_kind {
    UAPMD_SELECTION_REGION_TIMELINE        = 0,
    UAPMD_SELECTION_REGION_MIDI_NOTES      = 1,
    UAPMD_SELECTION_REGION_MIDI_AUTOMATION = 2
} uapmd_selection_region_kind_t;

/* ── The service ─────────────────────────────────────────────────────────── */

/* TimelineFacade::document().selections(). Borrowed; valid as long as the timeline. */
UAPMD_C_EXPORT uapmd_selection_service_t uapmd_tl_selections(uapmd_timeline_facade_t tl);

UAPMD_C_EXPORT int64_t uapmd_selection_service_default_context(uapmd_selection_service_t svc);
/* `owner_object_id` (NULL for none) is the clip or track the context belongs to; a context is
 * dropped automatically when its owner leaves the document. */
UAPMD_C_EXPORT int64_t uapmd_selection_service_create_context(uapmd_selection_service_t svc, const char* owner_object_id);
UAPMD_C_EXPORT bool    uapmd_selection_service_has_context(uapmd_selection_service_t svc, int64_t context);
UAPMD_C_EXPORT void    uapmd_selection_service_remove_context(uapmd_selection_service_t svc, int64_t context);

/* Copies of a context's selection and of the active context's selection. The first is NULL for
 * an unknown context. Destroy with uapmd_selection_destroy(). */
UAPMD_C_EXPORT uapmd_selection_t uapmd_selection_service_snapshot(uapmd_selection_service_t svc, int64_t context);
UAPMD_C_EXPORT uapmd_selection_t uapmd_selection_service_active_selection(uapmd_selection_service_t svc);

/* Replaces a context's selection. False when it was rejected (unknown context, an object that is
 * not in the document, an invalid region); the reason is then in
 * uapmd_selection_service_last_error(). */
UAPMD_C_EXPORT bool uapmd_selection_service_replace(uapmd_selection_service_t svc, int64_t context, uapmd_selection_t selection);
UAPMD_C_EXPORT void uapmd_selection_service_clear(uapmd_selection_service_t svc, int64_t context);
UAPMD_C_EXPORT const char* uapmd_selection_service_last_error(void);

/* UAPMD_SELECTION_NO_CONTEXT when none is active. Pass UAPMD_SELECTION_NO_CONTEXT to deactivate.
 * False for an unknown context. */
UAPMD_C_EXPORT int64_t uapmd_selection_service_active_context(uapmd_selection_service_t svc);
UAPMD_C_EXPORT bool    uapmd_selection_service_set_active_context(uapmd_selection_service_t svc, int64_t context);

/* Listener. Callbacks run synchronously on the model thread, except while a document batch is
 * open or from inside another callback, when they are delivered afterwards. The selection
 * handles passed to `selection_changed` are valid only during the call. Any callback may be NULL.
 * Returns a token (> 0) for uapmd_selection_service_remove_listener(). */
typedef void (*uapmd_selection_changed_cb_t)(int64_t context, uapmd_selection_t previous, uapmd_selection_t current, void* user_data);
typedef void (*uapmd_selection_active_context_changed_cb_t)(int64_t previous_context, int64_t current_context, void* user_data);
typedef void (*uapmd_selection_context_removed_cb_t)(int64_t context, void* user_data);

UAPMD_C_EXPORT int64_t uapmd_selection_service_add_listener(uapmd_selection_service_t svc, void* user_data,
                                                              uapmd_selection_changed_cb_t selection_changed,
                                                              uapmd_selection_active_context_changed_cb_t active_context_changed,
                                                              uapmd_selection_context_removed_cb_t context_removed);
UAPMD_C_EXPORT void    uapmd_selection_service_remove_listener(uapmd_selection_service_t svc, int64_t token);

/* ── A selection: reading ────────────────────────────────────────────────── */

UAPMD_C_EXPORT void     uapmd_selection_destroy(uapmd_selection_t selection);

UAPMD_C_EXPORT uint32_t    uapmd_selection_object_count(uapmd_selection_t selection);
UAPMD_C_EXPORT const char* uapmd_selection_object_at(uapmd_selection_t selection, uint32_t index);

UAPMD_C_EXPORT uint32_t uapmd_selection_region_count(uapmd_selection_t selection);
/* uapmd_selection_region_kind_t */
UAPMD_C_EXPORT int32_t  uapmd_selection_region_kind(uapmd_selection_t selection, uint32_t region);

/* Timeline regions. `restricted` is false when the region spans all tracks; otherwise the
 * region covers exactly the listed tracks (possibly none). */
UAPMD_C_EXPORT double      uapmd_selection_region_start_seconds(uapmd_selection_t selection, uint32_t region);
UAPMD_C_EXPORT double      uapmd_selection_region_end_seconds(uapmd_selection_t selection, uint32_t region);
UAPMD_C_EXPORT bool        uapmd_selection_region_tracks_restricted(uapmd_selection_t selection, uint32_t region);
UAPMD_C_EXPORT uint32_t    uapmd_selection_region_track_count(uapmd_selection_t selection, uint32_t region);
UAPMD_C_EXPORT const char* uapmd_selection_region_track_at(uapmd_selection_t selection, uint32_t region, uint32_t index);

/* MIDI note and MIDI automation regions. Tick ranges are half-open; equal ends mean a point. */
UAPMD_C_EXPORT const char* uapmd_selection_region_clip_id(uapmd_selection_t selection, uint32_t region);
UAPMD_C_EXPORT int64_t     uapmd_selection_region_start_tick(uapmd_selection_t selection, uint32_t region);
UAPMD_C_EXPORT int64_t     uapmd_selection_region_end_tick(uapmd_selection_t selection, uint32_t region);

/* MIDI note regions. Pitch bounds include both ends; each is -1 when the region has no pitch
 * constraint. Group and channel are -1 when unconstrained. */
UAPMD_C_EXPORT int32_t uapmd_selection_region_pitch_min(uapmd_selection_t selection, uint32_t region);
UAPMD_C_EXPORT int32_t uapmd_selection_region_pitch_max(uapmd_selection_t selection, uint32_t region);
UAPMD_C_EXPORT int32_t uapmd_selection_region_note_group(uapmd_selection_t selection, uint32_t region);
UAPMD_C_EXPORT int32_t uapmd_selection_region_note_channel(uapmd_selection_t selection, uint32_t region);

/* MIDI automation regions: the lane (clip, optional note, group, channel, MIDI channel status
 * including registered/assignable per-note controllers, parameter), then the value range. */
UAPMD_C_EXPORT const char* uapmd_selection_region_lane_note_id(uapmd_selection_t selection, uint32_t region); /* NULL for a clip-level lane */
UAPMD_C_EXPORT int32_t     uapmd_selection_region_lane_group(uapmd_selection_t selection, uint32_t region);
UAPMD_C_EXPORT int32_t     uapmd_selection_region_lane_channel(uapmd_selection_t selection, uint32_t region);
UAPMD_C_EXPORT int32_t     uapmd_selection_region_lane_status(uapmd_selection_t selection, uint32_t region);
UAPMD_C_EXPORT int32_t     uapmd_selection_region_lane_parameter(uapmd_selection_t selection, uint32_t region);
UAPMD_C_EXPORT bool        uapmd_selection_region_has_values(uapmd_selection_t selection, uint32_t region);
UAPMD_C_EXPORT double      uapmd_selection_region_value_min(uapmd_selection_t selection, uint32_t region);
UAPMD_C_EXPORT double      uapmd_selection_region_value_max(uapmd_selection_t selection, uint32_t region);

/* ── A selection: building ───────────────────────────────────────────────── */

/* An empty selection owned by the caller; fill it, pass it to ..._replace(), destroy it. */
UAPMD_C_EXPORT uapmd_selection_t uapmd_selection_create(void);
UAPMD_C_EXPORT void     uapmd_selection_add_object(uapmd_selection_t selection, const char* object_id);

/* Each add function appends a region and returns its index. */
UAPMD_C_EXPORT uint32_t uapmd_selection_add_timeline_region(uapmd_selection_t selection, double start_seconds, double end_seconds);
/* Restricts a timeline region to a list of tracks (initially none), as opposed to all tracks. */
UAPMD_C_EXPORT void     uapmd_selection_region_restrict_tracks(uapmd_selection_t selection, uint32_t region);
UAPMD_C_EXPORT void     uapmd_selection_region_add_track(uapmd_selection_t selection, uint32_t region, const char* track_id);

/* Pass -1 for an absent constraint. */
UAPMD_C_EXPORT uint32_t uapmd_selection_add_midi_note_region(uapmd_selection_t selection, const char* clip_id,
                                                               int64_t start_tick, int64_t end_tick,
                                                               int32_t pitch_min, int32_t pitch_max,
                                                               int32_t group, int32_t channel);
/* `note_id` NULL for a clip-level lane; `has_values` false for the whole value range. */
UAPMD_C_EXPORT uint32_t uapmd_selection_add_midi_automation_region(uapmd_selection_t selection, const char* clip_id,
                                                                     const char* note_id,
                                                                     int32_t group, int32_t channel,
                                                                     int32_t status, int32_t parameter,
                                                                     int64_t start_tick, int64_t end_tick,
                                                                     bool has_values, double value_min, double value_max);

/* ── Projection ──────────────────────────────────────────────────────────── */

/* projectSelection(): what a selection means for consumers that think in clips, tracks and a
 * time range. The selection itself is not altered. Destroy with
 * uapmd_selection_projection_destroy(). */
UAPMD_C_EXPORT uapmd_selection_projection_t uapmd_selection_project(uapmd_timeline_facade_t tl, uapmd_selection_t selection);
UAPMD_C_EXPORT void        uapmd_selection_projection_destroy(uapmd_selection_projection_t projection);
UAPMD_C_EXPORT uint32_t    uapmd_selection_projection_clip_count(uapmd_selection_projection_t projection);
UAPMD_C_EXPORT const char* uapmd_selection_projection_clip_at(uapmd_selection_projection_t projection, uint32_t index);
UAPMD_C_EXPORT uint32_t    uapmd_selection_projection_track_count(uapmd_selection_projection_t projection);
UAPMD_C_EXPORT const char* uapmd_selection_projection_track_at(uapmd_selection_projection_t projection, uint32_t index);
UAPMD_C_EXPORT bool        uapmd_selection_projection_has_time_range(uapmd_selection_projection_t projection);
UAPMD_C_EXPORT double      uapmd_selection_projection_range_start_seconds(uapmd_selection_projection_t projection);
UAPMD_C_EXPORT double      uapmd_selection_projection_range_end_seconds(uapmd_selection_projection_t projection);

#ifdef __cplusplus
}
#endif

#endif /* UAPMD_C_SELECTION_H */
