package com.skypie0102.githubbckp.backup

import android.content.Context
import com.skypie0102.githubbckp.data.local.BackupDao
import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.storage.RemoteBackup
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

            // Existing verified objects are candidates for in-place replacement.
            // The newest one is reused by providers that support it. Older
            // duplicates from previous app versions are cleaned after the new
            // bytes have been verified.
            val previousBackups = backupDao.getRetainableBackups(request.repository.id, request.type)
            val previousRemote = previousBackups.firstOrNull()?.toRemoteBackupOrNull()

            backupDao.updateBackupStatus(backupId, BackupStatus.UPLOADING)
            val remoteBackup = storageProvider.upload(
                artifact = artifact,
                existing = previousRemote,
            )

            backupDao.updateBackupStatus(backupId, BackupStatus.VERIFYING)
            check(storageProvider.verify(remoteBackup)) {
                "Remote backup verification failed"
            }

            val cleanupWarnings = cleanupSupersededBackups(previousBackups, remoteBackup)
            val warnings = artifact.warnings + cleanupWarnings

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
                warningMessage = warnings
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("\n")
                    ?.take(MAX_WARNING_LENGTH),
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

    private suspend fun cleanupSupersededBackups(
        previousBackups: List<BackupEntity>,
        current: RemoteBackup,
    ): List<String> = buildList {
        previousBackups.forEach { backup ->
            val previous = backup.toRemoteBackupOrNull() ?: return@forEach
            if (previous.provider == current.provider && previous.id == current.id) {
                // This provider updated the existing object in place. The old
                // history row no longer owns a distinct remote artifact.
                backupDao.markRemoteDeleted(backup.id, System.currentTimeMillis())
                return@forEach
            }
            runCatching {
                storageProvider.delete(previous)
                backupDao.markRemoteDeleted(backup.id, System.currentTimeMillis())
            }.onFailure { throwable ->
                add(
                    "Verified the current mirror, but could not remove an older duplicate " +
                        "${previous.name}: ${throwable.message ?: throwable.javaClass.simpleName}",
                )
            }
        }
    }

    private fun BackupEntity.toRemoteBackupOrNull(): RemoteBackup? {
        val provider = storageProvider ?: return null
        return RemoteBackup(
            id = remoteFileId ?: return null,
            name = remoteFileName ?: return null,
            sizeBytes = remoteSizeBytes ?: return null,
            checksumSha256 = checksumSha256 ?: return null,
            checksumMd5 = remoteChecksumMd5 ?: return null,
            provider = provider,
        )
    }

    private companion object {
        const val MAX_WARNING_LENGTH = 2_000
    }
}
