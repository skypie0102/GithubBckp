package com.skypie0102.githubbckp.github

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class GithubReleaseAsset(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val apiUrl: String,
)

data class GithubLatestRelease(
    val id: Long,
    val tagName: String,
    val name: String?,
    val body: String?,
    val htmlUrl: String,
    val publishedAt: String?,
    val updatedAt: String,
    val tarballUrl: String,
    val assets: List<GithubReleaseAsset>,
)

@Singleton
class GithubLatestReleaseService @Inject constructor(
    private val authManager: GithubAuthManager,
) {
    suspend fun getLatestRelease(owner: String, name: String): GithubLatestRelease? =
        withContext(Dispatchers.IO) {
            val token = authManager.requireAccessToken()
            val url = "$API_BASE/repos/${encodePath(owner)}/${encodePath(name)}/releases/latest"
            val connection = openApiGet(url, token, JSON_ACCEPT)
            try {
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    return@withContext null
                }
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                if (code !in 200..299) {
                    throw IOException("GitHub latest release HTTP $code: ${body.take(300)}")
                }
                parseLatestReleaseJson(body)
            } finally {
                connection.disconnect()
            }
        }

    suspend fun downloadSource(
        release: GithubLatestRelease,
        destination: File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): Long = download(
        url = release.tarballUrl,
        destination = destination,
        accept = JSON_ACCEPT,
        onProgress = onProgress,
    )

    suspend fun downloadAsset(
        asset: GithubReleaseAsset,
        destination: File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): Long = download(
        url = asset.apiUrl,
        destination = destination,
        accept = OCTET_STREAM_ACCEPT,
        onProgress = onProgress,
    )

    private suspend fun download(
        url: String,
        destination: File,
        accept: String,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val token = authManager.requireAccessToken()
        destination.parentFile?.mkdirs()
        destination.delete()

        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            requireTrustedGithubDownloadUrl(current)
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept", accept)
                setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
                if (current.host.equals("api.github.com", ignoreCase = true)) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }

            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        throw IOException("GitHub download exceeded redirect limit")
                    }
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("GitHub download redirect had no Location header")
                    current = URL(current, location)
                    return@repeat
                }
                if (code !in 200..299) {
                    val error = connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()
                    throw IOException(
                        "GitHub download HTTP $code" +
                            if (error.isBlank()) "" else ": ${error.take(300)}",
                    )
                }

                val total = connection.contentLengthLong.takeIf { it >= 0L }
                var downloaded = 0L
                connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                    FileOutputStream(destination).buffered(BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            downloaded += count
                            onProgress(downloaded, total)
                        }
                    }
                }
                check(destination.isFile && destination.length() == downloaded) {
                    "GitHub download did not persist the expected number of bytes"
                }
                return@withContext downloaded
            } finally {
                connection.disconnect()
            }
        }

        destination.delete()
        throw IOException("GitHub download failed")
    }

    private fun openApiGet(
        url: String,
        token: String,
        accept: String,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("Accept", accept)
        setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
        setRequestProperty("Authorization", "Bearer $token")
    }

    private companion object {
        const val API_BASE = "https://api.github.com"
        const val GITHUB_API_VERSION = "2022-11-28"
        const val JSON_ACCEPT = "application/vnd.github+json"
        const val OCTET_STREAM_ACCEPT = "application/octet-stream"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 180_000
        const val MAX_REDIRECTS = 8
        const val BUFFER_SIZE = 256 * 1024
    }
}

internal fun parseLatestReleaseJson(body: String): GithubLatestRelease {
    val json = JSONObject(body)
    val assetsJson = json.optJSONArray("assets")
    val assets = buildList {
        if (assetsJson != null) {
            for (index in 0 until assetsJson.length()) {
                val item = assetsJson.getJSONObject(index)
                add(
                    GithubReleaseAsset(
                        id = item.getLong("id"),
                        name = item.getString("name"),
                        sizeBytes = item.optLong("size", -1L).coerceAtLeast(0L),
                        apiUrl = item.getString("url"),
                    ),
                )
            }
        }
    }

    return GithubLatestRelease(
        id = json.getLong("id"),
        tagName = json.getString("tag_name"),
        name = json.optString("name").takeIf { it.isNotBlank() },
        body = json.optString("body").takeIf { it.isNotBlank() },
        htmlUrl = json.getString("html_url"),
        publishedAt = json.optString("published_at").takeIf { it.isNotBlank() },
        updatedAt = json.getString("updated_at"),
        tarballUrl = json.getString("tarball_url"),
        assets = assets,
    )
}

internal fun requireTrustedGithubDownloadUrl(url: URL) {
    if (!url.protocol.equals("https", ignoreCase = true)) {
        throw IOException("GitHub download redirected to a non-HTTPS URL")
    }
    val host = url.host.lowercase()
    val trusted =
        host == "api.github.com" ||
            host == "github.com" ||
            host == "codeload.github.com" ||
            host.endsWith(".githubusercontent.com")
    if (!trusted) {
        throw IOException("GitHub download redirected to an untrusted host")
    }
}

private fun encodePath(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
