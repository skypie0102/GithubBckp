package com.skypie0102.githubbckp.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.skypie0102.githubbckp.mirror.MirrorManifest
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

    suspend fun readManifest(repositoryId: Long): MirrorManifest? = withContext(Dispatchers.IO) {
        val folder = repositoryFolder(repositoryId, create = false) ?: return@withContext null
        reconcileFolder(repositoryId, folder)
        val document = folder.findFile(STABLE_NAME)
            ?: folder.findFile(PENDING_NAME)
            ?: return@withContext null
        val input = context.contentResolver.openInputStream(document.uri)
            ?: throw IOException("Stored mirror can no longer be opened")
        input.use(MirrorManifest::readFromArchive)
    }

    suspend fun estimateUpdateWorkingBytes(repositoryId: Long): Long? = withContext(Dispatchers.IO) {
        val folder = repositoryFolder(repositoryId, create = false) ?: return@withContext null
        reconcileFolder(repositoryId, folder)
        val document = folder.findFile(STABLE_NAME)
            ?: folder.findFile(PENDING_NAME)
            ?: return@withContext null
        val compressedBytes = document.length().takeIf { it > 0L } ?: storedMirror(document).sizeBytes
        val input = context.contentResolver.openInputStream(document.uri)
            ?: throw IOException("Stored mirror can no longer be opened")
        val expandedBytes = input.use(com.skypie0102.githubbckp.mirror.TarGzArchive::expandedSizeBytes)
        requiredUpdateWorkspaceBytes(compressedBytes, expandedBytes)
    }

    suspend fun copyCurrentTo(repositoryId: Long, destination: File): StoredMirror? =
        withContext(Dispatchers.IO) {
            val folder = repositoryFolder(repositoryId, create = false) ?: return@withContext null
            reconcileFolder(repositoryId, folder)
            val document = folder.findFile(STABLE_NAME)
                ?: folder.findFile(PENDING_NAME)
                ?: return@withContext null

            destination.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val input = context.contentResolver.openInputStream(document.uri)
                ?: throw IOException("Stored mirror can no longer be opened")
            input.buffered(BUFFER_SIZE).use { source ->
                FileOutputStream(destination).buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            size += count
                        }
                    }
                }
            }

            StoredMirror(
                uri = document.uri,
                name = document.name ?: STABLE_NAME,
                sizeBytes = size,
                sha256 = digest.digest().joinToString("") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                },
            )
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

    suspend fun reconcile(repositoryId: Long): Boolean = withContext(Dispatchers.IO) {
        val folder = repositoryFolder(repositoryId, create = false) ?: return@withContext false
        reconcileFolder(repositoryId, folder)
    }

    suspend fun hasCurrentMirror(repositoryId: Long): Boolean = withContext(Dispatchers.IO) {
        val folder = repositoryFolder(repositoryId, create = false) ?: return@withContext false
        reconcileFolder(repositoryId, folder)
        folder.findFile(STABLE_NAME) != null || folder.findFile(PENDING_NAME) != null
    }

    suspend fun deleteLegacyMirrors(owner: String, name: String): List<String> =
        withContext(Dispatchers.IO) {
            val rootUri = preferences.documentTreeUri() ?: return@withContext emptyList()
            val root = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext emptyList()
            val backups = root.findFile(ROOT_DIRECTORY)?.takeIf { it.isDirectory }
                ?: return@withContext emptyList()
            val ownerFolder = backups.findFile(owner)?.takeIf { it.isDirectory }
                ?: return@withContext emptyList()
            val repositoryFolder = ownerFolder.findFile(name)?.takeIf { it.isDirectory }
                ?: return@withContext emptyList()

            val failures = mutableListOf<String>()
            repositoryFolder.listFiles()
                .filter { document ->
                    document.isFile && isLegacyMirrorFileName(owner, name, document.name.orEmpty())
                }
                .forEach { document ->
                    if (!document.delete()) {
                        failures += document.name ?: "legacy mirror"
                    }
                }

            if (repositoryFolder.listFiles().isEmpty()) {
                repositoryFolder.delete()
            }
            if (ownerFolder.listFiles().isEmpty()) {
                ownerFolder.delete()
            }
            failures
        }

    suspend fun delete(repositoryId: Long) = withContext(Dispatchers.IO) {
        val folder = repositoryFolder(repositoryId, create = false) ?: return@withContext
        folder.findFile(PENDING_NAME)?.delete()
        folder.findFile(STABLE_NAME)?.delete()
        if (folder.listFiles().isEmpty()) {
            folder.delete()
        }
    }

    private fun reconcileFolder(repositoryId: Long, folder: DocumentFile): Boolean {
        val stable = folder.findFile(STABLE_NAME)
        val pending = folder.findFile(PENDING_NAME) ?: return false

        if (stable != null) {
            // A crash before commit left a candidate behind. The stable mirror
            // is authoritative, so discard the candidate.
            pending.delete()
            return false
        }

        // No stable archive means the process may have died between retiring
        // the old file and renaming the already-verified pending file, or during
        // a first backup. Re-verify the pending archive before promoting it.
        val scratch = File(context.cacheDir, "mirror-reconcile-$repositoryId").apply {
            parentFile?.mkdirs()
            delete()
        }
        val verifyDirectory = File(context.cacheDir, "mirror-reconcile-$repositoryId-verify")
        var verified = false
        return try {
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
            verified = true

            check(pending.renameTo(STABLE_NAME)) {
                "Verified pending mirror could not be promoted"
            }
            true
        } catch (_: Throwable) {
            // Never destroy the only verified copy merely because the document
            // provider refused a rename. The normal read path accepts a pending
            // file when no stable mirror exists and can retry promotion later.
            if (!verified) pending.delete()
            false
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
            ?: if (create) {
                root.createDirectory(ROOT_DIRECTORY)
                    ?: throw IOException("Could not create backup root directory")
            } else {
                return null
            }

        val id = repositoryId.toString()
        backups.findFile(id)?.takeIf { it.isDirectory }?.let { return it }
        return if (create) {
            backups.createDirectory(id)
                ?: throw IOException("Could not create repository backup directory")
        } else {
            null
        }
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

    internal fun isLegacyMirrorFileName(owner: String, name: String, fileName: String): Boolean {
    val safeName = "$owner-$name".replace(Regex("[^A-Za-z0-9._-]"), "_")
    val stable = "$safeName.mirror.zip"
    if (fileName == stable) return true
    return fileName.startsWith("$safeName.pending-") && fileName.endsWith(".mirror.zip")
}

internal fun requiredUpdateWorkspaceBytes(compressedBytes: Long, expandedBytes: Long): Long {
    require(compressedBytes >= 0L) { "Compressed size cannot be negative" }
    require(expandedBytes >= 0L) { "Expanded size cannot be negative" }

    fun add(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    fun multiply(value: Long, factor: Long): Long =
        if (value == 0L || factor == 0L) 0L
        else if (value > Long.MAX_VALUE / factor) Long.MAX_VALUE
        else value * factor

    val archiveCopies = multiply(compressedBytes, 2L)
    val growthHeadroom = maxOf(expandedBytes / 4L, MIN_UPDATE_HEADROOM_BYTES)
    return add(add(archiveCopies, expandedBytes), growthHeadroom)
}

private const val MIN_UPDATE_HEADROOM_BYTES = 64L * 1024L * 1024L

private companion object {
        const val ROOT_DIRECTORY = "GitHub Backups"
        const val STABLE_NAME = "mirror.tar.gz"
        const val PENDING_NAME = "mirror.pending.tar.gz"
        const val MIME_TYPE = "application/gzip"
        const val BUFFER_SIZE = 256 * 1024
    }
}
