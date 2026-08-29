package dev.atsushieno.uapmd.cmp

/**
 * Minimal platform file chooser. Only what the Project menu needs for now;
 * import/export grow their own filters later.
 *
 * Returns null when the user cancels, or when the platform has no picker yet.
 */
expect suspend fun pickProjectFileToOpen(): String?

/**
 * A destination to write to. [defaultName] seeds the name the platform offers,
 * which on the web is the only say the user gets: a browser cannot choose a
 * directory, it can only download a named file.
 */
expect suspend fun pickProjectFileToSave(defaultName: String): String?
/**
 * Separate MIDI and audio choosers, as uapmd-app has them: `addSmfClipToTrack`
 * offers "SMF Files" (`*.mid`, `*.midi`, `*.smf`) and the audio paths offer
 * "Audio Files" (`*.wav`, `*.flac`, `*.ogg`). One combined "media" chooser stood
 * in for both, which left Android's MIDI imports filtering on audio extensions
 * only — an SMF could not be selected at all.
 */
expect suspend fun pickMidiFileToOpen(): String?
expect suspend fun pickAudioFileToOpen(): String?

/**
 * Hands a file the app has just written to the user.
 *
 * On every platform with a real filesystem the picker already chose where the file
 * goes, so this does nothing. The browser has no such destination: a save writes
 * into the in-memory filesystem first and only reaches the user as a download, so
 * the web build needs telling once the bytes are actually there. Call it after any
 * successful write to a path that came from [pickProjectFileToSave].
 */
expect fun deliverSavedFile(path: String)

/**
 * Saves the current project wherever the platform puts files, and reports an error
 * message or null (saved, or the user cancelled).
 *
 * This is a whole operation rather than "pick a path, then write to it" because a
 * saved project is a *directory* — the `.uapmd` document plus its graphs and
 * extensions — and only some platforms can be handed a directory to write into.
 * uapmd-app packs the tree into a `.uapmdz` and writes it through the document
 * provider (`MainWindow::handleSaveProject`), which is the only route that works in
 * a browser, where the file reaches the user as a download.
 */
expect suspend fun saveProjectToPlatform(host: UapmdHost, defaultName: String): String?

/**
 * Optional file to import at startup, for development and screenshots.
 * uapmd-app has the same idea in its web build (`maybeScheduleAutoImport`).
 */
expect fun startupImportPath(): String?

/** Dev hook: instantiate the first catalog entry of this format at startup. */
expect fun startupInstantiateFormat(): String?

/** Dev hook: press "+ Add Track" this many times at startup. */
expect fun startupAddTracks(): Int

/** How many plug-ins the instantiate dev hook should create; defaults to 1. */
expect fun startupInstantiateCount(): Int

/** Dev hook: a project to load at startup, so the load path can be measured. */
expect fun startupLoadProjectPath(): String?

/** Dev hook: force a full plug-in rescan before anything else. */
expect fun startupForceRescan(): Boolean

/** Dev hook: render the loaded project to this WAV path, to inspect real audio. */
expect fun startupRenderPath(): String?

/** Dev hook: reconfigure the audio device to this buffer size at startup. */
expect fun startupBufferSize(): Int

/** Dev hook: stop the UI poll, to isolate its cost from audio behaviour. */
expect fun startupSuppressPolling(): Boolean

/** Dev hook: start playback for N seconds after loading, to measure audio. */
expect fun startupPlaySeconds(): Int

/** Dev hook: how many times to load the project, for re-load testing. */
expect fun startupLoadCount(): Int

/** Dev hook: save the session to this path once startup work is done. */
expect fun startupSaveProjectPath(): String?

/**
 * Every thread's stack, for the load watchdog. A hung load shows up as a blocked
 * main thread and this is the only way to see what it is blocked in without root.
 */
expect fun dumpThreadStacks(): String

/**
 * Drives any pending file-picker work. uapmd's Android document provider
 * completes a pick when it is ticked, so the UI poll loop calls this every
 * frame; the other platforms resolve their pickers directly and do nothing.
 */
expect fun tickPlatformFilePicker()

/**
 * Back-to-quit handling, where the platform has a back gesture at all.
 *
 * uapmd-app cannot do this (ImGui owns the frame loop), but AAP's own Compose
 * host does, in `androidaudioplugin-ui-compose-app`'s `GenericPluginManagerMain`.
 */
@androidx.compose.runtime.Composable
expect fun PlatformQuitBackHandler()
