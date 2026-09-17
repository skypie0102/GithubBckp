package com.skypie0102.githubbckp.backup

import android.content.Context
import android.net.Uri
import com.skypie0102.githubbckp.github.GithubAuthManager
import com.skypie0102.githubbckp.github.GithubRepositoryRestoreGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.json.JSONObject

enum class RecoveryDrillStatus {
    VERIFIED,
    FAILED,
}

data class RecoveryDrillRecord(
    val id: String,
    val restoreId: String,
    val archiveName: String,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
    val status: RecoveryDrillStatus,
    val targetKind: RecoveryTargetKind,
    val targetInput: String,
    val repositoryFullName: String?,
    val repositoryUrl: String?,
    val verifiedRefCount: Int,
    val lfsObjectCount: Int,
    val lfsRepresentativeDownloads: Int,
    val releaseCount: Int,
    val releaseAssetCount: Int,
    val archivalWikiRefCount: Int,
    val archivalDiscussionRecordCount: Int,
    val message: String,
)

fun RecoveryDrillRecord.auditFileName(): String {
    val target = repositoryFullName
        ?.replace('/', '-')
        ?.replace(Regex("[^A-Za-z0-9._-]"), "-")
        ?.take(100)
        ?.ifBlank { null }
        ?: "recovery-drill"
    return "$target-drill-audit.json"
}

fun RecoveryDrillRecord.toAuditJson(): JSONObject = JSONObject()
    .put("formatVersion", 1)
    .put("reportType", "github-recovery-drill")
    .put("drillId", id)
    .put("restoreId", restoreId)
    .put("archiveName", archiveName)
    .put("startedAtEpochMs", startedAtEpochMs)
    .put("completedAtEpochMs", completedAtEpochMs)
    .put("status", status.name)
    .put(
        "target",
        JSONObject()
            .put("kind", targetKind.name)
            .put("requested", targetInput)
            .putNullable("repositoryFullName", repositoryFullName)
            .putNullable("repositoryUrl", repositoryUrl)
            .put("privateRequired", true),
    )
    .put(
        "mainGit",
        JSONObject()
            .put("publication", "automatic")
            .put("remoteRefsVerified", verifiedRefCount),
    )
    .put(
        "gitLfs",
        JSONObject()
            .put("publication", "automatic")
            .put("remoteObjectsAvailable", lfsObjectCount)
            .put("representativeObjectsDownloadedAndVerified", lfsRepresentativeDownloads),
    )
    .put(
        "releases",
        JSONObject()
            .put("publication", "automatic")
            .put("remoteReleasesVerified", releaseCount)
            .put("remoteAssetsVerified", releaseAssetCount),
    )
    .put(
        "wiki",
        JSONObject()
            .put("publication", "archival-only")
            .put("preservedRefCount", archivalWikiRefCount),
    )
    .put(
        "discussions",
        JSONObject()
            .put("publication", "archival-only")
            .put("preservedRecordCount", archivalDiscussionRecordCount),
    )
    .put("message", message)
    .put("targetDeletion", "never automatic")

@Singleton
class RecoveryDrillStore {
    private val rootDirectory: File

    @Inject
    constructor(@ApplicationContext context: Context) {
        rootDirectory = File(context.filesDir, DIRECTORY_NAME)
    }

    internal constructor(rootDirectory: File) {
        this.rootDirectory = rootDirectory
    }

