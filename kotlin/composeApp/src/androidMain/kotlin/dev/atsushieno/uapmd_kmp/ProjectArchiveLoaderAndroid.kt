package dev.atsushieno.uapmd_kmp

import dev.atsushieno.uapmd.JniBridge

actual object PlatformProjectArchiveLoader {
    actual fun prepareProjectLoad(filePath: String): PreparedProjectLoad {
        val prepared = JniBridge.uapmdPrepareProjectLoad(filePath)
        if (prepared == 0L)
            return PreparedProjectLoad(error = "Failed to prepare project file.")
        return if (JniBridge.uapmdPreparedProjectSuccess(prepared))
            PreparedProjectLoad(
                projectPath = JniBridge.uapmdPreparedProjectPath(prepared),
                tempDirectory = prepared.toString()
            )
        else
            PreparedProjectLoad(
                tempDirectory = prepared.toString(),
                error = JniBridge.uapmdPreparedProjectError(prepared)
            )
    }

    actual fun cleanupPreparedProject(tempDirectory: String) {
        tempDirectory.toLongOrNull()?.let { JniBridge.uapmdPreparedProjectDestroy(it) }
    }
}
