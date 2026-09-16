package com.skypie0102.githubbckp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryInventoryTest {
    @Test
    fun normalizationReactivatesRowsWithoutChangingSelection() {
        val repository = RepositoryEntity(
            githubId = 7,
            owner = "octo",
            name = "demo",
            defaultBranch = "main",
            isPrivate = false,
            selectedForBackup = false,
            lastKnownSha = "abc",
            isAvailable = false,
        )

        val normalized = normalizeRepositoryInventory(listOf(repository)).single()

        assertTrue(normalized.isAvailable)
        assertFalse(normalized.selectedForBackup)
        assertEquals("abc", normalized.lastKnownSha)
    }

    @Test
    fun normalizationDeduplicatesRepositoryIds() {
        val first = RepositoryEntity(
            githubId = 8,
            owner = "octo",
            name = "demo",
            defaultBranch = "main",
            isPrivate = false,
            isAvailable = true,
        )
        val duplicate = first.copy(name = "duplicate-page-result")

        val normalized = normalizeRepositoryInventory(listOf(first, duplicate))

        assertEquals(1, normalized.size)
        assertEquals("demo", normalized.single().name)
    }
}
