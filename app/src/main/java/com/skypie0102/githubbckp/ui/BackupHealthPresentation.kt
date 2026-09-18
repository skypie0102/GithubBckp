package com.skypie0102.githubbckp.ui

import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.mirror.MirrorAttemptStatus
import com.skypie0102.githubbckp.worker.BackupCadence

enum class RepositoryBackupHealthState {
    HEALTHY,
    UPDATE_AVAILABLE,
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
    val hasMirror: Boolean = false,
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
    val verifiedCount: Int = repositories.count { it.hasMirror }
    val updateAvailableCount: Int = repositories.count {
        it.state == RepositoryBackupHealthState.UPDATE_AVAILABLE
    }
    val protectedCount: Int = repositories.count {
        it.state == RepositoryBackupHealthState.HEALTHY ||
            it.state == RepositoryBackupHealthState.UPDATE_AVAILABLE ||
            it.state == RepositoryBackupHealthState.UPDATING
    }
    val attentionCount: Int = repositories.count {
        it.state !in setOf(
            RepositoryBackupHealthState.HEALTHY,
            RepositoryBackupHealthState.UPDATING,
        )
    }
    val updateAvailableRepositories: List<RepositoryBackupHealth> = repositories.filter {
        it.state == RepositoryBackupHealthState.UPDATE_AVAILABLE
    }
    val problemRepositories: List<RepositoryBackupHealth> = repositories.filter {
        it.state !in setOf(
            RepositoryBackupHealthState.HEALTHY,
            RepositoryBackupHealthState.UPDATE_AVAILABLE,
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
    globalBlockMessage: String? = null,
    remoteRefsDigests: Map<Long, String> = emptyMap(),
): BackupHealthSummary {
    val selectedRepositories = repositories
        .filter { it.selectedForBackup }
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
            val remoteRefsDigest = remoteRefsDigests[repository.githubId]

            val repositoryBlockMessage = if (!repository.isAvailable) {
                "Repository cannot be read with the current GitHub token."
            } else {
                null
            }
            val effectiveBlockMessage = repositoryBlockMessage ?: globalBlockMessage

            val state = when {
                attemptStatus == MirrorAttemptStatus.CHECKING.name ||
                    attemptStatus == MirrorAttemptStatus.UPDATING.name ->
                    RepositoryBackupHealthState.UPDATING
                effectiveBlockMessage != null ->
                    RepositoryBackupHealthState.BLOCKED
                mirror == null -> RepositoryBackupHealthState.NEVER_BACKED_UP
                !hasPersistedMirror && hasPreviouslySucceeded ->
                    RepositoryBackupHealthState.MISSING
                attemptStatus == MirrorAttemptStatus.BLOCKED.name ->
                    RepositoryBackupHealthState.BLOCKED
                attemptStatus == MirrorAttemptStatus.FAILED.name ->
                    RepositoryBackupHealthState.FAILED
                !mirror.lastWarning.isNullOrBlank() ->
                    RepositoryBackupHealthState.BLOCKED
                !hasPersistedMirror ->
                    RepositoryBackupHealthState.NEVER_BACKED_UP
                remoteRefsDigest != null &&
                    mirror.lastRefsDigest != null &&
                    remoteRefsDigest != mirror.lastRefsDigest ->
                    RepositoryBackupHealthState.UPDATE_AVAILABLE
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
                hasMirror = hasPersistedMirror,
                latestCheckedAtEpochMs = mirror?.lastCheckedAtEpochMs,
                latestChangedAtEpochMs = mirror?.lastChangedAtEpochMs,
                latestAttemptAtEpochMs = mirror?.lastAttemptAtEpochMs,
                archiveSizeBytes = mirror?.archiveSizeBytes,
                sourceHead = mirror?.lastSourceHead,
                warningMessage = effectiveBlockMessage ?: mirror?.lastWarning,
                errorMessage = mirror?.lastError,
            )
        },
    )
}

private const val MILLIS_PER_HOUR = 60L * 60L * 1000L
