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
import kotlinx.coroutines.CancellationException

enum class BackupReverificationStatus {
    VERIFIED,
    FAILED,
}

data class BackupReverificationResult(
    val backupId: Long,
    val status: BackupReverificationStatus,
    val reverifiedAtEpochMs: Long,
    val message: String,
)

fun BackupEntity.canReverifyBackup(): Boolean =
    status == BackupStatus.COMPLETED &&
        remoteDeletedAtEpochMs == null &&
        storageProvider != null &&
        !remoteFileId.isNullOrBlank() &&
        !remoteFileName.isNullOrBlank() &&
        remoteSizeBytes != null &&
        !checksumSha256.isNullOrBlank() &&
        !remoteChecksumMd5.isNullOrBlank()

internal fun BackupEntity.toReverificationRemoteBackup(): RemoteBackup {
    check(canReverifyBackup()) { "Backup is not eligible for re-verification" }
    return RemoteBackup(
        id = checkNotNull(remoteFileId),
        name = checkNotNull(remoteFileName),
        sizeBytes = checkNotNull(remoteSizeBytes),
        checksumSha256 = checkNotNull(checksumSha256),
        checksumMd5 = checkNotNull(remoteChecksumMd5),
        provider = checkNotNull(storageProvider),
    )
}

internal fun requireMatchingReverificationDigests(
    expected: RemoteBackup,
    actual: FileDigestResult,
) {
    check(actual.sizeBytes == expected.sizeBytes) {
        "Stored artifact size changed: expected ${expected.sizeBytes} bytes, found ${actual.sizeBytes}"
    }
    check(actual.sha256.equals(expected.checksumSha256, ignoreCase = true)) {
        "Stored artifact SHA-256 no longer matches the verified backup record"
    }
    check(actual.md5.equals(expected.checksumMd5, ignoreCase = true)) {
        "Stored artifact MD5 no longer matches the verified backup record"
    }
}

@Singleton
class BackupReverificationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupDao: BackupDao,
    private val storageProvider: StorageProvider,
    private val mirrorRestoreService: GitMirrorRestoreService,
) {
    suspend fun reverify(backupId: Long): BackupReverificationResult {
        val backup = backupDao.getBackup(backupId)
            ?: error("Backup history row no longer exists")
        check(backup.canReverifyBackup()) {
            if (backup.remoteDeletedAtEpochMs != null) {
                "The remote backup was pruned and cannot be re-verified"
            } else {
                "This backup does not contain enough persisted provider/checksum metadata for re-verification"
            }
        }

        val remoteBackup = backup.toReverificationRemoteBackup()
        val workingDirectory = File(
            context.cacheDir,
            "backup-reverification/${backup.id}-${System.nanoTime()}",
        )
        val downloadedArtifact = File(workingDirectory, "artifact")
        workingDirectory.mkdirs()

        return try {
            storageProvider.download(remoteBackup, downloadedArtifact)
            val digests = calculateDigests(downloadedArtifact)
            requireMatchingReverificationDigests(remoteBackup, digests)

            val message = if (backup.type == BackupType.GIT_MIRROR) {
                val mirror = mirrorRestoreService.validate(downloadedArtifact, workingDirectory)
                buildString {
                    append("Remote bytes and Git mirror modules verified")
                    append(" • ${mirror.refNames.size} refs")
                    if (mirror.lfsObjectCount > 0) append(" • ${mirror.lfsObjectCount} LFS objects")
                    if (mirror.releaseCount > 0) append(" • ${mirror.releaseCount} releases")
                    val discussionCount = mirror.issueCount + mirror.pullRequestCount + mirror.issueCommentCount +
                        mirror.reviewCommentCount + mirror.reviewCount
                    if (discussionCount > 0) append(" • $discussionCount discussion records")
                }
            } else {
                "Remote artifact bytes verified against stored size, SHA-256, and MD5"
            }
            persistResult(backup.id, BackupReverificationStatus.VERIFIED, message)
        } catch (throwable: Exception) {
            if (throwable is CancellationException) throw throwable
            val message = (throwable.message ?: throwable.javaClass.simpleName).take(MAX_RESULT_MESSAGE_LENGTH)
            persistResult(backup.id, BackupReverificationStatus.FAILED, message)
        } finally {
            workingDirectory.deleteRecursively()
        }
    }

    private suspend fun persistResult(
        backupId: Long,
        status: BackupReverificationStatus,
        message: String,
    ): BackupReverificationResult {
        val timestamp = System.currentTimeMillis()
        val boundedMessage = message.take(MAX_RESULT_MESSAGE_LENGTH)
        backupDao.recordReverification(
            backupId = backupId,
            reverifiedAtEpochMs = timestamp,
            status = status,
            message = boundedMessage,
        )
        return BackupReverificationResult(
            backupId = backupId,
            status = status,
            reverifiedAtEpochMs = timestamp,
            message = boundedMessage,
        )
    }

    private companion object {
        const val MAX_RESULT_MESSAGE_LENGTH = 1_000
    }
}
