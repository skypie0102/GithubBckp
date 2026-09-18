package com.skypie0102.githubbckp.release

import org.junit.Assert.assertEquals
import org.junit.Test

class LatestReleaseManifestTest {
    @Test
    fun manifestRoundTripPreservesReleaseAndAssetMetadata() {
        val original = LatestReleaseManifest(
            repositoryId = 42L,
            repositoryOwner = "owner",
            repositoryName = "repo",
            releaseId = 100L,
            tagName = "v1.2.3",
            releaseName = "Release",
            releaseBody = "Notes",
            htmlUrl = "https://github.com/owner/repo/releases/tag/v1.2.3",
            publishedAt = "2026-09-18T10:00:00Z",
            updatedAt = "2026-09-18T11:00:00Z",
            sourceFileName = "source/source.tar.gz",
            sourceSizeBytes = 123L,
            sourceSha256 = "abc",
            assets = listOf(
                LatestReleaseAssetManifest(
                    id = 1L,
                    originalName = "app.apk",
                    storedName = "assets/app.apk",
                    sizeBytes = 456L,
                    sha256 = "def",
                ),
            ),
            appVersion = "test",
        )

        assertEquals(original, LatestReleaseManifest.fromJson(original.toJson()))
    }
}
