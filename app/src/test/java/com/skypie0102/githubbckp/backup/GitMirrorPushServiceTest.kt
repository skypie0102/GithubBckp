package com.skypie0102.githubbckp.backup

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitMirrorPushServiceTest {
    private val service = GitMirrorPushService()

    @Test
    fun pushesWritableRefsAndSkipsGithubPullRefs() = runBlocking {
        val root = Files.createTempDirectory("mirror-push-test").toFile()
        try {
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

            val mirror = File(root, "source.git")
            Git.cloneRepository()
                .setURI(source.toURI().toString())
                .setDirectory(mirror)
                .setMirror(true)
                .call()
                .use { git ->
                    val head = git.repository.resolve(Constants.HEAD)
                    val pullRef = git.repository.updateRef("refs/pull/1/head")
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
}
