package com.skypie0102.githubbckp.ui

import com.skypie0102.githubbckp.backup.BackupOrigin
import com.skypie0102.githubbckp.backup.BackupStatus
import com.skypie0102.githubbckp.backup.BackupType
import com.skypie0102.githubbckp.data.local.BackupEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupHistoryPresentationTest {
    @Test
    fun manualBackupIsLabeledManual() {
        assertEquals("Manual", backup(origin = BackupOrigin.MANUAL).originDisplayText())
    }

    @Test
    fun scheduledBackupShowsShortNormalizedRunId() {
        assertEquals(
            "Scheduled • run 12345678",
            backup(
                origin = BackupOrigin.SCHEDULED,
                scheduledRunId = " 1234567890 ",
            ).originDisplayText(),
        )
    }

    @Test
    fun scheduledLegacyBackupExplainsMissingRunId() {
        assertEquals(
            "Scheduled • run ID unavailable",
            backup(origin = BackupOrigin.SCHEDULED).originDisplayText(),
        )
        assertEquals(
            "Scheduled • run ID unavailable",
            backup(origin = BackupOrigin.SCHEDULED, scheduledRunId = "   ").originDisplayText(),
        )
    }

    @Test
    fun unknownLegacyOriginStaysUnknown() {
        assertEquals("Origin unknown", backup(origin = null).originDisplayText())
    }

    @Test
    fun shortRunIdRejectsBlankAndKeepsShortValues() {
        assertNull(shortScheduledRunId("  "))
        assertEquals("abc", shortScheduledRunId(" abc "))
    }

    private fun backup(
        origin: BackupOrigin?,
        scheduledRunId: String? = null,
    ): BackupEntity = BackupEntity(
        id = 1,
        repositoryId = 42,
        type = BackupType.GIT_MIRROR,
        status = BackupStatus.COMPLETED,
        startedAtEpochMs = 100,
        origin = origin,
        scheduledRunId = scheduledRunId,
    )
}
