package com.skypie0102.githubbckp.mirror

import android.content.Context
import com.skypie0102.githubbckp.data.local.MirrorDao
import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.storage.LocalMirrorStore
import com.skypie0102.githubbckp.storage.StoredMirror
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

enum class MirrorAttemptStatus {
    CHECKING,
    UPDATING,
    COMPLETED,
    FAILED,
    BLOCKED,
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
        onByteProgress: suspend (stage: MirrorStage, completedBytes: Long, totalBytes: Long) -> Unit =
            { _, _, _ -> },
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
                lastWarning = null,
            ),
        )

        return try {
            val storedManifest = mirrorStore.readManifest(repository.githubId)
            if (storedManifest != null) {
                val needsRebuild = mirrorEngine.remoteRequiresRebuild(
                    repository = mirrorRepository,
                    manifest = storedManifest,
                    onProgress = onStage,
                )
                if (!needsRebuild) {
                    val readableStorage = mirrorStore.ensureReadableLayout(
                        repositoryId = repository.githubId,
                        owner = repository.owner,
                        name = repository.name,
                    )
                    recordUnchanged(
                        repositoryId = repository.githubId,
                        manifest = storedManifest,
                        completedAt = System.currentTimeMillis(),
                        previousState = previousState,
                        copiedMirror = readableStorage,
                    )
                    return true
                }
            }

            val requiredBytes = if (storedManifest != null) {
                mirrorStore.estimateUpdateWorkingBytes(
                    repositoryId = repository.githubId,
                    reserveBrowsableCheckoutUpgrade = !storedManifest.workingTreeIncluded,
                )
            } else {
                MIN_INITIAL_WORKSPACE_BYTES
            }
            if (requiredBytes != null && session.usableSpace < requiredBytes) {
                error(
                    "Not enough temporary storage to safely update this mirror. " +
                        "Need about ${formatBytes(requiredBytes)} free in app cache.",
                )
            }

            mirrorDao.upsert(
                (mirrorDao.get(repository.githubId) ?: MirrorEntity(repository.githubId)).copy(
                    lastAttemptStatus = MirrorAttemptStatus.UPDATING.name,
                    lastWarning = null,
                ),
            )

            val existingArchive = File(session, "existing.tar.gz")
            var copiedMirror: StoredMirror? = null
            val result = if (storedManifest == null) {
                mirrorEngine.create(
                    repository = mirrorRepository,
                    workingDirectory = session,
                    onProgress = onStage,
                )
            } else {
                copiedMirror = mirrorStore.copyCurrentTo(repository.githubId, existingArchive)
                    ?: error("Stored mirror disappeared before update")
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
                    recordUnchanged(
                        repositoryId = repository.githubId,
                        manifest = result.manifest,
                        completedAt = completedAt,
                        previousState = mirrorDao.get(repository.githubId),
                        copiedMirror = copiedMirror,
                    )
                }

                is MirrorSyncResult.Rebuilt -> {
                    onStage(MirrorStage.COMMITTING)
                    val committed = mirrorStore.commit(
                        repositoryId = repository.githubId,
                        owner = repository.owner,
                        name = repository.name,
                        verifiedArchive = result.archive,
                        expectedSha256 = result.sha256,
                        onProgress = { writtenBytes, totalBytes ->
                            onByteProgress(MirrorStage.COMMITTING, writtenBytes, totalBytes)
                        },
                    )
                    val legacyCleanupFailures = mirrorStore.deleteLegacyMirrors(
                        owner = repository.owner,
                        name = repository.name,
                    )
                    val cleanupWarning = legacyCleanupFailures
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(
                            prefix = "Verified the new mirror, but could not remove legacy ZIP: ",
                            separator = ", ",
                        )
                        ?.take(MAX_WARNING_LENGTH)

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
                            lastWarning = cleanupWarning,
                        ),
                    )
                }
            }
            true
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
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

    private suspend fun recordUnchanged(
        repositoryId: Long,
        manifest: MirrorManifest,
        completedAt: Long,
        previousState: MirrorEntity?,
        copiedMirror: StoredMirror? = null,
    ) {
        val currentState = mirrorDao.get(repositoryId) ?: previousState
        val needsStorageMetadata =
            currentState?.archiveUri.isNullOrBlank() ||
                currentState?.archiveSha256.isNullOrBlank() ||
                (currentState?.archiveSizeBytes ?: 0L) <= 0L

        val storage = when {
            copiedMirror != null -> copiedMirror
            needsStorageMetadata -> mirrorStore.currentMirror(repositoryId)
            else -> null
        }

        mirrorDao.upsert(
            (currentState ?: MirrorEntity(repositoryId)).copy(
                archiveUri = storage?.uri?.toString() ?: currentState?.archiveUri,
                archiveSizeBytes = storage?.sizeBytes ?: currentState?.archiveSizeBytes,
                archiveSha256 = storage?.sha256 ?: currentState?.archiveSha256,
                formatVersion = manifest.formatVersion,
                lastCheckedAtEpochMs = completedAt,
                lastSuccessfulSyncAtEpochMs = completedAt,
                lastAttemptStatus = MirrorAttemptStatus.COMPLETED.name,
                lastSourceHead = manifest.headCommit,
                lastRefsDigest = manifest.refsDigest,
                lastError = null,
                lastWarning = null,
            ),
        )
    }

    private fun RepositoryEntity.toMirrorRepository() = MirrorRepository(
        id = githubId,
        owner = owner,
        name = name,
        defaultBranch = defaultBranch,
        isPrivate = isPrivate,
    )

    private fun formatBytes(bytes: Long): String {
        val gib = 1024.0 * 1024.0 * 1024.0
        val mib = 1024.0 * 1024.0
        return if (bytes >= gib) {
            "%.1f GiB".format(bytes / gib)
        } else {
            "%.0f MiB".format(bytes / mib)
        }
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 1_000
        const val MAX_WARNING_LENGTH = 1_000
        const val MIN_INITIAL_WORKSPACE_BYTES = 128L * 1024L * 1024L
    }
}
