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
| Import SMF as split tracks | Import ▾ | `importMidiTracksFromFile` is not exposed |
| Blocked-bundle list in Plugin Selector | collapsible list of blocked bundles | `uapmd_app_clear_plugin_blocklist` exists, but nothing enumerates the blocklist |
| Track busy state | freeze button disabled and spinning while the track renders | `FrozenTrackManager::isTrackBusy` and the runtime freeze state are not exposed |

The menu entries for the first four exist in `Toolbar.kt`, disabled, so the gap is visible in the
UI rather than silently absent. These belong in `uapmd-binding-missing-api.md` §3 as C API work.

## Not yet built

| Feature | uapmd-app | Notes |
|---|---|---|
| Sequence Editor window ("Edit Clips…") | Clips ▸ Edit Clips… | the whole window |
| Clip resize by dragging its edges | timeline | numeric resize exists in Clip Properties |
| Drag-to-select a range → add clip in range | timeline | |
| Timeline navigator position control | navigator | zoom is implemented, scrub position is not |
| Piano roll: per-note automation, NRPN picker | piano roll | deliberately deferred |
