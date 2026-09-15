package com.skypie0102.githubbckp.github

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class GithubRestoreRepository(
    val id: Long,
    val owner: String,
    val name: String,
    val cloneUrl: String,
    val htmlUrl: String,
    val isPrivate: Boolean,
) {
    val fullName: String = "$owner/$name"
}

@Singleton
class GithubRepositoryRestoreGateway @Inject constructor(
    private val authManager: GithubAuthManager,
) {
    suspend fun createRepository(
        name: String,
        isPrivate: Boolean,
    ): GithubRestoreRepository = withContext(Dispatchers.IO) {
        val normalizedName = name.trim()
        require(normalizedName.isNotBlank()) { "Repository name cannot be blank" }
        require(normalizedName.length <= 100) { "Repository name is too long" }

        val body = JSONObject()
            .put("name", normalizedName)
            .put("private", isPrivate)
            .put("auto_init", false)
            .put("description", "Restored by GithubBckp")
        requestRepository(
            url = CREATE_REPOSITORY_URL,
            method = "POST",
            body = body,
            token = authManager.requireAccessToken(),
            failurePrefix = "GitHub repository creation failed",
        )
    }

    suspend fun getRepository(fullName: String): GithubRestoreRepository = withContext(Dispatchers.IO) {
        val normalized = fullName.trim()
        val parts = normalized.split('/')
        require(parts.size == 2 && parts.all { it.isNotBlank() }) {
            "Existing repository must be entered as owner/repository"
        }
        requestRepository(
            url = "$API_BASE/repos/${path(parts[0])}/${path(parts[1])}",
            method = "GET",
            body = null,
            token = authManager.requireAccessToken(),
            failurePrefix = "GitHub repository lookup failed",
        )
    }

    private fun requestRepository(
        url: String,
        method: String,
        body: JSONObject?,
        token: String,
        failurePrefix: String,
    ): GithubRestoreRepository {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            doOutput = body != null
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
            if (body != null) {
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: text.take(300)
                throw IOException("$failurePrefix (HTTP $code): $message")
            }
            return parseRepository(JSONObject(text))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRepository(json: JSONObject): GithubRestoreRepository = GithubRestoreRepository(
        id = json.getLong("id"),
        owner = json.getJSONObject("owner").getString("login"),
        name = json.getString("name"),
        cloneUrl = json.getString("clone_url"),
        htmlUrl = json.getString("html_url"),
        isPrivate = json.optBoolean("private", false),
    )

    private fun path(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val API_BASE = "https://api.github.com"
        const val CREATE_REPOSITORY_URL = "$API_BASE/user/repos"
        const val GITHUB_API_VERSION = "2026-03-10"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
