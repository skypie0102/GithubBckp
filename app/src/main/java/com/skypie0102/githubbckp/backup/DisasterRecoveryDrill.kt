package com.skypie0102.githubbckp.backup

import android.content.Context
import com.skypie0102.githubbckp.github.GithubAuthManager
import com.skypie0102.githubbckp.github.GithubRepositoryRestoreGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.json.JSONArray
import org.json.JSONObject

enum class DisasterRecoveryDrillTarget {
    NEW_PRIVATE_REPOSITORY,
    EXISTING_EMPTY_PRIVATE_REPOSITORY,
}

data class DisasterRecoveryDrillPlan(
    val automaticallyRepublished: List<String>,
    val archivalOnly: List<String>,
)

data class DisasterRecoveryDrillResult(
    val restoreId: String,
    val repositoryFullName: String,
    val repositoryUrl: String,
    val completedAtEpochMs: Long,
    val verifiedGitRefCount: Int,
    val verifiedLfsObjectCount: Int,
    val verifiedReleaseCount: Int,
    val verifiedReleaseAssetCount: Int,
    val automaticallyRepublished: List<String>,
    val archivalOnly: List<String>,
)

fun MirrorRestoreRecord.toDisasterRecoveryDrillPlan(): DisasterRecoveryDrillPlan {
    val automatic = buildList {
        add("Git refs/history")
        if (lfsObjectCount > 0) add("Git LFS objects")
        if (releaseCount > 0 || releaseAssetCount > 0) add("GitHub releases/assets")
    }
    val archival = buildList {
        if (wikiRefCount > 0) add("GitHub wiki history")
        val discussionCount = issueCount + pullRequestCount + issueCommentCount + reviewCommentCount + reviewCount
        if (discussionCount > 0) add("Issue/pull-request discussion metadata")
    }
    return DisasterRecoveryDrillPlan(
        automaticallyRepublished = automatic,
        archivalOnly = archival,
    )
}

internal fun disasterRecoveryDrillTransactionKey(
    restoreId: String,
    target: DisasterRecoveryDrillTarget,
    targetIdentity: String,
): String {
    val normalizedTarget = targetIdentity.trim().lowercase()
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalizedTarget.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
        .take(20)
    return "drill-$restoreId-${target.name.lowercase()}-$digest"
}

