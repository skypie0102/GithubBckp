package com.skypie0102.githubbckp.github

import org.junit.Assert.assertEquals
import org.junit.Test

class GithubAuthManagerTest {
    @Test
    fun normalizesPersonalAccessTokenInput() {
        assertEquals("github_pat_example", normalizePersonalAccessToken("  github_pat_example\n"))
        assertEquals("", normalizePersonalAccessToken("   "))
    }
}
