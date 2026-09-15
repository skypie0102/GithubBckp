package com.skypie0102.githubbckp.backup

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitMirrorPushServiceTest {
    private val service = GitMirrorPushService()

    @Test
    fun pushesWritableRefsAndSkipsGithubPullRefs() = runBlocking {
        val root = Files.createTempDirectory("mirror-push-test").toFile()
        try {
            val mirror = createSourceMirror(root)
            FileRepositoryBuilder().setGitDir(mirror).setBare().build().use { repository ->
                val head = repository.resolve(Constants.HEAD)
                val pullRef = repository.updateRef("refs/pull/1/head")
                pullRef.setNewObjectId(head)
                assertTrue(pullRef.update().name in setOf("NEW", "FORCED", "FAST_FORWARD", "NO_CHANGE"))
            }

            val target = File(root, "target.git")
            Git.init().setBare(true).setDirectory(target).call().close()

            val result = service.push(
                repositoryDirectory = mirror,
                remoteUri = target.toURI().toString(),
            )

            assertTrue(result.pushedRefCount >= 3)
            assertEquals(listOf("refs/pull/1/head"), result.skippedReadOnlyRefs)
            FileRepositoryBuilder().setGitDir(target).setBare().build().use { repository ->
                assertNotNull(repository.resolve("refs/heads/master"))
                assertNotNull(repository.resolve("refs/heads/feature"))
                assertNotNull(repository.resolve("refs/tags/v1"))
                assertFalse(repository.refDatabase.exactRef("refs/pull/1/head") != null)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resumeAcceptsOnlyExactAlreadyPublishedMirror() = runBlocking {
        val root = Files.createTempDirectory("mirror-resume-test").toFile()
        try {
            val mirror = createSourceMirror(root)
            val target = File(root, "target.git")
            Git.init().setBare(true).setDirectory(target).call().close()
            val first = service.push(mirror, target.toURI().toString())

            val resumed = service.pushOrReconcilePublished(mirror, target.toURI().toString())
            assertEquals(first.pushedRefCount, resumed.pushedRefCount)

            FileRepositoryBuilder().setGitDir(target).setBare().build().use { repository ->
                val head = repository.resolve("refs/heads/master")
                val extra = repository.updateRef("refs/heads/unexpected")
                extra.setNewObjectId(head)
                assertTrue(extra.update().name in setOf("NEW", "FORCED", "FAST_FORWARD", "NO_CHANGE"))
            }
            val failure = runCatching {
                service.pushOrReconcilePublished(mirror, target.toURI().toString())
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure?.message.orEmpty().contains("not empty"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesNonEmptyTargetWithoutChangingIt() = runBlocking {
        val root = Files.createTempDirectory("mirror-non-empty-target-test").toFile()
        try {
            val mirror = createSourceMirror(root)
            val targetWork = File(root, "target-work")
            val targetCommit = Git.init().setDirectory(targetWork).call().use { git ->
                File(targetWork, "existing.txt").writeText("existing target state\n")
                git.add().addFilepattern("existing.txt").call()
                git.commit()
                    .setMessage("existing target")
                    .setAuthor("Target", "target@example.com")
                    .setCommitter("Target", "target@example.com")
                    .call()
            }
            val target = File(root, "target.git")
            Git.cloneRepository()
                .setURI(targetWork.toURI().toString())
                .setDirectory(target)
                .setBare(true)
                .call()
                .close()

            val failure = runCatching {
                service.push(
                    repositoryDirectory = mirror,
                    remoteUri = target.toURI().toString(),
                )
            }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertTrue(failure?.message.orEmpty().contains("not empty"))
            FileRepositoryBuilder().setGitDir(target).setBare().build().use { repository ->
                assertEquals(targetCommit.id, repository.resolve("refs/heads/master"))
                assertNull(repository.resolve("refs/heads/feature"))
                assertNull(repository.resolve("refs/tags/v1"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createSourceMirror(root: File): File {
        val source = File(root, "source")
        Git.init().setDirectory(source).call().use { git ->
            File(source, "README.md").writeText("restore push test\n")
            git.add().addFilepattern("README.md").call()
            val first = git.commit()
                .setMessage("initial")
                .setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com")
                .call()
            git.tag().setName("v1").call()
            git.branchCreate().setName("feature").setStartPoint(first).call()
        }

        return File(root, "source.git").also { mirror ->
            Git.cloneRepository()
                .setURI(source.toURI().toString())
                .setDirectory(mirror)
                .setMirror(true)
                .call()
                .close()
        }
    }
}
