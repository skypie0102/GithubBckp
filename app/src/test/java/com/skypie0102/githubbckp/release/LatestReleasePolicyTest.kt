package com.skypie0102.githubbckp.release

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestReleasePolicyTest {
    private val manifest = LatestReleaseManifest(
        repositoryId = 1L,
        repositoryOwner = "owner",
        repositoryName = "repo",
        releaseId = 10L,
        tagName = "v1",
        releaseName = null,
        releaseBody = null,
        htmlUrl = "https://github.com/owner/repo/releases/tag/v1",
        publishedAt = null,
        updatedAt = "u1",
        sourceFileName = "source/source.tar.gz",
        sourceSizeBytes = 1L,
        sourceSha256 = "a",
        assets = emptyList(),
        appVersion = "test",
    )

    @Test
    fun sameReleaseAndUpdatedAtSkipsBackup() {
        assertFalse(
            latestReleaseNeedsBackup(
                storedManifest = manifest,
                latestReleaseId = 10L,
                latestUpdatedAt = "u1",
                storedArchiveExists = true,
            ),
        )
    }

    @Test
    fun changedReleaseOrMetadataRequiresBackup() {
        assertTrue(latestReleaseNeedsBackup(manifest, 11L, "u1", true))
        assertTrue(latestReleaseNeedsBackup(manifest, 10L, "u2", true))
        assertTrue(latestReleaseNeedsBackup(manifest, 10L, "u1", false))
        assertTrue(latestReleaseNeedsBackup(null, 10L, "u1", true))
    }

    @Test
    fun assetNamesAreSafeAndUnique() {
        val used = mutableSetOf<String>()
        val first = uniqueAssetFileName("../app.apk", 1L, used)
        val second = uniqueAssetFileName("../app.apk", 2L, used)

        assertFalse(first.contains('/'))
        assertFalse(first.contains(".."))
        assertTrue(first != second)
    }
}
