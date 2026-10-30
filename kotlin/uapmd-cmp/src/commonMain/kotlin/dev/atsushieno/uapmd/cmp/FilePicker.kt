package dev.atsushieno.uapmd.cmp

/*
 * Platform pieces that are not document picking; that goes through AppModel's
 * document provider on every platform (DocumentPicking.kt).
 */

/**
 * Where a split audio import writes its stems.
 *
 * uapmd-app lets the user type a directory; every platform here either has no
 * directory chooser (the browser, Android's SAF) or no meaningful place for the
 * user to put them, so the stems go beside the source audio where there is a
 * real filesystem, and into the app's own writable area where there is not.
 */
expect fun defaultStemOutputDirectory(audioFilePath: String): String

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

/** Dev hook: show the restored plug-in whose display name contains this value. */
expect fun startupShowLoadedUi(): String?

/** Dev hook: instantiate this named plug-in before loading a project. */
expect fun startupPreloadPlugin(): String?

/** Dev hook: open the preloaded plug-in UI before project replacement. */
expect fun startupShowPreloadUi(): Boolean

/** Dev hook: save the session to this path once startup work is done. */
expect fun startupSaveProjectPath(): String?

/**
 * Every thread's stack, for the load watchdog. A hung load shows up as a blocked
 * main thread and this is the only way to see what it is blocked in without root.
 */
expect fun dumpThreadStacks(): String

/**
 * Back-to-quit handling, where the platform has a back gesture at all.
 *
 * uapmd-app cannot do this (ImGui owns the frame loop), but AAP's own Compose
 * host does, in `androidaudioplugin-ui-compose-app`'s `GenericPluginManagerMain`.
 */
@androidx.compose.runtime.Composable
expect fun PlatformQuitBackHandler()
