package com.skypie0102.githubbckp.mirror

import com.skypie0102.githubbckp.backup.GitLfsDownloadService
import com.skypie0102.githubbckp.backup.GitLfsObjectStore
import com.skypie0102.githubbckp.backup.GitLfsPointerScanner
import com.skypie0102.githubbckp.auth.SecureStore
import com.skypie0102.githubbckp.github.GithubAuthManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorEnginePlanTest {
    private val engine = MirrorEngine(
        authManager = GithubAuthManager(FakeSecureStore()),
        lfsPointerScanner = GitLfsPointerScanner(),
        lfsDownloadService = GitLfsDownloadService(GitLfsObjectStore()),
    )

    @Test
    fun requiresRebuild_returnsFalseWhenRefsAndMetadataMatch() {
        val repository = repository()
        val manifest = manifest(repository, refsDigest = "same")

        assertFalse(engine.requiresRebuild(manifest, repository, "same"))
    }

    @Test
    fun requiresRebuild_returnsTrueWhenRemoteRefsChanged() {
        val repository = repository()
        val manifest = manifest(repository, refsDigest = "before")

        assertTrue(engine.requiresRebuild(manifest, repository, "after"))
    }

    @Test
    fun requiresRebuild_returnsTrueForRepositoryRenameEvenWhenRefsMatch() {
        val repository = repository()
        val manifest = manifest(repository, refsDigest = "same")
        val renamed = repository.copy(name = "renamed")

        assertTrue(engine.requiresRebuild(manifest, renamed, "same"))
    }

    private fun repository() = MirrorRepository(
        id = 10L,
        owner = "owner",
        name = "repo",
        defaultBranch = "main",
        isPrivate = true,
    )

    private fun manifest(repository: MirrorRepository, refsDigest: String) = MirrorManifest(
        repositoryId = repository.id,
        repositoryOwner = repository.owner,
        repositoryName = repository.name,
        remoteUrl = repository.remoteUrl,
        defaultBranch = repository.defaultBranch,
        isPrivate = repository.isPrivate,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
        lastSuccessfulFetchAtEpochMs = 2L,
        refsDigest = refsDigest,
        headCommit = "abc",
        lfsIncluded = false,
        appVersion = "test",
    )

    /**
     * The plan tests never perform authentication. A tiny SecureStore fake
     * keeps this test dependency-free while exercising MirrorEngine's pure
     * rebuild decision.
     */
    private class FakeSecureStore : SecureStore {
        override fun put(key: String, value: String) = Unit
        override fun get(key: String): String? = null
        override fun remove(key: String) = Unit
    }
}
