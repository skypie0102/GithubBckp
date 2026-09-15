package com.skypie0102.githubbckp.backup

import com.skypie0102.githubbckp.github.GithubAuthManager
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
 * Creates the on-device equivalent of `git clone --mirror`, then packages the
 * bare repository into one portable artifact. Credentials are supplied only to
 * JGit's transport layer and are never written into the mirror's remote URL.
 */
@Singleton
class GitMirrorBackupEngine @Inject constructor(
    private val githubAuthManager: GithubAuthManager,
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
