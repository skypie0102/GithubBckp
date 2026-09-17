package com.skypie0102.githubbckp.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.skypie0102.githubbckp.backup.BackupArtifact
import com.skypie0102.githubbckp.backup.calculateDigests
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
        existing: RemoteBackup?,
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

        // Reuse only a document that is discoverable below the *currently selected*
        // repository folder. A persisted URI from an older document-tree selection
        // must not silently keep receiving backups after the user changes folders.
        val recordedDocument = existing
            ?.takeIf { it.provider == StorageDestination.DOCUMENT_TREE }
            ?.name
            ?.let(repositoryFolder::findFile)
            ?.takeIf { it.isFile && it.canWrite() }
        val stableDocument = repositoryFolder.findFile(artifact.file.name)
            ?.takeIf { it.isFile && it.canWrite() }
        val currentDocument = recordedDocument ?: stableDocument

        // Never truncate the last known-good document while producing its
        // replacement. Write a sibling first, verify its full bytes locally, then
        // retire the previous document. If the process dies while writing the
        // staged file, the current verified mirror remains untouched.
        val stagedName = "${artifact.file.name}.pending-${System.nanoTime()}"
        val stagedDocument = repositoryFolder.createFile(mimeType(artifact), stagedName)
            ?: throw IOException("Could not create a staged mirror in the selected folder")

        try {
            writeArtifact(artifact, stagedDocument, onProgress)
            requireArtifactDigests(artifact, stagedDocument)

            if (currentDocument != null && currentDocument.uri != stagedDocument.uri) {
                // Best effort: coordinator cleanup is deliberately idempotent and
                // retries removal after its independent provider verification.
                runCatching { currentDocument.delete() }
            }

            val stableStillExists = repositoryFolder.findFile(artifact.file.name)
                ?.takeIf { it.exists() && it.uri != stagedDocument.uri }
            if (stableStillExists == null && stagedDocument.name != artifact.file.name) {
                // Rename is cosmetic. If the provider cannot rename, keep the
                // verified staged document and persist its actual URI/name.
                stagedDocument.renameTo(artifact.file.name)
            }

            RemoteBackup(
                id = stagedDocument.uri.toString(),
                name = stagedDocument.name ?: artifact.file.name,
                sizeBytes = artifact.file.length(),
                checksumSha256 = artifact.checksumSha256,
                checksumMd5 = artifact.checksumMd5,
                provider = StorageDestination.DOCUMENT_TREE,
            )
        } catch (throwable: Throwable) {
            runCatching { stagedDocument.delete() }
            throw throwable
        }
    }

    override suspend fun verify(remoteBackup: RemoteBackup): Boolean = withContext(Dispatchers.IO) {
        val uri = Uri.parse(remoteBackup.id)
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext false
        val digests = input.use(::calculateDigests)
        digests.sizeBytes == remoteBackup.sizeBytes &&
            digests.sha256.equals(remoteBackup.checksumSha256, ignoreCase = true) &&
            digests.md5.equals(remoteBackup.checksumMd5, ignoreCase = true)
    }

    override suspend fun download(remoteBackup: RemoteBackup, destination: File): Unit = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(Uri.parse(remoteBackup.id))
            ?: throw IOException("Backup file is no longer available")
        input.buffered().use { source ->
            FileOutputStream(destination).buffered().use { output ->
                source.copyTo(output, bufferSize = BUFFER_SIZE)
            }
        }
        Unit
    }

    override suspend fun delete(remoteBackup: RemoteBackup) = withContext(Dispatchers.IO) {
        val document = DocumentFile.fromSingleUri(context, Uri.parse(remoteBackup.id))
            ?: return@withContext
        if (!document.exists()) return@withContext
        check(document.delete()) { "Could not delete ${remoteBackup.name}" }
    }

    private suspend fun writeArtifact(
        artifact: BackupArtifact,
        document: DocumentFile,
        onProgress: suspend (uploadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val totalBytes = artifact.file.length()
        var uploadedBytes = 0L
        val output = context.contentResolver.openOutputStream(document.uri, "wt")
            ?: throw IOException("Could not open the staged mirror for writing")
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
    }

    private fun requireArtifactDigests(artifact: BackupArtifact, document: DocumentFile) {
        val input = context.contentResolver.openInputStream(document.uri)
            ?: throw IOException("Could not reopen the staged mirror for verification")
        val digests = input.use(::calculateDigests)
        check(digests.sizeBytes == artifact.file.length()) {
            "Staged mirror size verification failed"
        }
        check(digests.sha256.equals(artifact.checksumSha256, ignoreCase = true)) {
            "Staged mirror SHA-256 verification failed"
        }
        check(digests.md5.equals(artifact.checksumMd5, ignoreCase = true)) {
            "Staged mirror MD5 verification failed"
        }
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
