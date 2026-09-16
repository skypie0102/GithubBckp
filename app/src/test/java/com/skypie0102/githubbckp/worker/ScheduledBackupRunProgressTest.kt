package com.skypie0102.githubbckp.worker

import com.skypie0102.githubbckp.backup.BackupStatus
import com.skypie0102.githubbckp.backup.BackupType
import com.skypie0102.githubbckp.data.local.BackupEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledBackupRunProgressTest {
    @Test
    fun returnsNullForSkippedOrUncorrelatedRunStatus() {
        val skipped = ScheduledBackupRunStatus(
            completedAtEpochMs = 1L,
            outcome = ScheduledBackupRunOutcome.SKIPPED_NO_REPOSITORIES,
            scheduledRunId = "run-1",
        )
        val uncorrelated = ScheduledBackupRunStatus(
            completedAtEpochMs = 2L,
            outcome = ScheduledBackupRunOutcome.QUEUED,
            repositoryCount = 2,
            scheduledRunId = null,
        )

        assertNull(summarizeScheduledBackupRun(skipped, emptyList()))
        assertNull(summarizeScheduledBackupRun(uncorrelated, emptyList()))
    }

    @Test
    fun countsTerminalActiveAndUnobservedRepositories() {
        val status = queuedStatus(expected = 5)
        val progress = summarizeScheduledBackupRun(
            status,
            listOf(
                backup(id = 1, repositoryId = 10, status = BackupStatus.COMPLETED),
                backup(id = 2, repositoryId = 20, status = BackupStatus.FAILED),
                backup(id = 3, repositoryId = 30, status = BackupStatus.UPLOADING),
                backup(id = 4, repositoryId = 40, status = BackupStatus.CANCELLED),
                backup(id = 5, repositoryId = 99, status = BackupStatus.COMPLETED, runId = "other-run"),
            ),
        ) ?: error("Expected progress")

        assertEquals(5, progress.expectedRepositoryCount)
        assertEquals(4, progress.observedRepositoryCount)
        assertEquals(1, progress.completedCount)
        assertEquals(1, progress.failedCount)
        assertEquals(1, progress.cancelledCount)
        assertEquals(1, progress.activeCount)
        assertEquals(1, progress.unobservedCount)
        assertEquals(3, progress.terminalCount)
        assertFalse(progress.fullyObserved)
        assertFalse(progress.finished)
    }

    @Test
    fun latestBackupRowWinsWhenRepositoryHasMultipleRows() {
        val status = queuedStatus(expected = 2)
        val progress = summarizeScheduledBackupRun(
            status,
            listOf(
                backup(id = 10, repositoryId = 10, status = BackupStatus.FAILED, startedAt = 100L),
                backup(id = 11, repositoryId = 10, status = BackupStatus.COMPLETED, startedAt = 200L),
                backup(id = 12, repositoryId = 20, status = BackupStatus.COMPLETED, startedAt = 150L),
            ),
        ) ?: error("Expected progress")

        assertEquals(2, progress.observedRepositoryCount)
        assertEquals(2, progress.completedCount)
        assertEquals(0, progress.failedCount)
        assertEquals(0, progress.activeCount)
        assertTrue(progress.fullyObserved)
        assertTrue(progress.finished)
    }

    @Test
    fun observedRowsBeyondExpectedCountDoNotProduceNegativeUnobservedCount() {
        val status = queuedStatus(expected = 1)
        val progress = summarizeScheduledBackupRun(
            status,
            listOf(
                backup(id = 1, repositoryId = 10, status = BackupStatus.COMPLETED),
                backup(id = 2, repositoryId = 20, status = BackupStatus.COMPLETED),
            ),
        ) ?: error("Expected progress")

        assertEquals(2, progress.observedRepositoryCount)
        assertEquals(0, progress.unobservedCount)
        assertTrue(progress.finished)
    }

    private fun queuedStatus(expected: Int) = ScheduledBackupRunStatus(
        completedAtEpochMs = 123L,
        outcome = ScheduledBackupRunOutcome.QUEUED,
        repositoryCount = expected,
        scheduledRunId = "run-1",
    )

    private fun backup(
        id: Long,
        repositoryId: Long,
        status: BackupStatus,
        startedAt: Long = id,
        runId: String = "run-1",
    ) = BackupEntity(
        id = id,
        repositoryId = repositoryId,
        type = BackupType.GIT_MIRROR,
        status = status,
        startedAtEpochMs = startedAt,
        scheduledRunId = runId,
    )
}
