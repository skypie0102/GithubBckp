package com.skypie0102.githubbckp.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisasterRecoveryDrillTest {
    @Test
    fun `plan distinguishes automatically republished and archival modules`() {
        val record = MirrorRestoreRecord(
            id = "restore-1",
            archiveName = "example.mirror.zip",
            createdAtEpochMs = 1L,
            refCount = 4,
            referencedObjectsVerified = 8,
            lfsObjectCount = 2,
            wikiRefCount = 3,
            releaseCount = 1,
            releaseAssetCount = 2,
            issueCount = 5,
            pullRequestCount = 2,
            issueCommentCount = 3,
            reviewCommentCount = 1,
            reviewCount = 2,
        )

        val plan = record.toDisasterRecoveryDrillPlan()

        assertEquals(
            listOf("Git refs/history", "Git LFS objects", "GitHub releases/assets"),
            plan.automaticallyRepublished,
        )
        assertEquals(
            listOf("GitHub wiki history", "Issue/pull-request discussion metadata"),
            plan.archivalOnly,
        )
    }

    @Test
    fun `plan omits absent optional modules`() {
        val record = MirrorRestoreRecord(
            id = "restore-2",
            archiveName = "minimal.mirror.zip",
            createdAtEpochMs = 1L,
            refCount = 1,
            referencedObjectsVerified = 1,
        )

        val plan = record.toDisasterRecoveryDrillPlan()

        assertEquals(listOf("Git refs/history"), plan.automaticallyRepublished)
        assertEquals(emptyList<String>(), plan.archivalOnly)
    }

    @Test
    fun `drill transaction keys isolate retries and targets from normal recovery`() {
        val restoreId = "1700000000000-123e4567-e89b-12d3-a456-426614174000"
        val first = disasterRecoveryDrillTransactionKey(
            restoreId,
            DisasterRecoveryDrillTarget.NEW_PRIVATE_REPOSITORY,
            "Example-Drill",
        )
        val retry = disasterRecoveryDrillTransactionKey(
            restoreId,
            DisasterRecoveryDrillTarget.NEW_PRIVATE_REPOSITORY,
            " example-drill ",
        )
        val otherTarget = disasterRecoveryDrillTransactionKey(
            restoreId,
            DisasterRecoveryDrillTarget.NEW_PRIVATE_REPOSITORY,
            "Another-Drill",
        )
        val existingTarget = disasterRecoveryDrillTransactionKey(
            restoreId,
            DisasterRecoveryDrillTarget.EXISTING_EMPTY_PRIVATE_REPOSITORY,
            "owner/example-drill",
        )

        assertEquals(first, retry)
        assertNotEquals(restoreId, first)
        assertNotEquals(first, otherTarget)
        assertNotEquals(first, existingTarget)
        assertTrue(Regex("[A-Za-z0-9._-]+").matches(first))
    }
}
