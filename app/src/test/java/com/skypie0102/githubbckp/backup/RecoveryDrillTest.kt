package com.skypie0102.githubbckp.backup

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryDrillTest {
    @Test
    fun `drill store round trips records`() {
        val root = Files.createTempDirectory("recovery-drill-store").toFile()
        try {
            val store = RecoveryDrillStore(root)
            val record = verifiedRecord()

            store.write(record)

            assertEquals(record, store.get(record.id))
            assertEquals(listOf(record), store.list())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `audit report distinguishes automatic and archival modules`() {
        val json = verifiedRecord().toAuditJson()

        assertEquals("github-recovery-drill", json.getString("reportType"))
        assertEquals("VERIFIED", json.getString("status"))
        assertTrue(json.getJSONObject("target").getBoolean("privateRequired"))
        assertEquals("automatic", json.getJSONObject("mainGit").getString("publication"))
        assertEquals("automatic", json.getJSONObject("gitLfs").getString("publication"))
        assertEquals("automatic", json.getJSONObject("releases").getString("publication"))
        assertEquals("archival-only", json.getJSONObject("wiki").getString("publication"))
        assertEquals("archival-only", json.getJSONObject("discussions").getString("publication"))
        assertEquals("never automatic", json.getString("targetDeletion"))
    }

    @Test
    fun `failed drill retains target context for diagnosis`() {
        val root = Files.createTempDirectory("recovery-drill-failed-store").toFile()
        try {
            val store = RecoveryDrillStore(root)
            val record = verifiedRecord().copy(
                status = RecoveryDrillStatus.FAILED,
                verifiedRefCount = 0,
                lfsObjectCount = 0,
                lfsRepresentativeDownloads = 0,
                releaseCount = 0,
                releaseAssetCount = 0,
                message = "remote verification failed",
            )

            store.write(record)
            val restored = checkNotNull(store.get(record.id))

            assertEquals(RecoveryDrillStatus.FAILED, restored.status)
            assertEquals("owner/drill-target", restored.repositoryFullName)
            assertEquals("remote verification failed", restored.message)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun verifiedRecord() = RecoveryDrillRecord(
        id = "drill-1",
        restoreId = "restore-1",
        archiveName = "owner-repo.mirror.zip",
        startedAtEpochMs = 1_000L,
        completedAtEpochMs = 2_000L,
        status = RecoveryDrillStatus.VERIFIED,
        targetKind = RecoveryTargetKind.NEW_REPOSITORY,
        targetInput = "drill-target",
        repositoryFullName = "owner/drill-target",
        repositoryUrl = "https://github.com/owner/drill-target",
        verifiedRefCount = 4,
        lfsObjectCount = 7,
        lfsRepresentativeDownloads = 3,
        releaseCount = 2,
        releaseAssetCount = 5,
        archivalWikiRefCount = 1,
        archivalDiscussionRecordCount = 9,
        message = "Recovery drill verified owner/drill-target",
    )
}
