package com.skypie0102.githubbckp.backup

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFailsWith
import org.junit.Test

class GitLfsDownloadServiceTest {
    private val service = GitLfsDownloadService()

    @Test
    fun parsesBasicDownloadActionAndHeaders() {
        val pointer = GitLfsPointer(oidSha256 = "b".repeat(64), sizeBytes = 4L)
        val response = """
            {
              "transfer": "basic",
              "hash_algo": "sha256",
              "objects": [
                {
                  "oid": "${pointer.oidSha256}",
                  "size": 4,
                  "actions": {
                    "download": {
                      "href": "https://objects.example.test/object",
                      "header": {"Authorization": "RemoteAuth abc"}
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = service.parseBatchResponse(response, listOf(pointer))

        assertEquals(
            GitLfsDownloadAction(
                href = "https://objects.example.test/object",
                headers = mapOf("Authorization" to "RemoteAuth abc"),
            ),
            parsed[pointer.oidSha256],
        )
    }

    @Test
    fun rejectsPerObjectBatchError() {
        val pointer = GitLfsPointer(oidSha256 = "c".repeat(64), sizeBytes = 7L)
        val response = """
            {
              "objects": [
                {
                  "oid": "${pointer.oidSha256}",
                  "size": 7,
                  "error": {"code": 404, "message": "Object does not exist"}
                }
              ]
            }
        """.trimIndent()

        assertFailsWith<IOException> {
            service.parseBatchResponse(response, listOf(pointer))
        }
    }

    @Test
    fun verifiesSizeAndSha256() {
        val root = Files.createTempDirectory("lfs-verify").toFile()
        try {
            val bytes = "large-file-content".toByteArray()
            val file = File(root, "object").apply { writeBytes(bytes) }
            val pointer = GitLfsPointer(
                oidSha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
                sizeBytes = bytes.size.toLong(),
            )

            service.verifyObject(file, pointer)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsCorruptObject() {
        val root = Files.createTempDirectory("lfs-corrupt").toFile()
        try {
            val file = File(root, "object").apply { writeText("wrong bytes") }
            val pointer = GitLfsPointer(
                oidSha256 = "d".repeat(64),
                sizeBytes = file.length(),
            )

            assertFailsWith<IOException> { service.verifyObject(file, pointer) }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
