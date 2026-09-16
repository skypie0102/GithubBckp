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
    val detailsAvailable: Boolean = true,
    val lfsObjectCount: Int = 0,
    val wikiRefCount: Int = 0,
    val wikiReferencedObjectsVerified: Int = 0,
    val releaseCount: Int = 0,
    val releaseAssetCount: Int = 0,
    val issueCount: Int = 0,
    val pullRequestCount: Int = 0,
    val issueCommentCount: Int = 0,
    val reviewCommentCount: Int = 0,
    val reviewCount: Int = 0,
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
                detailsAvailable = true,
                lfsObjectCount = result.lfsObjectCount,
                wikiRefCount = result.wikiRefNames.size,
                wikiReferencedObjectsVerified = result.wikiReferencedObjectsVerified,
                releaseCount = result.releaseCount,
                releaseAssetCount = result.releaseAssetCount,
                issueCount = result.issueCount,
                pullRequestCount = result.pullRequestCount,
                issueCommentCount = result.issueCommentCount,
                reviewCommentCount = result.reviewCommentCount,
                reviewCount = result.reviewCount,
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

    suspend fun requireRepositoryDirectory(id: String): File = withContext(Dispatchers.IO) {
        val entry = requireEntryDirectory(id)
        check(readMetadata(entry) != null) { "Restored mirror is missing or invalid" }
        File(entry, REPOSITORY_DIRECTORY_NAME).canonicalFile
    }

    suspend fun exportAuditReport(id: String, destination: Uri) = withContext(Dispatchers.IO) {
        val entry = requireEntryDirectory(id)
        val record = readMetadata(entry)
            ?: error("Restored mirror is missing or invalid")
        val report = record.toAuditSnapshot().toAuditJson().toString(2)
        context.contentResolver.openOutputStream(destination, "wt")
            ?.bufferedWriter()
            ?.use { writer -> writer.write(report) }
            ?: error("Unable to create the restore audit report")
    }

    suspend fun deleteRestore(id: String) = withContext(Dispatchers.IO) {
        val candidate = requireEntryDirectory(id)
        if (candidate.exists() && !candidate.deleteRecursively()) {
            error("Unable to delete restored mirror")
        }
    }

    private val restoresRoot: File
        get() = File(context.filesDir, RESTORES_DIRECTORY_NAME).apply { mkdirs() }

    private fun requireEntryDirectory(id: String): File {
        val root = restoresRoot.canonicalFile
        val candidate = File(root, id).canonicalFile
        check(candidate.parentFile == root) { "Invalid restore identifier" }
        return candidate
    }

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
            .put("version", METADATA_VERSION)
            .put("id", record.id)
            .put("archiveName", record.archiveName)
            .put("createdAtEpochMs", record.createdAtEpochMs)
            .put("refCount", record.refCount)
            .put("referencedObjectsVerified", record.referencedObjectsVerified)
            .put("lfsObjectCount", record.lfsObjectCount)
            .put("wikiRefCount", record.wikiRefCount)
            .put("wikiReferencedObjectsVerified", record.wikiReferencedObjectsVerified)
            .put("releaseCount", record.releaseCount)
            .put("releaseAssetCount", record.releaseAssetCount)
            .put("issueCount", record.issueCount)
            .put("pullRequestCount", record.pullRequestCount)
            .put("issueCommentCount", record.issueCommentCount)
            .put("reviewCommentCount", record.reviewCommentCount)
            .put("reviewCount", record.reviewCount)
        File(directory, METADATA_FILE_NAME).writeText(metadata.toString())
    }

    private fun readMetadata(directory: File): MirrorRestoreRecord? = runCatching {
        val repository = File(directory, REPOSITORY_DIRECTORY_NAME)
        check(File(repository, "HEAD").isFile)
        check(File(repository, "objects").isDirectory)
        val json = JSONObject(File(directory, METADATA_FILE_NAME).readText())
        val id = json.getString("id")
        check(id == directory.name)
        val version = json.optInt("version", 1)
        MirrorRestoreRecord(
            id = id,
            archiveName = json.getString("archiveName"),
            createdAtEpochMs = json.getLong("createdAtEpochMs"),
            refCount = json.getInt("refCount"),
            referencedObjectsVerified = json.getInt("referencedObjectsVerified"),
            detailsAvailable = version >= METADATA_VERSION,
            lfsObjectCount = json.optInt("lfsObjectCount", 0),
            wikiRefCount = json.optInt("wikiRefCount", 0),
            wikiReferencedObjectsVerified = json.optInt("wikiReferencedObjectsVerified", 0),
            releaseCount = json.optInt("releaseCount", 0),
            releaseAssetCount = json.optInt("releaseAssetCount", 0),
            issueCount = json.optInt("issueCount", 0),
            pullRequestCount = json.optInt("pullRequestCount", 0),
            issueCommentCount = json.optInt("issueCommentCount", 0),
            reviewCommentCount = json.optInt("reviewCommentCount", 0),
            reviewCount = json.optInt("reviewCount", 0),
        )
    }.getOrNull()

    private fun safeFileName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(120)
        .ifBlank { "mirror-backup.zip" }

    private companion object {
        const val METADATA_VERSION = 2
        const val RESTORES_DIRECTORY_NAME = "restored-mirrors"
        const val REPOSITORY_DIRECTORY_NAME = "repository.git"
        const val METADATA_FILE_NAME = "restore.json"
        const val COPY_BUFFER_SIZE = 256 * 1024
    }
}
