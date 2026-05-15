package dev.atsushieno.uapmd_kmp

data class PreparedProjectLoad(
    val projectPath: String? = null,
    val tempDirectory: String? = null,
    val error: String? = null
)

expect object PlatformProjectArchiveLoader {
    fun prepareProjectLoad(filePath: String): PreparedProjectLoad
    fun cleanupPreparedProject(tempDirectory: String)
}
