# Missing `uapmd-binding` API — independent of the Compose UI refresh

Binding work the `uapmd-cmp` effort exposes as *still missing*. **It is not UI work.** It belongs
on `main` on its own schedule; `uapmd-cmp` merely consumes it. Nothing here is invented — every
item is an existing declaration in uapmd or in `c-api/`. What is already written lives in the
code and in git, not here.

Ground rule this document exists to serve: `uapmd-binding` may contain **only** bindings of the
uapmd API. Anything else belongs in the app.

---

## 1 · Still unbound in `uapmd-c-app.h`

Ordered by when `uapmd-cmp` needs them. Several pass or return structs **by value**, which is
where the Emscripten ABI rules (sret first-argument, byval-as-pointer, `WASM_BIGINT`) bite.
Both by-value directions are now proven in practice:
- **struct in a callback** — `uapmd_plugin_instance_result_t` arrives as a pointer on wasm/js
  (int32 id @0, `char*` @4, `char*` @8), as `Structure.ByValue` on JNA, `CValue<>` on cinterop.
- **struct returned (sret)** — `uapmd_app_project_result_t` (bool @0, `char*` @4, size 8) takes
  the result pointer as the **first** argument on wasm/js. Verified on the JVM by round-tripping
  a real `.uapmd` file through `save_project_sync` then `load_project`.
- **struct containing an array of structs** — `uapmd_ump_events_result_t`
  (bool @0, `char*` @4, uint32 @8, ptr @12; elements are uint64 @0, uint32 @8, ptr @12) decoded
  on a real 8136-event clip. On JNA note that `Structure.useMemory` is protected: walk the array
  with a `Structure(Pointer)` constructor and `share(i * size())` instead.

**Verify layouts, do not guess them.** The wasm/js offsets used here were checked against the
compiler rather than reasoned about:

```
emcc -Ic-api/include -Xclang -fdump-record-layouts-complete -fsyntax-only <file.c>
```

Confirmed: `uapmd_graph_connection_t` id@0, bus_type@8, source@12, target@24, **sizeof 40**;
`uapmd_graph_connections_result_t` and `uapmd_ump_events_result_t` both bool@0, char*@4,
uint32@8, ptr@12, sizeof 16; `uapmd_ump_event_t` tick@0, word_count@8, words@12, sizeof 16;
`uapmd_op_result_t` and `uapmd_app_project_result_t` bool@0, char*@4, sizeof 8;
`uapmd_clip_marker_t` id@0, offset@8, refType@16, refClip@20, refMarker@24, name@28, sizeof 32;
`uapmd_audio_warp_point_t` offset@0, speed@8, refType@16, refClip@20, refMarker@24, sizeof 32;
`uapmd_clip_audio_events_result_t` ok@0, err@4, mCount@8, markers@12, wCount@16, warps@20, sizeof 24.

| Priority | Functions | Struct-by-value? |
|---|---|---|
| Plugin UI | `show_plugin_ui` (the parent-handle/resize-handler form) | no |
| Plugin state | `save_plugin_state`, `load_plugin_state` | **yes** — `uapmd_plugin_state_result_t` |
| Project I/O | `document_provider` `pick_save`, `write`, `resolve_to_path`, `persist_handle`, `restore_handle` on `AppModel.documentProvider` — `tick()`, `pickOpenDocuments`, `readDocument` and the folder calls are bound (the JSFX Settings window needs them); saving still goes through the per-platform file pickers | **yes** — `uapmd_document_handle_t` in, `uapmd_document_io_result_t` in callbacks |
| Clips | `add_clip_to_track`, `add_midi_clip_to_track`, `add_midi_clip_from_data` | **yes** — `uapmd_clip_add_result_t`. Note `TimelineFacade.addMidiClipFromFile()` / `addAudioClip()` were already bound and cover the common cases |
| Offline render | `start_render`, `cancel_render`, `get_render_status`, `clear_render_status` | **yes**. Not needed so far: `SequencerEngine.renderOffline()` was already bound and drives the Exporter directly |
| Track graph | `request_show_track_graph` (the request/serve indirection; not needed — the app opens its own window) | no |
| Master markers | `master_marker_count`, `get_master_marker`, `set_master_markers` | **yes**. Not needed: `SequencerEngine.masterTrackMarkers` and `ProjectCommands.setMasterTrackMarkers()` already cover both directions |
| Misc | `add_device_input_to_track` | no |

