package com.skypie0102.githubbckp.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubAuthManagerTest {
    @Test
    fun normalizesPersonalAccessTokenInput() {
        assertEquals("github_pat_example", normalizePersonalAccessToken("  github_pat_example\n"))
        assertEquals("", normalizePersonalAccessToken("   "))
    }

    @Test
    fun parsesCommaAndWhitespaceSeparatedScopes() {
        val scopes = parseGithubOauthScopes("repo, workflow   offline_access,repo")

        assertEquals(setOf("repo", "workflow", "offline_access"), scopes)
    }

    @Test
    fun detectsWorkflowPermissionFromCachedScopeText() {
        assertTrue(githubScopesContainWorkflow("repo, workflow, offline_access"))
        assertTrue(githubScopesContainWorkflow("repo workflow"))
        assertFalse(githubScopesContainWorkflow("repo, offline_access"))
    }
}
