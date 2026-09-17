package com.skypie0102.githubbckp.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubAuthManagerTest {
    @Test
    fun parsesCommaAndWhitespaceSeparatedScopes() {
        val scopes = parseGithubOauthScopes("repo, workflow   gist,repo")

        assertEquals(setOf("repo", "workflow", "gist"), scopes)
    }

    @Test
    fun detectsWorkflowPermissionFromClassicPatScopes() {
        assertTrue(githubScopesContainWorkflow("repo, workflow"))
        assertTrue(githubScopesContainWorkflow("repo workflow"))
        assertFalse(githubScopesContainWorkflow("repo, gist"))
    }

    @Test
    fun tokenWithoutReportedClassicScopesIsNotAssumedToHaveWorkflowPermission() {
        assertFalse(githubScopesContainWorkflow(null))
        assertFalse(githubScopesContainWorkflow(""))
    }
}
