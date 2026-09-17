package com.skypie0102.githubbckp.worker

import com.skypie0102.githubbckp.backup.BackupStatus
import com.skypie0102.githubbckp.backup.BackupType
import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupProblemNotificationPolicyTest {
    @Test
    fun `daily schedule marks verified backup overdue after two cadence windows`() {
        val overdue = findOverdueBackupRepositories(
            repositories = listOf(repository(1L)),
            backups = listOf(completed(1L, NOW - hours(49))),
            settings = enabledSettings(BackupCadence.DAILY),
            scheduleEnabledAtEpochMs = NOW - hours(100),
            nowEpochMs = NOW,
        )

        assertEquals(listOf(1L), overdue.map { it.repositoryId })
    }

    @Test
    fun `legacy source snapshot does not suppress mirror overdue alert`() {
        val legacySnapshot = completed(1L, NOW - hours(1)).copy(type = BackupType.SOURCE_ARCHIVE)
        val overdue = findOverdueBackupRepositories(
            repositories = listOf(repository(1L)),
            backups = listOf(legacySnapshot),
            settings = enabledSettings(BackupCadence.DAILY),
            scheduleEnabledAtEpochMs = NOW - hours(100),
            nowEpochMs = NOW,
        )

        assertEquals(listOf(1L), overdue.map { it.repositoryId })
    }

    @Test
    fun `weekly schedule does not mark recent backup overdue`() {
        val overdue = findOverdueBackupRepositories(
            repositories = listOf(repository(1L)),
            backups = listOf(completed(1L, NOW - hours(13L * 24L))),
            settings = enabledSettings(BackupCadence.WEEKLY),
            scheduleEnabledAtEpochMs = NOW - hours(30L * 24L),
            nowEpochMs = NOW,
        )

        assertTrue(overdue.isEmpty())
    }

    @Test
    fun `never backed up repository becomes overdue only after grace window`() {
        val settings = enabledSettings(BackupCadence.DAILY)
        val beforeWindow = findOverdueBackupRepositories(
            repositories = listOf(repository(1L)),
            backups = emptyList(),
            settings = settings,
            scheduleEnabledAtEpochMs = NOW - hours(47),
            nowEpochMs = NOW,
        )
        val afterWindow = findOverdueBackupRepositories(
            repositories = listOf(repository(1L)),
            backups = emptyList(),
            settings = settings,
            scheduleEnabledAtEpochMs = NOW - hours(49),
            nowEpochMs = NOW,
        )

        assertTrue(beforeWindow.isEmpty())
        assertEquals(listOf(1L), afterWindow.map { it.repositoryId })
    }

    @Test
    fun `unavailable and unselected repositories never become overdue`() {
        val overdue = findOverdueBackupRepositories(
            repositories = listOf(
                repository(1L, selected = false),
                repository(2L, available = false),
            ),
            backups = emptyList(),
            settings = enabledSettings(BackupCadence.DAILY),
            scheduleEnabledAtEpochMs = NOW - hours(100),
            nowEpochMs = NOW,
        )

        assertTrue(overdue.isEmpty())
    }

    @Test
    fun `superseded completion does not protect an overdue repository`() {
        val superseded = completed(1L, NOW - hours(1)).copy(remoteDeletedAtEpochMs = NOW - hours(1))
        val overdue = findOverdueBackupRepositories(
            repositories = listOf(repository(1L)),
            backups = listOf(superseded),
            settings = enabledSettings(BackupCadence.DAILY),
            scheduleEnabledAtEpochMs = NOW - hours(100),
            nowEpochMs = NOW,
        )

        assertEquals(listOf(1L), overdue.map { it.repositoryId })
    }

    @Test
    fun `failure notifications are rate limited per policy window`() {
        assertTrue(shouldNotifyBackupFailure(null, NOW))
        assertFalse(shouldNotifyBackupFailure(NOW - hours(5), NOW))
        assertTrue(shouldNotifyBackupFailure(NOW - hours(6), NOW))
    }

    private fun enabledSettings(cadence: BackupCadence) = BackupScheduleSettings(
        enabled = true,
        cadence = cadence,
    )

    private fun repository(
        id: Long,
        selected: Boolean = true,
        available: Boolean = true,
    ) = RepositoryEntity(
        githubId = id,
        owner = "owner",
        name = "repo-$id",
        defaultBranch = "main",
        isPrivate = true,
        selectedForBackup = selected,
        isAvailable = available,
    )

    private fun completed(repositoryId: Long, completedAt: Long) = BackupEntity(
        id = repositoryId,
        repositoryId = repositoryId,
        type = BackupType.GIT_MIRROR,
        status = BackupStatus.COMPLETED,
        startedAtEpochMs = completedAt - hours(1),
        completedAtEpochMs = completedAt,
        checksumSha256 = "sha256",
    )

    private fun hours(value: Long): Long = value * 60L * 60L * 1000L

    private companion object {
        const val NOW = 2_000_000_000_000L
    }
}
