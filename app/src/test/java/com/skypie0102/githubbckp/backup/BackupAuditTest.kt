package com.skypie0102.githubbckp.backup

import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.storage.StorageDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupAuditTest {
    @Test
    fun prefersImmutableBackupTimeRepositorySnapshot() {
        val currentRepository = RepositoryEntity(
            githubId = 42,
            owner = "octo",
            name = "renamed-demo",
            defaultBranch = "develop",
            isPrivate = false,
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
            repositoryOwnerAtBackup = "octo",
            repositoryNameAtBackup = "demo",
            repositoryDefaultBranchAtBackup = "main",
            repositoryPrivateAtBackup = true,
            origin = BackupOrigin.SCHEDULED,
            scheduledRunId = "run-123",
        )

        val snapshot = backup.toBackupAuditSnapshot(currentRepository)
        val json = snapshot.toBackupAuditJson(generatedAtEpochMs = 999)
        val repository = json.getJSONObject("repository")
        val backupJson = json.getJSONObject("backup")

        assertEquals(4, json.getInt("formatVersion"))
        assertEquals("github-backup-artifact-audit", json.getString("reportType"))
        assertEquals(999, json.getLong("generatedAtEpochMs"))
        assertEquals("octo/demo", repository.getString("fullName"))
        assertEquals("octo/demo", backup.repositoryDisplayName(currentRepository))
        assertEquals("octo/demo", backup.repositoryDisplayName(repository = null))
        assertEquals("main", repository.getString("defaultBranch"))
        assertTrue(repository.getBoolean("private"))
        assertEquals("backup-time-snapshot", repository.getString("metadataSource"))
        assertEquals("GIT_MIRROR", backupJson.getString("type"))
        assertEquals("SCHEDULED", backupJson.getString("origin"))
        assertEquals("run-123", backupJson.getString("scheduledRunId"))
        assertEquals("COMPLETED", backupJson.getString("status"))
        assertEquals("DELETED_BY_RETENTION", json.getJSONObject("storage").getString("remoteState"))
        assertEquals(
            "PERSISTED_VERIFIED_HISTORY",
            json.getJSONObject("integrityVerification").getString("state"),
        )
        assertEquals("abc123", json.getJSONObject("integrityVerification").getString("artifactSha256"))
        assertFalse(json.getJSONArray("limitations").toString().contains("current local repository cache"))
        assertFalse(json.getJSONArray("limitations").toString().contains("origin tracking"))
        assertFalse(json.getJSONArray("limitations").toString().contains("predates scheduled-run correlation"))
        assertTrue(json.getJSONArray("limitations").toString().contains("unique-work KEEP"))
        assertTrue(snapshot.backupAuditReportFileName().contains("octo-demo-backup-7"))
    }

    @Test
    fun fallsBackToCurrentCacheForPreV4BackupAndKeepsLegacyOriginUnknown() {
        val currentRepository = RepositoryEntity(
            githubId = 77,
            owner = "octo",
            name = "legacy",
            defaultBranch = "main",
            isPrivate = true,
        )
        val backup = BackupEntity(
            id = 8,
            repositoryId = 77,
            type = BackupType.SOURCE_ARCHIVE,
            status = BackupStatus.COMPLETED,
            startedAtEpochMs = 10,
            completedAtEpochMs = 20,
            checksumSha256 = "sha",
        )

        val json = backup.toBackupAuditSnapshot(currentRepository)
            .toBackupAuditJson(generatedAtEpochMs = 30)
        val repository = json.getJSONObject("repository")
        val backupJson = json.getJSONObject("backup")

        assertEquals("octo/legacy", repository.getString("fullName"))
        assertEquals("octo/legacy", backup.repositoryDisplayName(currentRepository))
        assertEquals("current-local-repository-cache", repository.getString("metadataSource"))
        assertTrue(backupJson.isNull("origin"))
        assertTrue(backupJson.isNull("scheduledRunId"))
        assertTrue(json.getJSONArray("limitations").toString().contains("pre-v4"))
        assertTrue(json.getJSONArray("limitations").toString().contains("origin"))
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
            origin = BackupOrigin.MANUAL,
        )

        val snapshot = backup.toBackupAuditSnapshot(repository = null)
        val json = snapshot.toBackupAuditJson(generatedAtEpochMs = 30)
        val repository = json.getJSONObject("repository")
        val backupJson = json.getJSONObject("backup")

        assertEquals(88, repository.getLong("githubId"))
        assertTrue(repository.isNull("fullName"))
        assertEquals("Repository #88", backup.repositoryDisplayName(repository = null))
        assertEquals("unavailable", repository.getString("metadataSource"))
        assertEquals("MANUAL", backupJson.getString("origin"))
        assertTrue(backupJson.isNull("scheduledRunId"))
        assertEquals("UNKNOWN", json.getJSONObject("storage").getString("remoteState"))
        assertFalse(json.getJSONArray("limitations").toString().contains("origin tracking"))
        assertFalse(json.getJSONArray("limitations").toString().contains("scheduled-run correlation"))
        assertTrue(snapshot.backupAuditReportFileName().startsWith("repository-88-backup-9"))
    }

    @Test
    fun scheduledBackupWithoutRunIdIsExplicitlyMarkedAsLegacyCorrelation() {
        val backup = BackupEntity(
            id = 10,
            repositoryId = 99,
            type = BackupType.GIT_MIRROR,
            status = BackupStatus.FAILED,
            startedAtEpochMs = 10,
            completedAtEpochMs = 20,
            origin = BackupOrigin.SCHEDULED,
        )

        val json = backup.toBackupAuditSnapshot(repository = null)
            .toBackupAuditJson(generatedAtEpochMs = 30)
        val backupJson = json.getJSONObject("backup")
        val limitations = json.getJSONArray("limitations").toString()

        assertEquals("SCHEDULED", backupJson.getString("origin"))
        assertTrue(backupJson.isNull("scheduledRunId"))
        assertTrue(limitations.contains("predates scheduled-run correlation"))
        assertTrue(limitations.contains("unique-work KEEP"))
    }
}
