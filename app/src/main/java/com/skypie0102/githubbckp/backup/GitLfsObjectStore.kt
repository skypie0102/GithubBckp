package com.skypie0102.githubbckp.backup

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitLfsObjectStore @Inject constructor() {
    fun objectFile(repositoryDirectory: File, oidSha256: String): File {
        require(oidSha256.length == 64) { "Invalid Git LFS SHA-256 OID" }
        return File(
            repositoryDirectory,
            "lfs/objects/${oidSha256.substring(0, 2)}/${oidSha256.substring(2, 4)}/$oidSha256",
        )
    }

    fun requireVerifiedObject(repositoryDirectory: File, pointer: GitLfsPointer): File {
        val file = objectFile(repositoryDirectory, pointer.oidSha256)
        if (!file.isFile) {
            throw IOException("Git LFS object ${pointer.oidSha256} is missing from this mirror backup")
        }
        verify(file, pointer)
        return file
    }

    fun verify(file: File, pointer: GitLfsPointer) {
        if (file.length() != pointer.sizeBytes) {
            throw IOException(
                "Git LFS object ${pointer.oidSha256} size mismatch: expected ${pointer.sizeBytes}, got ${file.length()}",
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().toHex()
        if (!actual.equals(pointer.oidSha256, ignoreCase = true)) {
            throw IOException("Git LFS object ${pointer.oidSha256} failed SHA-256 verification")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val BUFFER_SIZE = 128 * 1024
    }
}