    @Synchronized
    fun write(record: RecoveryDrillRecord) {
        rootDirectory.mkdirs()
        val target = fileFor(record.id)
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(serialize(record).toString())
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    @Synchronized
    fun list(): List<RecoveryDrillRecord> {
        rootDirectory.mkdirs()
        return rootDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { file -> runCatching { parse(file.readText()) }.getOrNull() }
            .sortedByDescending { it.completedAtEpochMs }
    }

    @Synchronized
    fun get(id: String): RecoveryDrillRecord? {
        val file = fileFor(id)
        return if (file.isFile) parse(file.readText()) else null
    }

    private fun fileFor(id: String): File {
        if (!ID_REGEX.matches(id)) throw IOException("Invalid recovery drill identifier")
        rootDirectory.mkdirs()
        val root = rootDirectory.canonicalFile
        val file = File(root, "$id.json").canonicalFile
        if (file.parentFile != root) throw IOException("Invalid recovery drill path")
        return file
    }

    private fun serialize(record: RecoveryDrillRecord): JSONObject = JSONObject()
        .put("version", FORMAT_VERSION)
        .put("id", record.id)
        .put("restoreId", record.restoreId)
        .put("archiveName", record.archiveName)
        .put("startedAtEpochMs", record.startedAtEpochMs)
        .put("completedAtEpochMs", record.completedAtEpochMs)
        .put("status", record.status.name)
        .put("targetKind", record.targetKind.name)
        .put("targetInput", record.targetInput)
        .putNullable("repositoryFullName", record.repositoryFullName)
        .putNullable("repositoryUrl", record.repositoryUrl)
        .put("verifiedRefCount", record.verifiedRefCount)
        .put("lfsObjectCount", record.lfsObjectCount)
        .put("lfsRepresentativeDownloads", record.lfsRepresentativeDownloads)
        .put("releaseCount", record.releaseCount)
        .put("releaseAssetCount", record.releaseAssetCount)
        .put("archivalWikiRefCount", record.archivalWikiRefCount)
        .put("archivalDiscussionRecordCount", record.archivalDiscussionRecordCount)
        .put("message", record.message)

    private fun parse(text: String): RecoveryDrillRecord {
        try {
            val json = JSONObject(text)
            if (json.getInt("version") != FORMAT_VERSION) {
                throw IOException("Unsupported recovery drill record version")
            }
            return RecoveryDrillRecord(
                id = json.getString("id"),
                restoreId = json.getString("restoreId"),
                archiveName = json.getString("archiveName"),
                startedAtEpochMs = json.getLong("startedAtEpochMs"),
                completedAtEpochMs = json.getLong("completedAtEpochMs"),
                status = RecoveryDrillStatus.valueOf(json.getString("status")),
                targetKind = RecoveryTargetKind.valueOf(json.getString("targetKind")),
                targetInput = json.getString("targetInput"),
                repositoryFullName = json.optNullableString("repositoryFullName"),
                repositoryUrl = json.optNullableString("repositoryUrl"),
                verifiedRefCount = json.optInt("verifiedRefCount", 0),
                lfsObjectCount = json.optInt("lfsObjectCount", 0),
                lfsRepresentativeDownloads = json.optInt("lfsRepresentativeDownloads", 0),
                releaseCount = json.optInt("releaseCount", 0),
                releaseAssetCount = json.optInt("releaseAssetCount", 0),
                archivalWikiRefCount = json.optInt("archivalWikiRefCount", 0),
                archivalDiscussionRecordCount = json.optInt("archivalDiscussionRecordCount", 0),
                message = json.optString("message", ""),
            )
        } catch (throwable: IOException) {
            throw throwable
        } catch (throwable: Exception) {
            throw IOException("Recovery drill record is corrupt", throwable)
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "recovery-drills"
        const val FORMAT_VERSION = 1
        val ID_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}

@Singleton
class RecoveryDrillCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val restoreCoordinator: MirrorRestoreCoordinator,
    private val restorePublisher: GithubMirrorRestorePublisher,
    private val repositoryGateway: GithubRepositoryRestoreGateway,
    private val authManager: GithubAuthManager,
    private val pushService: GitMirrorPushService,
    private val lfsPointerScanner: GitLfsPointerScanner,
    private val lfsDownloadService: GitLfsDownloadService,
    private val releaseVerifier: RecoveryDrillReleaseVerifier,
    private val transactionStore: RecoveryTransactionStore,
    private val store: RecoveryDrillStore,
) {
    suspend fun runToNewPrivateRepository(
        restore: MirrorRestoreRecord,
        repositoryName: String,
    ): RecoveryDrillRecord = runDrill(
        restore = restore,
        targetKind = RecoveryTargetKind.NEW_REPOSITORY,
        targetInput = repositoryName.trim(),
    ) {
        restorePublisher.publishToNewRepository(
            restoreId = restore.id,
            repositoryName = repositoryName,
            isPrivate = true,
        )
    }

    suspend fun runToExistingPrivateEmptyRepository(
        restore: MirrorRestoreRecord,
        repositoryFullName: String,
    ): RecoveryDrillRecord {
        val normalized = repositoryFullName.trim()
        val existing = repositoryGateway.getRepository(normalized)
        if (!existing.isPrivate) {
            throw IOException("Recovery drill target must be a private GitHub repository")
        }
        return runDrill(
            restore = restore,
            targetKind = RecoveryTargetKind.EXISTING_EMPTY_REPOSITORY,
            targetInput = normalized,
        ) {
            restorePublisher.publishToExistingEmptyRepository(
                restoreId = restore.id,
                repositoryFullName = normalized,
            )
        }
    }

    suspend fun listDrills(): List<RecoveryDrillRecord> = withContext(Dispatchers.IO) { store.list() }

    suspend fun exportAuditReport(id: String, destination: Uri) = withContext(Dispatchers.IO) {
        val record = store.get(id) ?: error("Recovery drill record no longer exists")
        val report = record.toAuditJson().toString(2)
        context.contentResolver.openOutputStream(destination, "wt")
            ?.bufferedWriter()
            ?.use { writer -> writer.write(report) }
            ?: error("Unable to create the recovery drill audit report")
    }

    private suspend fun runDrill(
        restore: MirrorRestoreRecord,
        targetKind: RecoveryTargetKind,
        targetInput: String,
        publish: suspend () -> GithubRestorePublishResult,
    ): RecoveryDrillRecord {
        val startedAt = System.currentTimeMillis()
        val drillId = "$startedAt-${UUID.randomUUID()}"
        val scratchDirectory = File(context.cacheDir, "recovery-drill/$drillId")
        scratchDirectory.mkdirs()

        try {
            val published = publish()
            val transaction = transactionStore.get(restore.id)
                ?: throw IOException("Recovery transaction is missing after publication")
            val repository = repositoryGateway.getRepository(published.repositoryFullName)
            if (repository.id != transaction.repositoryId) {
                throw IOException("Recovery drill target identity changed after publication")
            }
            if (!repository.isPrivate) {
                throw IOException("Recovery drill target must remain private")
            }

            val token = authManager.requireRecoveryAccessToken()
            val repositoryDirectory = restoreCoordinator.requireRepositoryDirectory(restore.id)
            val credentials = UsernamePasswordCredentialsProvider("x-access-token", token)
            val verifiedRefs = pushService.verifyPublishedRefs(
                repositoryDirectory = repositoryDirectory,
                remoteUri = repository.cloneUrl,
                credentialsProvider = credentials,
            )
            val lfsPointers = withContext(Dispatchers.IO) { lfsPointerScanner.scan(repositoryDirectory) }
            val lfsVerification = withContext(Dispatchers.IO) {
                lfsDownloadService.verifyRemoteObjects(
                    repositoryFullName = repository.fullName,
                    accessToken = token,
                    pointers = lfsPointers,
                    scratchDirectory = scratchDirectory,
                )
            }
            val releaseVerification = withContext(Dispatchers.IO) {
                releaseVerifier.verify(
                    repositoryFullName = repository.fullName,
                    accessToken = token,
                    repositoryDirectory = repositoryDirectory,
                )
            }

            val record = RecoveryDrillRecord(
                id = drillId,
                restoreId = restore.id,
                archiveName = restore.archiveName,
                startedAtEpochMs = startedAt,
                completedAtEpochMs = System.currentTimeMillis(),
                status = RecoveryDrillStatus.VERIFIED,
                targetKind = targetKind,
                targetInput = targetInput,
                repositoryFullName = repository.fullName,
                repositoryUrl = repository.htmlUrl,
                verifiedRefCount = verifiedRefs,
                lfsObjectCount = lfsVerification.availableObjectCount,
                lfsRepresentativeDownloads = lfsVerification.downloadedRepresentativeCount,
                releaseCount = releaseVerification.releaseCount,
                releaseAssetCount = releaseVerification.assetCount,
                archivalWikiRefCount = restore.wikiRefCount,
                archivalDiscussionRecordCount = restore.discussionRecordCount(),
                message = buildString {
                    append("Recovery drill verified ${repository.fullName}: $verifiedRefs Git refs")
                    if (lfsVerification.availableObjectCount > 0) {
                        append(", ${lfsVerification.availableObjectCount} LFS objects available")
                        append(" (${lfsVerification.downloadedRepresentativeCount} re-downloaded)")
                    }
                    if (releaseVerification.releaseCount > 0) {
                        append(", ${releaseVerification.releaseCount} releases / ${releaseVerification.assetCount} assets")
                    }
                },
            )
            withContext(Dispatchers.IO) { store.write(record) }
            return record
        } catch (throwable: Exception) {
            if (throwable is CancellationException) throw throwable
            val transaction = runCatching { transactionStore.get(restore.id) }.getOrNull()
            val failed = RecoveryDrillRecord(
                id = drillId,
                restoreId = restore.id,
                archiveName = restore.archiveName,
                startedAtEpochMs = startedAt,
                completedAtEpochMs = System.currentTimeMillis(),
                status = RecoveryDrillStatus.FAILED,
                targetKind = targetKind,
                targetInput = targetInput,
                repositoryFullName = transaction?.repositoryFullName,
                repositoryUrl = transaction?.repositoryUrl,
                verifiedRefCount = 0,
                lfsObjectCount = 0,
                lfsRepresentativeDownloads = 0,
                releaseCount = 0,
                releaseAssetCount = 0,
                archivalWikiRefCount = restore.wikiRefCount,
                archivalDiscussionRecordCount = restore.discussionRecordCount(),
                message = (throwable.message ?: throwable.javaClass.simpleName).take(MAX_MESSAGE_LENGTH),
            )
            withContext(Dispatchers.IO) { store.write(failed) }
            throw IOException("Recovery drill failed: ${failed.message}", throwable)
        } finally {
            scratchDirectory.deleteRecursively()
        }
    }

    private fun MirrorRestoreRecord.discussionRecordCount(): Int =
        issueCount + pullRequestCount + issueCommentCount + reviewCommentCount + reviewCount

    private companion object {
        const val MAX_MESSAGE_LENGTH = 1_000
    }
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
    put(key, value ?: JSONObject.NULL)

private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
