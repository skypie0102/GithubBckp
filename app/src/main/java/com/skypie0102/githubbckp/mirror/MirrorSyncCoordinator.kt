package com.skypie0102.githubbckp.mirror

import android.content.Context
import com.skypie0102.githubbckp.data.local.MirrorDao
import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.storage.LocalMirrorStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class MirrorAttemptStatus {
    CHECKING,
    UPDATING,
    COMPLETED,
    FAILED,
}

@Singleton
class MirrorSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mirrorEngine: MirrorEngine,
    private val mirrorStore: LocalMirrorStore,
    private val mirrorDao: MirrorDao,
) {
    suspend fun sync(
        repository: RepositoryEntity,
        onStage: suspend (MirrorStage) -> Unit = {},
    ): Boolean {
        val mirrorRepository = repository.toMirrorRepository()
        val session = File(context.cacheDir, "mirror-sync-${repository.githubId}")
        session.deleteRecursively()
        check(session.mkdirs()) { "Could not create mirror sync cache directory" }

        val attemptAt = System.currentTimeMillis()
        val previousState = mirrorDao.get(repository.githubId)
        mirrorDao.upsert(
            (previousState ?: MirrorEntity(repositoryId = repository.githubId)).copy(
                lastAttemptAtEpochMs = attemptAt,
                lastAttemptStatus = MirrorAttemptStatus.CHECKING.name,
                lastError = null,
            ),
        )

        return try {
            val existingArchive = File(session, "existing.tar.gz")
            val stored = mirrorStore.copyCurrentTo(repository.githubId, existingArchive)

            mirrorDao.upsert(
                (mirrorDao.get(repository.githubId) ?: MirrorEntity(repository.githubId)).copy(
                    lastAttemptStatus = MirrorAttemptStatus.UPDATING.name,
                ),
            )

            val result = if (stored == null) {
                mirrorEngine.create(
                    repository = mirrorRepository,
                    workingDirectory = session,
                    onProgress = onStage,
                )
            } else {
                mirrorEngine.update(
                    repository = mirrorRepository,
                    existingArchive = existingArchive,
                    workingDirectory = session,
                    onProgress = onStage,
                )
            }

            val completedAt = System.currentTimeMillis()
            when (result) {
                is MirrorSyncResult.Unchanged -> {
                    val current = mirrorStore.currentMirror(repository.githubId)
                        ?: error("Stored mirror disappeared after successful check")
                    mirrorDao.upsert(
                        (mirrorDao.get(repository.githubId) ?: MirrorEntity(repository.githubId)).copy(
                            archiveUri = current.uri.toString(),
                            archiveSizeBytes = current.sizeBytes,
                            archiveSha256 = current.sha256,
                            formatVersion = result.manifest.formatVersion,
                            lastCheckedAtEpochMs = completedAt,
                            lastSuccessfulSyncAtEpochMs = completedAt,
                            lastAttemptStatus = MirrorAttemptStatus.COMPLETED.name,
                            lastSourceHead = result.manifest.headCommit,
                            lastRefsDigest = result.manifest.refsDigest,
                            lastError = null,
                        ),
                    )
                }

                is MirrorSyncResult.Rebuilt -> {
                    val committed = mirrorStore.commit(
                        repositoryId = repository.githubId,
                        verifiedArchive = result.archive,
                        expectedSha256 = result.sha256,
                    )
                    mirrorDao.upsert(
                        (mirrorDao.get(repository.githubId) ?: MirrorEntity(repository.githubId)).copy(
                            archiveUri = committed.uri.toString(),
                            archiveSizeBytes = committed.sizeBytes,
                            archiveSha256 = committed.sha256,
                            formatVersion = result.manifest.formatVersion,
                            lastCheckedAtEpochMs = completedAt,
                            lastSuccessfulSyncAtEpochMs = completedAt,
                            lastChangedAtEpochMs = completedAt,
                            lastAttemptStatus = MirrorAttemptStatus.COMPLETED.name,
                            lastSourceHead = result.manifest.headCommit,
                            lastRefsDigest = result.manifest.refsDigest,
                            lastError = null,
                        ),
                    )
                }
            }
            true
        } catch (throwable: Throwable) {
            mirrorDao.upsert(
                (mirrorDao.get(repository.githubId) ?: previousState ?: MirrorEntity(repository.githubId)).copy(
                    lastAttemptAtEpochMs = attemptAt,
                    lastAttemptStatus = MirrorAttemptStatus.FAILED.name,
                    lastError = (throwable.message ?: throwable.javaClass.simpleName).take(MAX_ERROR_LENGTH),
                ),
            )
            false
        } finally {
            session.deleteRecursively()
        }
    }

    private fun RepositoryEntity.toMirrorRepository() = MirrorRepository(
        id = githubId,
        owner = owner,
        name = name,
        defaultBranch = defaultBranch,
        isPrivate = isPrivate,
    )

    private companion object {
        const val MAX_ERROR_LENGTH = 1_000
    }
}
