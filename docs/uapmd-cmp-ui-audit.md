# UI action audit: uapmd-app 0.5.6 → uapmd-cmp

What uapmd-app does that uapmd-cmp does not, verified against
`external/uapmd/source/tools/uapmd-app/gui/` at the pinned commit. Only outstanding items are
listed; matched behaviour is not.

## Blocked on missing C API

| Feature | uapmd-app | What is missing |
|---|---|---|
| Script editor | Command ▸ Show/Hide Script | `UapmdJSRuntime` is not exposed by the C API |
| MCP settings | Command ▸ Show/Hide MCP Settings | `McpServer` handle is not exposed |
| Import split audio tracks (Demucs) | Import ▾ | no C entry point for the Demucs import path |

The menu entries for these exist in `Toolbar.kt`, disabled, so the gap is visible in the UI
rather than silently absent. They belong in `uapmd-binding-missing-api.md` §2 as C API work.

## Not yet built

| Feature | uapmd-app | Notes |
|---|---|---|
| Blocked-bundle list in Plugin Selector | collapsible list of blocked bundles | **not** an API gap: `uapmd_scan_tool_blocklist_count` / `_get_blocklist_entry` exist and are bound as `ScanTool.blocklistCount` / `getBlocklistEntry`. Only the UI is missing |
| Beats view tempo map | `View: Beats` | uses the project tempo; `TempoMap` is not exposed, so tempo changes mid-project are not honoured |
| Piano roll: per-note automation, NRPN picker | piano roll | deliberately deferred |

## Divergences awaiting a decision

| What | Status |
|---|---|
| `ClipProperties` window | An invention. uapmd-app has no per-clip properties window — `setClipGain` / `setClipMuted` are never called from its GUI, and name/file editing lives in the Sequence Editor table. Keep or delete? |

## Known defects

| Defect | Evidence |
|---|---|
| Audio never recovers from an output route change | `OboeAudioIODevice: stream error ErrorDisconnected` → `reopening stream after error` → `restart after close failed: ErrorClosed`. Reproduced twice on Android by starting an audio capture mid-stream; any route change (headphones, Bluetooth, another app capturing) should do the same. The restart path is in `uapmd-engine/src/devices/OboeAudioIODevice.cpp`, i.e. upstream |
