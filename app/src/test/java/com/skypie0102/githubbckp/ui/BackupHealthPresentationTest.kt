package com.skypie0102.githubbckp.ui

import com.skypie0102.githubbckp.backup.MirrorStatus
import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.worker.BackupCadence
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupHealthPresentationTest {
    private val repository = RepositoryEntity(
        githubId = 1,
        owner = "owner",
        name = "repo",
        defaultBranch = "main",
        isPrivate = true,
        selectedForBackup = true,
    )

    @Test
    fun noSuccessfulMirrorNeedsFirstBackup() {
        assertEquals(
            RepositoryHealth.NEEDS_FIRST_BACKUP,
            repositoryHealth(
                repository = repository,
                mirror = null,
                scheduleEnabled = false,
                cadence = BackupCadence.DAILY,
                nowEpochMs = 1_000,
            ),
        )
    }

    @Test
    fun failedUpdateAfterSuccessIsReportedAsFailed() {
        val mirror = MirrorEntity(
            repositoryId = repository.githubId,
            status = MirrorStatus.FAILED,
            lastSuccessfulAtEpochMs = 1_000,
            lastStartedAtEpochMs = 2_000,
        )
        assertEquals(
            RepositoryHealth.FAILED,
            repositoryHealth(repository, mirror, false, BackupCadence.DAILY, 3_000),
        )
    }

    @Test
    fun dailyMirrorBecomesStaleAfterTwoCadenceWindows() {
        val now = TimeUnit.HOURS.toMillis(72)
        val mirror = MirrorEntity(
            repositoryId = repository.githubId,
            status = MirrorStatus.COMPLETED,
            lastSuccessfulAtEpochMs = 1,
            lastStartedAtEpochMs = 1,
        )
        assertEquals(
            RepositoryHealth.STALE,
            repositoryHealth(repository, mirror, true, BackupCadence.DAILY, now),
        )
    }
}
