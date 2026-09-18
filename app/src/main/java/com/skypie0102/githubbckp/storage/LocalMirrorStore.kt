package com.skypie0102.githubbckp.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.skypie0102.githubbckp.mirror.MirrorManifest
import com.skypie0102.githubbckp.mirror.MirrorVerifier
import com.skypie0102.githubbckp.mirror.TarGzArchive
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

internal data class MirrorStorageNames(
    val folderName: String,
    val stableFileName: String,
    val pendingFileName: String,
)

/**
 * Durable local mirror storage.
 *
 * Human-readable layout:
 *
 *   GitHub Backups/<owner>--<repo>--<repository-id>/<owner>--<repo>.tar.gz
 *
 * The GitHub repository ID remains in the folder name so identity stays stable
 * across duplicate names and repository renames. Repository name changes are
 * migrated transactionally on the next successful mirror update.
 */
@Singleton
class LocalMirrorStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: StoragePreferences,
) {
    suspend fun currentMirror(repositoryId: Long): StoredMirror? = withContext(Dispatchers.IO) {
        for (folder in repositoryFolders(repositoryId)) {
            reconcileFolder(repositoryId, folder)
            currentMirrorDocument(folder)?.let { return@withContext storedMirror(it) }
        }
        null
    }

    suspend fun readManifest(repositoryId: Long): MirrorManifest? = withContext(Dispatchers.IO) {
        for (folder in repositoryFolders(repositoryId)) {
            reconcileFolder(repositoryId, folder)
            val document = currentMirrorDocument(folder) ?: continue
            val input = context.contentResolver.openInputStream(document.uri)
                ?: throw IOException("Stored mirror can no longer be opened")
            return@withContext input.use(MirrorManifest::readFromArchive)
        }
        null
    }

    suspend fun estimateUpdateWorkingBytes(repositoryId: Long): Long? = withContext(Dispatchers.IO) {
        for (folder in repositoryFolders(repositoryId)) {
            reconcileFolder(repositoryId, folder)
            val document = currentMirrorDocument(folder) ?: continue
            val compressedBytes =
                document.length().takeIf { it > 0L } ?: storedMirror(document).sizeBytes
            val input = context.contentResolver.openInputStream(document.uri)
                ?: throw IOException("Stored mirror can no longer be opened")
            val expandedBytes = input.use(TarGzArchive::expandedSizeBytes)
            return@withContext requiredUpdateWorkspaceBytes(compressedBytes, expandedBytes)
        }
        null
    }

    suspend fun copyCurrentTo(repositoryId: Long, destination: File): StoredMirror? =
        withContext(Dispatchers.IO) {
            for (folder in repositoryFolders(repositoryId)) {
                reconcileFolder(repositoryId, folder)
                val document = currentMirrorDocument(folder) ?: continue

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

                return@withContext StoredMirror(
                    uri = document.uri,
                    name = document.name ?: "mirror.tar.gz",
                    sizeBytes = size,
                    sha256 = digest.digest().joinToString("") { byte ->
                        "%02x".format(byte.toInt() and 0xff)
                    },
                )
            }
            null
        }

    suspend fun commit(
        repositoryId: Long,
        owner: String,
        name: String,
        verifiedArchive: File,
        expectedSha256: String,
        onProgress: suspend (writtenBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): StoredMirror = withContext(Dispatchers.IO) {
        require(verifiedArchive.isFile) { "Verified mirror archive is missing" }

        val names = mirrorStorageNames(repositoryId, owner, name)
        val previousFolders = repositoryFolders(repositoryId)
        val folder = namedRepositoryFolder(names, create = true)
            ?: throw IOException("Could not create repository backup folder")
        reconcileFolder(repositoryId, folder)

        pendingMirrorDocument(folder)?.delete()
        val pending = folder.createFile(MIME_TYPE, names.pendingFileName)
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

            stableMirrorDocument(folder)?.let { stable ->
                check(stable.delete()) { "Could not retire previous mirror archive" }
            }

            check(pending.renameTo(names.stableFileName)) {
                "Could not promote pending mirror archive"
            }

            val committed = folder.findFile(names.stableFileName)
                ?: stableMirrorDocument(folder)
                ?: throw IOException("Committed mirror archive could not be found")
            val result = storedMirror(committed)

            // Only after the human-readable replacement is persisted, hashed,
            // and promoted do we retire old numeric/renamed repository folders.
            previousFolders
                .filter { it.uri != folder.uri }
                .forEach { previous ->
                    deleteMirrorFiles(previous)
                    if (previous.listFiles().isEmpty()) previous.delete()
                }

            result
        } catch (throwable: Throwable) {
            // If a prior stable copy still exists, discard the failed candidate.
            // Otherwise keep a verified pending candidate for startup recovery.
            if (stableMirrorDocument(folder) != null) {
                runCatching { pending.delete() }
            }
            throw throwable
        }
    }

    suspend fun reconcile(repositoryId: Long): Boolean = withContext(Dispatchers.IO) {
        repositoryFolders(repositoryId).any { folder ->
            reconcileFolder(repositoryId, folder)
        }
    }

    suspend fun hasCurrentMirror(repositoryId: Long): Boolean = withContext(Dispatchers.IO) {
        repositoryFolders(repositoryId).any { folder ->
            reconcileFolder(repositoryId, folder)
            currentMirrorDocument(folder) != null
        }
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
                    if (!document.delete()) failures += document.name ?: "legacy mirror"
                }

            if (repositoryFolder.listFiles().isEmpty()) repositoryFolder.delete()
            if (ownerFolder.listFiles().isEmpty()) ownerFolder.delete()
            failures
        }

    suspend fun delete(repositoryId: Long) = withContext(Dispatchers.IO) {
        repositoryFolders(repositoryId).forEach { folder ->
            deleteMirrorFiles(folder)
            if (folder.listFiles().isEmpty()) folder.delete()
        }
    }

    private fun reconcileFolder(repositoryId: Long, folder: DocumentFile): Boolean {
        val stable = stableMirrorDocument(folder)
        val pending = pendingMirrorDocument(folder)
        when (
            pendingMirrorRecoveryAction(
                stableExists = stable != null,
                pendingExists = pending != null,
            )
        ) {
            PendingMirrorRecoveryAction.NONE -> return false
            PendingMirrorRecoveryAction.DISCARD_PENDING -> {
                pending?.delete()
                return false
            }
            PendingMirrorRecoveryAction.VERIFY_AND_PROMOTE -> Unit
        }

        checkNotNull(pending)

        val scratch = File(context.cacheDir, "mirror-reconcile-$repositoryId").apply {
            parentFile?.mkdirs()
            delete()
        }
        val verifyDirectory =
            File(context.cacheDir, "mirror-reconcile-$repositoryId-verify")
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

            val stableName = stableFileNameForPending(pending.name.orEmpty())
            check(pending.renameTo(stableName)) {
                "Verified pending mirror could not be promoted"
            }
            true
        } catch (_: Throwable) {
            if (!verified) pending.delete()
            false
        } finally {
            scratch.delete()
            verifyDirectory.deleteRecursively()
        }
    }

    private fun repositoryFolders(repositoryId: Long): List<DocumentFile> {
        val backups = backupRoot(create = false) ?: return emptyList()
        return backups.listFiles()
            .asSequence()
            .filter { it.isDirectory }
            .filter { repositoryFolderMatches(it.name.orEmpty(), repositoryId) }
            .sortedBy { it.name == repositoryId.toString() }
            .toList()
    }

    private fun namedRepositoryFolder(
        names: MirrorStorageNames,
        create: Boolean,
    ): DocumentFile? {
        val backups = backupRoot(create) ?: return null
        backups.findFile(names.folderName)?.takeIf { it.isDirectory }?.let { return it }
        return if (create) backups.createDirectory(names.folderName) else null
    }

    private fun backupRoot(create: Boolean): DocumentFile? {
        val rootUri = preferences.documentTreeUri() ?: return null
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: throw IOException("The selected backup folder is no longer available")
        check(root.canWrite()) { "The selected backup folder is read-only" }

        return root.findFile(ROOT_DIRECTORY)?.takeIf { it.isDirectory }
            ?: if (create) {
                root.createDirectory(ROOT_DIRECTORY)
                    ?: throw IOException("Could not create backup root directory")
            } else {
                null
            }
    }

    private fun currentMirrorDocument(folder: DocumentFile): DocumentFile? =
        stableMirrorDocument(folder) ?: pendingMirrorDocument(folder)

    private fun stableMirrorDocument(folder: DocumentFile): DocumentFile? =
        folder.listFiles()
            .asSequence()
            .filter { it.isFile && isStableMirrorArchiveName(it.name.orEmpty()) }
            .sortedBy { it.name == LEGACY_STABLE_NAME }
            .firstOrNull()

    private fun pendingMirrorDocument(folder: DocumentFile): DocumentFile? =
        folder.listFiles()
            .firstOrNull { it.isFile && isPendingMirrorArchiveName(it.name.orEmpty()) }

    private fun deleteMirrorFiles(folder: DocumentFile) {
        folder.listFiles()
            .filter { document ->
                document.isFile && (
                    isStableMirrorArchiveName(document.name.orEmpty()) ||
                        isPendingMirrorArchiveName(document.name.orEmpty())
                    )
            }
            .forEach { it.delete() }
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
            name = document.name ?: LEGACY_STABLE_NAME,
            sizeBytes = size,
            sha256 = digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            },
        )
    }

    private companion object {
        const val ROOT_DIRECTORY = "GitHub Backups"
        const val LEGACY_STABLE_NAME = "mirror.tar.gz"
        const val MIME_TYPE = "application/gzip"
        const val BUFFER_SIZE = 256 * 1024
    }
}

