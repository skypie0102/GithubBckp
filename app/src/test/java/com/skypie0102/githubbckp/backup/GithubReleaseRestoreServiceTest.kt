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

class GithubReleaseRestoreServiceTest {
    private val service = GithubReleaseRestoreService(GithubReleaseBackupService())

    @Test
    fun readsValidatedBundledReleaseManifest() {
        val root = Files.createTempDirectory("release-restore-manifest-test").toFile()
        try {
            val releases = File(root, GithubReleaseBackupService.BUNDLED_RELEASES_DIRECTORY).apply { mkdirs() }
            val bytes = "release asset".toByteArray()
            val relativePath = "assets/release-1/10-example.zip"
            File(releases, relativePath).apply {
                parentFile?.mkdirs()
                writeBytes(bytes)
            }
            writeManifest(releases, relativePath, bytes)

            val parsed = service.readBundledReleases(releases)
            assertEquals(1, parsed.size)
            assertEquals("v1", parsed.single().tagName)
            assertEquals("Release one", parsed.single().name)
            assertEquals(1, parsed.single().assets.size)
            assertEquals("example.zip", parsed.single().assets.single().name)
            assertEquals(sha256(bytes), parsed.single().assets.single().sha256)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reusesGithubRenamedAssetBySha256() {
        val bytes = "same bytes".toByteArray()
        val expected = bundledAsset(
            name = ".example file.zip",
            bytes = bytes,
        )
        val remote = RemoteReleaseAsset(
            id = 99L,
            name = "example-file.zip",
            label = null,
            state = "uploaded",
            size = bytes.size.toLong(),
            digest = "sha256:${sha256(bytes)}",
        )

        val decision = service.decideExistingAsset(expected, listOf(remote), emptySet())
        assertEquals(ExistingAssetAction.REUSE, decision.action)
        assertEquals(99L, decision.assetId)
    }

    @Test
    fun deletesStarterAssetBeforeRetryingUpload() {
        val bytes = "retry bytes".toByteArray()
        val expected = bundledAsset("example.zip", bytes)
        val starter = RemoteReleaseAsset(
            id = 7L,
            name = "example.zip",
            label = null,
            state = "starter",
            size = 0L,
            digest = null,
        )

        val decision = service.decideExistingAsset(expected, listOf(starter), emptySet())
        assertEquals(ExistingAssetAction.DELETE_STARTER_AND_UPLOAD, decision.action)
        assertEquals(7L, decision.assetId)
    }

    @Test
    fun rejectsConflictingUploadedAssetWithSameName() {
        val expectedBytes = "expected bytes".toByteArray()
        val otherBytes = "different data".toByteArray()
        val expected = bundledAsset("example.zip", expectedBytes)
        val conflict = RemoteReleaseAsset(
            id = 8L,
            name = "example.zip",
            label = null,
            state = "uploaded",
            size = otherBytes.size.toLong(),
            digest = "sha256:${sha256(otherBytes)}",
        )

        assertThrows(IOException::class.java) {
            service.decideExistingAsset(expected, listOf(conflict), emptySet())
        }
    }

    @Test
    fun refusesAmbiguousDigestResume() {
        val bytes = "duplicate binary".toByteArray()
        val expected = bundledAsset("source name.zip", bytes)
        val digest = "sha256:${sha256(bytes)}"
        val remote = listOf(
            RemoteReleaseAsset(1L, "renamed-one.zip", null, "uploaded", bytes.size.toLong(), digest),
            RemoteReleaseAsset(2L, "renamed-two.zip", null, "uploaded", bytes.size.toLong(), digest),
        )

        assertThrows(IOException::class.java) {
            service.decideExistingAsset(expected, remote, emptySet())
        }
    }

    private fun bundledAsset(name: String, bytes: ByteArray): BundledGithubReleaseAsset =
        BundledGithubReleaseAsset(
            name = name,
            label = null,
            contentType = "application/zip",
            size = bytes.size.toLong(),
            sha256 = sha256(bytes),
            relativePath = "assets/release-1/10-example.zip",
        )

    private fun writeManifest(releases: File, relativePath: String, bytes: ByteArray) {
        val manifest = JSONObject()
            .put("formatVersion", 1)
            .put(
                "releases",
                JSONArray().put(
                    JSONObject()
                        .put("sourceId", 1)
                        .put("tagName", "v1")
                        .put("targetCommitish", "main")
                        .put("name", "Release one")
                        .put("body", "Notes")
                        .put("draft", false)
                        .put("prerelease", false)
                        .put("immutable", false)
                        .put(
                            "assets",
                            JSONArray().put(
                                JSONObject()
                                    .put("sourceId", 10)
                                    .put("name", "example.zip")
                                    .put("contentType", "application/zip")
                                    .put("size", bytes.size)
                                    .put("sha256", sha256(bytes))
                                    .put("relativePath", relativePath),
                            ),
                        ),
                ),
            )
        File(releases, GithubReleaseBackupService.MANIFEST_FILE_NAME).writeText(manifest.toString())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
