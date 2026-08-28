# `uapmd-cmp` — standing rules and architecture

The rules that constrain `uapmd-cmp`, and the defects still open against it. Outstanding gaps
versus uapmd-app are tracked in `uapmd-cmp-ui-audit.md`; binding gaps in
`uapmd-binding-missing-api.md`.

Reference for every question of behaviour: `external/uapmd/source/tools/uapmd-app/` at the pinned
submodule commit (`93c25a70`, 0.5.6).

---

## 2 · Architecture

`uapmd-cmp` is a thin Compose view layer over the Kotlin binding of `AppModel` — including
audio-engine control. Parity with uapmd-app is structural rather than a chase: both render the
same façade. Re-implementing app logic in Kotlin (what `composeApp` does) is the thing that
produced the drift being corrected, and it structurally cannot reach gain/mute/solo/freeze/graph/
history.

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

**The audio device configuration is part of "match uapmd-app".** Leaving the engine's automatic
buffer sizing on makes the Oboe device come up at `internalCapacity=1024 stabilizedBlock=1024`,
and on that block size the engine cannot sustain real time with a six-plug-in project on Android:
measured repeatedly at 87-92% of real time, i.e. the playhead advances ~10.7 s per 12 s of wall
clock, heard as continuous stuttering. uapmd-app runs at `stabilizedBlock=512`; matching it gives
99.95-99.98%. `UapmdHost.applyDefaultAudioBufferSize()` therefore turns auto sizing off and
configures 512 frames, and it must run **after** the engine is enabled - the audio device is
created asynchronously when the engine starts, so configuring earlier silently does nothing.

Compare the two apps' `OboeAudioIODevice: opened stream` log lines when this is in doubt; they
should agree on `internalCapacity` and `stabilizedBlock`.

`composeApp`'s audio layer works, but working is not the same as being good enough, and it has
never been shown to be. **uapmd-app's behaviour is the target.** That means adopting AppModel's
audio entry points rather than reimplementing them.

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

### 2.3 Event-loop ordering

remidy marshals engine completions through an `EventLoop`. A host must install one
(`initJvmEventLoop()` / `initAndroidEventLoop()`) **before** creating any engine or sequencer, or
async completions silently never fire — `addEmptyTrack` still creates the track, but its callback
never runs. On Android the loop must also not be the main looper; see `AndroidEventLoop.kt`.


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

### 2.5 We do not run uapmd-app's `main()` — so its setup is ours to reproduce

uapmd-cmp replaces `main_common.cpp`, and anything that entry point does silently becomes ours to
do, with no compile error when we skip it. The order is load-bearing:

1. install the platform event loop **before** AppModel exists (§2.3)
2. `uapmd_app_instantiate()`, then `notifyUiReady()`, then `notifyPersistentStorageReady()`
3. bring the audio engine to its per-platform initial state (desktop and mobile on, web off —
   browsers require a user gesture)
4. configure the audio device once the engine is up (§2.1)
5. teardown in reverse: engine off, flush the event loop, then `cleanupAppModel()`

`BootstrapProbeMain` exercises this headlessly; on web the persistent-storage step is what mounts
the IDBFS the plug-in list cache lives in.


## 3 · Window model

### 3.1 Floating, in-scene windows

uapmd-app is a multi-window application and several of its windows are *multi-instance*: instance
details, the track graph and the MIDI dump are per id, and more than one can be open at once.
Compose Multiplatform's `Window` is desktop-only, so the app draws its own draggable, resizable,
stackable windows inside the scene, addressed by a string key (`details:<id>`, `graph:<track>`,
`dump:<track>:<clip>`). Everything from the plug-in selector onwards depends on it.


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

### 6.0 Audio never recovers from an output route change

`OboeAudioIODevice` reports `stream error ErrorDisconnected`, logs `reopening stream after error`,
and then `restart after close failed: ErrorClosed` - after which audio never returns. Reproduced
twice on Android by starting an audio capture while a stream was open; any route change
(headphones, Bluetooth, another app capturing) takes the same path. The restart logic is in
`uapmd-engine/src/devices/OboeAudioIODevice.cpp`.


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
