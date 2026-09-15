package com.skypie0102.githubbckp.backup

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GithubReleaseBackupServiceTest {
    private val service = GithubReleaseBackupService()

    @Test
    fun validatesBundledReleaseAsset() {
        val root = Files.createTempDirectory("release-backup-test").toFile()
        try {
            val releases = File(root, GithubReleaseBackupService.BUNDLED_RELEASES_DIRECTORY)
            val bytes = "release asset bytes".toByteArray()
            val relativePath = "assets/release-1/10-example.zip"
            File(releases, relativePath).apply {
                parentFile?.mkdirs()
                writeBytes(bytes)
            }
            writeManifest(releases, relativePath, bytes)

            val result = service.validateBundledReleases(root)
            assertEquals(1, result?.releaseCount)
            assertEquals(1, result?.assetCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsCorruptBundledReleaseAsset() {
        val root = Files.createTempDirectory("release-corrupt-test").toFile()
        try {
            val releases = File(root, GithubReleaseBackupService.BUNDLED_RELEASES_DIRECTORY)
            val original = "release asset bytes".toByteArray()
            val relativePath = "assets/release-1/10-example.zip"
            val asset = File(releases, relativePath).apply {
                parentFile?.mkdirs()
                writeBytes(original)
            }
            writeManifest(releases, relativePath, original)
            asset.writeBytes("corrupt asset byte".toByteArray())

            assertThrows(IOException::class.java) {
                service.validateBundledReleases(root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsReleaseAssetPathTraversal() {
        val root = Files.createTempDirectory("release-path-test").toFile()
        try {
            val releases = File(root, GithubReleaseBackupService.BUNDLED_RELEASES_DIRECTORY).apply { mkdirs() }
            val manifest = JSONObject()
                .put("formatVersion", 1)
                .put(
                    "releases",
                    JSONArray().put(
                        JSONObject()
                            .put("tagName", "v1")
                            .put(
                                "assets",
                                JSONArray().put(
                                    JSONObject()
                                        .put("name", "escape.bin")
                                        .put("size", 1)
                                        .put("sha256", "0".repeat(64))
                                        .put("relativePath", "../../escape.bin"),
                                ),
                            ),
                    ),
                )
            File(releases, GithubReleaseBackupService.MANIFEST_FILE_NAME).writeText(manifest.toString())

            assertThrows(IOException::class.java) {
                service.validateBundledReleases(root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeManifest(releases: File, relativePath: String, bytes: ByteArray) {
        releases.mkdirs()
        val manifest = JSONObject()
            .put("formatVersion", 1)
            .put(
                "releases",
                JSONArray().put(
                    JSONObject()
                        .put("sourceId", 1)
                        .put("tagName", "v1")
                        .put("assets", JSONArray().put(
                            JSONObject()
                                .put("sourceId", 10)
                                .put("name", "example.zip")
                                .put("size", bytes.size)
                                .put("sha256", sha256(bytes))
                                .put("relativePath", relativePath),
                        )),
                ),
            )
        File(releases, GithubReleaseBackupService.MANIFEST_FILE_NAME).writeText(manifest.toString())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
