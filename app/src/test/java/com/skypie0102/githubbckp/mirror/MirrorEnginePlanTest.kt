package com.skypie0102.githubbckp.mirror

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorEnginePlanTest {
    @Test
    fun requiresRebuild_returnsFalseWhenRefsAndMetadataMatch() {
        val repository = repository()
        val manifest = manifest(repository, refsDigest = "same")

        assertFalse(mirrorRequiresRebuild(manifest, repository, "same"))
    }

    @Test
    fun requiresRebuild_returnsTrueForLegacyArchiveWithoutWorkingTree() {
        val repository = repository()
        val manifest = manifest(repository, refsDigest = "same").copy(
            workingTreeIncluded = false,
        )

        assertTrue(mirrorRequiresRebuild(manifest, repository, "same"))
    }

    @Test
    fun requiresRebuild_returnsTrueWhenRemoteRefsChanged() {
        val repository = repository()
        val manifest = manifest(repository, refsDigest = "before")

        assertTrue(mirrorRequiresRebuild(manifest, repository, "after"))
    }

    @Test
    fun requiresRebuild_returnsTrueForRepositoryRenameEvenWhenRefsMatch() {
        val repository = repository()
        val manifest = manifest(repository, refsDigest = "same")
        val renamed = repository.copy(name = "renamed")

        assertTrue(mirrorRequiresRebuild(manifest, renamed, "same"))
    }

    @Test
    fun requiresRebuild_returnsTrueWhenDefaultBranchChanges() {
        val repository = repository()
        val manifest = manifest(repository, refsDigest = "same")

        assertTrue(
            mirrorRequiresRebuild(
                manifest,
                repository.copy(defaultBranch = "trunk"),
                "same",
            ),
        )
    }

    @Test
    fun requiresRebuild_returnsTrueWhenPrivacyChanges() {
        val repository = repository()
        val manifest = manifest(repository, refsDigest = "same")

        assertTrue(
            mirrorRequiresRebuild(
                manifest,
                repository.copy(isPrivate = false),
                "same",
            ),
        )
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
}
