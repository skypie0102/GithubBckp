package com.skypie0102.githubbckp.ui

import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.mirror.MirrorAttemptStatus
import com.skypie0102.githubbckp.worker.BackupCadence
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupHealthPresentationTest {
    @Test
    fun selectedRepositoryWithoutMirrorNeedsAttention() {
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(1L)),
            mirrors = emptyList(),
            scheduleEnabled = false,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        assertEquals(RepositoryBackupHealthState.NEVER_BACKED_UP, summary.repositories.single().state)
        assertEquals(0, summary.verifiedCount)
        assertEquals(1, summary.attentionCount)
    }

    @Test
    fun unchangedRepositoryUsesLastCheckedForFreshness() {
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(1L)),
            mirrors = listOf(
                mirror(
                    id = 1L,
                    checkedAt = NOW - hours(2),
                    changedAt = NOW - hours(400),
                ),
            ),
            scheduleEnabled = true,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        val health = summary.repositories.single()
        assertEquals(RepositoryBackupHealthState.HEALTHY, health.state)
        assertEquals(NOW - hours(2), health.latestCheckedAtEpochMs)
        assertEquals(NOW - hours(400), health.latestChangedAtEpochMs)
    }

    @Test
    fun remoteRefsMismatchMarksMirrorAsUpdateAvailable() {
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(1L)),
            mirrors = listOf(mirror(1L)),
            scheduleEnabled = false,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
            remoteRefsDigests = mapOf(1L to "new-refs"),
        )

        assertEquals(
            RepositoryBackupHealthState.UPDATE_AVAILABLE,
            summary.repositories.single().state,
        )
        assertEquals(1, summary.updateAvailableCount)
        assertEquals(listOf(1L), summary.updateAvailableRepositories.map { it.repositoryId })
    }

    @Test
    fun matchingRemoteRefsKeepMirrorHealthy() {
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(1L)),
            mirrors = listOf(mirror(1L)),
            scheduleEnabled = false,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
            remoteRefsDigests = mapOf(1L to "refs"),
        )

        assertEquals(
            RepositoryBackupHealthState.HEALTHY,
            summary.repositories.single().state,
        )
    }

    @Test
    fun dailyScheduleMarksMirrorStaleAfterTwoCadenceWindowsWithoutCheck() {
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(1L)),
            mirrors = listOf(mirror(1L, checkedAt = NOW - hours(49))),
            scheduleEnabled = true,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        assertEquals(RepositoryBackupHealthState.STALE, summary.repositories.single().state)
    }

    @Test
    fun activeAttemptIsUpdating() {
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(1L)),
            mirrors = listOf(
                mirror(1L).copy(lastAttemptStatus = MirrorAttemptStatus.UPDATING.name),
            ),
            scheduleEnabled = false,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        assertEquals(RepositoryBackupHealthState.UPDATING, summary.repositories.single().state)
    }

    @Test
    fun failedAttemptKeepsExistingMirrorButShowsFailure() {
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(1L)),
            mirrors = listOf(
                mirror(1L).copy(
                    lastAttemptStatus = MirrorAttemptStatus.FAILED.name,
                    lastError = "network unavailable",
                ),
            ),
            scheduleEnabled = false,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        val health = summary.repositories.single()
        assertEquals(RepositoryBackupHealthState.FAILED, health.state)
        assertEquals("network unavailable", health.errorMessage)
        assertEquals(1, summary.verifiedCount)
    }

    @Test
    fun successfulMetadataWithoutArchiveIsMissing() {
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(1L)),
            mirrors = listOf(
                mirror(1L).copy(
                    archiveUri = null,
                    archiveSizeBytes = null,
                    archiveSha256 = null,
                ),
            ),
            scheduleEnabled = false,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        assertEquals(RepositoryBackupHealthState.MISSING, summary.repositories.single().state)
    }

    @Test
    fun unavailableSelectedRepositoryIsBlockedAndStillVisible() {
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(1L).copy(isAvailable = false)),
            mirrors = listOf(mirror(1L)),
            scheduleEnabled = false,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        assertEquals(1, summary.selectedCount)
        assertEquals(RepositoryBackupHealthState.BLOCKED, summary.repositories.single().state)
        assertEquals(1, summary.verifiedCount)
    }

    @Test
    fun globalReadinessProblemBlocksSelectedRepositoryWithoutClaimingBackupExists() {
        val summary = summarizeBackupHealth(
            repositories = listOf(repository(1L)),
            mirrors = emptyList(),
            scheduleEnabled = false,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
            globalBlockMessage = "Backup folder unavailable",
        )

        assertEquals(RepositoryBackupHealthState.BLOCKED, summary.repositories.single().state)
        assertEquals(0, summary.verifiedCount)
    }

    @Test
    fun unselectedRepositoriesAreExcluded() {
        val summary = summarizeBackupHealth(
            repositories = listOf(
                repository(1L, selected = true),
                repository(2L, selected = false),
            ),
            mirrors = listOf(mirror(1L), mirror(2L)),
            scheduleEnabled = false,
            cadence = BackupCadence.DAILY,
            nowEpochMs = NOW,
        )

        assertEquals(listOf(1L), summary.repositories.map { it.repositoryId })
    }

    private fun repository(id: Long, selected: Boolean = true) = RepositoryEntity(
        githubId = id,
        owner = "owner",
        name = "repo-$id",
        defaultBranch = "main",
        isPrivate = true,
        selectedForBackup = selected,
    )

    private fun mirror(
        id: Long,
        checkedAt: Long = NOW - hours(1),
        changedAt: Long = NOW - hours(1),
    ) = MirrorEntity(
        repositoryId = id,
        archiveUri = "content://mirror/$id",
        archiveSizeBytes = 1024L,
        archiveSha256 = "sha256",
        lastCheckedAtEpochMs = checkedAt,
        lastSuccessfulSyncAtEpochMs = checkedAt,
        lastChangedAtEpochMs = changedAt,
        lastAttemptAtEpochMs = checkedAt,
        lastAttemptStatus = MirrorAttemptStatus.COMPLETED.name,
        lastSourceHead = "deadbeef",
        lastRefsDigest = "refs",
    )

    private fun hours(value: Long): Long = value * 60L * 60L * 1000L

    private companion object {
        const val NOW = 2_000_000_000_000L
    }
}
