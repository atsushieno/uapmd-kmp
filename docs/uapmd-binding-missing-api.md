# Missing `uapmd-binding` API — independent of the Compose UI refresh

This is the binding work the `uapmd-cmp` effort exposes. **It is not UI work.** It belongs on
`main` on its own schedule; `uapmd-cmp` merely consumes it. Nothing here is invented — every item
is an existing declaration in uapmd or in `c-api/`.

Ground rule this document exists to serve: `uapmd-binding` may contain **only** bindings of the
uapmd API. Anything else belongs in the app.

---

## 1 · Done in this branch (needs lifting onto `main`)

`uapmd-c-app.h` was fully unbound before this work: `grep -c uapmd_app_ kotlin/uapmd-binding/src`
returned 0. **49 of its 81 functions are now bound** on all five backends.

| Group | Functions | Kotlin surface |
|---|---|---|
| Lifecycle | `instantiate`, `instance`, `cleanup` | `instantiateAppModel()` / `getAppModel()` / `cleanupAppModel()` |
| Accessors | `sequencer`, `transport`, `sample_rate`, `track_count` | `AppModel.sequencer/transport/sampleRate/trackCount` |
| Audio engine | `is_audio_engine_enabled`, `set_audio_engine_enabled`, `toggle_audio_engine`, `update_audio_device_settings`, `set_auto_buffer_size_enabled`, `auto_buffer_size_enabled`, `is_scanning` | `AppModel` engine members |
| Startup | `notify_ui_ready`, `notify_persistent_storage_ready` | same names |
| Scanning | `perform_plugin_scanning`, `cancel_plugin_scanning`, `generate_scan_report`, `clear_plugin_blocklist` | same names |
| Tracks | `add_track`, `remove_track`, `remove_all_tracks`, `timeline_track_count`, `get_timeline_track`, `master_timeline_track`, `get_timeline_state` | same names |
| History | `get_history_state`, `undo`, `redo` | `historyState`, `undo()`, `redo()` |
| Transport | all 10 `uapmd_transport_*` | `TransportController` |
| Plugin instances | `create_plugin_instance`, `remove_plugin_instance`, `get/set_instance_group`, `enable/disable_ump_device`, `request_show_instance_details`, `request_show_plugin_ui`, `hide_plugin_ui` | `AppModel` instance members + `PluginInstanceConfig` / `PluginInstanceResult` mirrors |

Files: `commonMain/UapmdAppModel.kt`, `{Jvm,Native,Android,WasmJs,Js}AppModel.kt`,
`androidMain/cpp/uapmd_jni_app.cpp`, plus per-backend declarations in `JnaLibrary.kt`,
`JniBridge.kt`, `WasmJsBridge.kt` and three `expect fun`s in `UapmdFactory.kt`.

### 1a · Supporting changes that are NOT new API — review these separately

These were made to serve the bindings above. They are the ones to scrutinise or drop:

| Change | Files | Nature |
|---|---|---|
| `TrackClearCb`, `HistoryMutationCb` | `JnaLibrary.kt` | mirror `uapmd_track_clear_cb_t` / `uapmd_history_mutation_cb_t`; required to bind `remove_all_tracks` / `undo` / `redo` |
| `uapmdDispatchErrorOnly`, `pendingErrorOnlyCallbacks` | `WasmJsBridge.kt` | wasm marshalling for the same C callback shape |
| `makeJsErrorOnly` | `JsAppModel.kt` | js equivalent |
| `UapmdTimelineState.toKotlin()` extracted | `JvmTimeline.kt` | refactor; body moved out of `getState()` so AppModel reuses it instead of duplicating |
| `decodeTimelineStateAt()` extracted | `WasmJsTimeline.kt` | same refactor, wasm |
| `private` → `internal`: `decodeUndoState`, `Off`, `makeJsTrackMutation` | `JsHistory.kt`, `WasmJsHistory.kt` | visibility only, so the AppModel files can reuse them |

**Reverted, for the record:** an `owned` borrow flag was added to the five `*RealtimeSequencer`
classes to stop `close()` double-freeing AppModel's handle. It has no counterpart in uapmd, so it
was removed; the app now wraps the borrow in `uapmd-cmp`'s `BorrowedRealtimeSequencer`. (The
binding does carry a pre-existing `owned` idiom on `ClipFragment` from the 0.5.6 work — if you
prefer consistency with that, the flag can come back as binding-side.)

### 1b · Build changes outside `uapmd-binding`

