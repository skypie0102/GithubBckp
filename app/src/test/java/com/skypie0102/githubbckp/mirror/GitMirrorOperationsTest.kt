package com.skypie0102.githubbckp.mirror

import java.io.File
import java.nio.file.Files
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GitMirrorOperationsTest {
    @Test
    fun fetchAndPrune_updatesChangedRefsAndDeletesRemovedRefs() {
        val root = Files.createTempDirectory("git-mirror-ops").toFile()
        try {
            val remoteDirectory = File(root, "remote.git")
            Git.init()
                .setBare(true)
                .setDirectory(remoteDirectory)
                .call()
                .close()
            val remoteUri = remoteDirectory.toURI().toString()

            val workingDirectory = File(root, "working")
            Git.init()
                .setInitialBranch("main")
                .setDirectory(workingDirectory)
                .call()
                .use { working ->
                    File(workingDirectory, "README.md").writeText("one")
                    working.add().addFilepattern("README.md").call()
                    working.commit()
                        .setMessage("initial")
                        .setAuthor("Test", "test@example.com")
                        .setCommitter("Test", "test@example.com")
                        .call()

                    working.checkout()
                        .setCreateBranch(true)
                        .setName("feature")
                        .call()
                    File(workingDirectory, "feature.txt").writeText("feature")
                    working.add().addFilepattern("feature.txt").call()
                    working.commit()
                        .setMessage("feature")
                        .setAuthor("Test", "test@example.com")
                        .setCommitter("Test", "test@example.com")
                        .call()
                    working.push()
                        .setRemote(remoteUri)
                        .setPushAll()
                        .call()

                    working.checkout().setName("main").call()
                }

            val mirrorDirectory = File(root, "mirror.git")
            GitMirrorOperations.cloneMirror(remoteUri, mirrorDirectory)

            assertEquals(
                GitMirrorOperations.remoteRefsDigest(remoteUri),
                GitMirrorOperations.localRefsDigest(mirrorDirectory),
            )
            openBare(mirrorDirectory).use { mirror ->
                assertNotNull(mirror.findRef("refs/heads/feature"))
            }

            val newHead = Git.open(workingDirectory).use { working ->
                File(workingDirectory, "README.md").writeText("two")
                working.add().addFilepattern("README.md").call()
                val commit = working.commit()
                    .setMessage("update")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call()
                working.push()
                    .setRemote(remoteUri)
                    .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                    .call()
                commit.id.name
            }

            openBare(remoteDirectory).use { remote ->
                remote.updateRef("refs/heads/feature").apply {
                    setForceUpdate(true)
                }.delete()
            }

            GitMirrorOperations.fetchAndPrune(
                remoteUri = remoteUri,
                repositoryDirectory = mirrorDirectory,
                defaultBranch = "main",
            )

            openBare(mirrorDirectory).use { mirror ->
                assertNull(mirror.findRef("refs/heads/feature"))
            }
            assertEquals(
                GitMirrorOperations.remoteRefsDigest(remoteUri),
                GitMirrorOperations.localRefsDigest(mirrorDirectory),
            )
            assertEquals(
                newHead,
                GitMirrorOperations.headCommit(mirrorDirectory, "main"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun openBare(directory: File) =
        FileRepositoryBuilder()
            .setGitDir(directory)
            .setBare()
            .setMustExist(true)
            .build()
}
