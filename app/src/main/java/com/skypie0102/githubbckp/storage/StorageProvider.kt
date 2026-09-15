package com.skypie0102.githubbckp.storage

import com.skypie0102.githubbckp.backup.BackupArtifact

enum class StorageDestination {
    GOOGLE_DRIVE,
    DOCUMENT_TREE,
}

data class RemoteBackup(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val checksumSha256: String,
    val checksumMd5: String,
    val provider: StorageDestination,
)

interface StorageProvider {
    suspend fun upload(
        artifact: BackupArtifact,
        onProgress: suspend (uploadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): RemoteBackup

    suspend fun verify(remoteBackup: RemoteBackup): Boolean

    suspend fun delete(remoteBackup: RemoteBackup)
}
