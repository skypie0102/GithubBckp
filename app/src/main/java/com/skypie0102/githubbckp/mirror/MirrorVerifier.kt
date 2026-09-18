package com.skypie0102.githubbckp.mirror

import com.skypie0102.githubbckp.backup.GitLfsObjectStore
import com.skypie0102.githubbckp.backup.GitLfsPointerScanner
import java.io.File
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

data class MirrorVerificationResult(
    val manifest: MirrorManifest,
    val refCount: Int,
    val lfsObjectCount: Int,
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

            if (manifest.workingTreeIncluded) {
                val workingTree = File(verificationDirectory, WORKING_TREE_DIRECTORY)
                check(workingTree.isDirectory) {
                    "Browsable repository checkout is missing from mirror"
                }
            }

            val refCount = FileRepositoryBuilder()
                .setGitDir(repositoryDirectory)
                .setMustExist(true)
                .build()
                .use { repository ->
                    check(repository.isBare) { "Mirror repository is not bare" }
                    repository.refDatabase.getRefsByPrefix("refs/").size
                }

            val pointers = GitLfsPointerScanner().scan(repositoryDirectory)
            check(manifest.lfsIncluded == pointers.isNotEmpty()) {
                "Mirror manifest LFS state does not match reachable repository pointers"
            }
            val objectStore = GitLfsObjectStore()
            pointers.forEach { pointer ->
                objectStore.requireVerifiedObject(repositoryDirectory, pointer)
            }

            MirrorVerificationResult(
                manifest = manifest,
                refCount = refCount,
                lfsObjectCount = pointers.size,
            )
        } finally {
            verificationDirectory.deleteRecursively()
        }
    }

    const val REPOSITORY_DIRECTORY = "repository.git"
    const val WORKING_TREE_DIRECTORY = "repository"
}
