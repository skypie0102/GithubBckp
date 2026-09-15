package com.skypie0102.githubbckp.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.skypie0102.githubbckp.backup.BackupArtifact
import com.skypie0102.githubbckp.backup.calculateDigests
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileInputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes backup artifacts through Android's Storage Access Framework. The user
 * chooses the root directory, so this works with local storage and any cloud
 * provider that exposes a DocumentsProvider without broad storage permission.
 */
@Singleton
class DocumentTreeStorageProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: StoragePreferences,
) : StorageProvider {
    override suspend fun upload(
        artifact: BackupArtifact,
        onProgress: suspend (uploadedBytes: Long, totalBytes: Long) -> Unit,
    ): RemoteBackup = withContext(Dispatchers.IO) {
        val rootUri = preferences.documentTreeUri()
            ?: throw IOException("Choose a backup folder first")
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: throw IOException("The selected backup folder is no longer available")
        check(root.canWrite()) { "The selected backup folder is read-only" }

        val repositoryFolder = root
            .findOrCreateDirectory("GitHub Backups")
            .findOrCreateDirectory(artifact.repository.owner)
            .findOrCreateDirectory(artifact.repository.name)
        val document = repositoryFolder.createFile(mimeType(artifact), artifact.file.name)
            ?: throw IOException("Could not create ${artifact.file.name} in the selected folder")

        val totalBytes = artifact.file.length()
        var uploadedBytes = 0L
        val output = context.contentResolver.openOutputStream(document.uri, "w")
            ?: throw IOException("Could not open the destination file for writing")
        output.buffered().use { destination ->
            FileInputStream(artifact.file).use { source ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    destination.write(buffer, 0, count)
                    uploadedBytes += count
                    onProgress(uploadedBytes, totalBytes)
                }
            }
        }

        RemoteBackup(
            id = document.uri.toString(),
            name = document.name ?: artifact.file.name,
            sizeBytes = totalBytes,
            checksumSha256 = artifact.checksumSha256,
            checksumMd5 = artifact.checksumMd5,
            provider = StorageDestination.DOCUMENT_TREE,
        )
    }

    override suspend fun verify(remoteBackup: RemoteBackup): Boolean = withContext(Dispatchers.IO) {
        val uri = Uri.parse(remoteBackup.id)
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext false
        val digests = input.use(::calculateDigests)
        digests.sizeBytes == remoteBackup.sizeBytes &&
            digests.sha256.equals(remoteBackup.checksumSha256, ignoreCase = true) &&
            digests.md5.equals(remoteBackup.checksumMd5, ignoreCase = true)
    }

    override suspend fun delete(remoteBackup: RemoteBackup) = withContext(Dispatchers.IO) {
        val document = DocumentFile.fromSingleUri(context, Uri.parse(remoteBackup.id))
            ?: throw IOException("Backup file is no longer available")
        check(document.delete()) { "Could not delete ${remoteBackup.name}" }
    }

    private fun DocumentFile.findOrCreateDirectory(name: String): DocumentFile =
        findFile(name)?.takeIf { it.isDirectory }
            ?: createDirectory(name)
            ?: throw IOException("Could not create backup directory: $name")

    private fun mimeType(artifact: BackupArtifact): String = when {
        artifact.file.name.endsWith(".zip", ignoreCase = true) -> "application/zip"
        artifact.file.name.endsWith(".gz", ignoreCase = true) -> "application/gzip"
        else -> "application/octet-stream"
    }

    private companion object {
        const val BUFFER_SIZE = 256 * 1024
    }
}
