package com.skypie0102.githubbckp.storage

import com.skypie0102.githubbckp.backup.BackupArtifact
import java.io.File

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
    /**
     * Stores [artifact]. When [existing] belongs to this provider, implementations
     * should update that object in place so a repository has one logical mirror.
     */
    suspend fun upload(
        artifact: BackupArtifact,
        existing: RemoteBackup? = null,
        onProgress: suspend (uploadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): RemoteBackup

    suspend fun verify(remoteBackup: RemoteBackup): Boolean

    suspend fun download(remoteBackup: RemoteBackup, destination: File)

    suspend fun delete(remoteBackup: RemoteBackup)
}
