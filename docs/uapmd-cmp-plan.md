# Plan: `uapmd-cmp` — a fresh Compose Multiplatform app for uapmd 0.5.6

Status: **in progress.** Phase 0 essentially complete (0.0/0.1/0.5 verified); Phase 1 started.
Open questions resolved — see §7. Progress log at the end of this document.
Supersedes: `docs/ui-parity-tracker.md` (tracker for the *old* `composeApp`; keep as history).

Sources read for this plan:

- `external/uapmd/docs/users/USERS_GUIDE.md`, `USE_VIRTUAL_MIDI_DEVICES.md` and the
  screenshots under `external/uapmd/docs/images/` (v0.1 … v0.5.2 plus the v0.5 walkthrough set).
- `external/uapmd/source/tools/uapmd-app/` at the pinned submodule commit (`93c25a70`, 0.5.6).
- `kotlin/composeApp/` and `kotlin/uapmd-binding/` as they stand today.
- `c-api/include/c-api/*.h` and `c-api/CMakeLists.txt`.

---

## 1 · Findings that shape the plan

### 1.1 The users guide is behind the code

The guide documents the v0.5 UI: a two-row toolbar (`Audio Engine`, `Device Settings`, play,
pause, `Scale`, theme / `Plugins`, `Script`, `MCP`, `Import`, `Project`, In/Out spectra), a
seconds-only timeline, a plugin selector, and a plugin-instance details window. It says so
itself: *"Screenshots might be outdated, but they would still mostly make sense."*

At 0.5.6 the app has moved on substantially. Building only to the guide would land us one
release behind on day one. The guide is the right source for **what the app is for** and for
the user-visible vocabulary; the 0.5.6 source is the right source for **the actual feature
list**. This plan uses both.

What changed since the guide's screenshots:

| Area | v0.5 (guide) | 0.5.6 (source) |
|---|---|---|
| Toolbar | two rows, everything inline | one row; `Device Settings` / `Script` / `MCP` / addins folded into a **Command ▾** popup that also carries Undo/Redo |
| Transport | play, pause | play/stop, **record** (into the selected MIDI clip), pause/resume |
| Timeline | seconds only | **seconds ⇄ beats/ticks** toggle, two full editor implementations, a navigator row |
| Track legend | 3–4 icon buttons + "Add Plugin" | clips, graph, **gain slider**, **M**, **S**, **freeze**, plugin context, "more" menu |
| Clips | import only | add empty MIDI2 / empty audio / from file (SMF, SMF2, audio), clear all, **piano roll editor**, MIDI dump editor, **audio event (marker/warp) editor** |
| History | none | **undo/redo** engine, gestures, compounds, dirty tracking, unsaved-project dialog on quit |
| Windows | selector, details, device settings, script, exporter, import | + **Mixer Monitor**, **Addin Manager**, **Plugin Graph Editor** (per track), master marker editor |

### 1.2 The old app is not just behind — it is built one layer too low

This is the decisive finding.

`c-api/include/c-api/uapmd-c-app.h` (432 lines) already wraps `uapmd_app::AppModel` — the
*exact* façade that uapmd-app's ImGui code renders against. It covers audio-engine enable,
scanning, instance create/remove, UMP device enable/disable, plugin UI show/hide, plugin state
save/load, clip add/remove, track add/remove, timeline tracks and state, **track graph editing**,
clip markers/warps, master markers, UMP event editing, **undo/redo**, project save/load, offline
render, and the whole `TransportController`.

`kotlin/uapmd-binding` binds **none of it**:

```
$ grep -rn "uapmd_app_\|uapmd_transport_" kotlin/uapmd-binding/src | wc -l
0
```

Instead, `composeApp/…/UapmdModel.kt` (891 lines) re-implements app-model concerns in Kotlin on
top of the *engine-level* binding. That is why the app drifts: every AppModel change upstream has
to be re-derived by hand in Kotlin, and anything AppModel does that the engine API doesn't expose
(track gain/mute/solo, freeze, graph editing, history) simply cannot be reached.

Scale of the gap today: `composeApp` is ~6,560 Kotlin lines; `uapmd-app/gui` is ~19,856 C++ lines.

### 1.3 One real constraint: AppModel is not built for Wasm

`c-api/CMakeLists.txt` excludes it deliberately:

```cmake
if(EMSCRIPTEN)
    list(REMOVE_ITEM UAPMD_C_API_SOURCES src/uapmd-c-app.cpp)
endif()
...
if(NOT EMSCRIPTEN)
    target_link_libraries(uapmd-c-api PRIVATE uapmd-app-model)
endif()
```

That is *our* exclusion, not an upstream impossibility: upstream `uapmd-app`'s own
`web_main.cpp` uses `AppModel::instantiate()`, and `uapmd-app-model/CMakeLists.txt` already
guards its desktop-only dependencies behind `UAPMD_BUILDING_WASM`. So it should be enable-able,
but it is real work and it must be proven before the whole app is committed to that layer.

---

### 1.4 Provenance of the binding gaps — checked, and not a regression

