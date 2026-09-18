package com.skypie0102.githubbckp.mirror

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

            writeManifest(staging, lfsIncluded = false)

            val archive = File(root, "mirror.tar.gz")
            TarGzArchive.create(staging, archive)

            val result = MirrorVerifier.verify(
                archive = archive,
                verificationDirectory = File(root, "verify"),
                expectedRepositoryId = 42L,
            )

            assertEquals(42L, result.manifest.repositoryId)
            assertEquals(0, result.refCount)
            assertEquals(0, result.lfsObjectCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verify_requiresBrowsableCheckoutWhenManifestPromisesIt() {
        val root = Files.createTempDirectory("mirror-verifier-working-tree").toFile()
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
                refsDigest = "test",
                lfsIncluded = false,
                workingTreeIncluded = true,
                appVersion = "test",
            ).writeTo(File(staging, MirrorManifest.FILE_NAME))

            val archive = File(root, "mirror.tar.gz")
            TarGzArchive.create(staging, archive)

            assertThrows(IllegalStateException::class.java) {
                MirrorVerifier.verify(
                    archive = archive,
                    verificationDirectory = File(root, "verify"),
                    expectedRepositoryId = 42L,
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verify_rejectsMirrorWithMissingReachableLfsObject() {
        val root = Files.createTempDirectory("mirror-verifier-lfs").toFile()
        try {
            val staging = File(root, "staging").apply { mkdirs() }
            createMirrorWithLfsPointer(
                root = root,
                mirrorDirectory = File(staging, MirrorVerifier.REPOSITORY_DIRECTORY),
            )
            writeManifest(staging, lfsIncluded = true)

            val archive = File(root, "mirror.tar.gz")
            TarGzArchive.create(staging, archive)

            assertThrows(IOException::class.java) {
                MirrorVerifier.verify(
                    archive = archive,
                    verificationDirectory = File(root, "verify"),
                    expectedRepositoryId = 42L,
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verify_acceptsReachableLfsObjectWithMatchingBytes() {
        val root = Files.createTempDirectory("mirror-verifier-lfs-ok").toFile()
        try {
            val staging = File(root, "staging").apply { mkdirs() }
            val mirror = File(staging, MirrorVerifier.REPOSITORY_DIRECTORY)
            createMirrorWithLfsPointer(root, mirror)

            val objectFile = File(
                mirror,
                "lfs/objects/${LFS_OID.substring(0, 2)}/${LFS_OID.substring(2, 4)}/$LFS_OID",
            )
            objectFile.parentFile?.mkdirs()
            objectFile.writeText("hello")
            writeManifest(staging, lfsIncluded = true)

            val archive = File(root, "mirror.tar.gz")
            TarGzArchive.create(staging, archive)

            val result = MirrorVerifier.verify(
                archive = archive,
                verificationDirectory = File(root, "verify"),
                expectedRepositoryId = 42L,
            )

            assertEquals(1, result.lfsObjectCount)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createMirrorWithLfsPointer(root: File, mirrorDirectory: File) {
        val workingDirectory = File(root, "working-${System.nanoTime()}")
        Git.init()
            .setInitialBranch("main")
            .setDirectory(workingDirectory)
            .call()
            .use { git ->
                File(workingDirectory, "asset.bin").writeText(
                    "version https://git-lfs.github.com/spec/v1\n" +
                        "oid sha256:$LFS_OID\n" +
                        "size 5\n",
                )
                git.add().addFilepattern("asset.bin").call()
                git.commit()
                    .setMessage("add lfs pointer")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call()
            }

        Git.cloneRepository()
            .setURI(workingDirectory.toURI().toString())
            .setDirectory(mirrorDirectory)
            .setMirror(true)
            .call()
            .close()
    }

    private fun writeManifest(staging: File, lfsIncluded: Boolean) {
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
            refsDigest = "test",
            lfsIncluded = lfsIncluded,
            workingTreeIncluded = false,
            appVersion = "test",
        ).writeTo(File(staging, MirrorManifest.FILE_NAME))
    }

    private companion object {
        const val LFS_OID = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    }
}
