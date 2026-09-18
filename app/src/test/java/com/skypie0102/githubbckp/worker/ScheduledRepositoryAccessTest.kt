package com.skypie0102.githubbckp.worker

import com.skypie0102.githubbckp.data.local.RepositoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledRepositoryAccessTest {
    @Test
    fun preflightSeparatesReadableAndInaccessibleRepositories() = runBlocking {
        val readable = repository(1L, "readable")
        val blocked = repository(2L, "blocked")

        val results = verifyRepositoryReadAccess(
            repositories = listOf(readable, blocked),
            accessCheck = { repository -> repository.githubId == readable.githubId },
        )

        assertTrue(results.single { it.repository.githubId == 1L }.readable)
        assertFalse(results.single { it.repository.githubId == 2L }.readable)
    }

    @Test
    fun repositoryRemoteUrlUsesCanonicalGithubGitEndpoint() {
        assertEquals(
            "https://github.com/octo/demo.git",
            repositoryRemoteUrl(repository(7L, "demo")),
        )
    }

    private fun repository(id: Long, name: String) = RepositoryEntity(
        githubId = id,
        owner = "octo",
        name = name,
        defaultBranch = "main",
        isPrivate = true,
        selectedForBackup = true,
        isAvailable = true,
    )
}
