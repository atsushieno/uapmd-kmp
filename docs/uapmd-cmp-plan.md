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


### 2.6 Verify UI and behaviour headlessly, not by eye

Three harnesses exist so a claim about the UI can be checked rather than asserted. Use them
before reporting a UI change as working.

| Task | What it does |
|---|---|
| `:uapmd-cmp:renderUiSnapshot` | Renders a view off-screen to a PNG at device density. `-Duapmd.cmp.snapshotView=` picks `timeline` (default), `selector`, `graph`, `instance` or `pianoroll`; `-Duapmd.cmp.snapshotSize=WxH` and `-Duapmd.cmp.snapshotDensity=` set the frame. This is how a clipped legend, an unreadable label or a link that never draws gets caught. |
| `:uapmd-cmp:runBootstrapProbe` | Drives AppModel headlessly - audio start/stop, tracks, plug-ins, clips, graph, tempo map, piano-roll edits - and fails on the first broken check. |
| Serving the wasm build | The dev server injects COOP/COEP itself, so the service-worker path in `index.html` never runs there and a dev-server load proves nothing about a static deploy. To exercise what users get, build `:uapmd-cmp:wasmJsBrowserDistribution` and serve `build/dist/wasmJs/productionExecutable` with COOP/COEP headers of your own; the two builds have already differed in practice. |
| `:uapmd-cmp:runPianoRollScrollProbe` | Scrolls the piano roll with the wheel through `ImageComposeScene` and compares renders. One notch versus twelve: a viewport that moves once and stops renders them the same. |
| `:uapmd-cmp:runScanPollProbe` | Times what the UI poll costs while a plug-in scan runs, reporting first/median/p95/max per call. Use it before adding anything to the poll. |
| `:uapmd-cmp:runKeyboardDragProbe` | Drags a pointer across the on-screen keyboard through `ImageComposeScene` and reports the notes produced, for touch and for mouse separately. |

**"Scrolling" means the scroll machinery, not drag-to-pan.** A viewport the wheel,
a trackpad and a scrollbar can move - `horizontalScroll`/`verticalScroll` over content
sized to the whole document - is what an editor means by scrolling, and it is what
uapmd-app's roll has (a scrolled child with `hScroll`/`vScrollPx`). Panning by
dragging the canvas is not a substitute: in an editor a drag belongs to the notes,
and inside a floating window it fights the window's own gestures. Sizing the content
also removes the scroll arithmetic - pointer coordinates arrive in content space, so
hit testing needs no offsets.

**Every preview note-on needs its note-off.** The synth holds a note until it is
released, so auditioning on click without releasing leaves notes sounding and
eventually jams every voice. Audition on press, release on lift
(`detectTapGestures(onPress = { … tryAwaitRelease(); … })`).

**Never key a `pointerInput` on state the gesture itself writes.** Compose restarts
the detector when a key changes, so the first delta lands and the gesture is then
cancelled: the view jumps once and stops following the pointer. This has now caused
three separate "it does not work" reports - the timeline navigator's zoom, and the
piano roll's vertical and horizontal scrolling. Read the value inside the handler
instead, and take callbacks through `rememberUpdatedState`.

A harness must not set up the state the feature under test is supposed to establish. The graph
snapshot used to call `ensureTrackUsesEditorGraph()` itself, which hid the fact that opening the
editor did not - the window opened on the simple chain and drew every node unconnected.

### 2.7 The startup scan is fast-only; the catalog arrives later

`AppModel::maybeStartInitialPluginScan` runs with `requireFastScanning = true`
(`AppModel.cpp:430`), so on a cold cache it legitimately finds **nothing** and the
selector opens empty. The full sweep is the slow scan the "Scan Plugins" button
runs, which on this machine takes a while and finds ~287 entries where the fast scan
found 0. Two consequences worth keeping in mind:

- An empty selector on first run is not a scanning failure, and `isScanning` alone
  cannot distinguish a long scan from a stuck one - which is why the progress counts
  and `lastPluginScanError` are bound and shown, as uapmd-app shows them.
- The catalog must be re-read when a scan *finishes*. Reading it only while it is
  empty means a rescan never reaches the list, so pressing Scan appears to do
  nothing whether or not the scan worked.

