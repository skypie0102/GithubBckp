package com.skypie0102.githubbckp.mirror

import java.io.File
import java.nio.file.Files
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorVerifierTest {
    @Test
    fun verify_acceptsArchiveContainingBareRepositoryAndManifest() {
        val root = Files.createTempDirectory("mirror-verifier").toFile()
        try {
            val staging = File(root, "staging").apply { mkdirs() }
            val repositoryDirectory = File(staging, MirrorVerifier.REPOSITORY_DIRECTORY)
            Git.init()
                .setBare(true)
                .setDirectory(repositoryDirectory)
                .call()
                .close()

            MirrorManifest(
                repositoryId = 42L,
                repositoryOwner = "owner",
                repositoryName = "repo",
                remoteUrl = "https://github.com/owner/repo.git",
                defaultBranch = "main",
                isPrivate = false,
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 2L,
                lastSuccessfulFetchAtEpochMs = 2L,
                refsDigest = "empty",
                lfsIncluded = false,
                appVersion = "test",
            ).writeTo(File(staging, MirrorManifest.FILE_NAME))

            val archive = File(root, "mirror.tar.gz")
            TarGzArchive.create(staging, archive)

            val result = MirrorVerifier.verify(
                archive = archive,
                verificationDirectory = File(root, "verify"),
                expectedRepositoryId = 42L,
            )

            assertEquals(42L, result.manifest.repositoryId)
            assertEquals(0, result.refCount)
        } finally {
            root.deleteRecursively()
        }
    }
}
