# UI action audit: uapmd-app (`f5d490d5`) → uapmd-cmp

What uapmd-app does that uapmd-cmp does not, verified against
`external/uapmd/source/tools/uapmd-app/gui/` at the pinned commit. Only outstanding items are
listed; matched behaviour is not.

## Blocked on missing C API

| Feature | uapmd-app | What is missing |
|---|---|---|
| Why a freeze failed | freeze button tooltip | `FrozenTrackManager::errorMessageForTrack` has no C wrapper, so a failed freeze shows a red snowflake with no reason. Easy to reach: a clip long enough to exceed `kMaximumFrozenTrackBytes` lands in `Error` immediately |

This belongs in `uapmd-binding-missing-api.md` §2 as C API work.

## Not yet built

| Feature | uapmd-app | Notes |
|---|---|---|
| Piano roll: per-note automation, NRPN picker | piano roll | deliberately deferred |
| Rendering to a file on web | Project ▸ Render To File | The output path and the delivery are wired, but the render itself never finishes: the button stays on "Rendering…" with no progress and no file, on an empty project, for as long as it was left. Untested beyond that — it is the render, not the file handling |
| Loading a packed project on the Kotlin/JS target | `jsMain`'s `prepareProjectLoad` is still the pass-through that wasmJs used to be, so a `.uapmdz` would reach the engine as a ZIP. Dormant — uapmd-cmp builds wasmJs, not js — but it is the same defect, and `jsMain` has no archive helper bound yet |
| Details window opens for a newly created instance | after Instantiate Plugin | uapmd-app's `MainWindow::handleInstantiatePlugin` passes a completion that calls `instanceDetails().showWindow(result.instanceId)` on success, so creating a plug-in leaves its Details window open. uapmd-cmp now dismisses the selector on success (2026-09-16) but does not open Details — the other half of the same gesture, left out because it was not asked for |
| JSFX Settings window | System ▸ JSFX Settings | uapmd-app's GUI registers its own `JsfxResourcesCommand` (not an addin) into the command registry; uapmd-cmp has no JSFX resources window yet |
| Platform MIDI connections | System ▸ Device Settings | needs the MIDI port list, which the C API does not expose |
| File pickers on iOS | — | `pickProjectFileToOpen`, `pickMidiFileToOpen` and `pickAudioFileToOpen` all return null on iOS; uapmd has `DocumentProviderIOS.mm`, so this is binding work, not new C API |

## Intentional divergences

| What | Status |
|---|---|
| `ClipProperties` window | Ahead of uapmd-app rather than diverging from it: uapmd-app has no per-clip properties window (`setClipGain` / `setClipMuted` are never called from its GUI), but these features are wanted there too. Kept by the user's decision, 2026-08-29 |

## Known defects

Defects in `external/uapmd` live in `uapmd-cmp-plan.md` §6, not here. This section is for
defects in everything else uapmd-cmp depends on.

| Defect | Evidence |
|---|---|
| Dragging the keyboard with a mouse stops sending notes after a few keys | `compose-audio-controls` 0.7.3, `DiatonicKeyboard.kt`. On a Move the handler drops the pointer from `pointerIdToNote` whenever `getNoteFromPosition` returns null (`:255-266`) and never re-registers it, so the drag is dead until the button is released. The mouse/stylus branch resolves by exact rect containment (`:44-51`) and returns null in the 1px gaps between white-key rects — `Size(wkWidth.toPx() - 1f, ...)` at `:315`, while keys are spaced a full `wkWidth` apart. Touch takes the nearest-match branch (`:52-56`) and cannot hit it, which is why Android is unaffected. `./gradlew :uapmd-cmp:runKeyboardDragProbe` establishes all three parts: Compose delivers 239-240 of 240 moves for mouse while the widget emits only 5 notes (so this is not a Compose Multiplatform delivery bug); touch sweeps 26; and the same mouse drag through the band where black-key rects cover the gaps sweeps 28. Desktop only. Upstream, in a library consumed as a Maven artifact |
