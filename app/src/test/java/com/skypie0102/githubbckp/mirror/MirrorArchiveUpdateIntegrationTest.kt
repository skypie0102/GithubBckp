package com.skypie0102.githubbckp.mirror

import java.io.File
import java.nio.file.Files
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MirrorArchiveUpdateIntegrationTest {
    @Test
    fun archivedMirrorCanBeExtractedIncrementallyUpdatedPrunedAndRepacked() {
        val root = Files.createTempDirectory("mirror-archive-update").toFile()
        try {
            val remote = File(root, "remote.git")
            Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote)
                .call()
                .close()
            val remoteUri = remote.toURI().toString()

            val working = File(root, "working")
            Git.init()
                .setInitialBranch("main")
                .setDirectory(working)
                .call()
                .use { git ->
                    File(working, "README.md").writeText("one")
                    git.add().addFilepattern("README.md").call()
                    git.commit()
                        .setMessage("initial")
                        .setAuthor("Test", "test@example.com")
                        .setCommitter("Test", "test@example.com")
                        .call()

                    git.checkout().setCreateBranch(true).setName("feature").call()
                    File(working, "feature.txt").writeText("feature")
                    git.add().addFilepattern("feature.txt").call()
                    git.commit()
                        .setMessage("feature")
                        .setAuthor("Test", "test@example.com")
                        .setCommitter("Test", "test@example.com")
                        .call()
                    git.push().setRemote(remoteUri).setPushAll().call()
                    git.checkout().setName("main").call()
                }

            val firstStaging = File(root, "first").apply { mkdirs() }
            val firstRepository = File(firstStaging, MirrorVerifier.REPOSITORY_DIRECTORY)
            GitMirrorOperations.cloneMirror(remoteUri, firstRepository)
            val createdAt = 100L
            writeManifest(
                staging = firstStaging,
                remoteUri = remoteUri,
                repository = firstRepository,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
            val firstArchive = File(root, "mirror.tar.gz")
            TarGzArchive.create(firstStaging, firstArchive)
            MirrorVerifier.verify(firstArchive, File(root, "verify-first"), expectedRepositoryId = REPOSITORY_ID)

            val newHead = Git.open(working).use { git ->
                File(working, "README.md").writeText("two")
                git.add().addFilepattern("README.md").call()
                val commit = git.commit()
                    .setMessage("update main")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call()
                git.push()
                    .setRemote(remoteUri)
                    .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                    .call()
                commit.id.name
            }
            openBare(remote).use { repository ->
                repository.updateRef("refs/heads/feature").apply {
                    setForceUpdate(true)
                }.delete()
            }

            val updateStaging = File(root, "update")
            TarGzArchive.extract(firstArchive, updateStaging)
            val updateRepository = File(updateStaging, MirrorVerifier.REPOSITORY_DIRECTORY)
            GitMirrorOperations.fetchAndPrune(remoteUri, updateRepository, "main")
            writeManifest(
                staging = updateStaging,
                remoteUri = remoteUri,
                repository = updateRepository,
                createdAt = createdAt,
                updatedAt = 200L,
            )

            val replacement = File(root, "replacement.tar.gz")
            TarGzArchive.create(updateStaging, replacement)
            val verified = MirrorVerifier.verify(
                replacement,
                File(root, "verify-replacement"),
                expectedRepositoryId = REPOSITORY_ID,
            )

            assertEquals(createdAt, verified.manifest.createdAtEpochMs)
            assertEquals(200L, verified.manifest.updatedAtEpochMs)
            assertEquals(newHead, verified.manifest.headCommit)
            assertEquals(
                GitMirrorOperations.remoteRefsDigest(remoteUri),
                verified.manifest.refsDigest,
            )

            val finalExtract = File(root, "final")
            TarGzArchive.extract(replacement, finalExtract)
            openBare(File(finalExtract, MirrorVerifier.REPOSITORY_DIRECTORY)).use { repository ->
                assertNotNull(repository.findRef("refs/heads/main"))
                assertNull(repository.findRef("refs/heads/feature"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedFetchLeavesCurrentArchiveByteForByteUntouched() {
        val root = Files.createTempDirectory("mirror-fetch-failure").toFile()
        try {
            val remote = File(root, "remote.git")
            Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote)
                .call()
                .close()
            val remoteUri = remote.toURI().toString()

            val working = File(root, "working")
            Git.init()
                .setInitialBranch("main")
                .setDirectory(working)
                .call()
                .use { git ->
                    File(working, "README.md").writeText("stable")
                    git.add().addFilepattern("README.md").call()
                    git.commit()
                        .setMessage("initial")
                        .setAuthor("Test", "test@example.com")
                        .setCommitter("Test", "test@example.com")
                        .call()
                    git.push().setRemote(remoteUri).setPushAll().call()
                }

            val staging = File(root, "first").apply { mkdirs() }
            val repository = File(staging, MirrorVerifier.REPOSITORY_DIRECTORY)
            GitMirrorOperations.cloneMirror(remoteUri, repository)
            writeManifest(staging, remoteUri, repository, 100L, 100L)
            val currentArchive = File(root, "mirror.tar.gz")
            TarGzArchive.create(staging, currentArchive)
            val stableBytes = currentArchive.readBytes()

            val updateStaging = File(root, "update")
            TarGzArchive.extract(currentArchive, updateStaging)
            remote.deleteRecursively()

            assertThrows(Exception::class.java) {
                GitMirrorOperations.fetchAndPrune(
                    remoteUri = remoteUri,
                    repositoryDirectory = File(updateStaging, MirrorVerifier.REPOSITORY_DIRECTORY),
                    defaultBranch = "main",
                )
            }

            assertArrayEquals(stableBytes, currentArchive.readBytes())
            MirrorVerifier.verify(
                currentArchive,
                File(root, "verify-stable"),
                expectedRepositoryId = REPOSITORY_ID,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interruptedReplacementArchiveCannotDamageCurrentArchive() {
        val root = Files.createTempDirectory("mirror-compression-failure").toFile()
        try {
            val remote = File(root, "remote.git")
            Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote)
                .call()
                .close()
            val remoteUri = remote.toURI().toString()

            val working = File(root, "working")
            Git.init()
                .setInitialBranch("main")
                .setDirectory(working)
                .call()
                .use { git ->
                    File(working, "README.md").writeText("stable")
                    git.add().addFilepattern("README.md").call()
                    git.commit()
                        .setMessage("initial")
                        .setAuthor("Test", "test@example.com")
                        .setCommitter("Test", "test@example.com")
                        .call()
                    git.push().setRemote(remoteUri).setPushAll().call()
                }

            val staging = File(root, "first").apply { mkdirs() }
            val repository = File(staging, MirrorVerifier.REPOSITORY_DIRECTORY)
            GitMirrorOperations.cloneMirror(remoteUri, repository)
            writeManifest(staging, remoteUri, repository, 100L, 100L)
            val currentArchive = File(root, "mirror.tar.gz")
            TarGzArchive.create(staging, currentArchive)
            val stableBytes = currentArchive.readBytes()

            val interruptedCandidate = File(root, "mirror.pending.tar.gz")
            interruptedCandidate.writeBytes(stableBytes.copyOf(stableBytes.size / 2))

            assertThrows(Exception::class.java) {
                MirrorVerifier.verify(
                    interruptedCandidate,
                    File(root, "verify-candidate"),
                    expectedRepositoryId = REPOSITORY_ID,
                )
            }

            assertArrayEquals(stableBytes, currentArchive.readBytes())
            MirrorVerifier.verify(
                currentArchive,
                File(root, "verify-stable"),
                expectedRepositoryId = REPOSITORY_ID,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeManifest(
        staging: File,
        remoteUri: String,
        repository: File,
        createdAt: Long,
        updatedAt: Long,
    ) {
        MirrorManifest(
            repositoryId = REPOSITORY_ID,
            repositoryOwner = "owner",
            repositoryName = "repo",
            remoteUrl = remoteUri,
            defaultBranch = "main",
            isPrivate = false,
            createdAtEpochMs = createdAt,
            updatedAtEpochMs = updatedAt,
            lastSuccessfulFetchAtEpochMs = updatedAt,
            refsDigest = GitMirrorOperations.localRefsDigest(repository),
            headCommit = GitMirrorOperations.headCommit(repository, "main"),
            lfsIncluded = false,
            appVersion = "test",
        ).writeTo(File(staging, MirrorManifest.FILE_NAME))
    }

    private fun openBare(directory: File) =
        FileRepositoryBuilder()
            .setGitDir(directory)
            .setBare()
            .setMustExist(true)
            .build()

    private companion object {
        const val REPOSITORY_ID = 99L
    }
}
