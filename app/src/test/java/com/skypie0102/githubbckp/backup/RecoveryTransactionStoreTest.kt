package com.skypie0102.githubbckp.backup

import com.skypie0102.githubbckp.github.GithubRestoreRepository
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecoveryTransactionStoreTest {
    @Test
    fun persistsMonotonicRecoveryPhases() {
        val root = Files.createTempDirectory("recovery-transaction-test").toFile()
        try {
            val store = RecoveryTransactionStore(root)
            val repository = repository(id = 42L, fullName = "owner/recovered")
            val bound = store.bind("restore-1", RecoveryTargetKind.NEW_REPOSITORY, repository)
            assertEquals(RecoveryPhase.TARGET_BOUND, bound.phase)

            val lfs = store.markLfsPublished("restore-1", 42L, lfsObjectCount = 3)
            assertEquals(RecoveryPhase.LFS_PUBLISHED, lfs.phase)
            assertEquals(3, lfs.lfsObjectCount)

            val git = store.markGitPublished(
                "restore-1",
                42L,
                MirrorPushResult(5, listOf("refs/pull/1/head")),
            )
            assertEquals(RecoveryPhase.GIT_PUBLISHED, git.phase)
            assertEquals(5, git.pushedRefCount)
            assertEquals(listOf("refs/pull/1/head"), git.skippedReadOnlyRefs)

            val releases = store.markReleasesPublished(
                "restore-1",
                42L,
                GithubReleaseRestoreResult(
                    releaseCount = 2,
                    assetCount = 4,
                    immutableReleaseCount = 1,
                ),
            )
            assertEquals(RecoveryPhase.RELEASES_PUBLISHED, releases.phase)
            assertEquals(2, releases.releaseCount)
            assertEquals(4, releases.releaseAssetCount)

            val reloaded = RecoveryTransactionStore(root).get("restore-1")
            assertEquals(releases, reloaded)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesReleasePhaseBeforeGitPublication() {
        val root = Files.createTempDirectory("recovery-release-order-test").toFile()
        try {
            val store = RecoveryTransactionStore(root)
            store.bind("restore-order", RecoveryTargetKind.NEW_REPOSITORY, repository(42L, "owner/recovered"))

            assertThrows(IOException::class.java) {
                store.markReleasesPublished(
                    "restore-order",
                    42L,
                    GithubReleaseRestoreResult(1, 1, 0),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesRebindingRestoreToDifferentRepository() {
        val root = Files.createTempDirectory("recovery-rebind-test").toFile()
        try {
            val store = RecoveryTransactionStore(root)
            store.bind("restore-2", RecoveryTargetKind.NEW_REPOSITORY, repository(1L, "owner/one"))

            assertThrows(IOException::class.java) {
                store.bind("restore-2", RecoveryTargetKind.NEW_REPOSITORY, repository(2L, "owner/two"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun repository(id: Long, fullName: String): GithubRestoreRepository {
        val (owner, name) = fullName.split('/')
        return GithubRestoreRepository(
            id = id,
            owner = owner,
            name = name,
            cloneUrl = "https://github.com/$fullName.git",
            htmlUrl = "https://github.com/$fullName",
            isPrivate = true,
        )
    }
}
