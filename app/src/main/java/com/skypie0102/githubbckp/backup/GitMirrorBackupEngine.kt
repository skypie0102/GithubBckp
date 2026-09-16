package com.skypie0102.githubbckp.backup

import com.skypie0102.githubbckp.github.GithubAuthManager
import com.skypie0102.githubbckp.github.GithubGateway
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

/**
 * Creates the on-device equivalent of `git clone --mirror`, downloads every
 * standard Git LFS object referenced by reachable mirror refs, optionally
 * mirrors an initialized GitHub wiki, bundles release data and discussion
 * metadata, then packages everything into one portable artifact.
 */
@Singleton
class GitMirrorBackupEngine @Inject constructor(
    private val githubAuthManager: GithubAuthManager,
    private val githubGateway: GithubGateway,
    private val lfsPointerScanner: GitLfsPointerScanner,
    private val lfsDownloadService: GitLfsDownloadService,
    private val wikiBackupService: GithubWikiBackupService,
    private val releaseBackupService: GithubReleaseBackupService,
    private val discussionBackupService: GithubDiscussionBackupService,
) : BackupEngine {
    override suspend fun createBackup(
        request: BackupRequest,
        workingDirectory: File,
        onProgress: suspend (BackupStatus) -> Unit,
    ): BackupArtifact = withContext(Dispatchers.IO) {
        require(request.type == BackupType.GIT_MIRROR) {
            "GitMirrorBackupEngine only handles GIT_MIRROR"
        }
        workingDirectory.mkdirs()
        val createdAt = System.currentTimeMillis()
        val safeName = "${request.repository.owner}-${request.repository.name}"
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val mirrorDirectory = File(workingDirectory, "$safeName.git")
        val archive = File(workingDirectory, "$safeName-$createdAt.mirror.zip")
        val token = githubAuthManager.requireAccessToken()

        onProgress(BackupStatus.DOWNLOADING)
        Git.cloneRepository()
            .setURI("https://github.com/${request.repository.fullName}.git")
            .setDirectory(mirrorDirectory)
            .setMirror(true)
            .setCredentialsProvider(
                UsernamePasswordCredentialsProvider("x-access-token", token),
            )
            .call()
            .use { git ->
                check(git.repository.isBare) { "Mirror clone did not produce a bare repository" }
            }

        val lfsPointers = lfsPointerScanner.scan(mirrorDirectory)
        lfsDownloadService.downloadAll(
            repositoryFullName = request.repository.fullName,
            accessToken = token,
            pointers = lfsPointers,
            repositoryDirectory = mirrorDirectory,
        )

        val wiki = if (githubGateway.repositoryHasWiki(request.repository)) {
            wikiBackupService.backup(
                repository = request.repository,
                accessToken = token,
                destination = File(mirrorDirectory, GithubWikiBackupService.BUNDLED_WIKI_DIRECTORY),
            )
        } else {
            null
        }

        val releases = releaseBackupService.backup(
            repository = request.repository,
            accessToken = token,
            destination = File(mirrorDirectory, GithubReleaseBackupService.BUNDLED_RELEASES_DIRECTORY),
        )

        val discussions = discussionBackupService.backup(
            repository = request.repository,
            accessToken = token,
            destination = File(mirrorDirectory, GithubDiscussionBackupService.BUNDLED_DISCUSSIONS_DIRECTORY),
        )

        onProgress(BackupStatus.PACKAGING)
        zipBareRepository(mirrorDirectory, archive)
        mirrorDirectory.deleteRecursively()

        onProgress(BackupStatus.CHECKSUM)
        val digests = calculateDigests(archive)
        BackupArtifact(
            repository = request.repository,
            type = request.type,
            file = archive,
            checksumSha256 = digests.sha256,
            checksumMd5 = digests.md5,
            createdAtEpochMs = createdAt,
            warnings = buildList {
                if (wiki != null) {
                    add(
                        "Wiki history is bundled and validated in this mirror backup, but automatic GitHub wiki publication is not implemented yet.",
                    )
                }
                if (releases != null) {
                    add(
                        "Release metadata and ${releases.assetCount} release asset${if (releases.assetCount == 1) "" else "s"} are bundled, verified, and restorable. GitHub's original latest-release selection and immutable release state are not automatically reproduced.",
                    )
                }
                if (discussions != null) {
                    add(
                        "Issues, pull requests, comments, review comments, and reviews are bundled and locally verified. Automatic GitHub discussion publication, timeline events, and referenced attachment bytes are not implemented yet.",
                    )
                }
            },
        )
    }

    private fun zipBareRepository(source: File, destination: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { zip ->
            source.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relativePath = file.relativeTo(source).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relativePath))
                    FileInputStream(file).use { input -> input.copyTo(zip, bufferSize = 128 * 1024) }
                    zip.closeEntry()
                }
        }
        check(destination.length() > 0L) { "Mirror archive is empty" }
    }
}
