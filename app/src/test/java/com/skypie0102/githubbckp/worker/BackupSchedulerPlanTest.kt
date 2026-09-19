package com.skypie0102.githubbckp.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSchedulerPlanTest {
    @Test
    fun repositoryPlansPreserveRequestedOrderForSequentialQueue() {
        val plans = repositoryWorkPlans(listOf(11L, 22L, 33L))

        assertEquals(listOf(11L, 22L, 33L), plans.map { it.repositoryId })
        assertEquals(
            listOf("backup-11", "backup-22", "backup-33"),
            plans.map { it.tag },
        )
    }

    @Test
    fun duplicateRepositoryIdsAreOnlyQueuedOnce() {
        val plans = repositoryWorkPlans(listOf(11L, 11L, 22L, -1L))

        assertEquals(listOf(11L, 22L), plans.map { it.repositoryId })
    }
}
