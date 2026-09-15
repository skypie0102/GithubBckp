package com.skypie0102.githubbckp.backup

import android.content.Context
import com.skypie0102.githubbckp.github.GithubRestoreRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

enum class RecoveryTargetKind {
    NEW_REPOSITORY,
    EXISTING_EMPTY_REPOSITORY,
}

enum class RecoveryPhase {
    TARGET_BOUND,
    LFS_PUBLISHED,
    GIT_PUBLISHED,
}

data class RecoveryTransaction(
    val restoreId: String,
    val targetKind: RecoveryTargetKind,
    val repositoryId: Long,
    val repositoryFullName: String,
    val repositoryUrl: String,
    val phase: RecoveryPhase,
    val lfsObjectCount: Int,
    val pushedRefCount: Int,
    val skippedReadOnlyRefs: List<String>,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Singleton
class RecoveryTransactionStore {
    private val rootDirectory: File

    @Inject
    constructor(@ApplicationContext context: Context) {
        rootDirectory = File(context.filesDir, DIRECTORY_NAME)
    }

    internal constructor(rootDirectory: File) {
        this.rootDirectory = rootDirectory
    }

    @Synchronized
    fun get(restoreId: String): RecoveryTransaction? {
        val file = fileFor(restoreId)
        if (!file.exists()) return null
        return parse(file.readText())
    }

    @Synchronized
    fun bind(
        restoreId: String,
        targetKind: RecoveryTargetKind,
        repository: GithubRestoreRepository,
    ): RecoveryTransaction {
        val existing = get(restoreId)
        if (existing != null) {
            if (existing.targetKind != targetKind || existing.repositoryId != repository.id) {
                throw IOException(
                    "This restored mirror is already bound to recovery target ${existing.repositoryFullName}",
                )
            }
            return existing
        }
        val now = System.currentTimeMillis()
        return RecoveryTransaction(
            restoreId = restoreId,
            targetKind = targetKind,
            repositoryId = repository.id,
            repositoryFullName = repository.fullName,
            repositoryUrl = repository.htmlUrl,
            phase = RecoveryPhase.TARGET_BOUND,
            lfsObjectCount = 0,
            pushedRefCount = 0,
            skippedReadOnlyRefs = emptyList(),
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        ).also(::write)
    }

    @Synchronized
    fun markLfsPublished(
        restoreId: String,
        repositoryId: Long,
        lfsObjectCount: Int,
    ): RecoveryTransaction = update(restoreId, repositoryId) { current ->
        current.advance(
            phase = RecoveryPhase.LFS_PUBLISHED,
            lfsObjectCount = lfsObjectCount,
        )
    }

    @Synchronized
    fun markGitPublished(
        restoreId: String,
        repositoryId: Long,
        result: MirrorPushResult,
    ): RecoveryTransaction = update(restoreId, repositoryId) { current ->
        current.advance(
            phase = RecoveryPhase.GIT_PUBLISHED,
            pushedRefCount = result.pushedRefCount,
            skippedReadOnlyRefs = result.skippedReadOnlyRefs,
        )
    }

    private fun update(
        restoreId: String,
        repositoryId: Long,
        transform: (RecoveryTransaction) -> RecoveryTransaction,
    ): RecoveryTransaction {
        val current = get(restoreId)
            ?: throw IOException("Recovery transaction is missing")
        if (current.repositoryId != repositoryId) {
            throw IOException("Recovery target identity changed")
        }
        val updated = transform(current)
        if (updated.phase.ordinal < current.phase.ordinal) {
            throw IOException("Recovery phase cannot move backward")
        }
        write(updated)
        return updated
    }

    private fun RecoveryTransaction.advance(
        phase: RecoveryPhase,
        lfsObjectCount: Int = this.lfsObjectCount,
        pushedRefCount: Int = this.pushedRefCount,
        skippedReadOnlyRefs: List<String> = this.skippedReadOnlyRefs,
    ): RecoveryTransaction = copy(
        phase = if (phase.ordinal >= this.phase.ordinal) phase else this.phase,
        lfsObjectCount = lfsObjectCount,
        pushedRefCount = pushedRefCount,
        skippedReadOnlyRefs = skippedReadOnlyRefs,
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    private fun write(transaction: RecoveryTransaction) {
        rootDirectory.mkdirs()
        val target = fileFor(transaction.restoreId)
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(serialize(transaction).toString())
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun serialize(transaction: RecoveryTransaction): JSONObject = JSONObject()
        .put("version", FORMAT_VERSION)
        .put("restoreId", transaction.restoreId)
        .put("targetKind", transaction.targetKind.name)
        .put("repositoryId", transaction.repositoryId)
        .put("repositoryFullName", transaction.repositoryFullName)
        .put("repositoryUrl", transaction.repositoryUrl)
        .put("phase", transaction.phase.name)
        .put("lfsObjectCount", transaction.lfsObjectCount)
        .put("pushedRefCount", transaction.pushedRefCount)
        .put("skippedReadOnlyRefs", JSONArray(transaction.skippedReadOnlyRefs))
        .put("createdAtEpochMs", transaction.createdAtEpochMs)
        .put("updatedAtEpochMs", transaction.updatedAtEpochMs)

    private fun parse(text: String): RecoveryTransaction {
        try {
            val json = JSONObject(text)
            if (json.getInt("version") != FORMAT_VERSION) {
                throw IOException("Unsupported recovery transaction version")
            }
            val skippedJson = json.optJSONArray("skippedReadOnlyRefs") ?: JSONArray()
            val skipped = buildList {
                for (index in 0 until skippedJson.length()) add(skippedJson.getString(index))
            }
            return RecoveryTransaction(
                restoreId = json.getString("restoreId"),
                targetKind = RecoveryTargetKind.valueOf(json.getString("targetKind")),
                repositoryId = json.getLong("repositoryId"),
                repositoryFullName = json.getString("repositoryFullName"),
                repositoryUrl = json.getString("repositoryUrl"),
                phase = RecoveryPhase.valueOf(json.getString("phase")),
                lfsObjectCount = json.optInt("lfsObjectCount", 0),
                pushedRefCount = json.optInt("pushedRefCount", 0),
                skippedReadOnlyRefs = skipped,
                createdAtEpochMs = json.getLong("createdAtEpochMs"),
                updatedAtEpochMs = json.getLong("updatedAtEpochMs"),
            )
        } catch (throwable: IOException) {
            throw throwable
        } catch (throwable: Throwable) {
            throw IOException("Recovery transaction is corrupt", throwable)
        }
    }

    private fun fileFor(restoreId: String): File {
        if (!RESTORE_ID_REGEX.matches(restoreId)) {
            throw IOException("Invalid restore identifier")
        }
        rootDirectory.mkdirs()
        val root = rootDirectory.canonicalFile
        val file = File(root, "$restoreId.json").canonicalFile
        if (file.parentFile != root) throw IOException("Invalid recovery transaction path")
        return file
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val DIRECTORY_NAME = "recovery-transactions"
        val RESTORE_ID_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}
