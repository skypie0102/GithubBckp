package com.skypie0102.githubbckp.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledBackupReadinessTest {
    @Test
    fun blocksWhenGithubIsDisconnectedFirst() {
        val readiness = evaluateScheduledBackupReadiness(
            githubAuthenticated = false,
            documentTreeConfigured = false,
            notificationsReady = false,
        )

        assertFalse(readiness.ready)
        assertEquals(ScheduledBackupBlockReason.GITHUB_DISCONNECTED, readiness.blockReason)
    }

    @Test
    fun localFolderIsRequired() {
        val readiness = evaluateScheduledBackupReadiness(
            githubAuthenticated = true,
            documentTreeConfigured = false,
            notificationsReady = true,
        )

        assertFalse(readiness.ready)
        assertEquals(ScheduledBackupBlockReason.DOCUMENT_TREE_MISSING, readiness.blockReason)
    }

    @Test
    fun visibleNotificationsAreRequired() {
        val readiness = evaluateScheduledBackupReadiness(
            githubAuthenticated = true,
            documentTreeConfigured = true,
            notificationsReady = false,
        )

        assertFalse(readiness.ready)
        assertEquals(ScheduledBackupBlockReason.NOTIFICATIONS_DISABLED, readiness.blockReason)
    }

    @Test
    fun readyWhenGithubFolderAndNotificationsAreAvailable() {
        val readiness = evaluateScheduledBackupReadiness(
            githubAuthenticated = true,
            documentTreeConfigured = true,
            notificationsReady = true,
        )

        assertTrue(readiness.ready)
        assertNull(readiness.blockReason)
    }

    @Test
    fun runStatusSeparatesNoRepositoriesBlockedAndQueuedOutcomes() {
        val noRepositories = scheduledBackupRunStatus(
            completedAtEpochMs = 100L,
            repositoryCount = 0,
            scheduledRunId = "run-empty",
        )
        val blocked = scheduledBackupRunStatus(
            completedAtEpochMs = 200L,
            repositoryCount = 4,
            scheduledRunId = "run-blocked",
            readiness = ScheduledBackupReadiness(
                ready = false,
                blockReason = ScheduledBackupBlockReason.NOTIFICATIONS_DISABLED,
            ),
        )
        val queued = scheduledBackupRunStatus(
            completedAtEpochMs = 300L,
            repositoryCount = 3,
            scheduledRunId = " run-queued ",
            readiness = ScheduledBackupReadiness(ready = true),
        )

        assertEquals(ScheduledBackupRunOutcome.SKIPPED_NO_REPOSITORIES, noRepositories.outcome)
        assertEquals(ScheduledBackupRunOutcome.SKIPPED_NOT_READY, blocked.outcome)
        assertEquals(ScheduledBackupBlockReason.NOTIFICATIONS_DISABLED, blocked.blockReason)
        assertEquals(ScheduledBackupRunOutcome.QUEUED, queued.outcome)
        assertEquals("run-queued", queued.scheduledRunId)
    }

    @Test
    fun runStatusNormalizesNegativeCountsAndBlankRunIds() {
        val status = scheduledBackupRunStatus(
            completedAtEpochMs = 400L,
            repositoryCount = -5,
            scheduledRunId = "   ",
        )

        assertEquals(ScheduledBackupRunOutcome.SKIPPED_NO_REPOSITORIES, status.outcome)
        assertEquals(0, status.repositoryCount)
        assertNull(status.scheduledRunId)
    }
}
