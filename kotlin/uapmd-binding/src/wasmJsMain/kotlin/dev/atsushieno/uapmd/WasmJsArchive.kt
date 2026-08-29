package dev.atsushieno.uapmd

// FS.mkdir throws when the directory already exists, and a throw inside a @JsFun
// reaches Kotlin as an opaque `kotlin.js.JsException`. Existing is not an error
// here — the caller only needs the directory to be there.
@JsFun("""(mod, path) => {
    try { mod.FS.mkdir(path); }
    catch (e) { if (!(e && e.errno === 20)) { try { mod.FS.stat(path); } catch (_) { throw e; } } }
}""")
private external fun emscriptenFsMkdir(mod: UapmdCApiModule, path: String)

// Recursively removes a directory tree from Emscripten MEMFS.
@JsFun("""(mod, path) => {
    function rm(p) {
        try {
            var s = mod.FS.stat(p);
            if (mod.FS.isDir(s.mode)) {
                mod.FS.readdir(p).forEach(function(n) {
                    if (n !== '.' && n !== '..') rm(p + '/' + n);
                });
                mod.FS.rmdir(p);
            } else {
                mod.FS.unlink(p);
            }
        } catch(e) {}
    }
    rm(path);
}""")
private external fun emscriptenFsRmRecursive(mod: UapmdCApiModule, path: String)

// uapmd_project_archive_extract_result_t layout (WASM32):
//  +0  bool    success  (1 byte + 3 pad)
//  +4  char*   error
//  +8  char*   project_file

/**
 * Extracts a .uapmdz archive into [destDir] (which must not already exist).
 * Returns the path of the extracted .uapmd project file, or null on failure.
 * Call [removeExtractedArchive] when the project has been loaded.
 */
fun extractProjectArchive(archivePath: String, destDir: String): String? {
    val mod = wasmMod
    emscriptenFsMkdir(mod, destDir)
    return withTwoCStringsKt(archivePath, destDir) { archPtr, destPtr ->
        val resultPtr = mod.uapmdProjectArchiveExtract(archPtr, destPtr)
        if (resultPtr == 0) null
        else try {
            val success = mod.getValue(resultPtr + 0, "i8").toInt() != 0
            if (!success) null
            else {
                val projPtr = mod.getValue(resultPtr + 8, "i32").toInt()
                if (projPtr != 0) mod.utf8ToString(projPtr) else null
            }
        } finally {
            mod.uapmdProjectArchiveExtractResultFree(resultPtr)
        }
    }
}

fun removeExtractedArchive(destDir: String) {
    emscriptenFsRmRecursive(wasmMod, destDir)
}

/** True when [path] is a packed project (.uapmdz) rather than a bare .uapmd. */
fun isProjectArchive(path: String): Boolean =
    withCStringKt(path) { p -> wasmMod.uapmdProjectArchiveIsArchive(p) }

/**
 * Hands a file in the Emscripten filesystem to the user as a browser download.
 *
 * A browser cannot write to a chosen directory, so this is what "save" means on the
 * web: the engine writes into MEMFS as it would to disk, and the bytes are then
 * offered to the user under the name they picked.
 */
@JsFun("""(mod, path, name) => {
    const data = mod.FS.readFile(path);
    const blob = new Blob([data], { type: 'application/octet-stream' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    // Revoked on a later turn: revoking synchronously can cancel the download.
    setTimeout(() => { URL.revokeObjectURL(url); a.remove(); }, 10000);
}""")
private external fun emscriptenDownloadFile(mod: UapmdCApiModule, path: String, name: String)

/** True when [path] exists in the Emscripten filesystem. */
@JsFun("(mod, path) => { try { mod.FS.stat(path); return true; } catch (e) { return false; } }")
private external fun emscriptenFsExists(mod: UapmdCApiModule, path: String): Boolean

/**
 * Offers a file written into MEMFS to the user. Returns false when nothing was
 * written there — a render that failed, say — so the caller can say so rather than
 * trigger an empty download.
 */
fun downloadFileFromMemfs(path: String): Boolean {
    val mod = wasmMod
    if (!emscriptenFsExists(mod, path)) return false
    val name = path.substringAfterLast('/').ifEmpty { "download" }
    emscriptenDownloadFile(mod, path, name)
    return true
}

/** A private MEMFS directory to write a save into before delivering it. */
private var nextSaveId = 1

fun createSaveScratchPath(defaultName: String): String {
    val dir = "/tmp/uapmd_save_${nextSaveId++}"
    emscriptenFsMkdir(wasmMod, dir)
    return "$dir/${defaultName.substringAfterLast('/').ifEmpty { "untitled" }}"
}