---

## 2 · Missing from the C API itself (not just from Kotlin)

These exist in uapmd's C++ headers but have no `c-api/` wrapper at all, so they need C work
first. All predate 0.5.6 — they were simply never needed by a KMP app before.

| Item | Home in uapmd | Needed for |
|---|---|---|
| `PreparedSequencerTrack` family — `prepareTrack`, `addPluginToPreparedTrack`, `publishPreparedTrack` | `uapmd-engine/…/SequencerEngine.hpp` | 0.5.6 delta not covered by `13dac10` |
| `PluginInstanceLifecycleListener` add/remove | same | 0.5.6 delta not covered by `13dac10` |
| `restoreNodeId` parameter on `addPluginToTrack()` | same | 0.5.6 delta not covered by `13dac10` |
| `FrozenTrackManager::errorMessageForTrack` | `uapmd-engine/…/FrozenTrackManager.hpp` | showing why a freeze failed; the runtime state alone says only that it did |

Bound since (2026-10-06): `AudioGraphNode::parameterUpdateEvent()` / `parameterMetadataRefreshEvent()`
as `uapmd_node_{add,remove}_parameter_{update,metadata_refresh}_listener` → `PluginNode.add/remove…Listener`
on all platforms. uapmd-cmp's Details window uses both: the metadata one to follow plug-ins that reshape
their parameter list (AAP `notify_parameters_changed`, e.g. BYOD presets, ysfx script loads), the update
one for values the plug-in changes itself. The update listener is the primitive `ParameterUpdateListener`
fun interface because AAP notifies it from the audio thread (`AudioPluginNodeImpl` forwards
`parameterChangeEvent()` from `processAudio()`), where a boxing `(Int, Double) -> Unit` would allocate.

Deliberately **not** wanted, for the record: `setEngineActive` / `setOutputMuted` /
`resetProcessingState` / `outputAnalyser`. They are internals of a sequence
`uapmd_app_set_audio_engine_enabled` already performs correctly; exposing them would only invite
a worse reimplementation in Kotlin.

---

## 3 · Deferred from the `f5d490d5` bump

Bound in this bump: audio workers (count, configure, stop-on-deadline, fault, reset,
wait), the two dropped-event counters, `kNoDeviceIndex`, the Virtual MIDI Devices addin
and its `AppModel` members, the project-command and panel registries, `/uapmd/app/model/v1`,
`uapmd-augene2` (project service and the `Integration` model from
upstream `uapmd-augene2.hpp`), and the pre-existing `midiApiSupportsDynamicUmpEndpoints()`.

Not bound, deliberately or for now:

| Item | Home in uapmd | Why |
|---|---|---|
| `AudioWorkers::diagnostic()` | `uapmd-engine/…/AudioWorkers.hpp` | a struct with 33- and 128-element snapshot arrays; the MCP `get_audio_worker_diagnostics` tool already exposes it through `McpServer` |
| `AudioWorkers::setThreadSetup()` | same | takes a `std::function` returning a C++ token; device-side plumbing |
| `AudioPerformanceCounter` | `…/AudioPerformanceCounter.hpp` | no consumer yet; a lock-free queue drained with `tryDequeue()` |
| `TimelineFacade::replaceMidiClipData(ClipInfo)` | `…/TimelineFacade.hpp` | needs a `ClipInfo` marshalled *into* C++; only Augene2 uses it, internally |
| `MidiClipReader` in Kotlin | `uapmd-data` | C has `read_any_format` and now `read_smf2_clip` plus `ClipInfo.name`; Kotlin has never bound the reader |
| `PanelRegistry::render()` | `uapmd-addin-core` | draws ImGui; an addin that wants other toolkits exposes a model, as Augene2 now does |
| `SequencerTrack` plugin-output events and processing groups, `supportsParallelTrackProcessing()`, `AudioThreadScope` | `uapmd-engine`, `uapmd-graph`, `remidy` | internals of the parallel track scheduler |

---

## 4 · The `bba2bd85` bump (public API moved under the bindings)

Everything below is bound in C and in all five Kotlin platforms (JVM, native, Android, JS, WasmJs)
unless a row says otherwise.

**Changed upstream, followed here**

