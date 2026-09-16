package com.skypie0102.githubbckp.backup

import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.storage.StorageDestination
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupReverificationTest {
    @Test
    fun `completed non-pruned backup with provider metadata is eligible`() {
        assertTrue(backup().canReverifyBackup())
    }

    @Test
    fun `retention-pruned backup is not eligible`() {
        assertFalse(backup().copy(remoteDeletedAtEpochMs = 123L).canReverifyBackup())
    }

    @Test
    fun `legacy backup without persisted provider metadata is not eligible`() {
        assertFalse(backup().copy(remoteChecksumMd5 = null).canReverifyBackup())
    }

    @Test
    fun `matching downloaded digests pass`() {
        val remote = backup().toReverificationRemoteBackup()
        requireMatchingReverificationDigests(
            expected = remote,
            actual = FileDigestResult(
                sha256 = remote.checksumSha256,
                md5 = remote.checksumMd5,
                sizeBytes = remote.sizeBytes,
            ),
        )
    }

    @Test
    fun `size mismatch fails`() {
        val remote = backup().toReverificationRemoteBackup()
        assertFails {
            requireMatchingReverificationDigests(
                expected = remote,
                actual = FileDigestResult(
                    sha256 = remote.checksumSha256,
                    md5 = remote.checksumMd5,
                    sizeBytes = remote.sizeBytes + 1,
                ),
            )
        }
    }

    @Test
    fun `sha mismatch fails`() {
        val remote = backup().toReverificationRemoteBackup()
        assertFails {
            requireMatchingReverificationDigests(
                expected = remote,
                actual = FileDigestResult(
                    sha256 = "different",
                    md5 = remote.checksumMd5,
                    sizeBytes = remote.sizeBytes,
                ),
            )
        }
    }

    @Test
    fun `md5 mismatch fails`() {
        val remote = backup().toReverificationRemoteBackup()
        assertFails {
            requireMatchingReverificationDigests(
                expected = remote,
                actual = FileDigestResult(
                    sha256 = remote.checksumSha256,
                    md5 = "different",
                    sizeBytes = remote.sizeBytes,
                ),
            )
        }
    }

    private fun backup() = BackupEntity(
        id = 9L,
        repositoryId = 42L,
        type = BackupType.GIT_MIRROR,
        status = BackupStatus.COMPLETED,
        startedAtEpochMs = 1_000L,
        completedAtEpochMs = 2_000L,
        checksumSha256 = "sha256-value",
        storageProvider = StorageDestination.GOOGLE_DRIVE,
        remoteFileId = "file-id",
        remoteFileName = "backup.mirror.zip",
        remoteSizeBytes = 1234L,
        remoteChecksumMd5 = "md5-value",
    )

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue("Expected validation to fail", failed)
    }
}
