package com.skypie0102.githubbckp.backup

import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.storage.StorageDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupAuditTest {
    @Test
    fun serializesVerifiedCompletedBackupAndRetentionState() {
        val repository = RepositoryEntity(
            githubId = 42,
            owner = "octo",
            name = "demo",
            defaultBranch = "main",
            isPrivate = true,
        )
        val backup = BackupEntity(
            id = 7,
            repositoryId = 42,
            type = BackupType.GIT_MIRROR,
            status = BackupStatus.COMPLETED,
            startedAtEpochMs = 100,
            completedAtEpochMs = 200,
            checksumSha256 = "abc123",
            storageProvider = StorageDestination.DOCUMENT_TREE,
            remoteFileId = "content://backup/7",
            remoteFileName = "octo-demo.mirror.zip",
            remoteSizeBytes = 1234,
            remoteChecksumMd5 = "deadbeef",
            remoteDeletedAtEpochMs = 300,
            warningMessage = "discussion publication not implemented",
        )

        val snapshot = backup.toBackupAuditSnapshot(repository)
        val json = snapshot.toBackupAuditJson(generatedAtEpochMs = 999)

        assertEquals(1, json.getInt("formatVersion"))
        assertEquals("github-backup-artifact-audit", json.getString("reportType"))
        assertEquals(999, json.getLong("generatedAtEpochMs"))
        assertEquals("octo/demo", json.getJSONObject("repository").getString("currentKnownFullName"))
        assertEquals("GIT_MIRROR", json.getJSONObject("backup").getString("type"))
        assertEquals("COMPLETED", json.getJSONObject("backup").getString("status"))
        assertEquals("DELETED_BY_RETENTION", json.getJSONObject("storage").getString("remoteState"))
        assertEquals(
            "PERSISTED_VERIFIED_HISTORY",
            json.getJSONObject("integrityVerification").getString("state"),
        )
        assertEquals("abc123", json.getJSONObject("integrityVerification").getString("artifactSha256"))
        assertTrue(snapshot.backupAuditReportFileName().contains("octo-demo-backup-7"))
    }

    @Test
    fun preservesUnknownRepositoryMetadataWithoutInventingIdentity() {
        val backup = BackupEntity(
            id = 9,
            repositoryId = 88,
            type = BackupType.SOURCE_ARCHIVE,
            status = BackupStatus.COMPLETED,
            startedAtEpochMs = 10,
            completedAtEpochMs = 20,
            checksumSha256 = "sha",
        )

        val snapshot = backup.toBackupAuditSnapshot(repository = null)
        val json = snapshot.toBackupAuditJson(generatedAtEpochMs = 30)
        val repository = json.getJSONObject("repository")

        assertEquals(88, repository.getLong("githubId"))
        assertTrue(repository.isNull("currentKnownFullName"))
        assertEquals("unavailable", repository.getString("metadataSource"))
        assertEquals("UNKNOWN", json.getJSONObject("storage").getString("remoteState"))
        assertTrue(snapshot.backupAuditReportFileName().startsWith("repository-88-backup-9"))
    }
}
