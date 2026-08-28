package dev.atsushieno.uapmd.cmp

/**
 * Minimal platform file chooser. Only what the Project menu needs for now;
 * import/export grow their own filters later.
 *
 * Returns null when the user cancels, or when the platform has no picker yet.
 */
expect suspend fun pickProjectFileToOpen(): String?
expect suspend fun pickProjectFileToSave(): String?
expect suspend fun pickMediaFileToOpen(): String?

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
