package dev.atsushieno.uapmd.cmp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.atsushieno.uapmd.AppModel
import dev.atsushieno.uapmd.DocumentFilter
import dev.atsushieno.uapmd.DocumentHandle
import dev.atsushieno.uapmd.DocumentProvider
import dev.atsushieno.uapmd.FolderPickResult
import dev.atsushieno.uapmd.Jsfx
import dev.atsushieno.uapmd.JsfxDuplicateListing
import dev.atsushieno.uapmd.JsfxPluginFormat
import dev.atsushieno.uapmd.JsfxRegisteredFolder

/**
 * What uapmd-app's `JsfxResourcesWindow` does, without the drawing: where the
 * user manages the JSFX effects available to them.
 *
 * JSFX is a filesystem format, so this is about locations and files rather than
 * settings: which folders are searched, and getting content into a folder that
 * is. A folder is handed over one of two ways, and the platform decides which:
 * desktop gives a location, which is added to the search paths and read where it
 * sits; Android and iOS give a grant, which is registered and copied in.
 *
 * Every pick and read goes through the model's own document provider, because a
 * registered folder's token means something only to the provider that picked it,
 * and the model re-reads registered folders through that provider.
 *
 * Kept by the host rather than the window, as uapmd-app keeps it in MainWindow,
 * so that a status survives closing and reopening the window.
 */
