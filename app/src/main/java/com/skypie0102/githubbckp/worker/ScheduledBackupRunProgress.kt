package com.skypie0102.githubbckp.worker

import com.skypie0102.githubbckp.backup.BackupStatus
import com.skypie0102.githubbckp.data.local.BackupEntity

data class ScheduledBackupRunProgress(
    val scheduledRunId: String,
    val expectedRepositoryCount: Int,
    val observedRepositoryCount: Int,
    val activeCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val cancelledCount: Int,
    val unobservedCount: Int,
) {
    val terminalCount: Int
        get() = completedCount + failedCount + cancelledCount

    val fullyObserved: Boolean
        get() = unobservedCount == 0

    val finished: Boolean
        get() = fullyObserved && activeCount == 0
}

fun summarizeScheduledBackupRun(
    status: ScheduledBackupRunStatus,
    backups: List<BackupEntity>,
): ScheduledBackupRunProgress? {
    val runId = status.scheduledRunId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (status.outcome != ScheduledBackupRunOutcome.QUEUED) return null

    val latestByRepository = backups
        .asSequence()
        .filter { it.scheduledRunId == runId }
        .groupBy { it.repositoryId }
        .values
        .mapNotNull { rows ->
            rows.maxWithOrNull(
                compareBy<BackupEntity> { it.startedAtEpochMs }
                    .thenBy { it.id },
            )
        }

    var active = 0
    var completed = 0
    var failed = 0
    var cancelled = 0
    latestByRepository.forEach { backup ->
        when (backup.status) {
            BackupStatus.COMPLETED -> completed += 1
            BackupStatus.FAILED -> failed += 1
            BackupStatus.CANCELLED -> cancelled += 1
            BackupStatus.QUEUED,
            BackupStatus.DOWNLOADING,
            BackupStatus.PACKAGING,
            BackupStatus.CHECKSUM,
            BackupStatus.UPLOADING,
            BackupStatus.VERIFYING,
            -> active += 1
        }
    }

    val expected = status.repositoryCount.coerceAtLeast(0)
    val observed = latestByRepository.size
    return ScheduledBackupRunProgress(
        scheduledRunId = runId,
        expectedRepositoryCount = expected,
        observedRepositoryCount = observed,
        activeCount = active,
        completedCount = completed,
        failedCount = failed,
        cancelledCount = cancelled,
        unobservedCount = (expected - observed).coerceAtLeast(0),
    )
}