internal fun mirrorStorageNames(
    repositoryId: Long,
    owner: String,
    name: String,
): MirrorStorageNames {
    val label = readableRepositoryLabel(owner, name)
    return MirrorStorageNames(
        folderName = "$label--$repositoryId",
        stableFileName = "$label.tar.gz",
        pendingFileName = "$label.pending.tar.gz",
    )
}

internal fun repositoryFolderMatches(folderName: String, repositoryId: Long): Boolean =
    folderName == repositoryId.toString() || folderName.endsWith("--$repositoryId")

internal fun isStableMirrorArchiveName(fileName: String): Boolean =
    fileName.endsWith(".tar.gz") && !isPendingMirrorArchiveName(fileName)

internal fun isPendingMirrorArchiveName(fileName: String): Boolean =
    fileName.endsWith(".pending.tar.gz")

internal fun stableFileNameForPending(fileName: String): String =
    if (fileName.endsWith(".pending.tar.gz")) {
        fileName.removeSuffix(".pending.tar.gz") + ".tar.gz"
    } else {
        "mirror.tar.gz"
    }

private fun readableRepositoryLabel(owner: String, name: String): String {
    val raw = "$owner--$name"
    return raw
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_')
        .ifBlank { "repository" }
        .take(MAX_READABLE_LABEL_LENGTH)
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
private const val MAX_READABLE_LABEL_LENGTH = 180

internal enum class PendingMirrorRecoveryAction {
    NONE,
    DISCARD_PENDING,
    VERIFY_AND_PROMOTE,
}

internal fun pendingMirrorRecoveryAction(
    stableExists: Boolean,
    pendingExists: Boolean,
): PendingMirrorRecoveryAction = when {
    !pendingExists -> PendingMirrorRecoveryAction.NONE
    stableExists -> PendingMirrorRecoveryAction.DISCARD_PENDING
    else -> PendingMirrorRecoveryAction.VERIFY_AND_PROMOTE
}
