package com.skypie0102.githubbckp.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.skypie0102.githubbckp.mirror.TarGzArchive
import com.skypie0102.githubbckp.release.LatestReleaseManifest
import com.skypie0102.githubbckp.release.LatestReleaseVerifier
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

data class StoredLatestRelease(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Singleton
class LocalLatestReleaseStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: StoragePreferences,
) {
    suspend fun current(repositoryId: Long): StoredLatestRelease? = withContext(Dispatchers.IO) {
        val releaseDirectory = releaseDirectory(repositoryId, create = false)
            ?: return@withContext null
        reconcile(repositoryId, releaseDirectory)
        stableDocuments(releaseDirectory).firstOrNull()?.let(::storedRelease)
    }

    suspend fun readManifest(repositoryId: Long): LatestReleaseManifest? =
        withContext(Dispatchers.IO) {
            val releaseDirectory = releaseDirectory(repositoryId, create = false)
                ?: return@withContext null
            reconcile(repositoryId, releaseDirectory)
            val stable = stableDocuments(releaseDirectory).firstOrNull()
                ?: return@withContext null
            val input = context.contentResolver.openInputStream(stable.uri)
                ?: throw IOException("Stored latest release can no longer be opened")
            input.use {
                LatestReleaseManifest.fromJson(
                    TarGzArchive.readTextEntry(it, LatestReleaseManifest.FILE_NAME),
                )
            }
        }

    suspend fun commit(
        repositoryId: Long,
        owner: String,
        name: String,
        tagName: String,
        verifiedArchive: File,
        expectedSha256: String,
        onProgress: suspend (writtenBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): StoredLatestRelease = withContext(Dispatchers.IO) {
        require(verifiedArchive.isFile) { "Verified latest release archive is missing" }

        val releaseDirectory = releaseDirectory(
            repositoryId = repositoryId,
            owner = owner,
            name = name,
            create = true,
        ) ?: throw IOException("Could not create latest release backup directory")
        reconcile(repositoryId, releaseDirectory)

        pendingDocument(releaseDirectory)?.delete()
        val pendingName = latestReleasePendingName(repositoryId, owner, name)
        val pending = releaseDirectory.createFile(MIME_TYPE, pendingName)
            ?: throw IOException("Could not create pending latest release archive")

        try {
            val total = verifiedArchive.length()
            var written = 0L
            val output = context.contentResolver.openOutputStream(pending.uri, "wt")
                ?: throw IOException("Could not open pending latest release archive")
            output.buffered(BUFFER_SIZE).use { destination ->
                FileInputStream(verifiedArchive).buffered(BUFFER_SIZE).use { source ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        destination.write(buffer, 0, count)
                        written += count
                        onProgress(written, total)
                    }
                }
            }

            val persisted = storedRelease(pending)
            check(persisted.sizeBytes == total) {
                "Pending latest release size verification failed"
            }
            check(persisted.sha256.equals(expectedSha256, ignoreCase = true)) {
                "Pending latest release SHA-256 verification failed"
            }

            stableDocuments(releaseDirectory).forEach { stable ->
                check(stable.delete()) {
                    "Could not retire previous latest release archive"
                }
            }

            val stableName = latestReleaseStableName(repositoryId, owner, name, tagName)
            check(pending.renameTo(stableName)) {
                "Could not promote pending latest release archive"
            }
            val committed = releaseDirectory.findFile(stableName)
                ?: throw IOException("Committed latest release archive could not be found")
            storedRelease(committed)
        } catch (throwable: Throwable) {
            if (stableDocuments(releaseDirectory).isNotEmpty()) {
                runCatching { pending.delete() }
            }
            throw throwable
        }
    }

    suspend fun reconcile(repositoryId: Long): Boolean = withContext(Dispatchers.IO) {
        val directory = releaseDirectory(repositoryId, create = false)
            ?: return@withContext false
        reconcile(repositoryId, directory)
    }

    private fun reconcile(
        repositoryId: Long,
        directory: DocumentFile,
    ): Boolean {
        val pending = pendingDocument(directory) ?: return false
        if (stableDocuments(directory).isNotEmpty()) {
            pending.delete()
            return false
        }

        val scratch = File(context.cacheDir, "latest-release-reconcile-$repositoryId.tar.gz")
        val verifyDirectory = File(context.cacheDir, "latest-release-reconcile-$repositoryId")
        var verified = false
        return try {
            val input = context.contentResolver.openInputStream(pending.uri)
                ?: throw IOException("Could not reopen pending latest release archive")
            input.buffered(BUFFER_SIZE).use { source ->
                FileOutputStream(scratch).buffered(BUFFER_SIZE).use { destination ->
                    source.copyTo(destination, BUFFER_SIZE)
                }
            }

            val result = LatestReleaseVerifier.verify(
                archive = scratch,
                verificationDirectory = verifyDirectory,
                expectedRepositoryId = repositoryId,
            )
            verified = true
            val stableName = latestReleaseStableName(
                repositoryId = repositoryId,
                owner = result.manifest.repositoryOwner,
                name = result.manifest.repositoryName,
                tagName = result.manifest.tagName,
            )
            check(pending.renameTo(stableName)) {
                "Verified pending latest release could not be promoted"
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

    private fun releaseDirectory(
        repositoryId: Long,
        owner: String? = null,
        name: String? = null,
        create: Boolean,
    ): DocumentFile? {
        val backups = backupRoot(create) ?: return null
        val repositoryFolder = backups.listFiles()
            .firstOrNull {
                it.isDirectory && repositoryFolderMatches(it.name.orEmpty(), repositoryId)
            }
            ?: if (create && owner != null && name != null) {
                backups.createDirectory(mirrorStorageNames(repositoryId, owner, name).folderName)
            } else {
                null
            }
            ?: return null

        repositoryFolder.findFile(RELEASE_DIRECTORY)
            ?.takeIf { it.isDirectory }
            ?.let { return it }

        return if (create) repositoryFolder.createDirectory(RELEASE_DIRECTORY) else null
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

    private fun stableDocuments(directory: DocumentFile): List<DocumentFile> =
        directory.listFiles().filter { document ->
            document.isFile &&
                document.name.orEmpty().endsWith(RELEASE_SUFFIX) &&
                !document.name.orEmpty().endsWith(PENDING_SUFFIX)
        }

    private fun pendingDocument(directory: DocumentFile): DocumentFile? =
        directory.listFiles().firstOrNull { document ->
            document.isFile && document.name.orEmpty().endsWith(PENDING_SUFFIX)
        }

    private fun storedRelease(document: DocumentFile): StoredLatestRelease {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val input = context.contentResolver.openInputStream(document.uri)
            ?: throw IOException("Stored latest release can no longer be opened")
        input.use {
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
                size += count
            }
        }
        return StoredLatestRelease(
            uri = document.uri,
            name = document.name ?: "latest-release.tar.gz",
            sizeBytes = size,
            sha256 = digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            },
        )
    }

    private companion object {
        const val ROOT_DIRECTORY = "GitHub Backups"
        const val RELEASE_DIRECTORY = "latest-release"
        const val RELEASE_SUFFIX = "--release.tar.gz"
        const val PENDING_SUFFIX = "--latest-release.pending.tar.gz"
        const val MIME_TYPE = "application/gzip"
        const val BUFFER_SIZE = 256 * 1024
    }
}

internal fun latestReleaseStableName(
    repositoryId: Long,
    owner: String,
    name: String,
    tagName: String,
): String {
    val label = mirrorStorageNames(repositoryId, owner, name)
        .stableFileName
        .removeSuffix(".tar.gz")
    return "$label--${safeReleaseNamePart(tagName)}--release.tar.gz"
}

internal fun latestReleasePendingName(
    repositoryId: Long,
    owner: String,
    name: String,
): String {
    val label = mirrorStorageNames(repositoryId, owner, name)
        .stableFileName
        .removeSuffix(".tar.gz")
    return "$label--latest-release.pending.tar.gz"
}

internal fun safeReleaseNamePart(value: String): String =
    value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_')
        .ifBlank { "release" }
        .take(120)
