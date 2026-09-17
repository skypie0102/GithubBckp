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
        var replacementCandidate: RemoteBackup? = null
        var replacementCommitted = false

        return try {
            val workingDirectory = File(context.cacheDir, "backups/${request.repository.id}/$backupId")
            val engine = backupEngineFactory.forType(request.type)
            artifact = engine.createBackup(
                request = request,
                workingDirectory = workingDirectory,
                onProgress = { backupDao.updateBackupStatus(backupId, it) },
            )

            // Existing verified objects are retired only after a newly staged
            // replacement independently verifies and is persisted as current.
            val previousBackups = backupDao.getCurrentRemoteBackups(request.repository.id, request.type)

            backupDao.updateBackupStatus(backupId, BackupStatus.UPLOADING)
            val remoteBackup = storageProvider.upload(artifact = artifact)
            replacementCandidate = remoteBackup

            backupDao.updateBackupStatus(backupId, BackupStatus.VERIFYING)
            check(storageProvider.verify(remoteBackup)) {
                "Remote backup verification failed"
            }

            // Persist the verified current mirror before best-effort cleanup. A
            // crash during duplicate cleanup must never leave Room claiming that
            // no verified current mirror exists when the new remote object is good.
            val creationWarnings = artifact.warnings.toWarningMessage()
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
                warningMessage = creationWarnings,
            )
            replacementCommitted = true

            val cleanupWarnings = cleanupSupersededBackups(previousBackups, remoteBackup)
            if (cleanupWarnings.isNotEmpty()) {
                // Warning persistence is post-commit bookkeeping. Failure to write
                // the warning must not downgrade an already verified mirror.
                runCatching {
                    backupDao.updateCompletedBackupWarning(
                        backupId = backupId,
                        warningMessage = (artifact.warnings + cleanupWarnings).toWarningMessage(),
                    )
                }
            }
            true
        } catch (throwable: Throwable) {
            // A staged replacement that never became the committed current mirror
            // is disposable. Best-effort deletion prevents failed verification or
            // database writes from accumulating orphan candidates while leaving the
            // previous verified mirror untouched.
            if (!replacementCommitted) {
                replacementCandidate?.let { candidate ->
                    runCatching { storageProvider.delete(candidate) }
                }
            }
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

            if (previous.provider != current.provider || previous.id != current.id) {
                runCatching { storageProvider.delete(previous) }
                    .onFailure { throwable ->
                        add(
                            "Verified the current mirror, but could not remove an older duplicate " +
                                "${previous.name}: ${throwable.message ?: throwable.javaClass.simpleName}",
                        )
                    }
            }

            // The old history row no longer represents the logical current mirror
            // even if physical duplicate removal was not possible. Keep that cleanup
            // failure as a warning, but do not let stale rows participate in health,
            // re-verification, or the next replacement's current-object set.
            runCatching {
                backupDao.markRemoteDeleted(backup.id, System.currentTimeMillis())
            }.onFailure { throwable ->
                add(
                    "Verified the current mirror, but could not mark older history " +
                        "${previous.name} as superseded: ${throwable.message ?: throwable.javaClass.simpleName}",
                )
            }
        }
    }

    private fun List<String>.toWarningMessage(): String? =
        takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
            ?.take(MAX_WARNING_LENGTH)

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
