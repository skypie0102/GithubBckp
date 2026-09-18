package com.skypie0102.githubbckp.ui

import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.mirror.MirrorAttemptStatus
import com.skypie0102.githubbckp.worker.BackupCadence

enum class RepositoryBackupHealthState {
    HEALTHY,
    UPDATING,
    FAILED,
    STALE,
    MISSING,
    BLOCKED,
    NEVER_BACKED_UP,
}

data class RepositoryBackupHealth(
    val repositoryId: Long,
    val owner: String,
    val name: String,
    val state: RepositoryBackupHealthState,
    val latestCheckedAtEpochMs: Long? = null,
    val latestChangedAtEpochMs: Long? = null,
    val latestAttemptAtEpochMs: Long? = null,
    val archiveSizeBytes: Long? = null,
    val sourceHead: String? = null,
    val warningMessage: String? = null,
    val errorMessage: String? = null,
) {
    val fullName: String = "$owner/$name"
}

data class BackupHealthSummary(
    val repositories: List<RepositoryBackupHealth> = emptyList(),
) {
    val selectedCount: Int = repositories.size
    val verifiedCount: Int = repositories.count {
        it.state != RepositoryBackupHealthState.NEVER_BACKED_UP &&
            it.state != RepositoryBackupHealthState.MISSING
    }
    val protectedCount: Int = repositories.count {
        it.state == RepositoryBackupHealthState.HEALTHY ||
            it.state == RepositoryBackupHealthState.UPDATING
    }
    val attentionCount: Int = repositories.count {
        it.state !in setOf(
            RepositoryBackupHealthState.HEALTHY,
            RepositoryBackupHealthState.UPDATING,
        )
    }
    val problemRepositories: List<RepositoryBackupHealth> = repositories.filter {
        it.state !in setOf(
            RepositoryBackupHealthState.HEALTHY,
            RepositoryBackupHealthState.UPDATING,
        )
    }
}

fun summarizeBackupHealth(
    repositories: List<RepositoryEntity>,
    mirrors: List<MirrorEntity>,
    scheduleEnabled: Boolean,
    cadence: BackupCadence,
    nowEpochMs: Long,
): BackupHealthSummary {
    val selectedRepositories = repositories
        .filter { it.isAvailable && it.selectedForBackup }
        .sortedWith(compareBy<RepositoryEntity> { it.owner.lowercase() }.thenBy { it.name.lowercase() })
    if (selectedRepositories.isEmpty()) return BackupHealthSummary()

    val mirrorsByRepository = mirrors.associateBy { it.repositoryId }
    val freshnessWindowMs = cadence.repeatHours * 2L * MILLIS_PER_HOUR

    return BackupHealthSummary(
        repositories = selectedRepositories.map { repository ->
            val mirror = mirrorsByRepository[repository.githubId]
            val attemptStatus = mirror?.lastAttemptStatus
            val hasPersistedMirror = !mirror?.archiveUri.isNullOrBlank() &&
                !mirror?.archiveSha256.isNullOrBlank() &&
                (mirror?.archiveSizeBytes ?: 0L) > 0L
            val hasPreviouslySucceeded = mirror?.lastSuccessfulSyncAtEpochMs != null

            val state = when {
                mirror == null -> RepositoryBackupHealthState.NEVER_BACKED_UP
                attemptStatus == MirrorAttemptStatus.CHECKING.name ||
                    attemptStatus == MirrorAttemptStatus.UPDATING.name ->
                    RepositoryBackupHealthState.UPDATING
                !hasPersistedMirror && hasPreviouslySucceeded ->
                    RepositoryBackupHealthState.MISSING
                attemptStatus == MirrorAttemptStatus.FAILED.name ->
                    RepositoryBackupHealthState.FAILED
                !mirror.lastWarning.isNullOrBlank() ->
                    RepositoryBackupHealthState.BLOCKED
                !hasPersistedMirror ->
                    RepositoryBackupHealthState.NEVER_BACKED_UP
                scheduleEnabled &&
                    mirror.lastCheckedAtEpochMs != null &&
                    nowEpochMs - mirror.lastCheckedAtEpochMs > freshnessWindowMs ->
                    RepositoryBackupHealthState.STALE
                else -> RepositoryBackupHealthState.HEALTHY
            }

            RepositoryBackupHealth(
                repositoryId = repository.githubId,
                owner = repository.owner,
                name = repository.name,
                state = state,
                latestCheckedAtEpochMs = mirror?.lastCheckedAtEpochMs,
                latestChangedAtEpochMs = mirror?.lastChangedAtEpochMs,
                latestAttemptAtEpochMs = mirror?.lastAttemptAtEpochMs,
                archiveSizeBytes = mirror?.archiveSizeBytes,
                sourceHead = mirror?.lastSourceHead,
                warningMessage = mirror?.lastWarning,
                errorMessage = mirror?.lastError,
            )
        },
    )
}

private const val MILLIS_PER_HOUR = 60L * 60L * 1000L
