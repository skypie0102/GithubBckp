package com.skypie0102.githubbckp.release

import android.content.Context
import com.skypie0102.githubbckp.BuildConfig
import com.skypie0102.githubbckp.data.local.LatestReleaseDao
import com.skypie0102.githubbckp.data.local.LatestReleaseEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.github.GithubLatestReleaseService
import com.skypie0102.githubbckp.mirror.MirrorStage
import com.skypie0102.githubbckp.mirror.TarGzArchive
import com.skypie0102.githubbckp.storage.LocalLatestReleaseStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

enum class LatestReleaseAttemptStatus {
    CHECKING,
    DOWNLOADING,
    COMPLETED,
    NO_RELEASE,
    FAILED,
}

@Singleton
class LatestReleaseSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val githubService: GithubLatestReleaseService,
    private val store: LocalLatestReleaseStore,
    private val dao: LatestReleaseDao,
) {
    suspend fun sync(
        repository: RepositoryEntity,
        onStage: suspend (MirrorStage) -> Unit = {},
        onByteProgress: suspend (stage: MirrorStage, completedBytes: Long, totalBytes: Long) -> Unit =
            { _, _, _ -> },
    ): Boolean {
        val attemptAt = System.currentTimeMillis()
        val previous = dao.get(repository.githubId)
        dao.upsert(
            (previous ?: LatestReleaseEntity(repositoryId = repository.githubId)).copy(
                lastAttemptAtEpochMs = attemptAt,
                lastAttemptStatus = LatestReleaseAttemptStatus.CHECKING.name,
                lastError = null,
            ),
        )

        val session = File(context.cacheDir, "latest-release-sync-${repository.githubId}")
        session.deleteRecursively()
        check(session.mkdirs()) { "Could not create latest release sync cache directory" }

        return try {
            onStage(MirrorStage.CHECKING_RELEASE)
            val latest = githubService.getLatestRelease(repository.owner, repository.name)
            val checkedAt = System.currentTimeMillis()
            if (latest == null) {
                dao.upsert(
                    (previous ?: LatestReleaseEntity(repository.githubId)).copy(
                        lastCheckedAtEpochMs = checkedAt,
                        lastAttemptAtEpochMs = attemptAt,
                        lastAttemptStatus = LatestReleaseAttemptStatus.NO_RELEASE.name,
                        lastError = null,
                    ),
                )
                return true
            }

            val storedManifest = runCatching {
                store.readManifest(repository.githubId)
            }.getOrNull()
            val stored = store.current(repository.githubId)
            if (
                storedManifest != null &&
                stored != null &&
                storedManifest.releaseId == latest.id &&
                storedManifest.updatedAt == latest.updatedAt
            ) {
                dao.upsert(
                    (previous ?: LatestReleaseEntity(repository.githubId)).copy(
                        releaseId = latest.id,
                        tagName = latest.tagName,
                        releaseName = latest.name,
                        releaseUpdatedAt = latest.updatedAt,
                        archiveUri = stored.uri.toString(),
                        archiveName = stored.name,
                        archiveSizeBytes = stored.sizeBytes,
                        archiveSha256 = stored.sha256,
                        lastCheckedAtEpochMs = checkedAt,
                        lastAttemptAtEpochMs = attemptAt,
                        lastAttemptStatus = LatestReleaseAttemptStatus.COMPLETED.name,
                        lastError = null,
                    ),
                )
                return true
            }

            val knownAssetBytes = latest.assets.fold(0L) { total, asset ->
                safeAdd(total, asset.sizeBytes)
            }
            val minimumBeforeDownload = safeAdd(
                knownAssetBytes,
                MIN_RELEASE_DOWNLOAD_HEADROOM_BYTES,
            )
            if (session.usableSpace < minimumBeforeDownload) {
                error(
                    "Not enough temporary storage for latest release backup. " +
                        "Need at least ${formatBytes(minimumBeforeDownload)} free in app cache.",
                )
            }

            dao.upsert(
                (previous ?: LatestReleaseEntity(repository.githubId)).copy(
                    lastAttemptAtEpochMs = attemptAt,
                    lastAttemptStatus = LatestReleaseAttemptStatus.DOWNLOADING.name,
                    lastError = null,
                ),
            )

            val staging = File(session, STAGING_DIRECTORY).apply { mkdirs() }
            val source = File(staging, SOURCE_FILE)
            onStage(MirrorStage.DOWNLOADING_RELEASE)
            val sourceBytes = githubService.downloadSource(latest, source)
            val sourceSha256 = sha256(source)

            val assetsDirectory = File(staging, ASSETS_DIRECTORY).apply { mkdirs() }
            val usedNames = mutableSetOf<String>()
            val assetManifest = mutableListOf<LatestReleaseAssetManifest>()
            var completedAssetBytes = 0L

            latest.assets.forEach { asset ->
                val storedName = uniqueAssetFileName(
                    requestedName = asset.name,
                    assetId = asset.id,
                    usedNames = usedNames,
                )
                val destination = File(assetsDirectory, storedName)
                val downloaded = githubService.downloadAsset(asset, destination) { current, _ ->
                    if (knownAssetBytes > 0L) {
                        onByteProgress(
                            MirrorStage.DOWNLOADING_RELEASE,
                            safeAdd(completedAssetBytes, current).coerceAtMost(knownAssetBytes),
                            knownAssetBytes,
                        )
                    }
                }
                check(downloaded == asset.sizeBytes) {
                    "GitHub release asset size mismatch for ${asset.name}"
                }
                assetManifest += LatestReleaseAssetManifest(
                    id = asset.id,
                    originalName = asset.name,
                    storedName = "$ASSETS_DIRECTORY/$storedName",
                    sizeBytes = downloaded,
                    sha256 = sha256(destination),
                )
                completedAssetBytes = safeAdd(completedAssetBytes, downloaded)
            }

            LatestReleaseManifest(
                repositoryId = repository.githubId,
                repositoryOwner = repository.owner,
                repositoryName = repository.name,
                releaseId = latest.id,
                tagName = latest.tagName,
                releaseName = latest.name,
                releaseBody = latest.body,
                htmlUrl = latest.htmlUrl,
                publishedAt = latest.publishedAt,
                updatedAt = latest.updatedAt,
                sourceFileName = SOURCE_FILE,
                sourceSizeBytes = sourceBytes,
                sourceSha256 = sourceSha256,
                assets = assetManifest,
                appVersion = BuildConfig.VERSION_NAME,
            ).writeTo(File(staging, LatestReleaseManifest.FILE_NAME))

            val stagingBytes = directoryFileBytes(staging)
            val packagingReserve = safeAdd(stagingBytes, MIN_RELEASE_PACKAGING_HEADROOM_BYTES)
            if (session.usableSpace < packagingReserve) {
                error(
                    "Not enough temporary storage to package latest release. " +
                        "Need about ${formatBytes(packagingReserve)} more free in app cache.",
                )
            }

            val archive = File(session, PENDING_ARCHIVE)
            onStage(MirrorStage.PACKAGING_RELEASE)
            TarGzArchive.create(staging, archive)

            onStage(MirrorStage.VERIFYING_RELEASE)
            val verified = LatestReleaseVerifier.verify(
                archive = archive,
                verificationDirectory = File(session, VERIFY_DIRECTORY),
                expectedRepositoryId = repository.githubId,
            )

            onStage(MirrorStage.COMMITTING_RELEASE)
            val committed = store.commit(
                repositoryId = repository.githubId,
                owner = repository.owner,
                name = repository.name,
                tagName = latest.tagName,
                verifiedArchive = archive,
                expectedSha256 = verified.sha256,
                onProgress = { written, total ->
                    onByteProgress(MirrorStage.COMMITTING_RELEASE, written, total)
                },
            )

            val completedAt = System.currentTimeMillis()
            dao.upsert(
                LatestReleaseEntity(
                    repositoryId = repository.githubId,
                    releaseId = latest.id,
                    tagName = latest.tagName,
                    releaseName = latest.name,
                    releaseUpdatedAt = latest.updatedAt,
                    archiveUri = committed.uri.toString(),
                    archiveName = committed.name,
                    archiveSizeBytes = committed.sizeBytes,
                    archiveSha256 = committed.sha256,
                    lastCheckedAtEpochMs = completedAt,
                    lastChangedAtEpochMs = completedAt,
                    lastAttemptAtEpochMs = attemptAt,
                    lastAttemptStatus = LatestReleaseAttemptStatus.COMPLETED.name,
                    lastError = null,
                ),
            )
            true
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            dao.upsert(
                (dao.get(repository.githubId) ?: previous ?: LatestReleaseEntity(repository.githubId)).copy(
                    lastAttemptAtEpochMs = attemptAt,
                    lastAttemptStatus = LatestReleaseAttemptStatus.FAILED.name,
                    lastError = (throwable.message ?: throwable.javaClass.simpleName)
                        .take(MAX_ERROR_LENGTH),
                ),
            )
            false
        } finally {
            session.deleteRecursively()
        }
    }

    private fun directoryFileBytes(directory: File): Long =
        directory.walkTopDown()
            .filter { it.isFile }
            .fold(0L) { total, file -> safeAdd(total, file.length()) }

    private fun formatBytes(bytes: Long): String {
        val gib = 1024.0 * 1024.0 * 1024.0
        val mib = 1024.0 * 1024.0
        return if (bytes >= gib) {
            "%.1f GiB".format(bytes / gib)
        } else {
            "%.0f MiB".format(bytes / mib)
        }
    }

    private companion object {
        const val STAGING_DIRECTORY = "staging"
        const val ASSETS_DIRECTORY = "assets"
        const val SOURCE_FILE = "source/source.tar.gz"
        const val PENDING_ARCHIVE = "latest-release.tar.gz"
        const val VERIFY_DIRECTORY = "verify"
        const val MAX_ERROR_LENGTH = 1_000
        const val MIN_RELEASE_DOWNLOAD_HEADROOM_BYTES = 128L * 1024L * 1024L
        const val MIN_RELEASE_PACKAGING_HEADROOM_BYTES = 64L * 1024L * 1024L
    }
}

internal fun uniqueAssetFileName(
    requestedName: String,
    assetId: Long,
    usedNames: MutableSet<String>,
): String {
    val safe = requestedName
        .replace(Regex("[\\/\\p{Cntrl}]"), "_")
        .trim()
        .trim('.')
        .ifBlank { "asset-$assetId" }
        .take(180)

    if (usedNames.add(safe)) return safe

    val withId = "$assetId--$safe".take(220)
    check(usedNames.add(withId)) { "Could not create unique release asset filename" }
    return withId
}

private fun safeAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
