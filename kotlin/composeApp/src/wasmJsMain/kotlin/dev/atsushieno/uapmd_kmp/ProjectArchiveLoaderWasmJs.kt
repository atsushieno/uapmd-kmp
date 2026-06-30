package dev.atsushieno.uapmd_kmp

import dev.atsushieno.uapmd.extractProjectArchive
import dev.atsushieno.uapmd.removeExtractedArchive
import kotlin.random.Random

actual object PlatformProjectArchiveLoader {
    actual fun prepareProjectLoad(filePath: String): PreparedProjectLoad {
        if (!filePath.endsWith(".uapmdz"))
            return PreparedProjectLoad(projectPath = filePath)

        val tempDir = "/tmp/uapmd_proj_${Random.nextInt(0, 1_000_000)}"
        val projectFile = extractProjectArchive(filePath, tempDir)
            ?: return PreparedProjectLoad(error = "Failed to extract project archive: $filePath")

        return PreparedProjectLoad(projectPath = projectFile, tempDirectory = tempDir)
    }

    actual fun cleanupPreparedProject(tempDirectory: String) {
        removeExtractedArchive(tempDirectory)
    }
}
