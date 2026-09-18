package com.skypie0102.githubbckp.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.skypie0102.githubbckp.backup.RepositoryRef
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StoredMirrorArchive(
    val name: String,
    val sizeBytes: Long,
)

@Singleton
class LocalArchiveStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: StoragePreferences,
) {
    fun archiveName(repository: RepositoryRef): String =
        "${safe(repository.owner)}--${safe(repository.name)}.tar.gz"

    suspend fun copyExistingArchive(repository: RepositoryRef, destination: File): Boolean =
        withContext(Dispatchers.IO) {
            val directory = mirrorDirectory()
            val stableName = archiveName(repository)
            recoverInterruptedReplacement(directory, stableName)
            val archive = directory.findFile(stableName) ?: return@withContext false

            context.contentResolver.openInputStream(archive.uri)?.use { input ->
                destination.parentFile?.mkdirs()
                destination.outputStream().buffered().use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            } ?: throw IOException("Unable to read existing local mirror ${archive.name}")
            true
        }

    suspend fun replaceArchive(
        repository: RepositoryRef,
        source: File,
    ): StoredMirrorArchive = withContext(Dispatchers.IO) {
        require(source.isFile && source.length() > 0L) { "Mirror archive is empty" }

        val directory = mirrorDirectory()
        val stableName = archiveName(repository)
        val pendingName = "$stableName.pending"
        val previousName = "$stableName.previous"

        recoverInterruptedReplacement(directory, stableName)
        directory.findFile(pendingName)?.delete()
        directory.findFile(previousName)?.delete()

        val pending = directory.createFile(MIME_GZIP, pendingName)
            ?: throw IOException("Unable to create staged local mirror $pendingName")

        var previous: DocumentFile? = null
        try {
            context.contentResolver.openOutputStream(pending.uri, "wt")?.use { output ->
                source.inputStream().buffered().use { input ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            } ?: throw IOException("Unable to write staged local mirror $pendingName")

            if (pending.length() != source.length()) {
                throw IOException(
                    "Staged mirror size mismatch: expected ${source.length()} bytes, wrote ${pending.length()}",
                )
            }

            val existing = directory.findFile(stableName)
            if (existing != null) {
                if (!existing.renameTo(previousName)) {
                    throw IOException("Unable to stage the previous local mirror for replacement")
                }
                previous = directory.findFile(previousName)
                    ?: throw IOException("Previous local mirror disappeared during replacement")
            }

            if (!pending.renameTo(stableName)) {
                previous?.renameTo(stableName)
                throw IOException("Unable to finalize local mirror $stableName")
            }

            val finalized = directory.findFile(stableName)
                ?: throw IOException("Final local mirror $stableName is missing")

            if (previous != null && !previous.delete()) {
                throw IOException(
                    "The new mirror is valid, but the previous archive could not be removed. " +
                        "Run the backup again to retry cleanup.",
                )
            }

            StoredMirrorArchive(
                name = stableName,
                sizeBytes = finalized.length(),
            )
        } catch (throwable: Throwable) {
            directory.findFile(pendingName)?.delete()
            if (directory.findFile(stableName) == null) {
                directory.findFile(previousName)?.renameTo(stableName)
            }
            throw throwable
        }
    }

    private fun recoverInterruptedReplacement(directory: DocumentFile, stableName: String) {
        val pendingName = "$stableName.pending"
        val previousName = "$stableName.previous"
        val stable = directory.findFile(stableName)
        val previous = directory.findFile(previousName)

        when {
            stable != null -> previous?.delete()
            previous != null -> previous.renameTo(stableName)
        }
        directory.findFile(pendingName)?.delete()
    }

    private fun mirrorDirectory(): DocumentFile {
        val treeUri = preferences.documentTreeUri()
            ?: throw IOException("Choose a local backup folder first")
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("The selected backup folder is no longer available")
        if (!root.canRead() || !root.canWrite()) {
            throw IOException("The selected backup folder is not writable")
        }
        return root.findFile(APP_DIRECTORY)
            ?: root.createDirectory(APP_DIRECTORY)
            ?: throw IOException("Unable to create $APP_DIRECTORY in the selected backup folder")
    }

    private fun safe(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "repository" }

    private companion object {
        const val APP_DIRECTORY = "GitHub Mirrors"
        const val MIME_GZIP = "application/gzip"
        const val BUFFER_SIZE = 128 * 1024
    }
}
