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

@Singleton
class GithubAuthManager @Inject constructor(
    private val secureStore: SecureStore,
) {
    fun isAuthenticated(): Boolean = !secureStore.get(KEY_ACCESS_TOKEN).isNullOrBlank()

    suspend fun connectPersonalAccessToken(rawToken: String): String = withContext(Dispatchers.IO) {
        val token = normalizePersonalAccessToken(rawToken)
        require(token.isNotBlank()) { "Enter a GitHub personal access token" }

        val identity = validateToken(token)
        secureStore.put(KEY_ACCESS_TOKEN, token)
        clearLegacyOauthState()
        identity
    }

    suspend fun requireAccessToken(): String = withContext(Dispatchers.IO) {
        secureStore.get(KEY_ACCESS_TOKEN)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IOException("GitHub is not connected; enter a personal access token")
    }

    suspend fun hasValidAccessToken(): Boolean = withContext(Dispatchers.IO) {
        val token = secureStore.get(KEY_ACCESS_TOKEN)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext false
        runCatching { validateToken(token) }.isSuccess
    }

    fun disconnect() {
        secureStore.remove(KEY_ACCESS_TOKEN)
        clearLegacyOauthState()
    }

    private fun clearLegacyOauthState() {
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
            if (!githubTokenStatusAccepted(code)) {
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


internal fun githubTokenStatusAccepted(statusCode: Int): Boolean =
    statusCode in 200..299
