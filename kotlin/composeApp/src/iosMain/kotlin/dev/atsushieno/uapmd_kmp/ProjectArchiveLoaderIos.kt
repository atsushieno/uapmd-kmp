package dev.atsushieno.uapmd_kmp

actual object PlatformProjectArchiveLoader {
    actual fun prepareProjectLoad(filePath: String): PreparedProjectLoad =
        PreparedProjectLoad(projectPath = filePath)

    actual fun cleanupPreparedProject(tempDirectory: String) {}
}
