# UI action audit: uapmd-app 0.5.6 → uapmd-cmp

Every user-visible action in uapmd-app, checked one by one against uapmd-cmp.
Source of truth: `external/uapmd/source/tools/uapmd-app/gui/` at the pinned commit.

Status: ✅ matches · ⚠️ present but differs · ❌ missing

**Round 2: layout corrections.** Two structural mistakes found by re-reading
`MainWindow.cpp` rather than trusting my earlier note:

- **The toolbar is two rows, not one.** There is no `ImGui::SameLine()` after the theme
  toggle (`MainWindow.cpp:576-581`), so `Plugins` starts a second line, and the toolbar child is
  `90.0f * uiScale_` tall — two rows' worth. My plan had recorded "single-row" and I built to
  that. Row 1 is engine / Command / transport / scale / theme; row 2 is Plugins / Import /
  Project / In+Out meters.
- **There is no top-level Scan button.** uapmd-app scans from inside the Plugin Selector
  (`PluginSelector.cpp:116`). I had invented a toolbar Scan button; removed.

**Small screens.** Both toolbar rows are `FlowRow`s, so they wrap instead of clipping. The track
legend adapts: 260dp wide normally, 150dp under 620dp of window, and below that threshold the
gain slider, Mute and Solo move into the ⋮ menu rather than growing the row — fewer top-level
controls is the point, not taller rows. Legend and lane columns share one `trackHeight` so the
two scrolling columns cannot drift apart. Verified at **412×915** (a phone viewport) via
`-Duapmd.cmp.windowSize=412x915`, which the desktop build now accepts for exactly this.

**Round 1 fixes applied** (the three you reported, plus audit items):

| Was | Now |
|---|---|
| `.uapmdz` load **crashed** | archives are unpacked via `uapmd_prepare_project_load`, plug-in UIs closed and the audio engine stopped around the load — composeApp did all three, uapmd-cmp did none |
| per-track **Add Plugin** added a *new* track | the selector destination lives on the host; opening from a track pre-targets it, matching `setTargetMasterTrack`/`setTargetTrackIndex` |
| **no visible timeline tracks** | lanes have row backgrounds and dividers, so tracks read as rows even when empty |
| no master track | master row above the others, with "Add Master Plugin" and a reduced action set (1.x, 3.8, 5.3) |
| 1.4/1.5 labels static | Show/Hide toggles with window state |
| 1.8 no shortcuts | Ctrl/Cmd+Z, Shift+Ctrl+Z, Ctrl+Y |
| 1.12 no UI scale | Scale combo ×0.5…×4.0, via a density override |
| 1.13 no theme toggle | dark/light toggle |
| 1.19 no spectra | In/Out meters from `getInputSpectrum`/`getOutputSpectrum` |
| 2.3 linear gain, no gesture | dB slider with an undo **gesture**, so a drag is one history entry |
| 2.5 no additive solo | Ctrl/Cmd-click is additive |
| 3.3/3.6 missing clip entries | Add Empty Audio Clip, Add MIDI2 Clip from File |
| 6.4 no state save/load | Save/Load State + UMP group selector in Instance Details |
| 6.14 no quit dialog | unsaved-project dialog |


## 1 · Toolbar (`MainWindow.cpp:399-720`)

| # | uapmd-app | uapmd-cmp | Status |
|---|---|---|---|
| 1.1 | Audio Engine On/Off, colour-coded, tooltip | same, colour-coded | ✅ |
| 1.2 | Command ▸ Undo *desc* / Redo *desc*, disabled when unavailable or busy | same | ✅ |
| 1.3 | Command ▸ busy line "History operation in progress…" | same | ✅ |
| 1.4 | Command ▸ Show/Hide Device Settings | present, but label does not toggle | ⚠️ |
| 1.5 | Command ▸ Show/Hide Addins | present, label does not toggle | ⚠️ |
| 1.6 | Command ▸ Show/Hide Script | — | ❌ needs `UapmdJSRuntime` |
| 1.7 | Command ▸ Show/Hide MCP Settings | — | ❌ needs `McpServer` |
| 1.8 | Ctrl/Cmd+Z, Shift+Ctrl+Z, Ctrl+Y shortcuts | — | ❌ |
| 1.9 | Play / Stop, disabled when engine off | same | ✅ |
| 1.10 | Record into selected MIDI clip, red while recording | same | ✅ |
| 1.11 | Pause / Resume, disabled when not playing | same | ✅ |
| 1.12 | UI Scale ×0.5…×4.0 | — | ❌ |
| 1.13 | Theme toggle (dark/light) | — | ❌ dark only |
| 1.14 | Plugins (opens selector, targets new track) | same | ✅ |
| 1.15 | Import ▸ Import MIDI Tracks (SMF) — splits across tracks | single-clip import only | ⚠️ needs `importMidiTracksFromFile` |
| 1.16 | Import ▸ Import Split Audio Tracks (Demucs) | — | ❌ not in C API |
| 1.17 | Project ▸ Load / Save | same, unpacks archives | ✅ |
| 1.18 | Project ▸ Render To File | same | ✅ |
| 1.19 | In / Out spectrum analysers | — | ❌ |

