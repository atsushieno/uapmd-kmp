package dev.atsushieno.uapmd.cmp

import java.io.File

actual fun writeBytesToFile(path: String, bytes: ByteArray) = File(path).writeBytes(bytes)
actual fun readBytesFromFile(path: String): ByteArray? =
    File(path).takeIf { it.isFile }?.readBytes()
