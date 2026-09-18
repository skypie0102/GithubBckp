package com.skypie0102.githubbckp.mirror

import java.io.File
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

data class MirrorVerificationResult(
    val manifest: MirrorManifest,
    val refCount: Int,
)

object MirrorVerifier {
    fun verify(
        archive: File,
        verificationDirectory: File,
        expectedRepositoryId: Long? = null,
    ): MirrorVerificationResult {
        verificationDirectory.deleteRecursively()
        check(verificationDirectory.mkdirs()) {
            "Could not create mirror verification directory"
        }

        return try {
            TarGzArchive.extract(archive, verificationDirectory)

            val manifestFile = File(verificationDirectory, MirrorManifest.FILE_NAME)
            check(manifestFile.isFile) { "Mirror manifest is missing" }
            val manifest = MirrorManifest.readFrom(manifestFile)

            if (expectedRepositoryId != null) {
                check(manifest.repositoryId == expectedRepositoryId) {
                    "Mirror belongs to repository ${manifest.repositoryId}, expected $expectedRepositoryId"
                }
            }

            val repositoryDirectory = File(verificationDirectory, REPOSITORY_DIRECTORY)
            check(repositoryDirectory.isDirectory) { "Bare Git repository is missing from mirror" }

            FileRepositoryBuilder()
                .setGitDir(repositoryDirectory)
                .setMustExist(true)
                .build()
                .use { repository ->
                    check(repository.isBare) { "Mirror repository is not bare" }
                    MirrorVerificationResult(
                        manifest = manifest,
                        refCount = repository.refDatabase.getRefsByPrefix("refs/").size,
                    )
                }
        } finally {
            verificationDirectory.deleteRecursively()
        }
    }

    const val REPOSITORY_DIRECTORY = "repository.git"
}
