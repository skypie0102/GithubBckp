package com.skypie0102.githubbckp.ui

import com.skypie0102.githubbckp.backup.BackupStatus
import com.skypie0102.githubbckp.backup.BackupType
import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.worker.BackupCadence
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupHealthPresentationTest {
    @Test
    fun `selected repository with no verified backup needs attention`() {
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(id = 1L)),
            backups = emptyList(),
            scheduleEnabled = false,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        assertEquals(1, summary.selectedCount)
        assertEquals(1, summary.attentionCount)
        assertEquals(RepositoryBackupHealthState.NEVER_BACKED_UP, summary.repositories.single().state)
    }

    @Test
    fun `unselected repositories are excluded from health summary`() {
        val summary = summarizeBackupHealth(
            repositories = listOf(
                repository(id = 1L, selected = true),
                repository(id = 2L, selected = false),
            ),
            backups = emptyList(),
            scheduleEnabled = false,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        assertEquals(listOf(1L), summary.repositories.map { it.repositoryId })
    }

    @Test
    fun `recent verified backup is protected`() {
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(id = 1L)),
            backups = listOf(completed(id = 10L, repositoryId = 1L, completedAt = NOW - hours(6))),
            scheduleEnabled = true,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        assertEquals(RepositoryBackupHealthState.PROTECTED, summary.repositories.single().state)
        assertEquals(1, summary.protectedCount)
        assertEquals(0, summary.attentionCount)
    }

    @Test
    fun `failed attempt after latest verified backup is failed`() {
        val verifiedAt = NOW - hours(8)
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(id = 1L)),
            backups = listOf(
                completed(id = 10L, repositoryId = 1L, completedAt = verifiedAt),
                BackupEntity(
                    id = 11L,
                    repositoryId = 1L,
                    type = BackupType.GIT_MIRROR,
                    status = BackupStatus.FAILED,
                    startedAtEpochMs = verifiedAt + hours(1),
                    completedAtEpochMs = verifiedAt + hours(2),
                    errorMessage = "network unavailable",
                ),
            ),
            scheduleEnabled = false,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        val health = summary.repositories.single()
        assertEquals(RepositoryBackupHealthState.FAILED, health.state)
        assertEquals("network unavailable", health.errorMessage)
    }

    @Test
    fun `later successful backup clears earlier failure`() {
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(id = 1L)),
            backups = listOf(
                BackupEntity(
                    id = 10L,
                    repositoryId = 1L,
                    type = BackupType.GIT_MIRROR,
                    status = BackupStatus.FAILED,
                    startedAtEpochMs = NOW - hours(12),
                    completedAtEpochMs = NOW - hours(11),
                    errorMessage = "temporary failure",
                ),
                completed(id = 11L, repositoryId = 1L, completedAt = NOW - hours(3)),
            ),
            scheduleEnabled = false,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        assertEquals(RepositoryBackupHealthState.PROTECTED, summary.repositories.single().state)
    }

    @Test
    fun `daily schedule marks backup stale after two cadence windows`() {
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(id = 1L)),
            backups = listOf(completed(id = 10L, repositoryId = 1L, completedAt = NOW - hours(49))),
            scheduleEnabled = true,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        assertEquals(RepositoryBackupHealthState.STALE, summary.repositories.single().state)
    }

    @Test
    fun `staleness is not applied when automatic backups are disabled`() {
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(id = 1L)),
            backups = listOf(completed(id = 10L, repositoryId = 1L, completedAt = NOW - hours(200))),
            scheduleEnabled = false,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        assertEquals(RepositoryBackupHealthState.PROTECTED, summary.repositories.single().state)
    }

    @Test
    fun `completed backup warning remains visible`() {
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(id = 1L)),
            backups = listOf(
                completed(
                    id = 10L,
                    repositoryId = 1L,
                    completedAt = NOW - hours(3),
                    warning = "Wiki preserved but not automatically published",
                ),
            ),
            scheduleEnabled = false,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        assertEquals(RepositoryBackupHealthState.WARNING, summary.repositories.single().state)
        assertEquals(1, summary.protectedCount)
        assertEquals(1, summary.attentionCount)
    }

    @Test
    fun `retention-pruned completion does not count as a current verified artifact`() {
        val pruned = completed(id = 10L, repositoryId = 1L, completedAt = NOW - hours(2)).copy(
            remoteDeletedAtEpochMs = NOW - hours(1),
        )
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(id = 1L)),
            backups = listOf(pruned),
            scheduleEnabled = false,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        assertEquals(RepositoryBackupHealthState.NEVER_BACKED_UP, summary.repositories.single().state)
    }

    private fun repository(id: Long, selected: Boolean = true) = RepositoryEntity(
        githubId = id,
        owner = "owner",
        name = "repo-$id",
        defaultBranch = "main",
        isPrivate = true,
        selectedForBackup = selected,
    )

    private fun completed(
        id: Long,
        repositoryId: Long,
        completedAt: Long,
        warning: String? = null,
    ) = BackupEntity(
        id = id,
        repositoryId = repositoryId,
        type = BackupType.GIT_MIRROR,
        status = BackupStatus.COMPLETED,
        startedAtEpochMs = completedAt - hours(1),
        completedAtEpochMs = completedAt,
        checksumSha256 = "sha256",
        warningMessage = warning,
    )

    private fun hours(value: Long): Long = value * 60L * 60L * 1000L

    private companion object {
        const val NOW = 2_000_000_000_000L
    }
}
