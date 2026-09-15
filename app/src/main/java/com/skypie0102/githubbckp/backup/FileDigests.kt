package com.skypie0102.githubbckp.backup

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

internal data class FileDigestResult(
    val sha256: String,
    val md5: String,
    val sizeBytes: Long,
)

internal fun calculateDigests(file: File): FileDigestResult =
    FileInputStream(file).use(::calculateDigests)

internal fun calculateDigests(input: InputStream): FileDigestResult {
    val sha256 = MessageDigest.getInstance("SHA-256")
    val md5 = MessageDigest.getInstance("MD5")
    var sizeBytes = 0L
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        sha256.update(buffer, 0, count)
        md5.update(buffer, 0, count)
        sizeBytes += count
    }
    return FileDigestResult(
        sha256 = sha256.digest().toHex(),
        md5 = md5.digest().toHex(),
        sizeBytes = sizeBytes,
    )
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}
