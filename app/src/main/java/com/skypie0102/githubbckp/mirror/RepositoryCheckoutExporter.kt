package com.skypie0102.githubbckp.mirror

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk

/**
 * Materializes the default branch as a human-readable working-tree snapshot.
 *
 * The authoritative backup remains the bare mirror under repository.git.
 * This checkout exists so opening the archive shows normal repository files
 * without requiring Git tooling.
 *
 * Git symlinks are intentionally written as regular files containing their
 * link-target text. This keeps archive extraction path-safe on Android.
 * Submodules are represented as empty directories, like a clone that has not
 * initialized submodules.
 */
object RepositoryCheckoutExporter {
    fun export(
        repositoryDirectory: File,
        defaultBranch: String,
        destinationDirectory: File,
    ) {
        require(repositoryDirectory.isDirectory) { "Mirror repository directory is missing" }

        destinationDirectory.deleteRecursively()
        check(destinationDirectory.mkdirs()) {
            "Could not create repository checkout directory"
        }

        FileRepositoryBuilder()
            .setGitDir(repositoryDirectory)
            .setBare()
            .setMustExist(true)
            .build()
            .use { repository ->
                val head = repository.resolve("refs/heads/$defaultBranch") ?: return

                RevWalk(repository).use { revWalk ->
                    val commit = revWalk.parseCommit(head)
                    TreeWalk(repository).use { treeWalk ->
                        treeWalk.addTree(commit.tree)
                        treeWalk.isRecursive = true

                        val root = destinationDirectory.canonicalFile
                        val rootPrefix = root.path + File.separator

                        while (treeWalk.next()) {
                            val relativePath = treeWalk.pathString
                            val output = File(root, relativePath).canonicalFile
                            if (output.path != root.path && !output.path.startsWith(rootPrefix)) {
                                throw IOException("Unsafe repository path: $relativePath")
                            }

                            val mode = treeWalk.getFileMode(0)
                            if (mode == FileMode.GITLINK) {
                                if (!output.isDirectory && !output.mkdirs()) {
                                    throw IOException("Could not create submodule directory: $relativePath")
                                }
                                continue
                            }

                            if (mode.objectType != Constants.OBJ_BLOB) continue

                            output.parentFile?.let { parent ->
                                if (!parent.isDirectory && !parent.mkdirs()) {
                                    throw IOException("Could not create checkout parent: $relativePath")
                                }
                            }

                            val loader = repository.open(treeWalk.getObjectId(0), Constants.OBJ_BLOB)
                            FileOutputStream(output).use { destination ->
                                loader.copyTo(destination)
                            }

                            if (mode == FileMode.EXECUTABLE_FILE) {
                                output.setExecutable(true, false)
                            }
                        }
                    }
                }
            }
    }
}
