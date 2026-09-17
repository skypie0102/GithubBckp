package com.skypie0102.githubbckp.backup

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitLfsDownloadServiceTest {
    private val objectStore = GitLfsObjectStore()
    private val service = GitLfsDownloadService(objectStore)

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

        assertThrows(IOException::class.java) {
            service.parseBatchResponse(response, listOf(pointer))
        }
    }

    @Test
    fun representativeVerificationSampleIsDistinctAndBounded() {
        val first = GitLfsPointer(oidSha256 = "a".repeat(64), sizeBytes = 1L)
        val second = GitLfsPointer(oidSha256 = "b".repeat(64), sizeBytes = 2L)
        val third = GitLfsPointer(oidSha256 = "c".repeat(64), sizeBytes = 3L)
        val fourth = GitLfsPointer(oidSha256 = "d".repeat(64), sizeBytes = 4L)

        assertEquals(
            listOf(first, second, third),
            representativeLfsPointers(listOf(first, first, second, third, fourth), limit = 3),
        )
        assertEquals(emptyList<GitLfsPointer>(), representativeLfsPointers(listOf(first), limit = 0))
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

            objectStore.verify(file, pointer)
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

            assertThrows(IOException::class.java) { objectStore.verify(file, pointer) }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
