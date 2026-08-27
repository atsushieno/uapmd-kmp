package dev.atsushieno.uapmd.cmp

/**
 * Minimal platform file chooser. Only what the Project menu needs for now;
 * import/export grow their own filters later.
 *
 * Returns null when the user cancels, or when the platform has no picker yet.
 */
expect suspend fun pickProjectFileToOpen(): String?
expect suspend fun pickProjectFileToSave(): String?