- `c-api/CMakeLists.txt` — stop excluding `uapmd-c-app.cpp` and start linking `uapmd-app-model`
  under Emscripten. Verified: builds clean, 71 `uapmd_app_*` + 10 `uapmd_transport_*` symbols now
  export where there were zero.
- `kotlin/uapmd-binding/src/webMain/cpp/CMakeLists.txt` — stop filtering `uapmd-c-app.h` out of
  the auto-derived export list (3 lines removed).
- `kotlin/uapmd-binding/src/androidMain/cpp/CMakeLists.txt` — add `uapmd_jni_app.cpp`.

---

## 2 · Still unbound in `uapmd-c-app.h` (32 of 81)

Ordered by when `uapmd-cmp` needs them. Several pass or return structs **by value**, which is
where the Emscripten ABI rules (sret first-argument, byval-as-pointer, `WASM_BIGINT`) bite.
The instance-lifecycle group above was the first such case and confirmed the rule in practice:
`uapmd_plugin_instance_result_t` reaches the callback as a pointer on wasm/js (int32 id @0,
`char*` name @4, `char*` error @8) and as a JNA `Structure.ByValue` on the JVM.

| Priority | Functions | Struct-by-value? |
|---|---|---|
| Plugin UI | `show_plugin_ui` (the parent-handle/resize-handler form) | no |
| Plugin state | `save_plugin_state`, `load_plugin_state` | **yes** — `uapmd_plugin_state_result_t` |
| Project I/O | `save_project`, `save_project_sync`, `load_project`, `load_project_from_handle_token`, `document_provider` | **yes** — `uapmd_app_project_result_t` |
| Clips | `add_clip_to_track`, `add_midi_clip_to_track`, `add_midi_clip_from_data`, `create_empty_midi_clip`, `remove_clip_from_track` | **yes** — `uapmd_clip_add_result_t` |
| Offline render | `start_render`, `cancel_render`, `get_render_status`, `clear_render_status` | **yes** — settings in, status out |
| Track graph | `ensure_track_uses_editor_graph`, `request_show_track_graph`, `revert_track_to_simple_graph`, `get_track_graph_connections`, `connect_track_graph`, `disconnect_track_graph_connection` | **yes** |
| Markers / UMP events | `master_marker_count`, `get_master_marker`, `set_master_markers`, `get_clip_audio_events`, `set_clip_audio_events`, `get_midi_clip_ump_events`, `add_ump_event_to_clip`, `remove_ump_event_from_clip` | **yes** |
| Misc | `add_device_input_to_track` | no |

---

## 3 · Missing from the C API itself (not just from Kotlin)

These exist in uapmd's C++ headers but have no `c-api/` wrapper at all, so they need C work
first. All predate 0.5.6 — they were simply never needed by a KMP app before.

| Item | Home in uapmd | Needed for |
|---|---|---|
| `SequencerTrack::trackGain()` / `muted()` / `solo()` **getters** | `uapmd-engine/…/SequencerTrack.hpp` | track legend gain slider + M/S button state. Setters exist via `uapmd_commands_set_track_*`; only the getters are missing |
| `FrozenTrackManager` runtime state — `isTrackBusy`, runtime state, freeze policy | `uapmd-engine/…/FrozenTrackManager.hpp` | per-track freeze button (busy spinner, frozen/queued colours) |
| `TempoMap` | `uapmd-data/…/TempoMap.hpp` | beats/ticks timeline view |
| `MidiRecorder` | `uapmd-engine/…/MidiRecorder.hpp` | toolbar record button |
| `McpServer` | `tools/uapmd-app-model/…/McpServer.hpp` | MCP settings window |
| `PreparedSequencerTrack` family — `prepareTrack`, `addPluginToPreparedTrack`, `publishPreparedTrack` | `uapmd-engine/…/SequencerEngine.hpp` | 0.5.6 delta not covered by `13dac10` |
| `PluginInstanceLifecycleListener` add/remove | same | 0.5.6 delta not covered by `13dac10` |
| `restoreNodeId` parameter on `addPluginToTrack()` | same | 0.5.6 delta not covered by `13dac10` |

Deliberately **not** wanted, for the record: `setEngineActive` / `setOutputMuted` /
`resetProcessingState` / `outputAnalyser`. They are internals of a sequence
`uapmd_app_set_audio_engine_enabled` already performs correctly; exposing them would only invite
a worse reimplementation in Kotlin.
