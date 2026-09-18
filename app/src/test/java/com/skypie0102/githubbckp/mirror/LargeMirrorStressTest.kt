package com.skypie0102.githubbckp.mirror

import java.io.File
import java.nio.file.Files
import java.util.Random
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeMirrorStressTest {
    @Test
    fun syntheticLargeRepositoryCanBeMirroredArchivedAndVerified() {
        val root = Files.createTempDirectory("large-mirror-stress").toFile()
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
                    repeat(FILE_COUNT) { fileIndex ->
                        val bytes = ByteArray(FILE_SIZE_BYTES)
                        Random(fileIndex.toLong() + 1L).nextBytes(bytes)
                        File(working, "payload/file-$fileIndex.bin").apply {
                            parentFile?.mkdirs()
                            writeBytes(bytes)
                        }
                    }
                    git.add().addFilepattern("payload").call()
                    git.commit()
                        .setMessage("large synthetic payload")
                        .setAuthor("Test", "test@example.com")
                        .setCommitter("Test", "test@example.com")
                        .call()

                    repeat(TAG_COUNT) { index ->
                        git.tag().setName("stress-$index").call()
                    }

                    git.push().setRemote(remoteUri).setPushAll().setPushTags().call()
                }

            val staging = File(root, "staging").apply { mkdirs() }
            val mirror = File(staging, MirrorVerifier.REPOSITORY_DIRECTORY)
            GitMirrorOperations.cloneMirror(remoteUri, mirror)

            RepositoryCheckoutExporter.export(
                repositoryDirectory = mirror,
                defaultBranch = "main",
                destinationDirectory = File(staging, MirrorVerifier.WORKING_TREE_DIRECTORY),
            )

            MirrorManifest(
                repositoryId = REPOSITORY_ID,
                repositoryOwner = "stress",
                repositoryName = "large",
                remoteUrl = remoteUri,
                defaultBranch = "main",
                isPrivate = false,
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
                lastSuccessfulFetchAtEpochMs = 1L,
                refsDigest = GitMirrorOperations.localRefsDigest(mirror),
                headCommit = GitMirrorOperations.headCommit(mirror, "main"),
                lfsIncluded = false,
                workingTreeIncluded = true,
                appVersion = "test",
            ).writeTo(File(staging, MirrorManifest.FILE_NAME))

            val archive = File(root, "mirror.tar.gz")
            TarGzArchive.create(staging, archive)
            val verified = MirrorVerifier.verify(
                archive = archive,
                verificationDirectory = File(root, "verify"),
                expectedRepositoryId = REPOSITORY_ID,
            )

            assertTrue(archive.length() > MIN_EXPECTED_ARCHIVE_BYTES)
            assertEquals(
                GitMirrorOperations.remoteRefsDigest(remoteUri),
                verified.manifest.refsDigest,
            )
            assertTrue(verified.refCount >= TAG_COUNT + 1)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val REPOSITORY_ID = 404L
        const val FILE_COUNT = 12
        const val FILE_SIZE_BYTES = 1024 * 1024
        const val TAG_COUNT = 20
        const val MIN_EXPECTED_ARCHIVE_BYTES = 8L * 1024L * 1024L
    }
}
