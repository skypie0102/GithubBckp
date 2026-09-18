package com.skypie0102.githubbckp.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubAuthManagerTest {
    @Test
    fun revokedOrForbiddenTokenResponsesAreRejected() {
        assertTrue(githubTokenStatusAccepted(200))
        assertTrue(githubTokenStatusAccepted(204))
        assertFalse(githubTokenStatusAccepted(401))
        assertFalse(githubTokenStatusAccepted(403))
    }

    @Test
    fun normalizesPersonalAccessTokenInput() {
        assertEquals("github_pat_example", normalizePersonalAccessToken("  github_pat_example\n"))
        assertEquals("", normalizePersonalAccessToken("   "))
    }
}
