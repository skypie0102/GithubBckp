package com.skypie0102.githubbckp.github

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class GithubCreatedRepository(
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
    ): GithubCreatedRepository = withContext(Dispatchers.IO) {
        val normalizedName = name.trim()
        require(normalizedName.isNotBlank()) { "Repository name cannot be blank" }
        require(normalizedName.length <= 100) { "Repository name is too long" }

        val token = authManager.requireAccessToken()
        val body = JSONObject()
            .put("name", normalizedName)
            .put("private", isPrivate)
            .put("auto_init", false)
            .put("description", "Restored by GithubBckp")

        val connection = (URL(CREATE_REPOSITORY_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        try {
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
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
                throw IOException("GitHub repository creation failed (HTTP $code): $message")
            }
            val json = JSONObject(text)
            GithubCreatedRepository(
                id = json.getLong("id"),
                owner = json.getJSONObject("owner").getString("login"),
                name = json.getString("name"),
                cloneUrl = json.getString("clone_url"),
                htmlUrl = json.getString("html_url"),
                isPrivate = json.optBoolean("private", isPrivate),
            )
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CREATE_REPOSITORY_URL = "https://api.github.com/user/repos"
        const val GITHUB_API_VERSION = "2026-03-10"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
