package com.skypie0102.githubbckp.ui

import com.skypie0102.githubbckp.data.local.RepositoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryFilterTest {
    private val repositories = listOf(
        RepositoryEntity(1, "Octo", "Alpha-App", "main", false),
        RepositoryEntity(2, "team", "beta-service", "develop", true),
        RepositoryEntity(3, "other", "gamma", "release/v2", false),
    )

    @Test
    fun blankQueryReturnsOriginalInventory() {
        assertEquals(repositories, filterRepositories(repositories, "   "))
    }

    @Test
    fun matchesOwnerRepositoryCaseInsensitively() {
        assertEquals(
            listOf(repositories[0]),
            filterRepositories(repositories, "octo/alpha"),
        )
        assertEquals(
            listOf(repositories[1]),
            filterRepositories(repositories, "BETA-SERVICE"),
        )
    }

    @Test
    fun matchesDefaultBranchAndReturnsEmptyWhenNothingMatches() {
        assertEquals(
            listOf(repositories[2]),
            filterRepositories(repositories, "release/v2"),
        )
        assertEquals(emptyList<RepositoryEntity>(), filterRepositories(repositories, "missing"))
    }
}
