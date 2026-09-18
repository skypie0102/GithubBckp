package com.skypie0102.githubbckp.mirror

import com.skypie0102.githubbckp.BuildConfig
import com.skypie0102.githubbckp.backup.GitLfsDownloadService
import com.skypie0102.githubbckp.backup.GitLfsPointerScanner
import com.skypie0102.githubbckp.github.GithubAuthManager
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

data class MirrorRepository(
    val id: Long,
    val owner: String,
    val name: String,
    val defaultBranch: String,
    val isPrivate: Boolean,
) {
    val fullName: String = "$owner/$name"
    val remoteUrl: String = "https://github.com/$fullName.git"
}

enum class MirrorStage {
    CHECKING_REMOTE,
    EXTRACTING,
    CLONING,
    FETCHING,
    DOWNLOADING_LFS,
    OPTIMIZING,
    PACKAGING,
    VERIFYING,
    COMMITTING,
}

sealed interface MirrorSyncResult {
    data class Unchanged(
        val manifest: MirrorManifest,
    ) : MirrorSyncResult

    data class Rebuilt(
        val archive: File,
        val manifest: MirrorManifest,
        val sha256: String,
    ) : MirrorSyncResult
}

internal fun mirrorRequiresRebuild(
    manifest: MirrorManifest,
    repository: MirrorRepository,
    remoteRefsDigest: String,
): Boolean =
    manifest.refsDigest != remoteRefsDigest ||
        manifest.repositoryOwner != repository.owner ||
        manifest.repositoryName != repository.name ||
        manifest.remoteUrl != repository.remoteUrl ||
        manifest.defaultBranch != repository.defaultBranch ||
        manifest.isPrivate != repository.isPrivate ||
        !manifest.workingTreeIncluded

/**
 * Replacement engine for the old multi-module ZIP backup pipeline.
 *
 * One invocation either:
 * - creates a fresh bare Git mirror and packages it as .tar.gz;
 * - proves an existing archive is unchanged without extracting it; or
 * - extracts the current archive, fetches/prunes only the Git delta, updates
 *   LFS objects, and emits a fully verified replacement .tar.gz.
 *
 * The caller owns promotion of the returned pending archive into durable
 * storage. The current durable mirror is never modified by this class.
 */
