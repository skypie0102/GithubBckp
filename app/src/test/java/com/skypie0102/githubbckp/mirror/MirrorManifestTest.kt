package com.skypie0102.githubbckp.mirror

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorManifestTest {
    @Test
    fun manifestRoundTrip_preservesIdentityAndSyncMetadata() {
        val root = Files.createTempDirectory("mirror-manifest").toFile()
        try {
            val file = File(root, MirrorManifest.FILE_NAME)
            val manifest = MirrorManifest(
                repositoryId = 1234L,
                repositoryOwner = "octocat",
                repositoryName = "hello-world",
                remoteUrl = "https://github.com/octocat/hello-world.git",
                defaultBranch = "main",
                isPrivate = true,
                createdAtEpochMs = 100L,
                updatedAtEpochMs = 200L,
                lastSuccessfulFetchAtEpochMs = 200L,
                refsDigest = "abc123",
                headCommit = "deadbeef",
                lfsIncluded = true,
                appVersion = "test",
            )

            manifest.writeTo(file)

            assertEquals(manifest, MirrorManifest.readFrom(file))
        } finally {
            root.deleteRecursively()
        }
    }
}
