package com.skypie0102.githubbckp.backup

import android.util.Log
import com.skypie0102.githubbckp.data.local.BackupDao
import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.storage.RemoteBackup
import com.skypie0102.githubbckp.storage.StorageProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRetentionManager @Inject constructor(
    private val backupDao: BackupDao,
    private val storageProvider: StorageProvider,
    private val preferences: RetentionPreferences,
) {
    suspend fun prune(repositoryId: Long, type: BackupType) {
        val keepCount = preferences.keepCount()
        if (keepCount == RetentionPreferences.KEEP_ALL) return

        val expired = retentionCandidates(
            backups = backupDao.getRetainableBackups(repositoryId, type),
            keepCount = keepCount,
        )
        expired.forEach { backup ->
            runCatching {
                val remote = backup.toRemoteBackup()
                storageProvider.delete(remote)
                backupDao.markRemoteDeleted(backup.id, System.currentTimeMillis())
            }.onFailure { throwable ->
                Log.w(TAG, "Could not prune backup ${backup.id}", throwable)
            }
        }
    }

    private fun BackupEntity.toRemoteBackup(): RemoteBackup {
        val provider = requireNotNull(storageProvider) { "Backup has no storage provider" }
        return RemoteBackup(
            id = requireNotNull(remoteFileId) { "Backup has no remote ID" },
            name = requireNotNull(remoteFileName) { "Backup has no remote name" },
            sizeBytes = requireNotNull(remoteSizeBytes) { "Backup has no remote size" },
            checksumSha256 = requireNotNull(checksumSha256) { "Backup has no SHA-256" },
            checksumMd5 = requireNotNull(remoteChecksumMd5) { "Backup has no remote MD5" },
            provider = provider,
        )
    }

    private companion object {
        const val TAG = "BackupRetention"
    }
}

internal fun retentionCandidates(
    backups: List<BackupEntity>,
    keepCount: Int,
): List<BackupEntity> = if (keepCount <= 0) emptyList() else backups.drop(keepCount)