| Upstream | Bindings |
|---|---|
| `AudioPluginGraph::setEventOutputCallback()` removed; `AudioPluginNode::setEventOutputObserver()` (per node, a span of UMP words) | `uapmd_graph_set_event_output_callback` is gone; `uapmd_node_set_event_output_observer` / `PluginNode.setEventOutputObserver`. The engine installs its own observer on every node it hosts, so on an engine-managed node this replaces the engine's capture — documented on both |
| `AudioPluginGraph::refreshTimingInfo()` replaced by `AudioGraph::notifyLatencyChanged()` / `notifyTailLengthChanged()` | `uapmd_graph_notify_latency_changed` / `…_tail_length_changed`, `PluginGraph.notifyLatencyChanged()` / `notifyTailLengthChanged()` |
| `UapmdUmpInputMapper::loadPreset()` → `requestPresetLoad()` (queued to the host's sink, applied on the control thread) | `uapmd_ump_in_request_preset_load`, `UmpInputMapper.requestPresetLoad()` |
| `ProjectAddressBook` moved to `uapmd-data` and lost its runtime lookups (`sequencerTrack`, `pluginInstanceId`, `pluginAddress`) | those three functions, `uapmd_plugin_address_t` and `PluginAddress` are removed; track and clip addressing is unchanged |

**New upstream, now bound**

| Upstream | Bindings |
|---|---|
| `ProjectSelectionService` (`TimelineFacade::document().selections()`): contexts, replace/clear, active context, listeners, `projectSelection()` | `uapmd-c-selection.h`; `TimelineFacade.selections` (`ProjectSelectionService`, `ProjectSelection`, the three region kinds, `ProjectSelectionProjection`). Selections cross as snapshot handles with scalar accessors so no variant is marshalled; `SelectionProbeMain.kt` (`./gradlew :uapmd-cmp:runSelectionProbe`) checks it against the real library |
| `PianoRollSession` publishes note selection in its own context (`selection_context`, `reference_id`, `selectObject`, `isObjectSelected`, `syncSelection`) | `uapmd_piano_roll_session_selection_context` / `clip_reference_id` / `note_reference_id` / `is_object_selected` / `select_object` / `sync_selection`; the same on Kotlin's `PianoRollSession` |
| `SequencerEngine::notifyPluginEditorVisibilityChanged()` (an ARA editor view receives the selection during setup) | `uapmd_engine_notify_plugin_editor_visibility_changed`, `SequencerEngine.notifyPluginEditorVisibilityChanged()`. `UapmdHost.showPluginUi` / `hidePluginUi` / `destroyPluginUi` call it, because uapmd-cmp builds editor presentations itself instead of going through `AppModel::showPluginUI` (which notifies) |

**Not bound**

| Item | Why |
|---|---|
| `MidiClipDocument`, `ProjectDocument::midiContent()`, `TimelineFacade::replaceMidiClipData(…, content)` | the piano roll session (above) is the model hosts use; the document is the storage behind it, and the extra parameter needs a `MidiClipContentSnapshot` marshalled in |
| `PianoRollSession::isAutomationInSelectedRegion()` and automation `reference_id`s | automation events are not exposed per event by the piano roll binding at all yet |
| `ProjectDocumentView` additions (`masterTrackSnapshot`, `getObject`, `clipContentTickToProjectSeconds`), `ProjectDocumentEventDispatcher::setDeliveryHooks` | the document view and event source have never been bound; `AppModel` still serves `buildMasterTrackSnapshot()` |
| `ProjectSaveOptions::copy_media_files` / `preserve_empty_tracks` | `saveProject()` on the facade is not bound; `AppModel` save entry points are unchanged |
| `AudioThreadIdleWork` / `AudioThreadIdleRunner` | audio-thread plumbing for device implementations |
| `PluginInstanceLifecycleListener::pluginEditorVisibilityChanged()`, `AraSession::setPluginEditorVisible()` | listener interfaces for in-process addins; ARA itself is not bound |

**Wrapper-side addition (not an upstream change)**

| Item | Bindings |
|---|---|
| `TimelineFacade::loadProject()` is callback-based; the blocking `uapmd_tl_load_project` waits on a future | `uapmd_tl_load_project_async` / `TimelineFacade.loadProjectAsync()` (overridden on JS and WasmJs; elsewhere it wraps the blocking call). `UapmdHost` loads through it: in a browser a WebCLAP plug-in is fetched and compiled by JavaScript, so a main thread blocked in the synchronous call never lets it finish and the tab freezes right after "instantiating WebCLAP Piano" |
