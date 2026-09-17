package com.skypie0102.githubbckp.github

import com.skypie0102.githubbckp.auth.SecureStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Compatibility type retained so older UI state can compile while personal-token
 * setup replaces GitHub Device Flow. New code should not create device sessions.
 */
data class GithubDeviceSession(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

internal const val GITHUB_WORKFLOW_SCOPE = "workflow"

internal fun parseGithubOauthScopes(value: String): Set<String> = value
    .split(Regex("[\\s,]+"))
    .map(String::trim)
    .filter(String::isNotBlank)
    .toSet()

internal fun githubScopesContainWorkflow(value: String?): Boolean =
    value?.let(::parseGithubOauthScopes)?.contains(GITHUB_WORKFLOW_SCOPE) == true

@Singleton
class GithubAuthManager @Inject constructor(
    private val secureStore: SecureStore,
) {
    /** No build-time GitHub OAuth application configuration is required anymore. */
    fun isConfigured(): Boolean = true

    fun isAuthenticated(): Boolean = !secureStore.get(KEY_ACCESS_TOKEN).isNullOrBlank()

    /**
     * Kept for existing presentation logic. PAT permissions are enforced by GitHub
     * itself during the operation; fine-grained PATs do not expose OAuth scopes in
     * the same way as classic OAuth tokens.
     */
    fun hasWorkflowScopeCached(): Boolean = isAuthenticated()

    suspend fun connectPersonalAccessToken(rawToken: String): String = withContext(Dispatchers.IO) {
        val token = normalizePersonalAccessToken(rawToken)
        require(token.isNotBlank()) { "Enter a GitHub personal access token" }

        val identity = validateToken(token)
        secureStore.put(KEY_ACCESS_TOKEN, token)

        // Remove legacy Device Flow refresh/expiry state so this token is treated
        // only as a user-managed personal token.
        secureStore.remove(KEY_ACCESS_EXPIRES_AT)
        secureStore.remove(KEY_REFRESH_TOKEN)
        secureStore.remove(KEY_REFRESH_EXPIRES_AT)
        secureStore.remove(KEY_OAUTH_SCOPES)

        identity
    }

    suspend fun startDeviceFlow(): GithubDeviceSession = withContext(Dispatchers.IO) {
        throw IOException(
            "GitHub Device Flow is no longer used. Enter a personal access token in the app's GitHub setup.",
        )
    }

    suspend fun pollUntilAuthorized(session: GithubDeviceSession): String = withContext(Dispatchers.IO) {
        @Suppress("UNUSED_VARIABLE") val ignored = session
        throw IOException(
            "GitHub Device Flow is no longer used. Enter a personal access token in the app's GitHub setup.",
        )
    }

    suspend fun requireAccessToken(): String = withContext(Dispatchers.IO) {
        secureStore.get(KEY_ACCESS_TOKEN)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IOException("GitHub is not connected; enter a personal access token")
    }

    /**
     * Recovery uses the same PAT. The previous OAuth `workflow` scope pre-check is
     * intentionally gone because fine-grained PATs use repository permissions
     * rather than OAuth scope strings. GitHub remains the authority on whether a
     * given write is permitted, and existing recovery safety guards still apply.
     */
    suspend fun requireRecoveryAccessToken(): String = requireAccessToken()

    fun disconnect() {
        secureStore.remove(KEY_ACCESS_TOKEN)
        secureStore.remove(KEY_ACCESS_EXPIRES_AT)
        secureStore.remove(KEY_REFRESH_TOKEN)
        secureStore.remove(KEY_REFRESH_EXPIRES_AT)
        secureStore.remove(KEY_OAUTH_SCOPES)
    }

    private fun validateToken(token: String): String {
        val connection = (URL(GITHUB_USER_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException(
                    "GitHub rejected the personal access token (HTTP $code)" +
                        if (error.isBlank()) "" else ": ${error.take(240)}",
                )
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("login").takeIf { it.isNotBlank() }
                ?: "GitHub account"
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val GITHUB_USER_URL = "https://api.github.com/user"
        const val GITHUB_API_VERSION = "2026-03-10"
        const val KEY_ACCESS_TOKEN = "github.access-token"
        const val KEY_ACCESS_EXPIRES_AT = "github.access-token-expires-at"
        const val KEY_REFRESH_TOKEN = "github.refresh-token"
        const val KEY_REFRESH_EXPIRES_AT = "github.refresh-token-expires-at"
        const val KEY_OAUTH_SCOPES = "github.oauth-scopes"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

internal fun normalizePersonalAccessToken(value: String): String = value.trim()
