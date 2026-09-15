package com.skypie0102.githubbckp.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class MirrorRestoreRecord(
    val id: String,
    val archiveName: String,
    val createdAtEpochMs: Long,
    val refCount: Int,
    val referencedObjectsVerified: Int,
)

@Singleton
class MirrorRestoreCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val restoreService: GitMirrorRestoreService,
) {
    suspend fun restore(uri: Uri): MirrorRestoreRecord = withContext(Dispatchers.IO) {
        val createdAt = System.currentTimeMillis()
        val id = "$createdAt-${UUID.randomUUID()}"
        val entryDirectory = File(restoresRoot, id)
        val repositoryDirectory = File(entryDirectory, REPOSITORY_DIRECTORY_NAME)
        val scratchDirectory = File(context.cacheDir, "restore-import/$id")
        val archiveName = queryDisplayName(uri) ?: "mirror-backup.zip"
        val localArchive = File(scratchDirectory, safeFileName(archiveName))

        try {
            scratchDirectory.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(localArchive).buffered().use { output ->
                    input.copyTo(output, bufferSize = COPY_BUFFER_SIZE)
                }
            } ?: error("Unable to open the selected mirror archive")
            check(localArchive.length() > 0L) { "Selected mirror archive is empty" }

            entryDirectory.mkdirs()
            val result = restoreService.restore(
                archive = localArchive,
                destination = repositoryDirectory,
            )
            val record = MirrorRestoreRecord(
                id = id,
                archiveName = archiveName,
                createdAtEpochMs = createdAt,
                refCount = result.refNames.size,
                referencedObjectsVerified = result.referencedObjectsVerified,
            )
            writeMetadata(entryDirectory, record)
            record
        } catch (throwable: Throwable) {
            entryDirectory.deleteRecursively()
            throw throwable
        } finally {
            scratchDirectory.deleteRecursively()
        }
    }

    suspend fun listRestores(): List<MirrorRestoreRecord> = withContext(Dispatchers.IO) {
        restoresRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull(::readMetadata)
            .sortedByDescending { it.createdAtEpochMs }
    }

    suspend fun deleteRestore(id: String) = withContext(Dispatchers.IO) {
        val root = restoresRoot.canonicalFile
        val candidate = File(root, id).canonicalFile
        check(candidate.parentFile == root) { "Invalid restore identifier" }
        if (candidate.exists() && !candidate.deleteRecursively()) {
            error("Unable to delete restored mirror")
        }
    }

    private val restoresRoot: File
        get() = File(context.filesDir, RESTORES_DIRECTORY_NAME).apply { mkdirs() }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun writeMetadata(directory: File, record: MirrorRestoreRecord) {
        val metadata = JSONObject()
            .put("id", record.id)
            .put("archiveName", record.archiveName)
            .put("createdAtEpochMs", record.createdAtEpochMs)
            .put("refCount", record.refCount)
            .put("referencedObjectsVerified", record.referencedObjectsVerified)
        File(directory, METADATA_FILE_NAME).writeText(metadata.toString())
    }

    private fun readMetadata(directory: File): MirrorRestoreRecord? = runCatching {
        val repository = File(directory, REPOSITORY_DIRECTORY_NAME)
        check(File(repository, "HEAD").isFile)
        check(File(repository, "objects").isDirectory)
        val json = JSONObject(File(directory, METADATA_FILE_NAME).readText())
        MirrorRestoreRecord(
            id = json.getString("id"),
            archiveName = json.getString("archiveName"),
            createdAtEpochMs = json.getLong("createdAtEpochMs"),
            refCount = json.getInt("refCount"),
            referencedObjectsVerified = json.getInt("referencedObjectsVerified"),
        )
    }.getOrNull()

    private fun safeFileName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(120)
        .ifBlank { "mirror-backup.zip" }

    private companion object {
        const val RESTORES_DIRECTORY_NAME = "restored-mirrors"
        const val REPOSITORY_DIRECTORY_NAME = "repository.git"
        const val METADATA_FILE_NAME = "restore.json"
        const val COPY_BUFFER_SIZE = 256 * 1024
    }
}
