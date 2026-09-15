package com.skypie0102.githubbckp.storage

import com.skypie0102.githubbckp.backup.BackupArtifact
import com.skypie0102.githubbckp.storage.drive.GoogleDriveStorageProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageRouter @Inject constructor(
    private val preferences: StoragePreferences,
    private val googleDrive: GoogleDriveStorageProvider,
    private val documentTree: DocumentTreeStorageProvider,
) : StorageProvider {
    override suspend fun upload(
        artifact: BackupArtifact,
        onProgress: suspend (uploadedBytes: Long, totalBytes: Long) -> Unit,
    ): RemoteBackup = provider(preferences.destination()).upload(artifact, onProgress)

    override suspend fun verify(remoteBackup: RemoteBackup): Boolean =
        provider(remoteBackup.provider).verify(remoteBackup)

    override suspend fun delete(remoteBackup: RemoteBackup) {
        provider(remoteBackup.provider).delete(remoteBackup)
    }

    private fun provider(destination: StorageDestination): StorageProvider = when (destination) {
        StorageDestination.GOOGLE_DRIVE -> googleDrive
        StorageDestination.DOCUMENT_TREE -> documentTree
    }
}
