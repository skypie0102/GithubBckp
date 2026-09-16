package com.skypie0102.githubbckp.worker

import com.skypie0102.githubbckp.storage.StorageDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledBackupReadinessTest {
    @Test
    fun blocksWhenGithubIsDisconnectedBeforeCheckingDestination() {
        val readiness = evaluateScheduledBackupReadiness(
            githubAuthenticated = false,
            destination = StorageDestination.GOOGLE_DRIVE,
            driveAuthenticated = false,
            documentTreeConfigured = false,
        )

        assertFalse(readiness.ready)
        assertEquals(ScheduledBackupBlockReason.GITHUB_DISCONNECTED, readiness.blockReason)
    }

    @Test
    fun driveDestinationRequiresDriveConnection() {
        val blocked = evaluateScheduledBackupReadiness(
            githubAuthenticated = true,
            destination = StorageDestination.GOOGLE_DRIVE,
            driveAuthenticated = false,
            documentTreeConfigured = true,
        )
        val ready = evaluateScheduledBackupReadiness(
            githubAuthenticated = true,
            destination = StorageDestination.GOOGLE_DRIVE,
            driveAuthenticated = true,
            documentTreeConfigured = false,
        )

        assertFalse(blocked.ready)
        assertEquals(ScheduledBackupBlockReason.DRIVE_DISCONNECTED, blocked.blockReason)
        assertTrue(ready.ready)
        assertNull(ready.blockReason)
    }

    @Test
    fun documentTreeDestinationRequiresConfiguredTreeButNotDrive() {
        val blocked = evaluateScheduledBackupReadiness(
            githubAuthenticated = true,
            destination = StorageDestination.DOCUMENT_TREE,
            driveAuthenticated = true,
            documentTreeConfigured = false,
        )
        val ready = evaluateScheduledBackupReadiness(
            githubAuthenticated = true,
            destination = StorageDestination.DOCUMENT_TREE,
            driveAuthenticated = false,
            documentTreeConfigured = true,
        )

        assertFalse(blocked.ready)
        assertEquals(ScheduledBackupBlockReason.DOCUMENT_TREE_MISSING, blocked.blockReason)
        assertTrue(ready.ready)
        assertNull(ready.blockReason)
    }

    @Test
    fun runStatusSeparatesNoRepositoriesBlockedAndQueuedOutcomes() {
        val noRepositories = scheduledBackupRunStatus(
            completedAtEpochMs = 100L,
            repositoryCount = 0,
        )
        val blocked = scheduledBackupRunStatus(
            completedAtEpochMs = 200L,
            repositoryCount = 4,
            readiness = ScheduledBackupReadiness(
                ready = false,
                blockReason = ScheduledBackupBlockReason.DRIVE_DISCONNECTED,
            ),
        )
        val queued = scheduledBackupRunStatus(
            completedAtEpochMs = 300L,
            repositoryCount = 3,
            readiness = ScheduledBackupReadiness(ready = true),
        )

        assertEquals(ScheduledBackupRunOutcome.SKIPPED_NO_REPOSITORIES, noRepositories.outcome)
        assertEquals(0, noRepositories.repositoryCount)
        assertNull(noRepositories.blockReason)

        assertEquals(ScheduledBackupRunOutcome.SKIPPED_NOT_READY, blocked.outcome)
        assertEquals(4, blocked.repositoryCount)
        assertEquals(ScheduledBackupBlockReason.DRIVE_DISCONNECTED, blocked.blockReason)

        assertEquals(ScheduledBackupRunOutcome.QUEUED, queued.outcome)
        assertEquals(3, queued.repositoryCount)
        assertNull(queued.blockReason)
    }

    @Test
    fun runStatusNormalizesNegativeRepositoryCounts() {
        val status = scheduledBackupRunStatus(
            completedAtEpochMs = 400L,
            repositoryCount = -5,
        )

        assertEquals(ScheduledBackupRunOutcome.SKIPPED_NO_REPOSITORIES, status.outcome)
        assertEquals(0, status.repositoryCount)
    }
}
