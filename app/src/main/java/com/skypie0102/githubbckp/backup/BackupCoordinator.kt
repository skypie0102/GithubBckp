package com.skypie0102.githubbckp.backup

import android.content.Context
import com.skypie0102.githubbckp.data.local.BackupDao
import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.storage.StorageProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupDao: BackupDao,
    private val backupEngine: BackupEngine,
    private val storageProvider: StorageProvider,
) {
    suspend fun run(request: BackupRequest): Boolean {
        val startedAt = System.currentTimeMillis()
        val backupId = backupDao.insertBackup(
            BackupEntity(
                repositoryId = request.repository.id,
                type = request.type,
                status = BackupStatus.QUEUED,
                startedAtEpochMs = startedAt,
            ),
        )
        var artifact: BackupArtifact? = null

        return try {
            val workingDirectory = File(context.cacheDir, "backups/${request.repository.id}/$backupId")
            artifact = backupEngine.createBackup(
                request = request,
                workingDirectory = workingDirectory,
                onProgress = { backupDao.updateBackupStatus(backupId, it) },
            )

            backupDao.updateBackupStatus(backupId, BackupStatus.UPLOADING)
            val remoteBackup = storageProvider.upload(artifact)

            backupDao.updateBackupStatus(backupId, BackupStatus.VERIFYING)
            check(storageProvider.verify(remoteBackup)) {
                "Remote backup verification failed"
            }

            backupDao.completeBackup(
                backupId = backupId,
                status = BackupStatus.COMPLETED,
                completedAtEpochMs = System.currentTimeMillis(),
                checksumSha256 = artifact.checksumSha256,
                remoteFileId = remoteBackup.id,
            )
            true
        } catch (throwable: Throwable) {
            backupDao.failBackup(
                backupId = backupId,
                status = BackupStatus.FAILED,
                completedAtEpochMs = System.currentTimeMillis(),
                errorMessage = (throwable.message ?: throwable.javaClass.simpleName).take(1_000),
            )
            false
        } finally {
            artifact?.file?.delete()
            artifact?.file?.parentFile?.deleteRecursively()
        }
    }
}
