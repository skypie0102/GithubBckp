package com.skypie0102.githubbckp.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class LatestReleaseStorageNamingTest {
    @Test
    fun stableNameShowsRepositoryAndReleaseTag() {
        assertEquals(
            "skypie0102--GithubBckp--v0.3.3--release.tar.gz",
            latestReleaseStableName(
                repositoryId = 123L,
                owner = "skypie0102",
                name = "GithubBckp",
                tagName = "v0.3.3",
            ),
        )
    }

    @Test
    fun tagIsSanitizedForStorage() {
        assertEquals(
            "owner--repo--release_candidate_1--release.tar.gz",
            latestReleaseStableName(
                repositoryId = 1L,
                owner = "owner",
                name = "repo",
                tagName = "release/candidate 1",
            ),
        )
    }
}
