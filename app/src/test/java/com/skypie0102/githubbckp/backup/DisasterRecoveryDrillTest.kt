package com.skypie0102.githubbckp.backup

import org.junit.Assert.assertEquals
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
}