The gaps listed in step 0.4 are **not** things commit `13dac10` ("bump uapmd to 0.5.6 and update
API bindings") dropped. Verified against the tags:

```
$ git show 0.5.5:.../SequencerTrack.hpp  | grep "trackGain\|muted()\|solo()"      → present
$ git show 0.5.5:.../SequencerEngine.hpp | grep "setOutputMuted\|outputAnalyser"   → present
FrozenTrackManager / MidiRecorder / TempoMap → present in 0.5.5 headers too
```

Every one of them already existed in **0.5.5**, before that commit, so none was part of the
0.5.6 delta it was scoped to. They are the older and wider boundary: `c-api/` was only ever
wrapped as far as the KMP app happened to need, and the KMP app never had a track legend with
gain/mute/solo, a freeze button, or a record button to need them for.

Against its actual scope, `13dac10` did the job: the undo/history engine (`uapmd-c-undo.h` 369
lines + 806-line implementation), the addin manager, project/track dirty state, master-track
markers, `ProjectCommands`, `ProjectAddressBook` and the fragment types — bound across all five
backends (~4,500 lines). Three items of the 0.5.6 engine delta *were* left out and are worth
picking up when something needs them:

- the `PreparedSequencerTrack` family — `prepareTrack()`, `addPluginToPreparedTrack()`,
  `publishPreparedTrack()`
- `PluginInstanceLifecycleListener` add/remove
- the `restoreNodeId` parameter on `addPluginToTrack()`

Separately: `setEngineActive` / `setOutputMuted` / `resetProcessingState` / `outputAnalyser`
being absent from `c-api/` is **not** a gap to fill (§2.2). They are the internals of a sequence
`uapmd_app_set_audio_engine_enabled` already performs correctly; wrapping them would only invite
a worse reimplementation in Kotlin.

## 2 · Architecture decision

**Build `uapmd-cmp` as a thin Compose view layer over a new Kotlin binding of `AppModel`,
including for audio-engine control — matching uapmd-app rather than `composeApp`.**

The three options considered:

| | Approach | Verdict |
|---|---|---|
| A | Keep the engine-level binding; re-implement app logic in Kotlin (what `composeApp` does) | Rejected — this is the thing that produced the current drift, and it structurally cannot reach gain/mute/solo/freeze/graph/history |
| B | Bind `AppModel` through the existing C API; Compose renders it | **Chosen** — parity becomes structural, not a chase; the Kotlin app and the ImGui app render the same façade |
| C | Hybrid: AppModel on JVM/Android/iOS, engine-level fallback on Wasm | Rejected — with audio control on AppModel too (§2.1), the fallback would be a second, weaker audio layer, which is exactly what we are removing |

### 2.0 Layering rule: `uapmd-binding` mirrors uapmd, and nothing more

**No type or member may be added to `uapmd-binding` unless it exists in the uapmd API.**
Anything beyond that — aggregation, convenience wrappers, derived/computed data, platform
branching, UI-shaped state — belongs to `uapmd-cmp`.

This is what keeps the binding auditable against upstream: every declaration in it should be
traceable to a C++ declaration in uapmd, and a reader should be able to diff the two. It is also
what went wrong with `composeApp`, one layer up — `UapmdModel.kt` grew into a re-implementation
of AppModel because there was no line saying where binding ends and app begins.

Scope note: the rule governs *what may be added*, not "never edit the module" — binding
`AppModel` necessarily means adding files to `uapmd-binding`. What it forbids is adding anything
that is not in uapmd. Every public member added so far maps 1:1 onto a `uapmd_app_*` /
`uapmd_transport_*` function in `uapmd-c-app.h`.

Three consequences for this plan, all corrections to earlier drafts:

- **Handle-ownership belongs to the app.** `AppModel` owns its `RealtimeSequencer`, but that type
  is `AutoCloseable` and each backend's `close()` destroys the handle, so a borrowed instance
  could be double-freed. The first fix added an `owned` flag to the five `*RealtimeSequencer`
  classes — a member with no counterpart in uapmd, so it was reverted. The safety now lives in
  `uapmd-cmp` as `BorrowedRealtimeSequencer`, which delegates everything and no-ops `close()`.
  "Who owns this handle" is a fact about how this app uses the API, not part of the API.
  (Note the binding does have a pre-existing `owned` idiom on `ClipFragment`, from the 0.5.6
  work — so the pattern was not invented here, but that does not make it uapmd API.)

- **Clip preview data is app-side.** `ClipPreview` is `uapmd-app/gui/ClipPreview.hpp` — GUI code,
  not library API — so it must not appear in the binding. What the binding does provide is the
  raw material, already bound: `TimelineFacade.getMidiClipNotes()` and
  `AudioFileReader.readFrames()`. Waveform peaks and note rectangles are computed in `uapmd-cmp`.
- **The wasm engine-control fallback (§2.4) is app-side.** Choosing between two uapmd entry
  points based on platform is app logic; the `expect`/`actual` seam lives in `uapmd-cmp`, and the
  binding just exposes both calls as uapmd declares them.

For reference, everything else step 0.4 asks for is genuine uapmd API and passes the rule:

| Item | Home |
|---|---|
| `trackGain()` / `muted()` / `solo()` | `uapmd-engine` — `SequencerTrack.hpp` |
| `FrozenTrackManager` | `uapmd-engine` |
| `MidiRecorder` | `uapmd-engine` |
| `TempoMap` | `uapmd-data` |
| `AppModel`, `TransportController`, `McpServer` | `tools/uapmd-app-model` — see §7.3 |

### 2.1 Audio: match uapmd-app, not `composeApp`

`composeApp`'s audio layer works, but working is not the same as being good enough, and it has
never been shown to be. **uapmd-app's behaviour is the target.** That means adopting AppModel's
audio entry points rather than reimplementing them — and it turns out reimplementing them is not
even possible today (§2.2).

The gap is not cosmetic. Turning the engine **off**:

| | `composeApp` | `AppModel::setAudioEngineEnabled(false)` |
|---|---|---|
| | `engine.setActive(false)` then `sequencer.stopAudio()` | stop transport → mute output → background worker polls the output analyser until the signal falls below threshold (max 8 s) so release/reverb tails render out **inaudibly** → on the main thread: `setEngineActive(false)`, sleep ~2 buffer periods so the hardware ring drains with silence, `stopAudio()`, `stopProcessing()` on every instance, `resetProcessingState()`, unmute |

The comment in `AppModel.cpp` explains why the drain exists: several formats (VST3 notably)
preserve DSP state across deactivation, so a tail cut off here **resumes on restart**. Turning
the engine back on is equally careful — it guarantees starting from a deactivated state, finishing
an interrupted shutdown synchronously first, and reverts `audioEngineEnabled_` if `startAudio()`
fails. Plugin deactivation is explicitly enqueued on the main thread (VST3 `setActive()`) and
never blocked on, so the worker can be joined from the main thread without deadlocking.

`composeApp` does none of this. It is not wrong so much as unfinished.

So, adopted from AppModel — the reverse of what an earlier draft of this plan said:

- `uapmd_app_instantiate()` and its defaults: **1024 frames / 65536 UMP bytes / 48000 Hz**.
  These are what uapmd-app ships; `composeApp`'s 512 / 8192 carry no evidence behind them and
  are simply dropped. No parameterised `instantiate_with(...)` is needed.
- `uapmd_app_set_audio_engine_enabled` / `uapmd_app_toggle_audio_engine`
- `uapmd_app_update_audio_device_settings`
- `uapmd_app_set_auto_buffer_size_enabled` / `uapmd_app_auto_buffer_size_enabled`
  (auto buffer size has no `composeApp` equivalent at all)

### 2.2 Why this is not optional

The careful shutdown is built from `setEngineActive()`, `setOutputMuted()`,
`resetProcessingState()` and `outputAnalyser()`. **The C API exposes none of them** — the only
engine-activation entry point is `uapmd_engine_set_active`:

```
$ grep -n "set_engine_active\|set_output_muted\|reset_processing_state\|output_analyser" c-api/include/c-api/*.h
(no matches)
```

So AppModel's audio behaviour cannot be reproduced in Kotlin through today's C API at all. Either
we call `uapmd_app_set_audio_engine_enabled` and let C++ run the sequence, or we widen the C API
with four more primitives and then re-derive a subtle, thread-sensitive algorithm in Kotlin — for
no benefit. **Call the AppModel entry point.**

### 2.3 What `composeApp` still supplies

One thing only, and it is not audio-layer design — it is the KMP-side bootstrap ordering that has
no C++ counterpart, because uapmd-app's equivalent lives in its own `main()`:

1. **Platform event-loop init runs first, before `uapmd_app_instantiate()`.**
   `initJvmEventLoop()` on desktop (also from `main()` before `application {}`),
   `initAndroidEventLoop()` on Android **from the Android main thread** — it routes remidy
   `EventLoop` tasks to the main looper so plugins that require the UI thread initialise
   correctly. iOS and Wasm init nothing. AppModel does not do this and cannot; it also *depends*
   on it, since its shutdown path enqueues plugin deactivation via
   `remidy::EventLoop::enqueueTaskOnMainThread`.
2. The UI discipline of **reading state back** rather than assuming it — engine state from
   `isAudioEngineEnabled()`, playback from `engine.isPlaybackActive`, via a ~16 ms `tick()`.
   Spectra from `engine.getInputSpectrum(32)` / `getOutputSpectrum(32)`.

Device *enumeration* (`getAudioDeviceManager()`) and `reconfigureAudioDevice(...)` stay on the
existing bindings, but the settings they apply come from AppModel's values, not our own defaults.

### 2.4 Fallback rule: where AppModel is unreachable, do what `composeApp` does

The two rules compose into one policy:

> Match uapmd-app by default. Where a platform cannot reach AppModel, fall back to
> `composeApp`'s path — it is known to work.

So the audio path depending on AppModel is **not** a blocker for any target. If step 0.1
(Emscripten) slips, Wasm keeps engine control through the engine-level route `composeApp` uses on
wasmJs today — `uapmd_engine_set_active` plus `RealtimeSequencer.startAudio()`/`stopAudio()`.
What Wasm loses in that case is shutdown *quality* (the muted tail drain of §2.1), not engine
control.

One qualification on "known to work": on **wasm** that phrase carries much less history than on
desktop. `composeApp`'s wasm build had unresponsive plugin scanning until `aaed96b`, fixed only
just now. The good news is where that fix landed — `c-api/src/uapmd-c-tooling.cpp`,
`WasmJsBridge.kt` and `webMain/cpp/CMakeLists.txt`, i.e. **entirely below the app layer**, so
`uapmd-cmp` inherits it for free through `:uapmd-binding` with nothing to port. But treat wasm
claims as provisional and verify them on the target rather than by analogy with desktop.

Two conditions on this, so it does not quietly become the "two models" problem:

- It lives behind one `expect`/`actual` function — engine enable/disable — **in `uapmd-cmp`, not
  in the binding** (§2.0): the binding exposes both uapmd entry points as declared, and the app
  picks. The wasm `actual` carries a comment saying why it differs and what it gives up. One
  narrow seam, not a parallel model.
- It is expected to be temporary. AppModel's shutdown worker spawns a plain `std::thread` with no
  Emscripten guard, and our wasm build already runs with pthreads (the emitted
  `build-wasm/uapmd-c-api.js` carries the `ENVIRONMENT_IS_PTHREAD` / `em-pthread` worker
  machinery), so it should work once compiled. Upstream expects it to: uapmd-app's own
  `web_main.cpp:293` calls `setAudioEngineEnabled` on the web build.

Targets, matching the existing `composeApp`: `android`, `jvm`, `iosArm64`, `iosSimulatorArm64`,
`wasmJs`. (`composeApp` also carries a dead `jsMain` source set with no `js()` target — do not
copy that into the new module.)

---

### 2.5 We do not run uapmd-app's `main()` — enumerate what it sets up

The second defect in `aaed96b` is the one with the longest reach. uapmd writes its plugin cache
to `/browser/remidy-tooling/`, **a directory only upstream's `web_main.cpp` creates** — so in the
KMP app the wasm FS root held just `tmp,home,dev,proc` and the cache write went nowhere. A
completed scan would still have produced an empty list.

That is a general hazard, not a one-off: `uapmd-cmp` replaces uapmd-app's entry point, so
anything that entry point does becomes ours to do, silently, with no compile error when we skip
it. Adopting AppModel *increases* this surface. Phase 0 therefore includes an explicit audit of
`main_common.cpp` and `web_main.cpp`, whose AppModel-related sequence is:

```
remidy::setEventLoop(...); remidy::EventLoop::initializeOnUIThread();
AppModel::instantiate();
  ... construct UI ...
AppModel::instance().notifyUiReady();
AppModel::instance().notifyPersistentStorageReady();   // desktop
uapmd_init_browser_storage();                          // web, instead of the above
AppModel::instance().setAudioEngineEnabled(true);      // desktop
AppModel::instance().setAudioEngineEnabled(false);     // web - starts DISABLED
  ... run ...
AppModel::instance().setAudioEngineEnabled(false); AppModel::cleanupInstance();
```

**This needs no new C API surface.** Every entry point already exists — it is a matter of
calling them, in order, at the right time:

| Step | Entry point | Status |
|---|---|---|
| event loop | `uapmd_set_event_loop` (`uapmd-c-engine.h`) | exists; Kotlin already uses it via `initJvmEventLoop()` / `initAndroidEventLoop()` |
| instantiate | `uapmd_app_instantiate` / `uapmd_app_cleanup` | exists (`uapmd-c-app.h:25,27`), unbound |
| UI ready | `uapmd_app_notify_ui_ready` | exists (`:425`), unbound |
| storage ready | `uapmd_app_notify_persistent_storage_ready` | exists (`:426`), unbound |
| browser FS | *(upstream's `EM_JS uapmd_init_browser_storage`)* | **already ported into the binding** — see below |

"Unbound" here is not extra work: none of `uapmd-c-app.h` is bound, so these come along with
steps 0.2/0.3 for free.

The one item that genuinely is *not* in the C API — `uapmd_init_browser_storage`, an `EM_JS`
block living inside `web_main.cpp` — was already solved by `aaed96b`, in the binding rather than
the app: `initUapmdWasm()` now awaits `initBrowserFileSystem()` from `uapmd-wasm-adapter.mjs`,
which creates `/browser`, `/browser/uploads` and `/browser/remidy-tooling`, mounts IDBFS, and
falls back to in-memory when IDBFS is absent. `uapmd-cmp` inherits it.

Three things fall out of that sequence which `composeApp` does not do at all:

- **`notifyUiReady()` / `notifyPersistentStorageReady()`** must actually be called. Skipping
  them is exactly the `aaed96b` failure mode. On wasm there is a specific wiring job once 0.1
  lands: upstream's `EM_JS` signals `_uapmd_web_storage_ready(...)` back into C++, whereas our
  `initBrowserFileSystem()` only resolves a JS promise — that resolution has to be connected to
  `notifyPersistentStorageReady()`.
- **Web starts with the audio engine disabled**, desktop enabled. `composeApp` starts audio
  unconditionally everywhere. Browsers need a user gesture before audio anyway, so match this.
- **Teardown is ordered**: engine off, *then* `cleanupInstance()`. `composeApp` has no
  equivalent shutdown path.

## 3 · Module skeleton

```
kotlin/uapmd-cmp/
  build.gradle.kts          # modelled on composeApp/build.gradle.kts
  src/commonMain/kotlin/dev/atsushieno/uapmd/cmp/
  src/{androidMain,jvmMain,iosMain,wasmJsMain,webMain}/…
```

- `settings.gradle.kts`: `include(":uapmd-cmp")`.
- Android `namespace` / `applicationId`: `dev.atsushieno.uapmd_cmp` (installable side-by-side
  with the old app during the transition).
- iOS framework `baseName = "UapmdCmp"`; desktop `mainClass = "dev.atsushieno.uapmd.cmp.MainKt"`,
  `packageName = "uapmd-cmp"`.
- Carry over the `afterEvaluate` wasm-resource hook and the JVM probe tasks pattern from
  `composeApp/build.gradle.kts`.
- **`composeApp` stays untouched and buildable** until `uapmd-cmp` reaches parity, then it is
  removed in one commit.

### 3.1 The floating window manager — a core component, built first

§7.2 has a bigger consequence than it looks. uapmd-app is a **multi-window** application, and
several of its windows are *multi-instance*:

| Window | Key | Concurrent instances |
|---|---|---|
| Instance Details | `instanceId` | one per plugin instance |
| Plugin Graph Editor | `trackIndex` | one per track |
| MIDI Dump / Piano Roll | `(trackIndex, clipId)` | one per clip |
| Clip editor ("Edit Clips…") | `trackIndex` | one per track |
| Plugin Selector, Mixer Monitor, Device Settings, Addins, Script, MCP, Exporter, Audio Import | — | singleton |

Critically, these are **not OS windows**. uapmd-app does not enable ImGui's multi-viewport mode
(no `ConfigFlags_ViewportsEnable` anywhere in the tree), so every one of them is an ImGui window
drawn inside the single application window — which is exactly what the screenshots show: the
Plugin Selector's own title bar and ✕ overlapping the toolbar behind it. The one genuine
exception is plugin GUIs, which use `remidy::gui::ContainerWindow` and *are* real OS windows.

#### Is `Window` available off desktop? No — verified

Checked against the Compose Multiplatform **1.10.3** klibs this project resolves, not from
memory. The `androidx.compose.ui.window` package contains:

| Target | What is there | `Window` / `application` |
|---|---|---|
| jvm | `Window`, `DialogWindow`, `application`, `Dialog`, `Popup` | **yes** |
| wasmJs | `Dialog`, `Popup`, `ComposeViewport*`, `ComposeWindow` (root host) | **no** |
| iOS | `Dialog`, `Popup`, `ComposeUIViewController`, `ComposeView` | **no** |
| Android | `Dialog`, `Popup` | **no** |

`ComposeWindow` / `ComposeUIViewController` are the *root* hosts — the surface the whole app is
drawn into — not a child-window API. And `Dialog`/`Popup` are not substitutes: `Dialog` is modal
and centred, `Popup` is anchored and lightweight, and neither is draggable, resizable, or
stackable as a peer alongside others. Nothing there expresses "six details windows open at once,
arranged by the user".

#### Decision: one in-scene manager, all five targets

Native `Window` on desktop was considered and **dropped**. It would have saved no work — three of
five targets need the in-scene manager regardless, so the desktop path would be additive — and it
would have forced a shared abstraction spanning a real OS window and an in-scene panel, which
leaks or sinks to the lowest common denominator.

So: a single implementation in `commonMain`, no `expect`/`actual` split, identical behaviour
everywhere. Absolutely-positioned surfaces over the main content, with a draggable title bar,
close button, resize handle, focus/z-order stacking, and a registry keyed by the identifiers in
the table above so N instances coexist. This also happens to be exactly what uapmd-app does.

(The desktop *root* window is still `androidx.compose.ui.window.Window` from `application {}` —
that is the application window itself, not a child window. Plugin GUIs also remain real OS
windows on desktop, hosted the way `composeApp` already does it.)

The in-scene manager moves to **Phase 1**, ahead of nearly all feature work, because Phases 3
through 8 each deliver one or more windows and would otherwise each invent their own container.
It is the single highest-leverage piece of infrastructure in the plan, and getting it wrong late
is expensive.

### What is worth porting from `composeApp` rather than rewriting

These are UI-shaped and largely model-agnostic; they should be moved over and re-fitted to the
new model rather than written again:

- `nodegraph/NodeGraph.kt` (552 lines) — there is no ImNodes for Compose; this is the graph
  editor substrate.
- `ui/MidiKeyboard.kt`, `ui/SpectrumAnalyzer.kt`, `ui/ParameterList.kt`.
- The platform `DocumentPicker*` / `ProjectArchiveLoader*` / `PluginUiHosting*` families,
  including `AndroidPlatformHostedPluginUiLayer.kt` (468 lines) — that one is hard-won
  Android AAP plugin-UI hosting and must not be rewritten.
- `timeline/ClipPreviewData.kt` as a starting point for clip previews.

Everything else — `MainWindow.kt`, `UapmdModel.kt`, `ClipTimeline.kt`, `TrackList.kt` — is
rewritten against the new model.

---

## 4 · Feature inventory (uapmd-app 0.5.6)

This is the parity target. Each row becomes a checklist item in the tracker that replaces
`docs/ui-parity-tracker.md`.

**Toolbar (single row)** — engine on/off (colour-coded, tooltip) · Command ▾ (Undo *desc* /
Redo *desc* with busy + disabled states, Device Settings, Addins, Script, MCP Settings) ·
Ctrl/Cmd+Z, Shift+Ctrl+Z, Ctrl+Y shortcuts · play/stop · record · pause/resume · UI-scale combo
(×0.5 … ×4.0) · theme toggle · Plugins · Import ▾ (SMF tracks, Demucs split audio) ·
Project ▾ (Load, Save, Render To File) · In and Out spectrum analysers.

**Timeline** — View: Seconds ⇄ View: Beats toggle · navigator (position + zoom) · master track
row · unified multi-track timeline with lane assignment · clip drag/move · drag-to-select-range
→ add clip · clip context actions.

**Track legend, row 1** — clips button · graph button · gain slider (dB readout, muted state,
undo *gesture* around the drag) · M (mute) · S (solo, Ctrl = additive).
**Row 2** — freeze (policy toggle; spinner while rendering; frozen/queued colours; disabled
while the track is busy) · plugin context button (labelled with the first instance, else
"Add Plugin" / "Add Master Plugin") · ⋮ more.

**Clips popup** — Edit Clips… · Add an Empty MIDI2 Clip · Add Empty Audio Clip · Create Audio
Clip From File… · Add a MIDI Clip from File… · Add MIDI2 Clip from File… · Clear All.
Master track gets a reduced set.

**Plugin popup** — per instance: Show/Hide *name* Details, Show/Hide *name* GUI (disabled when
the plugin has no UI), Delete *name* (at [n]) · Add Plugin.

**More popup** — Bypass / Enable Track Processing · Delete Track.

**Bottom bar** — ＋ add track · Mixer Monitor · Plugin Instances.

**Windows / panels** —
Plugin Selector — floating (scan, force rescan, remote scanner + timeout, collapsible blocked-bundle list,
search, sortable Format/Name/Vendor/ID table, Instantiate Plugin, destination selector, device
name + API) ·
Plugin Graph Editor, one per track (nodes, audio + event buses, connect/disconnect, Revert to
Simple Graph) ·
Instance Details, one per instance (Hide UI, bypass, Save/Load State, Delete, pitch-bend,
channel pressure, MIDI keyboard, preset combo, UMP group, parameter table with per-note
Context/Value-Key keyboard, filter, Reset) ·
Audio Graph Editor / "Plugin Instances" (track/plugin/format, enable toggle, details, remove,
UMP device name, enable/disable device) ·
Mixer Monitor (audible + render position, playback/preroll/latency-drain/output-alignment
status, monitoring policy, realtime infinite-tail policy, per-track table) ·
Device Settings (audio in/out, sample rate, buffer size, auto buffer, platform MIDI in/out
mapped onto tracks) ·
Addin Manager · Script Editor (JS runtime) · MCP Settings (Server/Client, port, relay URL,
auto-reconnect, connect/disconnect, status; informational variant on Wasm) ·
Exporter (render settings, progress, cancel) · Audio Import / Demucs ·
MIDI Dump (editable) · Audio Event List (markers + warps) ·
Piano Roll (h/v zoom + scroll, drag move/resize, snap grid, velocity, per-note automation,
NRPN plugin-parameter picker, live note preview) — *deferred to Phase 8* ·
Master marker editor + master meta dump · Unsaved Project dialog (Save / Discard / Cancel).

---

## 5 · Phases

### Phase 0 — Binding foundation *(blocking; no UI work in parallel)*

0.0 **Stand up the bootstrap on AppModel from the start.** Platform event-loop init (§2.3),
    then `uapmd_app_instantiate()`, then `uapmd_app_sequencer()`; drive engine on/off through
    `uapmd_app_set_audio_engine_enabled`. Get a window up that starts and *cleanly stops* audio
    on all five targets. Do **not** port `composeApp`'s `setActive`/`startAudio` pair as an
    interim step — it would have to be torn out again, and it is the behaviour we are replacing.
0.1 Enable `uapmd-c-app.cpp` + `uapmd-app-model` for Emscripten in `c-api/CMakeLists.txt`,
    following the `UAPMD_BUILDING_WASM` guards upstream already has; add the `uapmd_app_*` and
    `uapmd_transport_*` symbols to the wasm export list. Not a blocker: if it slips, Wasm takes
    the §2.4 fallback and this step moves to a later phase.
0.2 Common Kotlin API: `AppModel`, `TransportController` interfaces in `uapmd-binding`
    (`commonMain`), mirroring `uapmd-c-app.h` in the style of the existing `UapmdEngine.kt`.
    Includes the audio-engine and device-settings entry points listed in §2.1.
0.3 Five backends: JNA (`JnaLibrary.kt`), JNI (`uapmd_jni.cpp` + `JniBridge.kt`), cinterop
    (native), and the Emscripten bridge (`WasmJsBridge.kt`, and `JsBridge.kt` if `js()` is kept
    in the binding).
0.4 Fill the binding gaps the ImGui UI relies on but neither layer exposes yet — audit and
    extend the C API where needed: **track gain / muted / solo / bypassed getters**,
    `FrozenTrackManager` state (`isTrackBusy`, runtime state, freeze policy), `TempoMap`,
    `MidiRecorder` and the MCP server handle — plus the three 0.5.6 engine items listed in §1.4
    (`prepareTrack` family, lifecycle listener, `restoreNodeId`). **Not** clip-preview data: that
    is GUI code upstream and belongs in the app (§2.0).
0.45 Audit `main_common.cpp` / `web_main.cpp` against what `uapmd-cmp` does at startup and
    teardown (§2.5), and reproduce the lifecycle calls, the per-platform initial engine state and
    the ordered shutdown. Cheap to do, and the failure mode is silent.
0.5 A JVM smoke probe (in the pattern of `runJvmInstantiationProbe`) that drives AppModel
    headlessly: instantiate, add track, add plugin, undo, redo, save, load — plus an
    engine off/on cycle that asserts no audible tail resumes on restart (the specific failure
    `composeApp`'s shutdown would produce).

*Exit criterion: audio starts **and stops cleanly** on all five targets through AppModel, and
AppModel's document layer is reachable and exercised from Kotlin on all five.*

### Phase 1 — Module skeleton, app shell, and the window manager
Module, targets, theming (dark/light), UI-scale density override, the single-row toolbar with
the Command ▾ popup and keyboard shortcuts, transport incl. record, In/Out spectra, bottom bar,
unsaved-project dialog on quit. Ported: `SpectrumAnalyzer`.
**Plus the floating window manager of §3.1** — draggable/resizable/stackable in-scene windows
with a keyed registry for multi-instance windows. Everything from Phase 3 on depends on it.

### Phase 2 — Timeline
Seconds view first, then the beats/ticks view and the toggle; navigator; master + regular track
rows; clip rendering with previews; drag/move; drag-to-select-range. The full track legend
(gain/M/S/freeze/clips/graph/plugin/⋮) with undo gestures wired around the gain drag.

### Phase 3 — Plugin lifecycle
Plugin Selector as a **floating window** (§7.2), scanning with progress and cancel, blocklist,
instantiate with destination + device name + API. Plugin
Instances panel. Per-track plugin context popup.

### Phase 4 — Instance details, parameters, plugin UI
Instance Details window, parameter list with per-note context, presets, UMP group, state
save/load, MIDI keyboard, pitch-bend / channel pressure. Platform plugin-UI hosting — port the
existing `PluginUiHosting*` and the Android hosted-UI layer rather than rewriting.

### Phase 5 — Project and history
Undo/redo surfaced in Command ▾ with live descriptions and busy state; dirty tracking; project
save/load; document transactions and gestures used correctly from the UI; offline render
(Exporter window).

### Phase 6 — Clip editors *(piano roll excluded)*
MIDI dump editor, audio event list (markers/warps), master marker editor, clip
add/remove/rename/change-file actions. Selecting a MIDI clip opens the dump editor; the piano
roll arrives later (Phase 8).

### Phase 7 — Monitoring and tooling windows
Mixer Monitor, Device Settings incl. platform MIDI routing, Plugin Graph Editor (on the ported
`NodeGraph.kt`), Addin Manager, Script Editor, MCP Settings, Demucs import.

### Phase 8 — Piano roll
The largest single item (~2,000 C++ lines): h/v zoom and scroll, drag move/resize, snap grid,
velocity editing, per-note automation, the NRPN plugin-parameter picker, live note preview.
Deliberately last of the feature work — by this point the clip, history and plugin-parameter
plumbing it needs is all in place and proven.

### Phase 9 — Platform passes and cutover
Android (safe-area insets, document picker, AAP plugins), iOS simulator, Wasm, desktop
packaging. Then delete `composeApp` and fold `docs/ui-parity-tracker.md` into the new tracker.

---

## 6 · Risks

- **Emscripten AppModel (0.1)** — no longer a blocker for any target, because of the fallback
  rule in §2.4: if it slips, Wasm keeps engine control on the engine-level route and loses only
  the tail-drain shutdown quality. The risk that remains is the fallback silently becoming
  permanent; the `expect`/`actual` comment and a tracker row are the guard.
- **Main-thread blocking is the known wasm failure mode.** `aaed96b`'s first defect was a
  synchronous C API call (`uapmd_scan_tool_perform_scanning` -> `InProcessScanSessionManager::runScan`)
  blocking on a condition variable while the completion it waited for could only be delivered by
  the thread it had blocked — the page stopped compositing permanently. AppModel has the same
  shape in places: `joinAudioShutdownWorker()` joins from the main thread, and
  `completeAudioEngineShutdown()` sleeps on it for ~2 buffer periods. Upstream's comments show
  they designed around the deadlock, but 0.1 must be **reviewed under this lens and verified in a
  browser**, not assumed working because it compiles and pthreads are enabled.
- **Event-loop ordering.** AppModel's shutdown enqueues plugin deactivation on the main thread;
  if `initJvmEventLoop()` / `initAndroidEventLoop()` has not run first, that work never executes
  and the engine appears to hang on stop. §2.3, step 0.0.
- **Five backends per binding addition.** Every new C API surface costs work in JNA, JNI,
  cinterop and the Emscripten bridge. This dominates Phase 0 and recurs in 0.4.
- **No ImGui equivalents.** ImGui's *windowing* (§3.1), ImNodes (graph), ImTimeline (timeline)
  and the immediate-mode interaction model all have to be rebuilt in Compose. The window manager
  is front-loaded into Phase 1 to contain this; the timeline and piano roll are the two places
  where it stays expensive rather than merely tedious.
- **Testing.** There is no CI environment with plugins installed; verification is manual on
  desktop, and iOS is, per the guide, "tested only on simulators / rarely tested".
- **Moving target.** uapmd is under heavy development. Pin the submodule for the duration of a
  phase and re-baseline deliberately between phases.

---

## 7 · Decisions (settled)

1. **Wasm is a target.** Not "if it works out" — it is in scope, so step 0.1 (enabling
   `uapmd-app-model` for Emscripten) is planned work, not an experiment. The §2.4 fallback stays
   as schedule insurance, not as an exit.
2. **Buffer sizes**: take AppModel's 1024 / 65536 / 48000. No parameterised
   `uapmd_app_instantiate_with(...)`.
3. **`composeApp`** stays until it is removed at some later stage; it does not need freezing and
   does not gate anything. This holds on one condition — see §7.1.
4. **No tab navigation.** Confirmed: it brings in a lot of nonsense. The main UI is the timeline,
   always visible.
5. **Piano roll is deferred** to a late stage of the migration (now Phase 8), not part of the
   first parity pass. Everything else in the clip-editor group stays in Phase 6.

### 7.1 The one constraint that keeps decision 3 true

`composeApp` is unaffected by this work **only for as long as every `uapmd-binding` change is
additive**. Phase 0 does change that module — substantially — but it adds new interfaces
(`AppModel`, `TransportController`) and new `expect`/`actual` pairs rather than altering existing
signatures, so `composeApp` keeps compiling and can be deleted whenever convenient.

Where 0.4 touches an *existing* declaration — for instance adding `restoreNodeId` to
`addPluginToTrack()` — give it a default value so the change stays source-compatible. If some
gap genuinely cannot be filled additively, that is the moment to remove `composeApp` rather than
to contort the binding around it.

### 7.2 Do not diverge from uapmd-app UI behaviour

The old tracker's remaining two ground rules are **dropped**. They were divergences, and
divergence is the thing to avoid:

- The plugin list **is a floating window**, not a side panel. A side panel wrecks the flow of
  picking a plugin to add to an already-selected track.
- Instance details **are floating windows**, plural — more than one can be open at a time, which
  a single panel cannot express at all.

Generalised: where this plan and uapmd-app disagree about UI behaviour, uapmd-app wins unless
there is a platform reason that makes its behaviour impossible. See §3.1 — this has real
structural consequences, and they land early.

(Native OS windows on desktop were considered as a sanctioned exception — ImGui's in-window
placement being a constraint artifact rather than a design choice — but dropped; see §3.1.)

### 7.3 Scope of "the uapmd API" — settled

Does "the uapmd API" include `tools/uapmd-app-model`? `AppModel`, `TransportController` and
`McpServer` all live there rather than beside the libraries, and the whole architecture in §2
rests on binding them.

**Resolved: yes.** `uapmd-binding` already includes API bindings for AppModel, so it covers
`tools/` by construction. Phase 0 proceeds as written.


---

## 8 · Progress log

### 2026-08-28 — Phase 0 landed, Phase 1 started

**0.1 Emscripten AppModel — done, and it was not the obstacle it looked like.**
Removing the two exclusions (`c-api/CMakeLists.txt` dropping `uapmd-c-app.cpp` and not linking
`uapmd-app-model`, plus `webMain/cpp/CMakeLists.txt` filtering `uapmd-c-app.h` out of the export
list) was the whole change. `AppModel.cpp`, `McpServer.cpp` and `UapmdJSRuntime.cpp` all compile
under Emscripten unmodified. Verified: **71 `uapmd_app_*` + 10 `uapmd_transport_*` symbols**
exported in `build-wasm/uapmd-c-api.js`, where there were previously zero. The biggest unknown in
the plan is retired, and the §2.4 fallback is now insurance nobody expects to need.

**0.2 / 0.3 bootstrap subset — bound on all five backends.**
`AppModel` + `TransportController` in `commonMain`, with `JvmAppModel` (JNA), `NativeAppModel`
(cinterop), `AndroidAppModel` (new `cpp/uapmd_jni_app.cpp`, 26 JNI functions), `WasmJsAppModel`
and `JsAppModel`. Android symbols verified by diffing `external fun` names against `nm -D` of the
built `libuapmd-jni.so`, per the known trap that a missing JNI impl only fails at runtime.

One hazard found and handled: `AppModel` owns its `RealtimeSequencer`, but every platform's
sequencer wrapper destroys the handle in `close()`. All five gained an internal `owned` flag so
the borrowed instance handed out by `AppModel.sequencer` cannot double-free.

**0.0 bootstrap — done and verified, not merely compiled.**
`UapmdHost` (app-side, per §2.0) runs uapmd-app's own startup order: event loop → instantiate →
`notifyUiReady` → `notifyPersistentStorageReady` → per-platform initial engine state → ordered
teardown. `platformStartsWithAudioEngineEnabled` is `false` on wasm, `true` elsewhere, matching
`web_main.cpp`.

**0.5 probe — `./gradlew :uapmd-cmp:runBootstrapProbe`.** All checks pass on desktop:

```
sampleRate=48000 tracks=3
PASS  engine reports enabled
PASS  audio is playing after enable
PASS  engine reports disabled immediately
PASS  audio stopped within 15s (took 160ms)
PASS  audio is playing after restart
PASS  engine reports enabled after restart
```

The 160 ms is AppModel's tail drain doing its job — stop, mute, drain, deactivate on the main
thread, reset — and the restart check is the one `composeApp`'s `setActive`+`stopAudio` could not
have passed.

Two behaviours came free with AppModel that `composeApp` never had: an automatic initial plugin
scan at startup (the desktop run scans the real VST3/LV2/AU library), and three initial tracks,
matching the guide's "UAPMD initially launches with a few empty tracks".

**Phase 1 started: the floating window manager** (`ui/FloatingWindow.kt`) — keyed registry so
multi-instance windows coexist, draggable title bar, resize handle, click-to-front z-ordering,
cascade placement, and a drag clamp so a window cannot be lost off-edge.

**Not yet verified:** the window manager's drag/resize/stacking has been exercised only by
compiling and launching; the interactions themselves have not been driven. Worth a human pass, or
Compose UI tests, before Phase 3 starts depending on it.

**Next:** broaden the AppModel binding past the bootstrap subset (scanning, instances, tracks,
timeline, history, project I/O), then the real toolbar.

### 2026-08-28 (cont.) — AppModel binding slice 2

Second slice bound across all five backends: **plugin scanning** (perform / cancel / report /
clear blocklist), **track mutation** (add / remove / remove-all, asynchronous since 0.5.6),
**timeline track access and timeline state**, and **history** (`historyState`, `undo`, `redo`).

Extended `runBootstrapProbe` now covers the round trip, and passes:

```
PASS  addTrack callback fired (index=3, error=null)
PASS  track count grew (3 -> 4)
   history: canUndo=true undo='Add track' busy=false
PASS  adding a track produced an undoable step
PASS  undo callback fired (error=null)
PASS  undo restored the track count
PASS  redo is now available
   timeline: tempo=120.0 sig=4/4 sr=48000
```

That exercises the full path end to end: an async mutation routed through the undo engine, its
completion marshalled back over each binding's callback machinery, the resulting history entry
carrying a description the toolbar can show, and undo actually restoring document state.

Notes worth keeping:

- **`extern "C"` vs `extern` on `uapmd_jni_env()`.** The Android link failed with
  `undefined symbol: uapmd_jni_env` because the new file declared it `extern "C"` while
  `uapmd_jni.cpp` defines it with C++ linkage (`uapmd_jni_history.cpp` declares it plain
  `extern`). Match the existing declaration.
- **Reuse over duplication where it was cheap:** `UapmdTimelineState.toKotlin()` (JVM),
  `decodeTimelineStateAt` (wasm), and `decodeUndoState` / `Off` / `makeJsTrackMutation` (js) were
  extracted or widened from `private` rather than copied. The one real duplication is
  `pack_undo_state` in `uapmd_jni_app.cpp`, because the original sits in another file's anonymous
  namespace.
- **A new error-only callback shape** `(const char*, void*)` needed plumbing per backend:
  `TrackClearCb`/`HistoryMutationCb` (JNA), a `staticCFunction` trampoline (native),
  `app_error_only_trampoline` (JNI), `uapmdDispatchErrorOnly` (wasm), `makeJsErrorOnly` (js).

`composeApp` still builds, as required by §7.1 — every binding change so far has been additive.

**Still to do in the binding:** plugin instance lifecycle (create/remove, UMP device, plugin UI,
state save/load), clips, project save/load, offline render, track graph editing. Several of those
return or take structs by value, which is where the Emscripten ABI rules in the project memory
start to matter.

### 2026-08-28 (cont.) — process correction + Phase 1 toolbar

**Process correction.** Binding changes caused by *missing API* are independent of this UI work
and belong on `main` on their own. They must be reported as such, not folded into this plan.
`docs/uapmd-binding-missing-api.md` is now the running inventory: 40 of `uapmd-c-app.h`'s 81
functions bound, 41 still unbound, plus 8 items missing from the C API entirely. Two rules follow:

1. `uapmd-binding` gets **only** API bindings — nothing app-shaped. The `owned` flag was reverted
   accordingly; borrow safety now lives in `uapmd-cmp`'s `BorrowedRealtimeSequencer`.
2. Where the app needs something unbound, it is **reported**, not worked around inside the
   binding.

**Phase 1 toolbar.** Single-row 0.5.6 layout: engine on/off (colour-coded), Command popup with
live undo/redo descriptions and busy state, transport, Plugins, Scan/Cancel, Import and Project
popups, bottom bar with add-track / Mixer Monitor / Plugin Instances. `UapmdHost` polls uapmd
state at 100 ms, because it lives in C++ and changes without notifying Compose.

Controls whose binding is missing are rendered **disabled** rather than omitted, so the gap is
visible in the UI and traceable to the inventory: record (needs `MidiRecorder`), Import and
Project (need the clip and project-I/O bindings), and instance creation in the Plugin Selector.

### 2026-08-28 (cont.) — plugin instance lifecycle, and an upstream crash

**Bound (JVM so far):** `create_plugin_instance`, `remove_plugin_instance`,
`get/set_instance_group`, `enable/disable_ump_device`, `request_show_instance_details`,
`request_show_plugin_ui`, `hide_plugin_ui` — plus the `uapmd_plugin_instance_config_t` /
`uapmd_plugin_instance_result_t` mirrors. This is the first struct-by-value callback in the
AppModel surface and it marshals correctly: real error strings and ids round-trip.

Verified against the machine's real plugin library — 391 catalog entries, an AU plugin
instantiated (`Gateway`, 13 parameters, UMP group 0), retrievable from the host, then removed.

### Upstream bug found: crash on engine shutdown after removing a plugin

`uapmd-app-model/src/AppModel.cpp:783`

```cpp
auto* host = sequencer_.engine()->pluginHost();
for (auto id : host->instanceIds())
    host->getInstance(id)->stopProcessing();   // no null check
```

After `uapmd_app_remove_plugin_instance()`, `instanceIds()` still reports the removed id while
`getInstance()` returns `nullptr`, so `completeAudioEngineShutdown()` dereferences null.

Evidence:

- `SIGSEGV`, `si_addr: 0x0`, problematic frame
  `uapmd_app::AppModel::completeAudioEngineShutdown()+0x110`, reached from the event-loop task
  (`CApiEventLoop::enqueueTaskOnMainThreadImpl` → AWT EDT).
- Deterministic: instantiate → remove → engine off ⇒ crash. Skip only the removal and the same
  teardown is clean and the probe passes.
- **Not a binding artifact.** The crash is entirely inside `uapmd-app-model`; the binding's only
  involvement is providing the event loop that runs the task.

This affects uapmd-app itself, not just uapmd-cmp: removing a plugin and then toggling the audio
engine off should hit the same path.

Left for the human to fix — `external/uapmd` is a submodule, and commits there are yours. The
probe therefore makes removal **opt-in**: `-Duapmd.probe.removeInstance=1` reproduces it, and the
default run stays green. `tests/uapmd-app-shutdown-crash.c` is a started pure-C repro, but it is
**not working yet** — a bare C host installs no remidy `EventLoop`, so instantiation never
completes and it stalls before the interesting part.

### 2026-08-28 (cont.) — Phases 3-5 landed

**Phase 3 — Plugin Selector** (`ui/PluginSelector.kt`): scan / force-rescan / remote-scanner
controls, live search, sortable Format/Name/Vendor table over the real catalog, destination
selector (New Track or an existing track), Device Name + API fields, and instantiation reporting
success or the plugin's own error. The blocked-bundle list is deliberately absent, not faked —
enumerating it needs AppModel's `PluginScanTool`, which the C API does not expose.

**Track list** now shows real tracks with their plugin instances, each opening its own Details
window keyed `details:<instanceId>`, so several coexist.

**Phase 4 — Instance Details** (`ui/InstanceDetails.kt`): Show/Hide UI, delete, pitch bend,
channel pressure, a playable `MidiKeyboard`, presets, UMP group, and a filterable parameter table
with per-parameter reset. Parameter edits route through `ProjectCommands.setPluginParameterValue`
rather than poking the instance, so they are undoable — as in uapmd-app.

**Phase 5 (part) — Project I/O**: `load_project`, `save_project`, `save_project_sync`,
`load_project_from_handle_token` bound on all five backends and wired into the Project menu with
an AWT-based desktop file chooser. Android/iOS/web pickers return null for now with a comment
naming what each needs.

Binding count: **53 of 81** `uapmd-c-app.h` functions. Probe now at **24 checks**, all passing on
real plugins — instantiation, parameter edit through the undo engine, and a `.uapmd` project
round-trip.

### Correction: plugin GUI hosting is app work, NOT a missing API

An earlier note in this session claimed the C API lacked a way to observe AppModel's
`uiShowRequested`, and that this blocked plugin GUI support. **That was wrong**, and no such item
was added to the missing-API inventory.

Plugin UI is already fully bound, at the plugin level rather than through AppModel, on all five
backends:

```kotlin
PluginInstance.uiCapabilities / hasUiSupport
PluginInstance.createUiPresentation(request): PluginUiPresentation?
PluginUiPresentation.show() / hide() / close() / setSize() / getSize()
PluginUiHost.FloatingWindow | NativeEmbedded(parentHandle) | WebEmbedded(containerId)
```

`composeApp` uses exactly this and had working plugin UIs on **desktop and Android**. The
`uapmd_app_request_show_plugin_ui` route I bound is uapmd-app's own indirection — AppModel raises
a request and its `MainWindow` serves it by creating a `ContainerWindow`. We do not need that
split: `uapmd-cmp` can call `createUiPresentation()` directly.

So the outstanding work is **app-side porting**, already listed in §3 of this plan as
"port, don't rewrite":

- `PluginUiHosting.{jvm,android,ios,wasmJs}.kt`
- `AndroidPlatformHostedPluginUiLayer.kt` (468 lines of hard-won AAP UI hosting)
- Wasm was unsupported in `composeApp`, but `PluginUiHost.WebEmbedded(containerId)` is bound, so
  hosting the plugin's web content in a container element should be reachable.

Until that lands, Instance Details' "Show UI" button calls `requestShowPluginUi`, which nothing
services — it is a no-op and should be disabled with a reason rather than left looking functional.

### 2026-08-28 (cont.) — Timeline, plugin UI hosting, and a second upstream bug

**Phase 2 — Timeline** (`ui/Timeline.kt`) is now the main content, replacing the placeholder
track list: a legend column (per-track plugin button, bypass, delete) beside time-ruled lanes
with clip rectangles, MIDI note previews drawn from `getMidiClipNotes()`, a playhead, and a zoom
control. Verified with a real SMF — a 224-second clip with **3557 notes decoded** renders.

Clip import needed **no new bindings**: `TimelineFacade.addMidiClipFromFile()` /
`addAudioClip()` and `createAudioFileReader()` were already bound. Wired into the Import menu.

The Seconds/Beats toggle is present but disabled — the beats view needs `TempoMap`, which the C
API does not expose (inventory §3). Likewise the legend's gain slider and M/S buttons are
disabled: the *setters* exist via `ProjectCommands`, but without `trackGain()`/`muted()`/`solo()`
getters a correct control cannot be drawn.

**Phase 4 completed — plugin UI hosting**, ported from `composeApp` rather than rewritten:
`PluginUiHosting.kt` per platform plus the 481-line `AndroidPlatformHostedPluginUiLayer`, adapted
to take `UapmdHost` and a four-field `HostedInstanceInfo` instead of composeApp's much larger
`UapmdModel`/`InstanceInfo`. `UapmdHost.showPluginUi()` follows composeApp's negotiation:
platform-hosted (Android AAP) → existing presentation → embedded target → floating window.
The Details window now calls this instead of the `requestShowPluginUi` no-op.

### Upstream bug #2: CLAP plugin UI creation passes a null api string

`remidy/src/clap/PluginInstanceCLAP.UI.cpp:72` calls `tryCreateWith(nullptr, true)` as a
fallback, and `tryCreateWith` null-guards only the *support check*:

```cpp
if (api && !owner->plugin->guiIsApiSupported(api, floating)) return;
if (!owner->plugin->guiCreate(api, floating)) return;   // api may be nullptr
```

clap-helpers' `clapGuiCreate` then `strlen()`s it. Crash: `SIGSEGV`, `si_addr: 0x0`, frame
`_platform_strlen` under `clap::helpers::Plugin<...>::clapGuiCreate`, reproduced with Dexed.

Not our binding: it passes `host_kind = FloatingWindow` with a null *parent handle*, which is
correct; remidy does its own api negotiation. Plugin-dependent — plugins that tolerate a null api
will not crash, which is likely why `composeApp` looked fine on desktop with VST3/AU.

Probe gate: `-Duapmd.probe.pluginUi=1` exercises it; the default run stays green at **28 checks**.

Also fixed: `String.format` is JVM-only and had slipped into common code — caught only when
building wasm and iOS. Build all five targets, not just the JVM.

### 2026-08-28 (cont.) — regression fixed, Phase 7 windows

**Regression (reported, mine).** Swapping the placeholder track list for the Timeline dropped the
per-instance buttons that opened Instance Details, so Details became unreachable. Fixed by
implementing the plugin context menu uapmd-app actually has, which is better than what was lost:
the legend's plugin button is labelled with the first instance (or "Add Plugin" when empty) and
opens Show/Hide *name* Details, Show/Hide *name* GUI, Delete *name* (at [n]), Add Plugin. A ⋮
menu beside it carries Bypass/Enable Track Processing and Delete Track.

Verified in the running app, not by inspection: with `-Duapmd.cmp.instantiate=AU` the legend
reads "⋮ Gateway".

**Phase 7 (part).**
- **Mixer Monitor** — audible/render positions, master latency and render lead, and a per-track
  table of plugin count, latency, render lead, tail length and dirty state. The monitoring-policy
  and infinite-tail-policy dropdowns are absent: those enums are not in the C API.
- **Device Settings** — audio in/out pickers, sample rate, buffer size, and AppModel's
  auto-buffer-size switch, applied through `updateAudioDeviceSettings` + `reconfigureAudioDevice`.
  Platform MIDI routing is absent: the MIDI port list is not exposed.
- **Plugin Instances** — every instance across tracks with its Details toggle, UMP device name,
  Enable/Disable device and removal.

**Two dev hooks** (`-Duapmd.cmp.importMidi`, `-Duapmd.cmp.instantiate`) mirror uapmd-app's web
auto-import, so the UI can be brought up in a known state for verification. The second has to
wait for AppModel's asynchronous startup scan and retry across candidates, since the first
catalog entry frequently fails to instantiate.

Also worth noting: a sed-style edit corrupted an import because `DropdownMenu` is a prefix of
`DropdownMenuItem`. Caught by the compiler, but a reminder to anchor such replacements.

### 2026-08-28 (cont.) — Addins, MIDI clip events, dump editor

**Addin Manager** window: the engine publishes its extension points, then the manager loads what
is installed — uapmd-app's order. Lists each addin's state, built-in flag, package and path, with
an enable switch and any failure message. `AddinManager` was already bound; no new API needed.

**MIDI clip UMP events bound** on all five backends — `get_midi_clip_ump_events`,
`add_ump_event_to_clip`, `remove_ump_event_from_clip`, `remove_clip_from_track` — taking the
binding to **57 of 81**. This was the third and hardest by-value shape: a struct containing a
count and a pointer to an array of structs. Verified on the real 224-second SMF: **8136 events**
decoded, first word `40b16500` (a MIDI 2.0 CC), ticks non-decreasing.

**MIDI dump editor** (`ui/MidiDumpWindow.kt`): the raw UMP stream of one clip with a hex filter,
per-event removal and append. Reached by clicking a clip's label in the timeline, one window per
`(track, clip)`.

Probe now at **31 checks**, all passing.
