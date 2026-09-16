package com.skypie0102.githubbckp.github

import com.skypie0102.githubbckp.BuildConfig
import com.skypie0102.githubbckp.auth.SecureStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

class GithubWorkflowPermissionRequiredException : IOException(
    "GitHub authorization is missing the workflow permission required for complete repository recovery. Update GitHub permissions and approve workflow access.",
)

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
    fun isConfigured(): Boolean = BuildConfig.GITHUB_CLIENT_ID.isNotBlank()

    fun isAuthenticated(): Boolean = secureStore.get(KEY_ACCESS_TOKEN) != null

    fun hasWorkflowScopeCached(): Boolean =
        githubScopesContainWorkflow(secureStore.get(KEY_OAUTH_SCOPES))

    suspend fun startDeviceFlow(): GithubDeviceSession = withContext(Dispatchers.IO) {
        requireConfigured()
        val response = postForm(
            DEVICE_CODE_URL,
            mapOf(
                "client_id" to BuildConfig.GITHUB_CLIENT_ID,
                "scope" to "repo workflow offline_access",
            ),
        )
        GithubDeviceSession(
            deviceCode = response.getString("device_code"),
            userCode = response.getString("user_code"),
            verificationUri = response.getString("verification_uri"),
            expiresInSeconds = response.getLong("expires_in"),
            intervalSeconds = response.optLong("interval", 5L).coerceAtLeast(1L),
        )
    }

    suspend fun pollUntilAuthorized(session: GithubDeviceSession): String = withContext(Dispatchers.IO) {
        requireConfigured()
        val deadline = System.currentTimeMillis() + session.expiresInSeconds * 1_000L
        var intervalSeconds = session.intervalSeconds

        while (System.currentTimeMillis() < deadline) {
            val response = postForm(
                ACCESS_TOKEN_URL,
                mapOf(
                    "client_id" to BuildConfig.GITHUB_CLIENT_ID,
                    "device_code" to session.deviceCode,
                    "grant_type" to DEVICE_GRANT_TYPE,
                ),
            )

            response.optString("access_token").takeIf { it.isNotBlank() }?.let { accessToken ->
                persistTokenResponse(response)
                return@withContext accessToken
            }

            when (response.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalSeconds += 5L
                "access_denied" -> throw IOException("GitHub authorization was denied")
                "expired_token" -> throw IOException("GitHub device code expired")
                else -> throw IOException(response.optString("error_description", "GitHub authorization failed"))
            }
            delay(intervalSeconds * 1_000L)
        }

        throw IOException("GitHub device authorization expired")
    }

    suspend fun requireAccessToken(): String = withContext(Dispatchers.IO) {
        val accessToken = secureStore.get(KEY_ACCESS_TOKEN)
            ?: throw IOException("GitHub is not connected")
        val expiresAt = secureStore.get(KEY_ACCESS_EXPIRES_AT)?.toLongOrNull() ?: Long.MAX_VALUE
        if (expiresAt > System.currentTimeMillis() + TOKEN_EXPIRY_SKEW_MS) {
            return@withContext accessToken
        }

        val refreshToken = secureStore.get(KEY_REFRESH_TOKEN)
            ?: throw IOException("GitHub authorization expired; reconnect GitHub")
        refresh(refreshToken)
    }

    suspend fun requireRecoveryAccessToken(): String = withContext(Dispatchers.IO) {
        val token = requireAccessToken()
        val cached = secureStore.get(KEY_OAUTH_SCOPES)
        val scopes = if (cached != null) {
            parseGithubOauthScopes(cached)
        } else {
            fetchTokenScopes(token).also(::persistScopes)
        }
        if (GITHUB_WORKFLOW_SCOPE !in scopes) {
            throw GithubWorkflowPermissionRequiredException()
        }
        token
    }

    fun disconnect() {
        secureStore.remove(KEY_ACCESS_TOKEN)
        secureStore.remove(KEY_ACCESS_EXPIRES_AT)
        secureStore.remove(KEY_REFRESH_TOKEN)
        secureStore.remove(KEY_REFRESH_EXPIRES_AT)
        secureStore.remove(KEY_OAUTH_SCOPES)
    }

    private fun refresh(refreshToken: String): String {
        requireConfigured()
        val response = postForm(
            ACCESS_TOKEN_URL,
            mapOf(
                "client_id" to BuildConfig.GITHUB_CLIENT_ID,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
            ),
        )
        val token = response.optString("access_token")
        if (token.isBlank()) {
            disconnect()
            throw IOException(response.optString("error_description", "GitHub token refresh failed"))
        }
        persistTokenResponse(response)
        return token
    }

    private fun persistTokenResponse(response: JSONObject) {
        val now = System.currentTimeMillis()
        val expiresIn = response.optLong("expires_in", 0L)
        val refreshExpiresIn = response.optLong("refresh_token_expires_in", 0L)

        secureStore.put(KEY_ACCESS_TOKEN, response.getString("access_token"))
        secureStore.put(
            KEY_ACCESS_EXPIRES_AT,
            if (expiresIn > 0) (now + expiresIn * 1_000L).toString() else Long.MAX_VALUE.toString(),
        )
        response.optString("refresh_token").takeIf { it.isNotBlank() }?.let {
            secureStore.put(KEY_REFRESH_TOKEN, it)
        }
        if (refreshExpiresIn > 0) {
            secureStore.put(KEY_REFRESH_EXPIRES_AT, (now + refreshExpiresIn * 1_000L).toString())
        }
        response.optString("scope")
            .takeIf { it.isNotBlank() }
            ?.let(::parseGithubOauthScopes)
            ?.let(::persistScopes)
    }

    private fun fetchTokenScopes(token: String): Set<String> {
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
                throw IOException("Unable to verify GitHub OAuth permissions (HTTP $code): ${error.take(300)}")
            }
            val header = connection.getHeaderField("X-OAuth-Scopes")
                ?: throw IOException("GitHub did not report OAuth token scopes")
            parseGithubOauthScopes(header)
        } finally {
            connection.disconnect()
        }
    }

    private fun persistScopes(scopes: Set<String>) {
        secureStore.put(KEY_OAUTH_SCOPES, scopes.sorted().joinToString(","))
    }

    private fun requireConfigured() {
        check(isConfigured()) {
            "Set the GITHUB_CLIENT_ID Gradle property or environment variable before connecting GitHub"
        }
    }

    private fun postForm(endpoint: String, fields: Map<String, String>): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val body = fields.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }.toByteArray(StandardCharsets.UTF_8)

        return try {
            connection.outputStream.use { it.write(body) }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) {
                throw IOException("GitHub returned HTTP ${connection.responseCode}")
            }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private companion object {
        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val GITHUB_USER_URL = "https://api.github.com/user"
        const val GITHUB_API_VERSION = "2026-03-10"
        const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
        const val KEY_ACCESS_TOKEN = "github.access-token"
        const val KEY_ACCESS_EXPIRES_AT = "github.access-token-expires-at"
        const val KEY_REFRESH_TOKEN = "github.refresh-token"
        const val KEY_REFRESH_EXPIRES_AT = "github.refresh-token-expires-at"
        const val KEY_OAUTH_SCOPES = "github.oauth-scopes"
        const val TOKEN_EXPIRY_SKEW_MS = 60_000L
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
