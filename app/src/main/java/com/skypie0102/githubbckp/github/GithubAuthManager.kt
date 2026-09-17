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

class GithubWorkflowPermissionRequiredException : IOException(
    "GitHub token is missing the workflow permission required for complete repository recovery. Replace it with a token that includes workflow access.",
)

/**
 * Kept as a compatibility type for older UI state. Device Flow is no longer
 * used; personal GitHub authentication is provided directly with a PAT.
 */
data class GithubDeviceSession(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

data class GithubTokenValidation(
    val login: String,
    val scopes: Set<String>,
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
    /** No build-time GitHub OAuth application configuration is required. */
    fun isConfigured(): Boolean = true

    fun isAuthenticated(): Boolean = secureStore.get(KEY_ACCESS_TOKEN) != null

    fun hasWorkflowScopeCached(): Boolean =
        githubScopesContainWorkflow(secureStore.get(KEY_TOKEN_SCOPES))

    suspend fun connectWithPersonalAccessToken(rawToken: String): GithubTokenValidation = withContext(Dispatchers.IO) {
        val token = rawToken.trim()
        require(token.isNotBlank()) { "Enter a GitHub personal access token" }

        val validation = validateToken(token)
        secureStore.put(KEY_ACCESS_TOKEN, token)
        secureStore.put(KEY_TOKEN_SCOPES, validation.scopes.sorted().joinToString(","))
        secureStore.put(KEY_GITHUB_LOGIN, validation.login)
        validation
    }

    suspend fun requireAccessToken(): String = withContext(Dispatchers.IO) {
        secureStore.get(KEY_ACCESS_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("GitHub is not connected; enter a personal access token")
    }

    suspend fun requireRecoveryAccessToken(): String = withContext(Dispatchers.IO) {
        val token = requireAccessToken()
        if (!hasWorkflowScopeCached()) {
            throw GithubWorkflowPermissionRequiredException()
        }
        token
    }

    fun disconnect() {
        secureStore.remove(KEY_ACCESS_TOKEN)
        secureStore.remove(KEY_TOKEN_SCOPES)
        secureStore.remove(KEY_GITHUB_LOGIN)
    }

    private fun validateToken(token: String): GithubTokenValidation {
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
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: text.take(300)
                throw IOException("GitHub rejected the personal access token (HTTP $code): $message")
            }

            val json = JSONObject(text)
            GithubTokenValidation(
                login = json.optString("login").takeIf { it.isNotBlank() } ?: "GitHub user",
                scopes = parseGithubOauthScopes(connection.getHeaderField("X-OAuth-Scopes").orEmpty()),
            )
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val GITHUB_USER_URL = "https://api.github.com/user"
        const val GITHUB_API_VERSION = "2026-03-10"
        const val KEY_ACCESS_TOKEN = "github.access-token"
        const val KEY_TOKEN_SCOPES = "github.token-scopes"
        const val KEY_GITHUB_LOGIN = "github.login"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
