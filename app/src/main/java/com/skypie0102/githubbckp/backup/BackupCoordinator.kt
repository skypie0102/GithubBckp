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
    private val backupEngineFactory: BackupEngineFactory,
    private val storageProvider: StorageProvider,
    private val retentionManager: BackupRetentionManager,
) {
    suspend fun run(request: BackupRequest): Boolean {
        val startedAt = System.currentTimeMillis()
        val backupId = backupDao.insertBackup(
            BackupEntity(
                repositoryId = request.repository.id,
                type = request.type,
                status = BackupStatus.QUEUED,
                startedAtEpochMs = startedAt,
                repositoryOwnerAtBackup = request.repository.owner,
                repositoryNameAtBackup = request.repository.name,
                repositoryDefaultBranchAtBackup = request.repository.defaultBranch,
                repositoryPrivateAtBackup = request.repository.isPrivate,
                origin = request.origin,
                scheduledRunId = request.scheduledRunId,
            ),
        )
        var artifact: BackupArtifact? = null

        return try {
            val workingDirectory = File(context.cacheDir, "backups/${request.repository.id}/$backupId")
            val engine = backupEngineFactory.forType(request.type)
            artifact = engine.createBackup(
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
                storageProvider = remoteBackup.provider.name,
                remoteFileId = remoteBackup.id,
                remoteFileName = remoteBackup.name,
                remoteSizeBytes = remoteBackup.sizeBytes,
                remoteChecksumMd5 = remoteBackup.checksumMd5,
                warningMessage = artifact.warnings
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("\n")
                    ?.take(MAX_WARNING_LENGTH),
            )
            retentionManager.prune(request.repository.id, request.type)
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

    private companion object {
        const val MAX_WARNING_LENGTH = 2_000
    }
}
