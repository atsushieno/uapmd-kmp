package dev.atsushieno.uapmd.cmp

/** Minimal file IO for plug-in state blobs; no filesystem on web. */
expect fun writeBytesToFile(path: String, bytes: ByteArray)
expect fun readBytesFromFile(path: String): ByteArray?
