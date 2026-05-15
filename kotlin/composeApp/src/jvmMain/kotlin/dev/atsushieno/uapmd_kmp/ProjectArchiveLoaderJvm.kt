package dev.atsushieno.uapmd_kmp

import com.sun.jna.Pointer
import dev.atsushieno.uapmd.jna.UapmdLibrary

actual object PlatformProjectArchiveLoader {
    private val library = UapmdLibrary.INSTANCE

    actual fun prepareProjectLoad(filePath: String): PreparedProjectLoad {
        val prepared = library.uapmd_prepare_project_load(filePath)
            ?: return PreparedProjectLoad(error = "Failed to prepare project file.")
        val success = library.uapmd_prepared_project_success(prepared)
        return PreparedProjectLoad(
            projectPath = if (success) readCString { buffer, size ->
                library.uapmd_prepared_project_path(prepared, buffer, size)
            } else null,
            tempDirectory = Pointer.nativeValue(prepared).toString(),
            error = if (success) null else readCString { buffer, size ->
                library.uapmd_prepared_project_error(prepared, buffer, size)
            }
        )
    }

    actual fun cleanupPreparedProject(tempDirectory: String) {
        library.uapmd_prepared_project_destroy(Pointer.createConstant(tempDirectory.toLong()))
    }

    private fun readCString(read: (ByteArray?, Long) -> Long): String {
        val size = read(null, 0).toInt()
        if (size <= 1)
            return ""
        val buffer = ByteArray(size)
        read(buffer, size.toLong())
        val nullIndex = buffer.indexOf(0).let { if (it >= 0) it else buffer.size }
        return buffer.copyOf(nullIndex).toString(Charsets.UTF_8)
    }
}
