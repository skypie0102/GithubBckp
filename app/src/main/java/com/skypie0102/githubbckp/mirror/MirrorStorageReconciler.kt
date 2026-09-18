package com.skypie0102.githubbckp.mirror

import com.skypie0102.githubbckp.data.local.MirrorDao
import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.storage.LocalMirrorStore
import com.skypie0102.githubbckp.storage.StoragePreferences
import javax.inject.Inject
import javax.inject.Singleton

internal enum class MirrorStorageState {
    AVAILABLE,
    MISSING,
    UNAVAILABLE,
}

internal fun reconcileMirrorStorageState(
    mirror: MirrorEntity,
    storageState: MirrorStorageState,
    unavailableMessage: String = MirrorStorageReconciler.STORAGE_UNAVAILABLE_MESSAGE,
): MirrorEntity = when (storageState) {
    MirrorStorageState.UNAVAILABLE -> mirror.copy(
        lastAttemptStatus = MirrorAttemptStatus.BLOCKED.name,
        lastWarning = unavailableMessage.take(MirrorStorageReconciler.MAX_WARNING_LENGTH),
    )

    MirrorStorageState.MISSING -> mirror.copy(
        archiveUri = null,
        archiveSizeBytes = null,
        archiveSha256 = null,
        lastWarning = null,
    )

    MirrorStorageState.AVAILABLE -> if (
        mirror.lastWarning == MirrorStorageReconciler.STORAGE_UNAVAILABLE_MESSAGE
    ) {
        mirror.copy(
            lastAttemptStatus = if (mirror.lastSuccessfulSyncAtEpochMs != null) {
                MirrorAttemptStatus.COMPLETED.name
            } else {
                mirror.lastAttemptStatus
            },
            lastWarning = null,
        )
    } else {
        mirror
    }
}

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
                    reconcileMirrorStorageState(
                        mirror = mirror,
                        storageState = MirrorStorageState.UNAVAILABLE,
                    ),
                )
            }
            return
        }

        mirrors.forEach { mirror ->
            runCatching {
                mirrorStore.reconcile(mirror.repositoryId)
                val exists = mirrorStore.hasCurrentMirror(mirror.repositoryId)
                mirrorDao.upsert(
                    reconcileMirrorStorageState(
                        mirror = mirror,
                        storageState = if (exists) {
                            MirrorStorageState.AVAILABLE
                        } else {
                            MirrorStorageState.MISSING
                        },
                    ),
                )
            }.onFailure { throwable ->
                mirrorDao.upsert(
                    reconcileMirrorStorageState(
                        mirror = mirror,
                        storageState = MirrorStorageState.UNAVAILABLE,
                        unavailableMessage = throwable.message
                            ?: "The selected backup folder is unavailable.",
                    ),
                )
            }
        }
    }

    companion object {
        const val STORAGE_UNAVAILABLE_MESSAGE =
            "The selected local backup folder is unavailable or its permission was revoked."
        internal const val MAX_WARNING_LENGTH = 500
    }
}