## 2 · Track legend (`TimelineEditor.cpp:1182-1445`)

| # | uapmd-app | uapmd-cmp | Status |
|---|---|---|---|
| 2.1 | Clips icon → clip popup | Clips button → popup | ✅ |
| 2.2 | Graph icon → per-track graph window | Graph button | ✅ |
| 2.3 | Gain slider, dB readout, undo *gesture* around the drag | slider, plain commit, no gesture, linear not dB | ⚠️ |
| 2.4 | M mute, red when muted | same | ✅ |
| 2.5 | S solo, orange when soloed, Ctrl = additive | present; **no Ctrl-additive** | ⚠️ |
| 2.6 | Freeze button with policy state, spinner while rendering | menu item only, no state or spinner | ⚠️ needs `FrozenTrackManager` |
| 2.7 | Plugin context button labelled with first instance | same | ✅ |
| 2.8 | ⋮ More ▸ Bypass/Enable Track Processing | same | ✅ |
| 2.9 | ⋮ More ▸ Delete Track | same | ✅ |
| 2.10 | Controls disabled while the track is busy | — | ❌ needs `FrozenTrackManager::isTrackBusy` |

## 3 · Clips popup (`TimelineEditor.cpp:1446-1475`)

| # | uapmd-app | uapmd-cmp | Status |
|---|---|---|---|
| 3.1 | Edit Clips… (Sequence Editor window) | — | ❌ |
| 3.2 | Add an Empty MIDI2 Clip | same | ✅ |
| 3.3 | Add Empty Audio Clip | — | ❌ |
| 3.4 | Create Audio Clip From File… | same | ✅ |
| 3.5 | Add a MIDI Clip from File… | same | ✅ |
| 3.6 | Add MIDI2 Clip from File… | — | ❌ |
| 3.7 | Clear All | same | ✅ |
| 3.8 | Master track gets a reduced set | no master track row at all | ❌ |

## 4 · Plugin popup (`TimelineEditor.cpp:1477-1545`)

| # | uapmd-app | uapmd-cmp | Status |
|---|---|---|---|
| 4.1 | Show/Hide *name* Details per instance | same | ✅ |
| 4.2 | Show/Hide *name* GUI, disabled without UI support | same | ✅ |
| 4.3 | Delete *name* (at [n]) | same | ✅ |
| 4.4 | Add Plugin — **targets this track** | fixed; was adding a new track | ✅ |

## 5 · Timeline

| # | uapmd-app | uapmd-cmp | Status |
|---|---|---|---|
| 5.1 | View: Seconds ⇄ Beats | same, constant-tempo only | ⚠️ |
| 5.2 | Navigator: position controller + zoom slider | zoom slider only | ⚠️ |
| 5.3 | Master track row above the others | — | ❌ |
| 5.4 | Clip drag to move | same | ✅ |
| 5.5 | Clip resize by dragging edges | — | ❌ (numeric resize in properties) |
| 5.6 | Drag-to-select a range → add clip in range | — | ❌ |
| 5.7 | Clip context actions | same, plus Properties | ✅ |
| 5.8 | Playhead | same | ✅ |
| 5.9 | Bottom bar: + / Mixer Monitor / Plugin Instances | same | ✅ |

## 6 · Windows

| # | uapmd-app | uapmd-cmp | Status |
|---|---|---|---|
| 6.1 | Plugin Selector: scan, force rescan, remote + timeout | no timeout field | ⚠️ |
| 6.2 | Plugin Selector: collapsible blocked-bundle list | — | ❌ no C API to enumerate |
| 6.3 | Plugin Selector: search, sortable columns, instantiate, destination, device name + API | same | ✅ |
| 6.4 | Instance Details: UI, state save/load, pitch bend, pressure, keyboard, presets, UMP group, parameters | **no Save/Load State**, no UMP group editing | ⚠️ |
| 6.5 | Plugin Instances: per-instance UMP device name, enable/disable, details, remove | same | ✅ |
| 6.6 | Mixer Monitor: positions, latency table | same, no policy dropdowns | ⚠️ |
| 6.7 | Device Settings: audio in/out, rate, buffer, auto | same, **no platform MIDI routing** | ⚠️ |
| 6.8 | Addin Manager | same | ✅ |
| 6.9 | Track Graph Editor | same | ✅ |
| 6.10 | Exporter | same | ✅ |
| 6.11 | MIDI dump editor | same | ✅ |
| 6.12 | Audio event list (markers/warps) | same | ✅ |
| 6.13 | Piano roll: zoom, drag, snap, velocity, per-note automation, NRPN picker | drag/zoom/snap only | ⚠️ |
| 6.14 | Unsaved-project dialog on quit | — | ❌ |
| 6.15 | Script Editor | — | ❌ |
| 6.16 | MCP Settings | — | ❌ |