class JsfxResources internal constructor(
    private val host: UapmdHost,
    private val model: AppModel,
    private val format: JsfxPluginFormat
) {
    var searchPaths by mutableStateOf<List<String>>(emptyList())
        private set
    var useDefaults by mutableStateOf(true)
        private set
    var duplicateListing by mutableStateOf(JsfxDuplicateListing.PreferLatestVersion)
        private set
    var registeredFolders by mutableStateOf<List<JsfxRegisteredFolder>>(emptyList())
        private set

    /**
     * A name the user invents for a set of effects inside our own storage -- not a
     * path they have to know, so it is typable on every platform.
     */
    var destination by mutableStateOf("")
        private set

    var status by mutableStateOf("")
        private set
    var statusIsError by mutableStateOf(false)
        private set
    var pickInFlight by mutableStateOf(false)
        private set

    /**
     * Set while the catalog is being rebuilt, which is the slow part: a registered
     * folder is re-read and re-copied before anything is scanned.
     */
    var busy by mutableStateOf(false)
        private set

    val provider: DocumentProvider? get() = host.documentProvider
    val defaultSearchPaths: List<String> get() = format.defaultSearchPaths
    val contentDirectory: String get() = Jsfx.userContentDirectory

    /** The same 128-byte buffer uapmd-app's ImGui field has, less its terminator. */
    fun updateDestination(value: String) {
        destination = value.take(127)
    }

    /** Called when the window is shown, as uapmd-app reloads on `show()`. */
    fun loadFromFormat() {
        registeredFolders = Jsfx.registeredFolders
        searchPaths = format.searchPaths
        useDefaults = format.useDefaultSearchPaths
        duplicateListing = format.duplicateListing
    }

    private fun report(message: String, isError: Boolean) {
        status = message
        statusIsError = isError
    }

    /** How many JSFX effects the plugin list holds, the only number that says whether any of this worked. */
    private fun jsfxEffectCount(): Int = host.catalog.count { it.format == "JSFX" }

    /**
     * Effects only reach the plugin list through a scan, so everything here ends
     * with one. Removing a folder has to drop its effects as much as adding one has
     * to find them, which is why this is a refresh rather than a merge.
     *
     * The message the caller left on screen says something is happening, so this
     * replaces it when it stops -- otherwise it says so for ever.
     */
    private fun refreshCatalog() {
        busy = true
        model.refreshFastScannedPlugins { syncError ->
            host.onUiThread {
                busy = false
                registeredFolders = Jsfx.registeredFolders
                host.refreshCatalog()
                val available = "${jsfxEffectCount()} JSFX effects available."
                if (syncError.isNullOrEmpty())
                    report(available, false)
                else
                    // Still says how many there are: some folders may have been read
                    // even though one of them could not be.
                    report("$syncError -- $available", true)
            }
        }
    }

    private fun registerPickedFolder(picked: FolderPickResult) {
        val name = Jsfx.registerFolder(picked.token, picked.displayName)
        registeredFolders = Jsfx.registeredFolders
        if (name.isEmpty()) {
            pickInFlight = false
            report("That folder could not be registered.", true)
            return
        }
        report("Registered $name. Reading it...", false)
        syncRegisteredFolders()
    }

    fun syncRegisteredFolders() {
        // Refreshing the catalog re-reads the registered folders on its own, so this
        // asks for that rather than reading them and then asking, which would walk
        // every folder twice.
        pickInFlight = false
        report("Reading the registered folders...", false)
        refreshCatalog()
    }

    private fun applyToFormat() {
        format.setSearchPaths(searchPaths, useDefaults)
        model.pluginScanTool.saveSearchPathSettings()
        refreshCatalog()
    }

    private fun finishImport(filesWritten: UInt, failures: List<String>) {
        pickInFlight = false
        if (failures.isEmpty())
            report("Copied in $filesWritten file(s). Looking for new effects...", false)
        else {
            var message = failures.first()
            if (failures.size > 1)
                message += " (and ${failures.size - 1} more)"
            report(message, true)
        }
        if (filesWritten > 0u)
            refreshCatalog()
    }

    fun beginFileImport() {
        val provider = provider
        if (provider == null) {
            report("There is no file picker on this platform.", true)
            return
        }

        val filters = listOf(
            DocumentFilter(
                "JSFX effects and archives",
                listOf("application/zip", "text/plain", "application/octet-stream"),
                listOf("*.jsfx", "*.jsfx-inc", "*.zip")
            ),
            DocumentFilter("All files", listOf("*/*"), listOf("*"))
        )

        // Read now rather than in the callback: picking is asynchronous and the
        // field is free to change while the picker is up.
        val destination = destination

        pickInFlight = true
        report("Choosing files...", false)

        provider.pickOpenDocuments(filters, true) { picked ->
            host.onUiThread {
                when {
                    !picked.success -> {
                        pickInFlight = false
                        report("Could not open the file picker: ${picked.error ?: ""}", true)
                    }
                    picked.handles.isEmpty() -> {
                        pickInFlight = false
                        report("Nothing was chosen.", false)
                    }
                    else -> importDocuments(picked.handles, destination)
                }
            }
        }
    }

    fun beginFolderPick() {
        val provider = provider
        if (provider == null) {
            report("There is no folder picker on this platform.", true)
            return
        }

        val destination = destination

        pickInFlight = true
        report("Choosing a folder...", false)

        provider.pickFolder { picked ->
            // A folder that comes as bare documents (a browser's) was staged for the duration
            // of this very callback: the provider deletes the copies as soon as it returns,
            // exactly as uapmd-app copies them inside it. Deferring to the UI thread first
            // reads nothing -- every document is already gone -- so the reads are issued here.
            // Only the outcome, which touches UI state, is handled later.
            if (picked.success && picked.path.isEmpty() && picked.token.isEmpty() && picked.documents.isNotEmpty()) {
                host.onUiThread {
                    report("This platform cannot keep a folder; copying its effects in.", false)
                }
                importDocuments(picked.documents, destination.ifEmpty { picked.displayName })
            } else
                host.onUiThread { folderPicked(picked, destination) }
        }
    }

    private fun folderPicked(picked: FolderPickResult, destination: String) {
        if (!picked.success) {
            pickInFlight = false
            report("Could not open the folder picker: ${picked.error ?: ""}", true)
            return
        }
        if (picked.path.isEmpty() && picked.documents.isEmpty()) {
            pickInFlight = false
            report("Nothing was chosen.", false)
            return
        }

        // A folder that is a location goes straight into the search paths and is
        // read where it sits; nothing is copied and nothing goes stale.
        if (picked.path.isNotEmpty()) {
            pickInFlight = false
            val chosen = picked.path
            if (chosen in searchPaths) {
                report("That folder is already being looked in.", true)
                return
            }
            searchPaths = searchPaths + chosen
            applyToFormat()
            report("Added $chosen. Looking again for effects...", false)
            return
        }

        // A folder that is a grant is registered instead: the grant is kept, and the
        // folder is re-read whenever effects are looked for again. Files the user
        // adds to it later therefore show up, which is the point.
        if (picked.token.isNotEmpty()) {
            registerPickedFolder(picked)
            return
        }

        // A platform that gives neither a path nor a grant -- a browser -- is handled in
        // [beginFolderPick], while the documents still exist.
    }

    private fun importDocuments(documents: List<DocumentHandle>, subdirectory: String) {
        val provider = provider
        if (provider == null) {
            pickInFlight = false
            report("There is nothing to read documents with on this platform.", true)
            return
        }

        // Each document is read on its own, so the outcome is reported by whichever
        // read finishes last rather than by any particular one.
        var remaining = documents.size
        var written = 0u
        val failures = mutableListOf<String>()

        for (handle in documents) {
            // The display name is all a picked document reliably carries, and for one
            // that came out of a folder it is the path it had inside that folder.
            val relative = handle.displayName.ifEmpty { handle.id }
            val leaf = relative.substringAfterLast('/')
            val parent = relative.substringBeforeLast('/', "")
            val destination = when {
                parent.isEmpty() -> subdirectory
                subdirectory.isEmpty() -> parent
                else -> "$subdirectory/$parent"
            }

            provider.readDocument(handle) { io, data ->
                host.onUiThread {
                    if (!io.success)
                        failures += "$leaf: ${io.error ?: ""}"
                    else {
                        val result = Jsfx.importContent(leaf, data, destination)
                        if (result.success)
                            written += result.filesWritten
                        else
                            failures += "$leaf: ${result.error}"
                    }

                    if (--remaining > 0)
                        return@onUiThread
                    finishImport(written, failures)
                }
            }
        }
    }

    fun removeRegisteredFolder(token: String) {
        // The grant goes back as well as the record of it: forgetting the token alone
        // would leave the platform holding a permission nothing uses.
        provider?.releaseFolder(token)
        Jsfx.unregisterFolder(token)
        registeredFolders = Jsfx.registeredFolders
        refreshCatalog()
        report("Removed a registered folder. Looking again for effects...", false)
    }

    fun changeUseDefaults(value: Boolean) {
        useDefaults = value
        applyToFormat()
    }

    fun removeSearchPath(index: Int) {
        searchPaths = searchPaths.filterIndexed { i, _ -> i != index }
        applyToFormat()
        report("Removed a folder. Looking again for effects...", false)
    }

    fun changeDuplicateListing(value: JsfxDuplicateListing) {
        duplicateListing = value
        format.duplicateListing = value
        // Only what the plugin list presents changes, so it is re-read, not rescanned.
        host.refreshCatalog()
    }
}
