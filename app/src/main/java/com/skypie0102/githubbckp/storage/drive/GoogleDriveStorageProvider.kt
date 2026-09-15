package com.skypie0102.githubbckp.storage.drive

import com.skypie0102.githubbckp.backup.BackupArtifact
import com.skypie0102.githubbckp.storage.RemoteBackup
import com.skypie0102.githubbckp.storage.StorageProvider
import javax.inject.Inject

/**
 * Google Drive adapter boundary.
 *
 * TODO: Implement OAuth with the narrow drive.file scope and resumable uploads.
 * Keep access/refresh tokens in Keystore-backed storage, never BuildConfig/source.
 */
class GoogleDriveStorageProvider @Inject constructor() : StorageProvider {
    override suspend fun upload(
        artifact: BackupArtifact,
        onProgress: suspend (uploadedBytes: Long, totalBytes: Long) -> Unit,
    ): RemoteBackup = error("Google Drive authentication is not configured yet")

    override suspend fun verify(remoteBackup: RemoteBackup): Boolean = false

    override suspend fun delete(remoteBackup: RemoteBackup) {
        error("Google Drive authentication is not configured yet")
    }
}
