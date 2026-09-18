package com.skypie0102.githubbckp.mirror

import com.skypie0102.githubbckp.data.local.MirrorDao
import com.skypie0102.githubbckp.storage.LocalMirrorStore
import com.skypie0102.githubbckp.storage.StoragePreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MirrorStorageReconciler @Inject constructor(
    private val mirrorDao: MirrorDao,
    private val mirrorStore: LocalMirrorStore,
    private val storagePreferences: StoragePreferences,
) {
    suspend fun reconcileAll() {
        val mirrors = mirrorDao.getAll()
        if (mirrors.isEmpty()) return

        if (!storagePreferences.isDocumentTreeConfigured()) {
            mirrors.forEach { mirror ->
                mirrorDao.upsert(
                    mirror.copy(
                        lastAttemptStatus = MirrorAttemptStatus.BLOCKED.name,
                        lastWarning = STORAGE_UNAVAILABLE_MESSAGE,
                    ),
                )
            }
            return
        }

        mirrors.forEach { mirror ->
            runCatching {
                mirrorStore.reconcile(mirror.repositoryId)
                val exists = mirrorStore.hasCurrentMirror(mirror.repositoryId)
                if (!exists) {
                    mirrorDao.upsert(
                        mirror.copy(
                            archiveUri = null,
                            archiveSizeBytes = null,
                            archiveSha256 = null,
                            lastWarning = null,
                        ),
                    )
                } else if (mirror.lastWarning == STORAGE_UNAVAILABLE_MESSAGE) {
                    mirrorDao.upsert(
                        mirror.copy(
                            lastAttemptStatus = if (mirror.lastSuccessfulSyncAtEpochMs != null) {
                                MirrorAttemptStatus.COMPLETED.name
                            } else {
                                mirror.lastAttemptStatus
                            },
                            lastWarning = null,
                        ),
                    )
                }
            }.onFailure { throwable ->
                mirrorDao.upsert(
                    mirror.copy(
                        lastAttemptStatus = MirrorAttemptStatus.BLOCKED.name,
                        lastWarning = (
                            throwable.message
                                ?: "The selected backup folder is unavailable."
                            ).take(MAX_WARNING_LENGTH),
                    ),
                )
            }
        }
    }

    companion object {
        const val STORAGE_UNAVAILABLE_MESSAGE =
            "The selected local backup folder is unavailable or its permission was revoked."
        private const val MAX_WARNING_LENGTH = 500
    }
}
