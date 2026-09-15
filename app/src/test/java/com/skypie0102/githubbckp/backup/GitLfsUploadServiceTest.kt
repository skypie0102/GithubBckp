package com.skypie0102.githubbckp.backup

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GitLfsUploadServiceTest {
    private val objectStore = GitLfsObjectStore()
    private val service = GitLfsUploadService(objectStore)

    @Test
    fun parsesUploadAndVerifyActions() {
        val pointer = GitLfsPointer(oidSha256 = "a".repeat(64), sizeBytes = 12L)
        val response = """
            {
              "transfer": "basic",
              "hash_algo": "sha256",
              "objects": [
                {
                  "oid": "${pointer.oidSha256}",
                  "size": 12,
                  "actions": {
                    "upload": {
                      "href": "https://objects.example.test/upload",
                      "header": {"Authorization": "RemoteAuth upload"}
                    },
                    "verify": {
                      "href": "https://objects.example.test/verify",
                      "header": {"Authorization": "RemoteAuth verify"}
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val plan = service.parseBatchResponse(response, listOf(pointer)).getValue(pointer.oidSha256)

        assertEquals(
            GitLfsAction(
                href = "https://objects.example.test/upload",
                headers = mapOf("Authorization" to "RemoteAuth upload"),
            ),
            plan.upload,
        )
        assertEquals(
            GitLfsAction(
                href = "https://objects.example.test/verify",
                headers = mapOf("Authorization" to "RemoteAuth verify"),
            ),
            plan.verify,
        )
    }

    @Test
    fun treatsMissingActionsAsAlreadyPresent() {
        val pointer = GitLfsPointer(oidSha256 = "b".repeat(64), sizeBytes = 9L)
        val response = """
            {
              "objects": [
                {
                  "oid": "${pointer.oidSha256}",
                  "size": 9
                }
              ]
            }
        """.trimIndent()

        val plan = service.parseBatchResponse(response, listOf(pointer)).getValue(pointer.oidSha256)

        assertNull(plan.upload)
        assertNull(plan.verify)
    }

    @Test
    fun rejectsPerObjectUploadError() {
        val pointer = GitLfsPointer(oidSha256 = "c".repeat(64), sizeBytes = 5L)
        val response = """
            {
              "objects": [
                {
                  "oid": "${pointer.oidSha256}",
                  "size": 5,
                  "error": {"code": 403, "message": "Forbidden"}
                }
              ]
            }
        """.trimIndent()

        assertThrows(IOException::class.java) {
            service.parseBatchResponse(response, listOf(pointer))
        }
    }

    @Test
    fun missingBundledObjectFailsBeforeUpload() {
        val root = Files.createTempDirectory("lfs-upload-missing").toFile()
        try {
            val pointer = GitLfsPointer(oidSha256 = "d".repeat(64), sizeBytes = 42L)

            assertThrows(IOException::class.java) {
                objectStore.requireVerifiedObject(root, pointer)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
