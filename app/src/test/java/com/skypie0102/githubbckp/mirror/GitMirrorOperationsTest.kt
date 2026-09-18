package com.skypie0102.githubbckp.mirror

import java.io.File
import java.nio.file.Files
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
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
            val remote = createBareRemote(root)
            val remoteUri = remote.toURI().toString()
            val working = createWorkingRepository(root, remoteUri)
            val mirror = File(root, "mirror.git")
            GitMirrorOperations.cloneMirror(remoteUri, mirror)

            openBare(mirror).use { assertNotNull(it.findRef("refs/heads/feature")) }

            val newHead = Git.open(working).use { git ->
                File(working, "README.md").writeText("two")
                git.add().addFilepattern("README.md").call()
                val commit = git.commit()
                    .setMessage("update")
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
                repository.updateRef("refs/heads/feature").apply { setForceUpdate(true) }.delete()
            }

            GitMirrorOperations.fetchAndPrune(remoteUri, mirror, "main")

            openBare(mirror).use { assertNull(it.findRef("refs/heads/feature")) }
            assertEquals(newHead, GitMirrorOperations.headCommit(mirror, "main"))
            assertDigestsMatch(remoteUri, mirror)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fetchAndPrune_removesDeletedTag() {
        val root = Files.createTempDirectory("git-mirror-tag-prune").toFile()
        try {
            val remote = createBareRemote(root)
            val remoteUri = remote.toURI().toString()
            val working = createWorkingRepository(root, remoteUri)

            Git.open(working).use { git ->
                git.tag().setName("v1").call()
                git.push().setRemote(remoteUri).setPushTags().call()
            }

            val mirror = File(root, "mirror.git")
            GitMirrorOperations.cloneMirror(remoteUri, mirror)
            openBare(mirror).use { assertNotNull(it.findRef("refs/tags/v1")) }

            openBare(remote).use { repository ->
                repository.updateRef("refs/tags/v1").apply { setForceUpdate(true) }.delete()
            }

            GitMirrorOperations.fetchAndPrune(remoteUri, mirror, "main")

            openBare(mirror).use { assertNull(it.findRef("refs/tags/v1")) }
            assertDigestsMatch(remoteUri, mirror)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fetchAndPrune_acceptsForcedBranchRewrite() {
        val root = Files.createTempDirectory("git-mirror-force").toFile()
        try {
            val remote = createBareRemote(root)
            val remoteUri = remote.toURI().toString()
            val working = createWorkingRepository(root, remoteUri)

            val initialHead: String
            Git.open(working).use { git ->
                initialHead = git.repository.resolve("refs/heads/main").name
                File(working, "README.md").writeText("second")
                git.add().addFilepattern("README.md").call()
                git.commit()
                    .setMessage("second")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call()
                git.push()
                    .setRemote(remoteUri)
                    .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                    .call()
            }

            val mirror = File(root, "mirror.git")
            GitMirrorOperations.cloneMirror(remoteUri, mirror)

            val rewrittenHead = Git.open(working).use { git ->
                git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef(initialHead)
                    .call()
                File(working, "README.md").writeText("alternate")
                git.add().addFilepattern("README.md").call()
                val rewritten = git.commit()
                    .setMessage("alternate")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call()
                git.push()
                    .setRemote(remoteUri)
                    .setRefSpecs(RefSpec("+refs/heads/main:refs/heads/main"))
                    .setForce(true)
                    .call()
                rewritten.id.name
            }

            GitMirrorOperations.fetchAndPrune(remoteUri, mirror, "main")

            assertEquals(rewrittenHead, GitMirrorOperations.headCommit(mirror, "main"))
            assertDigestsMatch(remoteUri, mirror)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cloneMirror_supportsEmptyRepository() {
        val root = Files.createTempDirectory("git-mirror-empty").toFile()
        try {
            val remote = createBareRemote(root)
            val remoteUri = remote.toURI().toString()
            val mirror = File(root, "mirror.git")

            GitMirrorOperations.cloneMirror(remoteUri, mirror)

            openBare(mirror).use { repository ->
                assertEquals(0, repository.refDatabase.getRefsByPrefix("refs/").size)
            }
            assertDigestsMatch(remoteUri, mirror)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createBareRemote(root: File): File =
        File(root, "remote.git").also { directory ->
            Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(directory)
                .call()
                .close()
        }

    private fun createWorkingRepository(root: File, remoteUri: String): File =
        File(root, "working").also { directory ->
            Git.init()
                .setInitialBranch("main")
                .setDirectory(directory)
                .call()
                .use { git ->
                    File(directory, "README.md").writeText("one")
                    git.add().addFilepattern("README.md").call()
                    git.commit()
                        .setMessage("initial")
                        .setAuthor("Test", "test@example.com")
                        .setCommitter("Test", "test@example.com")
                        .call()

                    git.checkout().setCreateBranch(true).setName("feature").call()
                    File(directory, "feature.txt").writeText("feature")
                    git.add().addFilepattern("feature.txt").call()
                    git.commit()
                        .setMessage("feature")
                        .setAuthor("Test", "test@example.com")
                        .setCommitter("Test", "test@example.com")
                        .call()
                    git.push().setRemote(remoteUri).setPushAll().call()
                    git.checkout().setName("main").call()
                }
        }

    private fun assertDigestsMatch(remoteUri: String, mirror: File) {
        assertEquals(
            GitMirrorOperations.remoteRefsDigest(remoteUri),
            GitMirrorOperations.localRefsDigest(mirror),
        )
    }

    private fun openBare(directory: File) =
        FileRepositoryBuilder()
            .setGitDir(directory)
            .setBare()
            .setMustExist(true)
            .build()
}
