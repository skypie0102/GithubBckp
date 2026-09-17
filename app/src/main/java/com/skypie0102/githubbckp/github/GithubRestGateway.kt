package com.skypie0102.githubbckp.github

import com.skypie0102.githubbckp.backup.RepositoryRef
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
import org.json.JSONObject

@Singleton
class GithubRestGateway @Inject constructor(
    private val authManager: GithubAuthManager,
) : GithubGateway {
    override suspend fun listRepositories(): List<RepositoryRef> = withContext(Dispatchers.IO) {
        val token = authManager.requireAccessToken()
        buildList {
            var nextUrl: String? =
                "$API_BASE/user/repos?per_page=$PAGE_SIZE&sort=full_name&affiliation=owner,collaborator,organization_member"
            while (nextUrl != null) {
                val response = getJsonResponse(nextUrl, token)
                val repositories = JSONArray(response.body)
                for (index in 0 until repositories.length()) {
                    val item = repositories.getJSONObject(index)
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
                nextUrl = githubNextLink(response.linkHeader)?.let(::requireTrustedGithubApiUrl)
            }
        }
    }

    override suspend fun repositoryHasWiki(repository: RepositoryRef): Boolean = withContext(Dispatchers.IO) {
        val token = authManager.requireAccessToken()
        val url = "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}"
        getJsonObject(url, token).optBoolean("has_wiki", false)
    }

    private fun getJsonObject(url: String, token: String): JSONObject =
        JSONObject(getJsonResponse(url, token).body)

    private fun getJsonResponse(url: String, token: String): GithubJsonResponse {
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
            GithubJsonResponse(
                body = text,
                linkHeader = connection.getHeaderField("Link"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openGet(url: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun path(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val API_BASE = "https://api.github.com"
        const val PAGE_SIZE = 100
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000
    }
}

internal data class GithubJsonResponse(
    val body: String,
    val linkHeader: String?,
)

internal fun githubNextLink(linkHeader: String?): String? {
    if (linkHeader.isNullOrBlank()) return null
    return GITHUB_LINK_REGEX.findAll(linkHeader)
        .firstOrNull { match ->
            match.groupValues[2]
                .split(' ')
                .any { relation -> relation.equals("next", ignoreCase = true) }
        }
        ?.groupValues
        ?.get(1)
}

internal fun requireTrustedGithubApiUrl(value: String): String {
    val url = runCatching { URL(value) }
        .getOrElse { throw IOException("GitHub pagination returned an invalid next URL") }
    if (
        !url.protocol.equals("https", ignoreCase = true) ||
        !url.host.equals("api.github.com", ignoreCase = true) ||
        (url.port != -1 && url.port != 443)
    ) {
        throw IOException("GitHub pagination returned an untrusted next URL")
    }
    return url.toString()
}

private val GITHUB_LINK_REGEX = Regex(
    pattern = """<([^>]+)>\s*;[^,]*?\brel\s*=\s*\"([^\"]+)\"""",
    option = RegexOption.IGNORE_CASE,
)
