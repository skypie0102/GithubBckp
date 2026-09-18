package com.skypie0102.githubbckp.github

import java.io.File
import java.nio.file.Files
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubRepositoryAccessVerifierTest {
    @Test
    fun readableGitRemotePassesCapabilityCheck() {
        val root = Files.createTempDirectory("github-access-readable").toFile()
        try {
            val remote = File(root, "remote.git")
            Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote)
                .call()
                .close()

            assertTrue(gitRemoteReadable(remote.toURI().toString()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingOrInaccessibleGitRemoteFailsCapabilityCheck() {
        val root = Files.createTempDirectory("github-access-missing").toFile()
        try {
            val missing = File(root, "deleted.git")
            assertFalse(gitRemoteReadable(missing.toURI().toString()))
        } finally {
            root.deleteRecursively()
        }
    }
}