@Singleton
class DisasterRecoveryDrillService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val restoreCoordinator: MirrorRestoreCoordinator,
    private val restorePublisher: GithubMirrorRestorePublisher,
    private val repositoryGateway: GithubRepositoryRestoreGateway,
    private val authManager: GithubAuthManager,
    private val pushService: GitMirrorPushService,
) {
    suspend fun runToNewPrivateRepository(
        restoreId: String,
        repositoryName: String,
    ): DisasterRecoveryDrillResult {
        val record = requireRestore(restoreId)
        val normalizedName = repositoryName.trim()
        val publishResult = restorePublisher.publishToNewRepository(
            restoreId = restoreId,
            repositoryName = normalizedName,
            isPrivate = true,
            transactionKey = disasterRecoveryDrillTransactionKey(
                restoreId = restoreId,
                target = DisasterRecoveryDrillTarget.NEW_PRIVATE_REPOSITORY,
                targetIdentity = normalizedName,
            ),
        )
        return verifyAndPersist(record, publishResult)
    }

    suspend fun runToExistingEmptyPrivateRepository(
        restoreId: String,
        repositoryFullName: String,
    ): DisasterRecoveryDrillResult {
        val record = requireRestore(restoreId)
        val repository = repositoryGateway.getRepository(repositoryFullName)
        if (!repository.isPrivate) {
            throw IOException("Disaster-recovery drills require a private GitHub target")
        }
        val publishResult = restorePublisher.publishToExistingEmptyRepository(
            restoreId = restoreId,
            repositoryFullName = repository.fullName,
            transactionKey = disasterRecoveryDrillTransactionKey(
                restoreId = restoreId,
                target = DisasterRecoveryDrillTarget.EXISTING_EMPTY_PRIVATE_REPOSITORY,
                targetIdentity = repository.fullName,
            ),
        )
        return verifyAndPersist(record, publishResult)
    }

    suspend fun latestResult(restoreId: String): DisasterRecoveryDrillResult? = withContext(Dispatchers.IO) {
        val file = resultFile(restoreId)
        if (!file.isFile) return@withContext null
        runCatching { parseResult(JSONObject(file.readText())) }.getOrNull()
    }

    suspend fun deleteResult(restoreId: String) = withContext(Dispatchers.IO) {
        val file = resultFile(restoreId)
        if (file.exists() && !file.delete()) {
            throw IOException("Unable to delete disaster-recovery drill result")
        }
    }

    private suspend fun verifyAndPersist(
        record: MirrorRestoreRecord,
        publishResult: GithubRestorePublishResult,
    ): DisasterRecoveryDrillResult {
        val repository = repositoryGateway.getRepository(publishResult.repositoryFullName)
        if (!repository.isPrivate) {
            throw IOException("Disaster-recovery drill target is not private")
        }

        val token = authManager.requireRecoveryAccessToken()
        val credentials = UsernamePasswordCredentialsProvider("x-access-token", token)
        val repositoryDirectory = restoreCoordinator.requireRepositoryDirectory(record.id)
        val verifiedRefs = pushService.verifyPublished(
            repositoryDirectory = repositoryDirectory,
            remoteUri = repository.cloneUrl,
            credentialsProvider = credentials,
        )
        if (verifiedRefs != publishResult.pushedRefCount) {
            throw IOException(
                "Post-publication Git verification count changed: published ${publishResult.pushedRefCount}, verified $verifiedRefs",
            )
        }

        val plan = record.toDisasterRecoveryDrillPlan()
        val result = DisasterRecoveryDrillResult(
            restoreId = record.id,
            repositoryFullName = repository.fullName,
            repositoryUrl = repository.htmlUrl,
            completedAtEpochMs = System.currentTimeMillis(),
            verifiedGitRefCount = verifiedRefs,
            // LFS upload and release publication already perform remote verification
            // before their counts are returned by the safe recovery publisher.
            verifiedLfsObjectCount = publishResult.restoredLfsObjectCount,
            verifiedReleaseCount = publishResult.restoredReleaseCount,
            verifiedReleaseAssetCount = publishResult.restoredReleaseAssetCount,
            automaticallyRepublished = plan.automaticallyRepublished,
            archivalOnly = plan.archivalOnly,
        )
        persist(result)
        return result
    }

    private suspend fun requireRestore(restoreId: String): MirrorRestoreRecord {
        return restoreCoordinator.listRestores().singleOrNull { it.id == restoreId }
            ?: throw IOException("Restored mirror is missing or invalid")
    }

    private suspend fun persist(result: DisasterRecoveryDrillResult) = withContext(Dispatchers.IO) {
        val file = resultFile(result.restoreId)
        file.parentFile?.mkdirs()
        file.writeText(result.toJson().toString())
    }

    private fun resultFile(restoreId: String): File {
        require(restoreId.isNotBlank() && !restoreId.contains('/') && !restoreId.contains('\\')) {
            "Invalid restore identifier"
        }
        return File(File(context.filesDir, RESULTS_DIRECTORY), "$restoreId.json")
    }

    private fun DisasterRecoveryDrillResult.toJson(): JSONObject = JSONObject()
        .put("version", RESULT_VERSION)
        .put("restoreId", restoreId)
        .put("repositoryFullName", repositoryFullName)
        .put("repositoryUrl", repositoryUrl)
        .put("completedAtEpochMs", completedAtEpochMs)
        .put("verifiedGitRefCount", verifiedGitRefCount)
        .put("verifiedLfsObjectCount", verifiedLfsObjectCount)
        .put("verifiedReleaseCount", verifiedReleaseCount)
        .put("verifiedReleaseAssetCount", verifiedReleaseAssetCount)
        .put("automaticallyRepublished", JSONArray(automaticallyRepublished))
        .put("archivalOnly", JSONArray(archivalOnly))

    private fun parseResult(json: JSONObject): DisasterRecoveryDrillResult {
        if (json.optInt("version", -1) != RESULT_VERSION) {
            throw IOException("Unsupported disaster-recovery drill result version")
        }
        return DisasterRecoveryDrillResult(
            restoreId = json.getString("restoreId"),
            repositoryFullName = json.getString("repositoryFullName"),
            repositoryUrl = json.getString("repositoryUrl"),
            completedAtEpochMs = json.getLong("completedAtEpochMs"),
            verifiedGitRefCount = json.getInt("verifiedGitRefCount"),
            verifiedLfsObjectCount = json.getInt("verifiedLfsObjectCount"),
            verifiedReleaseCount = json.getInt("verifiedReleaseCount"),
            verifiedReleaseAssetCount = json.getInt("verifiedReleaseAssetCount"),
            automaticallyRepublished = json.getJSONArray("automaticallyRepublished").toStringList(),
            archivalOnly = json.getJSONArray("archivalOnly").toStringList(),
        )
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { index -> getString(index) }

    private companion object {
        const val RESULT_VERSION = 1
        const val RESULTS_DIRECTORY = "disaster-recovery-drills"
    }
}
