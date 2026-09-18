package com.skypie0102.githubbckp.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveBackupNotificationManagerTest {
    @Test
    fun progressPercentIsBoundedAndExactForCommitBytes() {
        assertEquals(0, backupProgressPercent(0L, 100L))
        assertEquals(50, backupProgressPercent(50L, 100L))
        assertEquals(99, backupProgressPercent(99L, 100L))
        assertEquals(100, backupProgressPercent(100L, 100L))
        assertEquals(100, backupProgressPercent(150L, 100L))
    }

    @Test
    fun progressPercentRejectsUnknownTotals() {
        assertNull(backupProgressPercent(10L, 0L))
        assertNull(backupProgressPercent(-1L, 100L))
    }
}
