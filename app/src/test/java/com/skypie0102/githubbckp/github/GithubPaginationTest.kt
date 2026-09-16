package com.skypie0102.githubbckp.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubPaginationTest {
    @Test
    fun findsNextRelationFromLinkHeader() {
        val header =
            "<https://api.github.com/user/repos?per_page=100&page=1>; rel=\"prev\", " +
                "<https://api.github.com/user/repos?per_page=100&page=3>; rel=\"next\", " +
                "<https://api.github.com/user/repos?per_page=100&page=9>; rel=\"last\""

        assertEquals(
            "https://api.github.com/user/repos?per_page=100&page=3",
            githubNextLink(header),
        )
    }

    @Test
    fun supportsMultipleRelationTokensAndNoNextCase() {
        assertEquals(
            "https://api.github.com/user/repos?page=2",
            githubNextLink("<https://api.github.com/user/repos?page=2>; rel=\"next last\""),
        )
        assertNull(
            githubNextLink(
                "<https://api.github.com/user/repos?page=1>; rel=\"prev\", " +
                    "<https://api.github.com/user/repos?page=9>; rel=\"last\"",
            ),
        )
        assertNull(githubNextLink(null))
    }

    @Test
    fun acceptsOnlyHttpsApiGithubPaginationUrls() {
        assertEquals(
            "https://api.github.com/user/repos?page=2",
            requireTrustedGithubApiUrl("https://api.github.com/user/repos?page=2"),
        )

        val wrongHost = runCatching {
            requireTrustedGithubApiUrl("https://example.com/user/repos?page=2")
        }.exceptionOrNull()
        val wrongScheme = runCatching {
            requireTrustedGithubApiUrl("http://api.github.com/user/repos?page=2")
        }.exceptionOrNull()
        val wrongPort = runCatching {
            requireTrustedGithubApiUrl("https://api.github.com:8443/user/repos?page=2")
        }.exceptionOrNull()

        assertTrue(wrongHost?.message?.contains("untrusted") == true)
        assertTrue(wrongScheme?.message?.contains("untrusted") == true)
        assertTrue(wrongPort?.message?.contains("untrusted") == true)
    }
}
