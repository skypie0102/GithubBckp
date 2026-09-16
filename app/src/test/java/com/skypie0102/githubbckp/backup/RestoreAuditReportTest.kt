package com.skypie0102.githubbckp.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreAuditReportTest {
    @Test
    fun serializesVerifiedModuleCountsAndCapabilities() {
        val record = MirrorRestoreRecord(
            id = "restore-1",
            archiveName = "owner-repo.mirror.zip",
            createdAtEpochMs = 123L,
            refCount = 7,
            referencedObjectsVerified = 5,
            detailsAvailable = true,
            lfsObjectCount = 2,
            wikiRefCount = 1,
            wikiReferencedObjectsVerified = 1,
            releaseCount = 3,
            releaseAssetCount = 4,
            issueCount = 6,
            pullRequestCount = 2,
            issueCommentCount = 8,
            reviewCommentCount = 5,
            reviewCount = 2,
        )

        val json = record.toAuditSnapshot().toAuditJson(generatedAtEpochMs = 456L)

        assertEquals(1, json.getInt("formatVersion"))
        assertEquals(456L, json.getLong("generatedAtEpochMs"))
        assertEquals(7, json.getJSONObject("mainGit").getInt("refCount"))
        assertEquals(2, json.getJSONObject("gitLfs").getInt("objectCount"))
        assertTrue(json.getJSONObject("wiki").getBoolean("present"))
        assertEquals(4, json.getJSONObject("releases").getInt("assetCount"))
        assertEquals(6, json.getJSONObject("discussions").getInt("issueCount"))
        assertEquals("not automated", json.getJSONObject("discussions").getString("githubPublication"))
        assertEquals("owner-repo-restore-audit.json", record.auditReportFileName())
    }

    @Test
    fun legacyMetadataDoesNotClaimOptionalModulesWereAbsent() {
        val record = MirrorRestoreRecord(
            id = "legacy",
            archiveName = "legacy.zip",
            createdAtEpochMs = 1L,
            refCount = 1,
            referencedObjectsVerified = 1,
            detailsAvailable = false,
        )

        val json = record.toAuditSnapshot().toAuditJson(generatedAtEpochMs = 2L)

        assertFalse(json.getBoolean("moduleDetailsAvailable"))
        assertFalse(json.getJSONObject("gitLfs").getBoolean("locallyValidated"))
        assertTrue(json.getJSONArray("notes").length() == 1)
    }
}
