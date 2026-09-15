package com.skypie0102.githubbckp.github

import com.skypie0102.githubbckp.backup.RepositoryRef
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

@Singleton
class GithubRestGateway @Inject constructor(
    private val authManager: GithubAuthManager,
) : GithubGateway {
    override suspend fun listRepositories(): List<RepositoryRef> = withContext(Dispatchers.IO) {
        val token = authManager.requireAccessToken()
        buildList {
            var page = 1
            while (true) {
                val url = "$API_BASE/user/repos?per_page=$PAGE_SIZE&page=$page&sort=full_name&affiliation=owner,collaborator,organization_member"
                val response = getJsonArray(url, token)
                for (index in 0 until response.length()) {
                    val item = response.getJSONObject(index)
                    add(
                        RepositoryRef(
                            id = item.getLong("id"),
                            owner = item.getJSONObject("owner").getString("login"),
                            name = item.getString("name"),
                            defaultBranch = item.optString("default_branch", "main"),
                            isPrivate = item.optBoolean("private", false),
                        ),
                    )
                }
                if (response.length() < PAGE_SIZE) break
                page += 1
            }
        }
    }

    override suspend fun downloadSourceArchive(
        repository: RepositoryRef,
        ref: String,
        destination: File,
    ): Long = withContext(Dispatchers.IO) {
        val token = authManager.requireAccessToken()
        destination.parentFile?.mkdirs()
        val initialUrl = "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/tarball/${path(ref)}"
        downloadFollowingRedirects(initialUrl, token, destination)
    }

    private fun getJsonArray(url: String, token: String): JSONArray {
        val connection = openGet(url, token)
        return try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw IOException("GitHub API HTTP $code: ${text.take(300)}")
            }
            JSONArray(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFollowingRedirects(
        initialUrl: String,
        token: String,
        destination: File,
    ): Long {
        var url = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = openGet(
                url = url,
                token = if (url.startsWith(API_BASE)) token else null,
                followRedirects = false,
            )
            try {
                when (val code = connection.responseCode) {
                    in 200..299 -> {
                        var total = 0L
                        connection.inputStream.use { input ->
                            destination.outputStream().buffered().use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    total += count
                                }
                            }
                        }
                        return total
                    }
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308,
                    -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("GitHub archive redirect did not include Location")
                        if (redirectCount >= MAX_REDIRECTS) {
                            throw IOException("Too many GitHub archive redirects")
                        }
                        url = URL(URL(url), location).toString()
                    }
                    else -> {
                        val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        throw IOException("GitHub archive HTTP $code: ${error.take(300)}")
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Unable to download GitHub archive")
    }

    private fun openGet(
        url: String,
        token: String?,
        followRedirects: Boolean = true,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        instanceFollowRedirects = followRedirects
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = ARCHIVE_READ_TIMEOUT_MS
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (!token.isNullOrBlank()) {
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    private fun path(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val API_BASE = "https://api.github.com"
        const val PAGE_SIZE = 100
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 30_000
        const val ARCHIVE_READ_TIMEOUT_MS = 120_000
        const val BUFFER_SIZE = 64 * 1024
    }
}
