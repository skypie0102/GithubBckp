package com.skypie0102.githubbckp.data.local

import com.skypie0102.githubbckp.backup.RepositoryRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryInventoryTest {
    @Test
    fun preservesSelectionUpdatesMetadataAndMarksMissingRepositoryUnavailable() {
        val existing = listOf(
            RepositoryEntity(
                githubId = 1,
                owner = "octo",
                name = "old-name",
                defaultBranch = "master",
                isPrivate = false,
                selectedForBackup = false,
                lastKnownSha = "abc",
                isAvailable = true,
            ),
            RepositoryEntity(
                githubId = 2,
                owner = "octo",
                name = "gone",
                defaultBranch = "main",
                isPrivate = true,
                selectedForBackup = true,
                isAvailable = true,
            ),
        )
        val remote = listOf(
            RepositoryRef(
                id = 1,
                owner = "renamed-owner",
                name = "new-name",
                defaultBranch = "main",
                isPrivate = true,
            ),
        )

        val plan = planRepositoryInventory(existing, remote)
        val repository = plan.repositories.single()

        assertEquals(1, plan.newlyUnavailableCount)
        assertEquals(0, plan.reactivatedCount)
        assertEquals("renamed-owner", repository.owner)
        assertEquals("new-name", repository.name)
        assertEquals("main", repository.defaultBranch)
        assertTrue(repository.isPrivate)
        assertFalse(repository.selectedForBackup)
        assertEquals("abc", repository.lastKnownSha)
        assertTrue(repository.isAvailable)
    }

    @Test
    fun reactivatesRepositoryWithPreviousSelectionAndDeduplicatesRemoteIds() {
        val existing = listOf(
            RepositoryEntity(
                githubId = 7,
                owner = "octo",
                name = "demo",
                defaultBranch = "main",
                isPrivate = false,
                selectedForBackup = false,
                isAvailable = false,
            ),
        )
        val remote = listOf(
            RepositoryRef(7, "octo", "demo", "main", false),
            RepositoryRef(7, "octo", "demo", "main", false),
            RepositoryRef(8, "octo", "new-repo", "main", false),
        )

        val plan = planRepositoryInventory(existing, remote)

        assertEquals(2, plan.repositories.size)
        assertEquals(0, plan.newlyUnavailableCount)
        assertEquals(1, plan.reactivatedCount)
        assertFalse(plan.repositories.first { it.githubId == 7L }.selectedForBackup)
        assertTrue(plan.repositories.first { it.githubId == 8L }.selectedForBackup)
    }
}
