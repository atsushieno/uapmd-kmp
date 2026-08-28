# Plan: `uapmd-cmp` — a fresh Compose Multiplatform app for uapmd 0.5.6

The standing rules and architecture for `uapmd-cmp`. Outstanding gaps against uapmd-app are
tracked in `uapmd-cmp-ui-audit.md`; binding gaps in `uapmd-binding-missing-api.md`.

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

### 1.3 Provenance of the binding gaps — checked, and not a regression

The binding gaps are **not** things commit `13dac10` ("bump uapmd to 0.5.6 and update
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

For reference, everything else needed is genuine uapmd API and passes the rule:

| Item | Home |
|---|---|
| `trackGain()` / `muted()` / `solo()` | `uapmd-engine` — `SequencerTrack.hpp` |
| `FrozenTrackManager` | `uapmd-engine` |
| `MidiRecorder` | `uapmd-engine` |
| `TempoMap` | `uapmd-data` |
| `AppModel`, `TransportController`, `McpServer` | `tools/uapmd-app-model` — see §5.3 |

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

So the audio path depending on AppModel is **not** a blocker for any target. Were Emscripten
support to be lost, Wasm would keep engine control through the engine-level route `composeApp`
uses on wasmJs today — `uapmd_engine_set_active` plus `RealtimeSequencer.startAudio()`/`stopAudio()`.
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
it. Adopting AppModel *increases* this surface, so `main_common.cpp` and `web_main.cpp` have to be
audited explicitly. Their AppModel-related sequence is:

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

§5.2 has a bigger consequence than it looks. uapmd-app is a **multi-window** application, and
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

The in-scene manager comes ahead of nearly all feature work, because almost every feature
delivers one or more windows and would otherwise invent its own container.
It is the single highest-leverage piece of infrastructure here, and getting it wrong late
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

## 4 · Risks

- **Main-thread blocking is the recurring failure mode, on wasm and on Android.** `aaed96b`'s
  defect was a synchronous C API call (`uapmd_scan_tool_perform_scanning` ->
  `InProcessScanSessionManager::runScan`) blocking on a condition variable while the completion it
  waited for could only be delivered by the thread it had blocked — the page stopped compositing
  permanently. Android has the same shape: an AAP plug-in bind waited on from the main thread can
  never be satisfied, because `onServiceConnected` is delivered on the main looper. AppModel has
  it too: `joinAudioShutdownWorker()` joins from the main thread and
  `completeAudioEngineShutdown()` sleeps on it for ~2 buffer periods. Anything that blocks or
  reaches a plug-in goes through `backgroundDispatcher()`; wasm must be verified **in a browser**,
  not assumed working because it compiles and pthreads are enabled.
- **Event-loop ordering.** AppModel's shutdown enqueues plugin deactivation on the main thread;
  if `initJvmEventLoop()` / `initAndroidEventLoop()` has not run first, that work never executes
  and the engine appears to hang on stop. See §2.3.
- **Five backends per binding addition.** Every new C API surface costs work in JNA, JNI,
  cinterop and the Emscripten bridge.
- **No ImGui equivalents.** ImGui's *windowing* (§3.1), ImNodes (graph) and ImTimeline (timeline)
  and the immediate-mode interaction model all have to be rebuilt in Compose. The timeline and
  piano roll are where this stays expensive rather than merely tedious.
- **Testing.** There is no CI environment with plugins installed; verification is manual on
  desktop, and iOS is, per the guide, "tested only on simulators / rarely tested".
- **Moving target.** uapmd is under heavy development. Pin the submodule and re-baseline
  deliberately.

---

## 5 · Decisions (settled)

1. **Wasm is a target.** Not "if it works out" — it is in scope, so enabling `uapmd-app-model`
   for Emscripten is required work, not an experiment. The §2.4 fallback is insurance, not an exit.
2. **Buffer sizes**: take AppModel's 1024 / 65536 / 48000. No parameterised
   `uapmd_app_instantiate_with(...)`.
3. **`composeApp`** stays until it is removed at some later stage; it does not need freezing and
   does not gate anything. This holds on one condition — see §5.1.
4. **No tab navigation.** Confirmed: it brings in a lot of nonsense. The main UI is the timeline,
   always visible.
5. **Piano roll is deferred** to a late stage of the migration, not part of the first parity
   pass. Everything else in the clip-editor group is not deferred.

### 5.1 The one constraint that keeps decision 3 true

`composeApp` is unaffected by this work **only for as long as every `uapmd-binding` change is
additive**. The binding work does change that module — substantially — but it adds new
interfaces (`AppModel`, `TransportController`) and new `expect`/`actual` pairs rather than
altering existing signatures, so `composeApp` keeps compiling and can be deleted whenever convenient.

Where 0.4 touches an *existing* declaration — for instance adding `restoreNodeId` to
`addPluginToTrack()` — give it a default value so the change stays source-compatible. If some
gap genuinely cannot be filled additively, that is the moment to remove `composeApp` rather than
to contort the binding around it.

### 5.2 Do not diverge from uapmd-app UI behaviour

Two rules an earlier tracker carried are **wrong** and must not come back. They were
divergences, and divergence is the thing to avoid:

- The plugin list **is a floating window**, not a side panel. A side panel wrecks the flow of
  picking a plugin to add to an already-selected track.
- Instance details **are floating windows**, plural — more than one can be open at a time, which
  a single panel cannot express at all.

Generalised: where this plan and uapmd-app disagree about UI behaviour, uapmd-app wins unless
there is a platform reason that makes its behaviour impossible. See §3.1 — this has real
structural consequences, and they land early.

(Native OS windows on desktop were considered as a sanctioned exception — ImGui's in-window
placement being a constraint artifact rather than a design choice — but dropped; see §3.1.)

### 5.3 Scope of "the uapmd API" — settled

Does "the uapmd API" include `tools/uapmd-app-model`? `AppModel`, `TransportController` and
`McpServer` all live there rather than beside the libraries, and the whole architecture in §2
rests on binding them.

**Yes.** `uapmd-binding` already includes API bindings for AppModel, so it covers `tools/` by
construction.


---

---

## 6 · Open bugs in `external/uapmd`

Found while building `uapmd-cmp`, but neither is a binding or app defect — both are upstream, and
`external/uapmd` is a submodule whose commits are yours, so they are left here rather than fixed.
Both affect uapmd-app itself, not only uapmd-cmp.

### 6.1 Null deref in `completeAudioEngineShutdown()` after removing a plug-in

`uapmd-app-model/src/AppModel.cpp:783`

```cpp
auto* host = sequencer_.engine()->pluginHost();
for (auto id : host->instanceIds())
    host->getInstance(id)->stopProcessing();   // no null check
```

After `uapmd_app_remove_plugin_instance()`, `instanceIds()` still reports the removed id while
`getInstance()` returns `nullptr`.

Deterministic: instantiate → remove → engine off ⇒ `SIGSEGV`, `si_addr: 0x0`, in
`uapmd_app::AppModel::completeAudioEngineShutdown()+0x110`, reached from the event-loop task.
Skipping only the removal leaves teardown clean. Reproduce with `-Duapmd.probe.removeInstance=1`;
the probe keeps it opt-in so default runs stay green.

`tests/uapmd-app-shutdown-crash.c` is a started pure-C repro but **does not work yet**: a bare C
host installs no remidy `EventLoop`, so instantiation never completes and it stalls before the
interesting part.

### 6.2 CLAP plug-in UI creation passes a null api string

`remidy/src/clap/PluginInstanceCLAP.UI.cpp:72` calls `tryCreateWith(nullptr, true)` as a fallback,
and `tryCreateWith` null-guards only the support check:

```cpp
if (api && !owner->plugin->guiIsApiSupported(api, floating)) return;
if (!owner->plugin->guiCreate(api, floating)) return;   // api may be nullptr
```

clap-helpers' `clapGuiCreate` then `strlen()`s it: `SIGSEGV`, `si_addr: 0x0`, in `_platform_strlen`
under `clap::helpers::Plugin<...>::clapGuiCreate`. Reproduced with Dexed.

Plug-in dependent — plug-ins that tolerate a null api do not crash, which is why `composeApp`
looked fine on desktop with VST3/AU. Reproduce with `-Duapmd.probe.pluginUi=1`.
