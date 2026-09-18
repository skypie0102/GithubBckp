package com.skypie0102.githubbckp.backup

import android.content.Context
import com.skypie0102.githubbckp.data.local.BackupDao
import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.storage.LocalArchiveStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Singleton
class MirrorBackupCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupDao: BackupDao,
    private val engine: MirrorBackupEngine,
    private val archiveStore: LocalArchiveStore,
) {
    suspend fun run(request: MirrorBackupRequest): Boolean {
        val previous = backupDao.getMirror(request.repository.id)
            ?: MirrorEntity(repositoryId = request.repository.id)
        val startedAt = System.currentTimeMillis()
        backupDao.upsertMirror(
            previous.copy(
                status = MirrorStatus.RUNNING,
                lastStartedAtEpochMs = startedAt,
                lastCompletedAtEpochMs = null,
                lastError = null,
            ),
        )

        val workDirectory = File(context.cacheDir, "mirror-backups/${request.repository.id}")
        val existingArchive = File(workDirectory, "existing.tar.gz")

        return try {
            workDirectory.deleteRecursively()
            workDirectory.mkdirs()
            val hasExisting = archiveStore.copyExistingArchive(request.repository, existingArchive)

            val result = engine.build(
                request = request,
                existingArchive = existingArchive.takeIf { hasExisting },
                workDirectory = File(workDirectory, "build"),
            )
            val stored = archiveStore.replaceArchive(
                repository = request.repository,
                source = result.archiveFile,
            )
            val completedAt = System.currentTimeMillis()

            backupDao.upsertMirror(
                MirrorEntity(
                    repositoryId = request.repository.id,
                    status = MirrorStatus.COMPLETED,
                    archiveName = stored.name,
                    archiveSizeBytes = stored.sizeBytes,
                    lastCommitSha = result.commitSha,
                    lastStartedAtEpochMs = startedAt,
                    lastCompletedAtEpochMs = completedAt,
                    lastSuccessfulAtEpochMs = completedAt,
                    lastError = null,
                ),
            )
            true
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                backupDao.upsertMirror(
                    previous.copy(
                        status = MirrorStatus.CANCELLED,
                        lastStartedAtEpochMs = startedAt,
                        lastCompletedAtEpochMs = System.currentTimeMillis(),
                        lastError = "Backup was interrupted by Android and will be retried when appropriate.",
                    ),
                )
            }
            throw cancellation
        } catch (throwable: Throwable) {
            backupDao.upsertMirror(
                previous.copy(
                    status = MirrorStatus.FAILED,
                    lastStartedAtEpochMs = startedAt,
                    lastCompletedAtEpochMs = System.currentTimeMillis(),
                    lastError = (throwable.message ?: throwable.javaClass.simpleName).take(500),
                ),
            )
            false
        } finally {
            workDirectory.deleteRecursively()
        }
    }
}
