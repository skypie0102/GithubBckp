package com.skypie0102.githubbckp.release

import com.skypie0102.githubbckp.mirror.TarGzArchive
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LatestReleaseVerifierTest {
    @Test
    fun verifiesSourceAndAssets() {
        val root = Files.createTempDirectory("latest-release-verify").toFile()
        try {
            val staging = File(root, "staging").apply { mkdirs() }
            val source = File(staging, "source/source.tar.gz").apply {
                parentFile?.mkdirs()
                writeBytes("source".toByteArray())
            }
            val asset = File(staging, "assets/app.apk").apply {
                parentFile?.mkdirs()
                writeBytes("apk".toByteArray())
            }
            LatestReleaseManifest(
                repositoryId = 42L,
                repositoryOwner = "owner",
                repositoryName = "repo",
                releaseId = 100L,
                tagName = "v1",
                releaseName = "Release",
                releaseBody = null,
                htmlUrl = "https://github.com/owner/repo/releases/tag/v1",
                publishedAt = null,
                updatedAt = "updated",
                sourceFileName = "source/source.tar.gz",
                sourceSizeBytes = source.length(),
                sourceSha256 = sha256(source),
                assets = listOf(
                    LatestReleaseAssetManifest(
                        id = 1L,
                        originalName = "app.apk",
                        storedName = "assets/app.apk",
                        sizeBytes = asset.length(),
                        sha256 = sha256(asset),
                    ),
                ),
                appVersion = "test",
            ).writeTo(File(staging, LatestReleaseManifest.FILE_NAME))

            val archive = File(root, "release.tar.gz")
            TarGzArchive.create(staging, archive)
            val verified = LatestReleaseVerifier.verify(
                archive = archive,
                verificationDirectory = File(root, "verify"),
                expectedRepositoryId = 42L,
            )

            assertEquals("v1", verified.manifest.tagName)
            assertEquals(1, verified.manifest.assets.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsTamperedAsset() {
        val root = Files.createTempDirectory("latest-release-tamper").toFile()
        try {
            val staging = File(root, "staging").apply { mkdirs() }
            val source = File(staging, "source/source.tar.gz").apply {
                parentFile?.mkdirs()
                writeBytes("source".toByteArray())
            }
            val asset = File(staging, "assets/app.apk").apply {
                parentFile?.mkdirs()
                writeBytes("tampered".toByteArray())
            }
            LatestReleaseManifest(
                repositoryId = 42L,
                repositoryOwner = "owner",
                repositoryName = "repo",
                releaseId = 100L,
                tagName = "v1",
                releaseName = null,
                releaseBody = null,
                htmlUrl = "https://github.com/owner/repo/releases/tag/v1",
                publishedAt = null,
                updatedAt = "updated",
                sourceFileName = "source/source.tar.gz",
                sourceSizeBytes = source.length(),
                sourceSha256 = sha256(source),
                assets = listOf(
                    LatestReleaseAssetManifest(
                        id = 1L,
                        originalName = "app.apk",
                        storedName = "assets/app.apk",
                        sizeBytes = asset.length(),
                        sha256 = "00",
                    ),
                ),
                appVersion = "test",
            ).writeTo(File(staging, LatestReleaseManifest.FILE_NAME))

            val archive = File(root, "release.tar.gz")
            TarGzArchive.create(staging, archive)

            assertThrows(IllegalStateException::class.java) {
                LatestReleaseVerifier.verify(
                    archive = archive,
                    verificationDirectory = File(root, "verify"),
                    expectedRepositoryId = 42L,
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
