package com.skypie0102.githubbckp.backup

import com.skypie0102.githubbckp.data.local.BackupEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRetentionManagerTest {
    private fun backup(id: Long) = BackupEntity(
        id = id,
        repositoryId = 1,
        type = BackupType.GIT_MIRROR,
        status = BackupStatus.COMPLETED,
        startedAtEpochMs = id,
    )

    @Test
    fun keepAllNeverSelectsAnythingForDeletion() {
        assertTrue(retentionCandidates(listOf(backup(3), backup(2), backup(1)), 0).isEmpty())
    }

    @Test
    fun keepsNewestNAndReturnsOlderCandidates() {
        val candidates = retentionCandidates(
            backups = listOf(backup(5), backup(4), backup(3), backup(2), backup(1)),
            keepCount = 3,
        )
        assertEquals(listOf(2L, 1L), candidates.map { it.id })
    }
}
