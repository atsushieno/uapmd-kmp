package dev.atsushieno.uapmd.cmp

// The browser has no local filesystem; plug-in state would go through the
// document provider instead.
actual fun writeBytesToFile(path: String, bytes: ByteArray): Unit =
    throw UnsupportedOperationException("File access is not available in the browser build.")
actual fun readBytesFromFile(path: String): ByteArray? = null