### 2.8 Scan out of process wherever the platform allows it

An in-process scan runs every plug-in's entry code inside the app, so one bad
plug-in takes the app down partway through - which is why uapmd-app defaults
`useRemoteScanner_` and `forceRescan_` to **true** (`PluginSelector.hpp:39-42`) and
offers the remote scanner everywhere `kRemoteScannerSupported` holds: desktop only,
never Android, iOS or the browser.

uapmd-cmp cannot use it the way uapmd-app does. Remote scanning relaunches the
host's own executable with `--scan-only --ipc-client …`; on the JVM that executable
is `java`, which serves no scanner, so the scan dies with "Remote scanner failed to
connect". uapmd's standalone `uapmd-scan` already understands those arguments
(`tools/uapmd-scan/main.cpp:74`), so the missing piece was a way to point the
launcher at it - added upstream as `setRemoteScannerExecutable`
(`RemoteScannerServer.hpp`), exposed as `uapmd_set_remote_scanner_executable`.

The desktop app resolves the binary from `-Duapmd.cmp.scannerExe`,
`UAPMD_SCAN_EXECUTABLE`, or beside the native library, and reports
`platformSupportsRemoteScanner` only when it found one. **A build that ships no
scanner must not default to remote**: a scan that runs in process and risks a crash
still beats one that cannot start.

Two consequences of getting this wrong, both seen in practice. With no scanner found
the app silently scans in process, where formats that must instantiate on the UI
thread block it - the window freezes mid-scan and coroutines pile up suspended - and
a single bad plug-in kills the app outright. So the Gradle build forwards the built
`uapmd-scan` path to the desktop app and to every probe that scans; without that
forwarding the flag is false and the default silently degrades.

Measured cost of the UI poll during a real 164-bundle remote scan
(`:uapmd-cmp:runScanPollProbe`, 167 samples): `slowScanProgress` median 157µs / p95
570µs, `lastPluginScanError` median 16µs, a full 287-entry catalog read median 2.5ms
/ p95 6.4ms. First calls cost 11-13ms on JNA layout setup, which is why the probe
reports medians rather than maxima - judging this on a handful of samples points at
the wrong thing.

### 2.9 WebCLAP scanning needs the audio worklet, and a truthful main-thread check

Two things have to hold before a plug-in scan finds anything in a browser.

**The audio engine must be running.** WebCLAP bundles are fetched and inspected by
the AudioWorklet; the bridge that carries a scan request only has a transport once
`WebAudioWorkletIODevice::start()` has created the worklet node. Scanning with the
engine off queues a request nothing delivers, and the scan then sits at 0 bundles -
uncancellable, because `shouldCancel` is only polled between bundles. The engine
cannot simply be started at load either: browsers require a user gesture. So the
selector disables Scan and says why while the engine is off.

**`EventLoop::runningOnMainThread()` must tell the truth.** `EventLoopEmscripten`
returned `true` unconditionally, and `AppModel::performPluginScanning` runs the scan
on a `std::thread` - a Web Worker, with its own JS scope, no `document`, no worklet
node and its own copy of `Module`. `runTaskOnMainThread()` therefore ran the bridge
code *inside the worker*, which built a second, unreachable bridge with `node: null`
and queued the request into it forever, while the main-thread bridge sat idle. Fixed
upstream: the check is now `emscripten_is_main_browser_thread()`, and a task enqueued
from a worker is proxied with `emscripten_async_run_in_main_runtime_thread`.

Note for future debugging: the worklet's fetches do **not** appear in the page's
network log, because a worker issues them. `performance.getEntriesByType('resource')`
does show them, and an empty page-level log means nothing here.

### 2.10 Changes to `external/uapmd` ship as patches, applied by the build

uapmd-cmp needs a few embedder hooks upstream does not have yet
(`setRemoteScannerExecutable`, the Emscripten main-thread check). They live in a
pinned submodule, so a fresh checkout - CI's especially - has none of them and would
compile unpatched sources. They are kept in `patches/uapmd/*.patch`, and every
native build depends on `:uapmd-binding:applyUapmdPatches`: the desktop and wasm C
API builds directly, and AGP's CMake tasks through an `afterEvaluate` match. CI runs
everything through Gradle, so nothing extra is needed there.

