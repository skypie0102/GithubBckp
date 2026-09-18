package com.skypie0102.githubbckp.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BackupSchedulerPlanTest {
    @Test
    fun simultaneousRepositoriesReceiveIndependentUniqueWorkNames() {
        val plans = repositoryWorkPlans(listOf(11L, 22L, 33L))

        assertEquals(listOf(11L, 22L, 33L), plans.map { it.repositoryId })
        assertEquals(3, plans.map { it.uniqueWorkName }.toSet().size)
        assertEquals(3, plans.map { it.tag }.toSet().size)
        assertNotEquals(plans[0].uniqueWorkName, plans[1].uniqueWorkName)
    }

    @Test
    fun duplicateRepositoryIdsAreOnlyEnqueuedOnce() {
        val plans = repositoryWorkPlans(listOf(11L, 11L, 22L, -1L))

        assertEquals(listOf(11L, 22L), plans.map { it.repositoryId })
    }
}
