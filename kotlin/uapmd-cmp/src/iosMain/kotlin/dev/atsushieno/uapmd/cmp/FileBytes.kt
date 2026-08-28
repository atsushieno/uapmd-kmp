package dev.atsushieno.uapmd.cmp

// Left unimplemented until the iOS document picker lands; the callers report
// the failure rather than pretending the write happened.
actual fun writeBytesToFile(path: String, bytes: ByteArray): Unit =
    throw UnsupportedOperationException("File access is not wired on iOS yet.")
actual fun readBytesFromFile(path: String): ByteArray? = null
