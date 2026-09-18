package com.skypie0102.githubbckp.worker

import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupProblemNotificationPolicyTest {
    @Test
    fun recentCheckKeepsUnchangedMirrorOutOfOverdueList() {
        val overdue = findOverdueBackupRepositories(
            repositories = listOf(repository(1L)),
            mirrors = listOf(
                MirrorEntity(
                    repositoryId = 1L,
                    archiveUri = "content://mirror/1",
                    archiveSizeBytes = 100L,
                    archiveSha256 = "sha",
                    lastCheckedAtEpochMs = NOW - hours(2),
                    lastChangedAtEpochMs = NOW - hours(300),
                ),
            ),
            settings = BackupScheduleSettings(enabled = true, cadence = BackupCadence.DAILY),
            scheduleEnabledAtEpochMs = NOW - hours(100),
            nowEpochMs = NOW,
        )

        assertTrue(overdue.isEmpty())
    }

    @Test
    fun staleLastCheckIsOverdue() {
        val overdue = findOverdueBackupRepositories(
            repositories = listOf(repository(1L)),
            mirrors = listOf(MirrorEntity(repositoryId = 1L, lastCheckedAtEpochMs = NOW - hours(49))),
            settings = BackupScheduleSettings(enabled = true, cadence = BackupCadence.DAILY),
            scheduleEnabledAtEpochMs = NOW - hours(100),
            nowEpochMs = NOW,
        )

        assertEquals(listOf(1L), overdue.map { it.repositoryId })
    }

    @Test
    fun neverBackedUpUsesScheduleEnableTimeAsGracePeriod() {
        val withinGrace = findOverdueBackupRepositories(
            repositories = listOf(repository(1L)),
            mirrors = emptyList(),
            settings = BackupScheduleSettings(enabled = true, cadence = BackupCadence.DAILY),
            scheduleEnabledAtEpochMs = NOW - hours(24),
            nowEpochMs = NOW,
        )
        val overdue = findOverdueBackupRepositories(
            repositories = listOf(repository(1L)),
            mirrors = emptyList(),
            settings = BackupScheduleSettings(enabled = true, cadence = BackupCadence.DAILY),
            scheduleEnabledAtEpochMs = NOW - hours(49),
            nowEpochMs = NOW,
        )

        assertTrue(withinGrace.isEmpty())
        assertEquals(listOf(1L), overdue.map { it.repositoryId })
    }

    @Test
    fun failureNotificationsAreRateLimited() {
        assertTrue(shouldNotifyBackupFailure(null, NOW))
        assertFalse(shouldNotifyBackupFailure(NOW - hours(1), NOW))
        assertTrue(shouldNotifyBackupFailure(NOW - hours(7), NOW))
    }

    private fun repository(id: Long) = RepositoryEntity(
        githubId = id,
        owner = "owner",
        name = "repo-$id",
        defaultBranch = "main",
        isPrivate = false,
        selectedForBackup = true,
    )

    private fun hours(value: Long) = value * 60L * 60L * 1000L

    private companion object {
        const val NOW = 2_000_000_000_000L
    }
}