@Singleton
class MirrorEngine @Inject constructor(
    private val authManager: GithubAuthManager,
    private val lfsPointerScanner: GitLfsPointerScanner,
    private val lfsDownloadService: GitLfsDownloadService,
) {
    suspend fun remoteRequiresRebuild(
        repository: MirrorRepository,
        manifest: MirrorManifest,
        onProgress: suspend (MirrorStage) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        check(manifest.repositoryId == repository.id) {
            "Stored mirror belongs to repository ${manifest.repositoryId}, expected ${repository.id}"
        }
        val token = authManager.requireAccessToken()
        onProgress(MirrorStage.CHECKING_REMOTE)
        val remoteRefsDigest = GitMirrorOperations.remoteRefsDigest(
            remoteUri = repository.remoteUrl,
            credentialsProvider = credentials(token),
        )
        mirrorRequiresRebuild(manifest, repository, remoteRefsDigest)
    }

    suspend fun create(
        repository: MirrorRepository,
        workingDirectory: File,
        onProgress: suspend (MirrorStage) -> Unit = {},
    ): MirrorSyncResult.Rebuilt = withContext(Dispatchers.IO) {
        val session = prepareSession(workingDirectory, repository.id)
        val staging = File(session, STAGING_DIRECTORY).apply { mkdirs() }
        val repositoryDirectory = File(staging, MirrorVerifier.REPOSITORY_DIRECTORY)
        val archive = File(session, PENDING_ARCHIVE_NAME)
        val token = authManager.requireAccessToken()
        val credentials = credentials(token)
        val now = System.currentTimeMillis()

        onProgress(MirrorStage.CLONING)
        GitMirrorOperations.cloneMirror(
            remoteUri = repository.remoteUrl,
            destination = repositoryDirectory,
            credentialsProvider = credentials,
        )

        onProgress(MirrorStage.DOWNLOADING_LFS)
        val lfsIncluded = synchronizeLfs(repository, token, repositoryDirectory)

        onProgress(MirrorStage.OPTIMIZING)
        RepositoryCheckoutExporter.export(
            repositoryDirectory = repositoryDirectory,
            defaultBranch = repository.defaultBranch,
            destinationDirectory = File(staging, MirrorVerifier.WORKING_TREE_DIRECTORY),
        )
        val refsDigest = GitMirrorOperations.localRefsDigest(repositoryDirectory)
        val manifest = manifest(
            repository = repository,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            lastSuccessfulFetchAtEpochMs = now,
            refsDigest = refsDigest,
            headCommit = GitMirrorOperations.headCommit(repositoryDirectory, repository.defaultBranch),
            lfsIncluded = lfsIncluded,
        )
        manifest.writeTo(File(staging, MirrorManifest.FILE_NAME))

        onProgress(MirrorStage.PACKAGING)
        TarGzArchive.create(staging, archive)

        onProgress(MirrorStage.VERIFYING)
        MirrorVerifier.verify(
            archive = archive,
            verificationDirectory = File(session, VERIFY_DIRECTORY),
            expectedRepositoryId = repository.id,
        )

        MirrorSyncResult.Rebuilt(
            archive = archive,
            manifest = manifest,
            sha256 = sha256(archive),
        )
    }

    suspend fun update(
        repository: MirrorRepository,
        existingArchive: File,
        workingDirectory: File,
        onProgress: suspend (MirrorStage) -> Unit = {},
    ): MirrorSyncResult = withContext(Dispatchers.IO) {
        require(existingArchive.isFile) { "Existing mirror archive is missing" }

        val existingManifest = MirrorManifest.readFromArchive(existingArchive)
        check(existingManifest.repositoryId == repository.id) {
            "Stored mirror belongs to repository ${existingManifest.repositoryId}, expected ${repository.id}"
        }

        val token = authManager.requireAccessToken()
        val credentials = credentials(token)

        onProgress(MirrorStage.CHECKING_REMOTE)
        val remoteRefsDigest = GitMirrorOperations.remoteRefsDigest(
            remoteUri = repository.remoteUrl,
            credentialsProvider = credentials,
        )

        if (!mirrorRequiresRebuild(existingManifest, repository, remoteRefsDigest)) {
            return@withContext MirrorSyncResult.Unchanged(existingManifest)
        }

        val session = prepareSession(workingDirectory, repository.id)
        val staging = File(session, STAGING_DIRECTORY)

        onProgress(MirrorStage.EXTRACTING)
        TarGzArchive.extract(existingArchive, staging)

        val extractedManifest = MirrorManifest.readFrom(File(staging, MirrorManifest.FILE_NAME))
        check(extractedManifest.repositoryId == repository.id) {
            "Extracted mirror identity changed during update"
        }
        val repositoryDirectory = File(staging, MirrorVerifier.REPOSITORY_DIRECTORY)

        onProgress(MirrorStage.FETCHING)
        GitMirrorOperations.fetchAndPrune(
            remoteUri = repository.remoteUrl,
            repositoryDirectory = repositoryDirectory,
            defaultBranch = repository.defaultBranch,
            credentialsProvider = credentials,
        )

        onProgress(MirrorStage.DOWNLOADING_LFS)
        val lfsIncluded = synchronizeLfs(repository, token, repositoryDirectory)

        onProgress(MirrorStage.OPTIMIZING)
        RepositoryCheckoutExporter.export(
            repositoryDirectory = repositoryDirectory,
            defaultBranch = repository.defaultBranch,
            destinationDirectory = File(staging, MirrorVerifier.WORKING_TREE_DIRECTORY),
        )
        val now = System.currentTimeMillis()
        val updatedManifest = manifest(
            repository = repository,
            createdAtEpochMs = extractedManifest.createdAtEpochMs,
            updatedAtEpochMs = now,
            lastSuccessfulFetchAtEpochMs = now,
            refsDigest = GitMirrorOperations.localRefsDigest(repositoryDirectory),
            headCommit = GitMirrorOperations.headCommit(repositoryDirectory, repository.defaultBranch),
            lfsIncluded = lfsIncluded,
        )
        updatedManifest.writeTo(File(staging, MirrorManifest.FILE_NAME))

        val archive = File(session, PENDING_ARCHIVE_NAME)
        onProgress(MirrorStage.PACKAGING)
        TarGzArchive.create(staging, archive)

        onProgress(MirrorStage.VERIFYING)
        MirrorVerifier.verify(
            archive = archive,
            verificationDirectory = File(session, VERIFY_DIRECTORY),
            expectedRepositoryId = repository.id,
        )

        MirrorSyncResult.Rebuilt(
            archive = archive,
            manifest = updatedManifest,
            sha256 = sha256(archive),
        )
    }

    private fun synchronizeLfs(
        repository: MirrorRepository,
        token: String,
        repositoryDirectory: File,
    ): Boolean {
        val pointers = lfsPointerScanner.scan(repositoryDirectory)
        if (pointers.isEmpty()) return false
        lfsDownloadService.downloadAll(
            repositoryFullName = repository.fullName,
            accessToken = token,
            pointers = pointers,
            repositoryDirectory = repositoryDirectory,
        )
        return true
    }

    private fun manifest(
        repository: MirrorRepository,
        createdAtEpochMs: Long,
        updatedAtEpochMs: Long,
        lastSuccessfulFetchAtEpochMs: Long,
        refsDigest: String,
        headCommit: String?,
        lfsIncluded: Boolean,
    ) = MirrorManifest(
        repositoryId = repository.id,
        repositoryOwner = repository.owner,
        repositoryName = repository.name,
        remoteUrl = repository.remoteUrl,
        defaultBranch = repository.defaultBranch,
        isPrivate = repository.isPrivate,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        lastSuccessfulFetchAtEpochMs = lastSuccessfulFetchAtEpochMs,
        refsDigest = refsDigest,
        headCommit = headCommit,
        lfsIncluded = lfsIncluded,
        workingTreeIncluded = true,
        appVersion = BuildConfig.VERSION_NAME,
    )

    private fun prepareSession(workingDirectory: File, repositoryId: Long): File =
        File(workingDirectory, "mirror-$repositoryId").apply {
            deleteRecursively()
            check(mkdirs()) { "Could not create mirror working directory" }
        }

    private fun credentials(token: String) =
        UsernamePasswordCredentialsProvider("x-access-token", token)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val STAGING_DIRECTORY = "staging"
        const val VERIFY_DIRECTORY = "verify"
        const val PENDING_ARCHIVE_NAME = "mirror.pending.tar.gz"
        const val BUFFER_SIZE = 256 * 1024
    }
}
