package com.skypie0102.githubbckp.worker

enum class ScheduledBackupBlockReason {
    GITHUB_DISCONNECTED,
    DOCUMENT_TREE_MISSING,
    NOTIFICATIONS_DISABLED,
    REPOSITORY_ACCESS_UNAVAILABLE,
}

data class ScheduledBackupReadiness(
    val ready: Boolean,
    val blockReason: ScheduledBackupBlockReason? = null,
)

fun evaluateScheduledBackupReadiness(
    githubAuthenticated: Boolean,
    documentTreeConfigured: Boolean,
    notificationsReady: Boolean,
): ScheduledBackupReadiness {
    if (!githubAuthenticated) {
        return ScheduledBackupReadiness(
            ready = false,
            blockReason = ScheduledBackupBlockReason.GITHUB_DISCONNECTED,
        )
    }
    if (!documentTreeConfigured) {
        return ScheduledBackupReadiness(
            ready = false,
            blockReason = ScheduledBackupBlockReason.DOCUMENT_TREE_MISSING,
        )
    }
    if (!notificationsReady) {
        return ScheduledBackupReadiness(
            ready = false,
            blockReason = ScheduledBackupBlockReason.NOTIFICATIONS_DISABLED,
        )
    }
    return ScheduledBackupReadiness(ready = true)
}

fun scheduledBackupRunStatus(
    completedAtEpochMs: Long,
    repositoryCount: Int,
    scheduledRunId: String? = null,
    readiness: ScheduledBackupReadiness? = null,
): ScheduledBackupRunStatus {
    val normalizedCount = repositoryCount.coerceAtLeast(0)
    val normalizedRunId = scheduledRunId?.trim()?.takeIf { it.isNotEmpty() }
    if (normalizedCount == 0) {
        return ScheduledBackupRunStatus(
            completedAtEpochMs = completedAtEpochMs,
            outcome = ScheduledBackupRunOutcome.SKIPPED_NO_REPOSITORIES,
            scheduledRunId = normalizedRunId,
        )
    }
    if (readiness != null && !readiness.ready) {
        return ScheduledBackupRunStatus(
            completedAtEpochMs = completedAtEpochMs,
            outcome = ScheduledBackupRunOutcome.SKIPPED_NOT_READY,
            repositoryCount = normalizedCount,
            blockReason = readiness.blockReason,
            scheduledRunId = normalizedRunId,
        )
    }
    return ScheduledBackupRunStatus(
        completedAtEpochMs = completedAtEpochMs,
        outcome = ScheduledBackupRunOutcome.QUEUED,
        repositoryCount = normalizedCount,
        scheduledRunId = normalizedRunId,
    )
}