The rule that shapes the design: **the patch step must never stop a build.** It runs
on every incremental build and meets messy states by nature - already applied, half
applied, or applied and then edited further while working on the hook. So:

- each file in a patch is handled **independently**, because a half-applied patch
  would otherwise be rejected whole;
- the apply order is **plain, then `--3way`, then `--ignore-whitespace`**, and that
  order is load-bearing. Git for Windows checks out with `core.autocrlf=true`, and
  `--3way` refuses a CRLF working tree outright while a plain apply takes it happily.
  Trying `--3way` first broke every Windows CI build: all three files were reported
  as refusing the patch, and the job then failed fourteen minutes later at
  `'setRemoteScannerExecutable': is not a member`. `--3way` is still needed, but only
  for what it is uniquely good at - completing a half-applied patch. The file it
  stages is unstaged again, since a build has no business leaving things staged;
- a file that will not take the patch is **left exactly as it is** - never reverted.
  An early version ran `git checkout --merge -- .` to clean up and destroyed the
  patched state of all three files;
- failure is not fatal. Sources that genuinely lack the hooks fail to compile
  seconds later at the call site, which says far more than a patch-tool error. A
  locally modified file is reported at lifecycle level; only a file that is
  *unmodified* and still refuses the patch warns, because that means the submodule
  moved and the patch is stale.

The warning carries git's own output, because a CI failure that does not say *why*
the patch was refused costs a round trip to find out - which is exactly what the
first Windows failure cost.

Refresh a patch with `git -C external/uapmd diff > patches/uapmd/<name>.patch`. To
check a change against a Windows-style checkout without one, convert the target
files to CRLF and run `:uapmd-binding:applyUapmdPatches`; that reproduces the
failure faithfully.

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

All were found while building `uapmd-cmp`, and none is a binding or app defect. `external/uapmd`
is a submodule whose commits are yours, so they are recorded here rather than fixed, and each one
affects uapmd-app itself and not only uapmd-cmp. Defects in anything else uapmd-cmp depends on
belong in `uapmd-cmp-ui-audit.md` under Known defects.

### 6.0 Audio never recovers from an output route change

`OboeAudioIODevice` reports `stream error ErrorDisconnected`, logs `reopening stream after error`,
and then `restart after close failed: ErrorClosed` - after which audio never returns. Reproduced
twice on Android by starting an audio capture while a stream was open; any route change
(headphones, Bluetooth, another app capturing) takes the same path. The restart logic is in
`uapmd-engine/src/devices/OboeAudioIODevice.cpp`.

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

### 6.3 A graph connection naming a built-in node is always refused

`TimelineFacadeImpl::resolvePluginInstanceId` (`TimelineFacadePlugins.cpp:637`) walks
`track->orderedInstanceIds()` and matches `getPluginNode(id)->nodeId()`, so it resolves plugin
nodes and nothing else. A `Plugin` endpoint naming a built-in node - the track's own
`builtin:track_gain`, say - resolves to -1, and `TimelineFacadeMixer.cpp:376` then rejects the
connection with "A plug-in graph endpoint no longer exists". uapmd-app's graph editor draws pins
for those nodes too and hits the same refusal, so neither app can wire them.

### 6.4 `coop-coep-sw.js` rejects any null-body-status response

`withIsolationHeaders` (`tools/uapmd-app/web/coop-coep-sw.js`) rebuilds every same-origin response
as `new Response(response.body, { status, statusText, headers })`. That constructor throws
`TypeError: Response with null body status cannot have body` for statuses 204, 205 and 304, and
the throw happens inside the `.then()` handed to `event.respondWith()`, so the browser reports
"A ServiceWorker intercepted the request and encountered an unexpected error" for the page or
script that was being fetched. Verified in a browser: 204, 205 and 304 throw, while 200, 206, 301,
404 and 500 construct fine.

The guard is to pass those statuses through untouched - they carry no body to re-wrap and no
document to isolate:

```js
if (response.status === 204 || response.status === 205 || response.status === 304)
    return response;
```

Not fixed here because `external/uapmd` commits are yours; uapmd-app serves the same file and
takes the same path.
