package com.skypie0102.githubbckp.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.skypie0102.githubbckp.mirror.MirrorVerifier
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StoredMirror(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * The only durable storage implementation for the refactored product.
 *
 * Each GitHub repository ID owns one logical mirror:
 *
 *   GitHub Backups/<repository-id>/mirror.tar.gz
 *
 * Writes always go to mirror.pending.tar.gz first. The old stable archive is
 * left untouched until the pending document has been fully written and hashed.
 */
@Singleton
class LocalMirrorStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: StoragePreferences,
) {
    suspend fun currentMirror(repositoryId: Long): StoredMirror? = withContext(Dispatchers.IO) {
        val folder = repositoryFolder(repositoryId, create = false) ?: return@withContext null
        reconcileFolder(repositoryId, folder)
        val document = folder.findFile(STABLE_NAME)
            ?: folder.findFile(PENDING_NAME)
            ?: return@withContext null
        storedMirror(document)
    }

    suspend fun copyCurrentTo(repositoryId: Long, destination: File): StoredMirror? =
        withContext(Dispatchers.IO) {
            val folder = repositoryFolder(repositoryId, create = false) ?: return@withContext null
            reconcileFolder(repositoryId, folder)
            val document = folder.findFile(STABLE_NAME)
                ?: folder.findFile(PENDING_NAME)
                ?: return@withContext null

            destination.parentFile?.mkdirs()
            context.contentResolver.openInputStream(document.uri)?.use { input ->
                FileOutputStream(destination).buffered(BUFFER_SIZE).use { output ->
                    input.buffered(BUFFER_SIZE).copyTo(output, BUFFER_SIZE)
                }
            } ?: throw IOException("Stored mirror can no longer be opened")

            storedMirror(document)
        }

    suspend fun commit(
        repositoryId: Long,
        verifiedArchive: File,
        expectedSha256: String,
        onProgress: suspend (writtenBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): StoredMirror = withContext(Dispatchers.IO) {
        require(verifiedArchive.isFile) { "Verified mirror archive is missing" }

        val folder = repositoryFolder(repositoryId, create = true)
            ?: throw IOException("Could not create repository backup folder")
        reconcileFolder(repositoryId, folder)

        // A stable mirror exists at this point if a recoverable previous pending
        // transaction was present. It remains untouched until the new pending
        // document has been completely persisted and re-hashed.
        folder.findFile(PENDING_NAME)?.delete()
        val pending = folder.createFile(MIME_TYPE, PENDING_NAME)
            ?: throw IOException("Could not create pending mirror archive")

        try {
            val total = verifiedArchive.length()
            var written = 0L
            val output = context.contentResolver.openOutputStream(pending.uri, "wt")
                ?: throw IOException("Could not open pending mirror archive for writing")
            output.buffered(BUFFER_SIZE).use { destination ->
                FileInputStream(verifiedArchive).buffered(BUFFER_SIZE).use { source ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        destination.write(buffer, 0, count)
                        written += count
                        onProgress(written, total)
                    }
                }
            }

            val persisted = storedMirror(pending)
            check(persisted.sizeBytes == total) { "Pending mirror size verification failed" }
            check(persisted.sha256.equals(expectedSha256, ignoreCase = true)) {
                "Pending mirror SHA-256 verification failed"
            }

            folder.findFile(STABLE_NAME)?.let { stable ->
                check(stable.delete()) { "Could not retire previous mirror archive" }
            }

            if (pending.name != STABLE_NAME) {
                pending.renameTo(STABLE_NAME)
            }

            val committed = folder.findFile(STABLE_NAME) ?: pending
            storedMirror(committed)
        } catch (throwable: Throwable) {
            // Only remove pending when the stable archive still exists. If a
            // provider fails after stable deletion, retaining the verified
            // pending document is safer and startup reconciliation can promote it.
            if (folder.findFile(STABLE_NAME) != null) {
                runCatching { pending.delete() }
            }
            throw throwable
        }
    }

    suspend fun reconcile(repositoryId: Long) = withContext(Dispatchers.IO) {
        val folder = repositoryFolder(repositoryId, create = false) ?: return@withContext
        reconcileFolder(repositoryId, folder)
    }

    suspend fun delete(repositoryId: Long) = withContext(Dispatchers.IO) {
        val folder = repositoryFolder(repositoryId, create = false) ?: return@withContext
        folder.findFile(PENDING_NAME)?.delete()
        folder.findFile(STABLE_NAME)?.delete()
        if (folder.listFiles().isEmpty()) {
            folder.delete()
        }
    }

    private fun reconcileFolder(repositoryId: Long, folder: DocumentFile) {
        val stable = folder.findFile(STABLE_NAME)
        val pending = folder.findFile(PENDING_NAME) ?: return

        if (stable != null) {
            // A crash before commit left a candidate behind. The stable mirror
            // is authoritative, so discard the candidate.
            pending.delete()
            return
        }

        // No stable archive means the process may have died between retiring
        // the old file and renaming the already-verified pending file, or during
        // a first backup. Re-verify the pending archive before promoting it.
        val scratch = File(context.cacheDir, "mirror-reconcile-$repositoryId").apply {
            parentFile?.mkdirs()
            delete()
        }
        val verifyDirectory = File(context.cacheDir, "mirror-reconcile-$repositoryId-verify")
        try {
            context.contentResolver.openInputStream(pending.uri)?.use { input ->
                FileOutputStream(scratch).buffered(BUFFER_SIZE).use { output ->
                    input.buffered(BUFFER_SIZE).copyTo(output, BUFFER_SIZE)
                }
            } ?: throw IOException("Could not reopen pending mirror")

            MirrorVerifier.verify(
                archive = scratch,
                verificationDirectory = verifyDirectory,
                expectedRepositoryId = repositoryId,
            )

            pending.renameTo(STABLE_NAME)
        } catch (_: Throwable) {
            pending.delete()
        } finally {
            scratch.delete()
            verifyDirectory.deleteRecursively()
        }
    }

    private fun repositoryFolder(repositoryId: Long, create: Boolean): DocumentFile? {
        val rootUri = preferences.documentTreeUri() ?: return null
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: throw IOException("The selected backup folder is no longer available")
        check(root.canWrite()) { "The selected backup folder is read-only" }

        val backups = root.findFile(ROOT_DIRECTORY)?.takeIf { it.isDirectory }
            ?: if (create) root.createDirectory(ROOT_DIRECTORY) else null
            ?: return null
        val id = repositoryId.toString()
        return backups.findFile(id)?.takeIf { it.isDirectory }
            ?: if (create) backups.createDirectory(id) else null
    }

    private fun storedMirror(document: DocumentFile): StoredMirror {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.contentResolver.openInputStream(document.uri)?.use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    digest.update(buffer, 0, read)
                    size += read
                }
            }
        } ?: throw IOException("Stored mirror can no longer be opened")

        return StoredMirror(
            uri = document.uri,
            name = document.name ?: STABLE_NAME,
            sizeBytes = size,
            sha256 = digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            },
        )
    }

    private companion object {
        const val ROOT_DIRECTORY = "GitHub Backups"
        const val STABLE_NAME = "mirror.tar.gz"
        const val PENDING_NAME = "mirror.pending.tar.gz"
        const val MIME_TYPE = "application/gzip"
        const val BUFFER_SIZE = 256 * 1024
    }
}
