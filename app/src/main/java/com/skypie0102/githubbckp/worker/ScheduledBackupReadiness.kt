package com.skypie0102.githubbckp.worker

import com.skypie0102.githubbckp.storage.StorageDestination

enum class ScheduledBackupBlockReason {
    GITHUB_DISCONNECTED,
    DRIVE_DISCONNECTED,
    DOCUMENT_TREE_MISSING,
}

data class ScheduledBackupReadiness(
    val ready: Boolean,
    val blockReason: ScheduledBackupBlockReason? = null,
)

fun evaluateScheduledBackupReadiness(
    githubAuthenticated: Boolean,
    destination: StorageDestination,
    driveAuthenticated: Boolean,
    documentTreeConfigured: Boolean,
): ScheduledBackupReadiness {
    if (!githubAuthenticated) {
        return ScheduledBackupReadiness(
            ready = false,
            blockReason = ScheduledBackupBlockReason.GITHUB_DISCONNECTED,
        )
    }

    return when (destination) {
        StorageDestination.GOOGLE_DRIVE -> if (driveAuthenticated) {
            ScheduledBackupReadiness(ready = true)
        } else {
            ScheduledBackupReadiness(
                ready = false,
                blockReason = ScheduledBackupBlockReason.DRIVE_DISCONNECTED,
            )
        }
        StorageDestination.DOCUMENT_TREE -> if (documentTreeConfigured) {
            ScheduledBackupReadiness(ready = true)
        } else {
            ScheduledBackupReadiness(
                ready = false,
                blockReason = ScheduledBackupBlockReason.DOCUMENT_TREE_MISSING,
            )
        }
    }
}
