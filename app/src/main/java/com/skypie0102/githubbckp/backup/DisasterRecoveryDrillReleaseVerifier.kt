package com.skypie0102.githubbckp.backup

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

data class GithubReleaseRemoteVerificationResult(
    val releaseCount: Int,
    val assetCount: Int,
)

private data class DrillRemoteRelease(
    val id: Long,
    val tagName: String,
    val name: String?,
    val body: String?,
    val draft: Boolean,
    val prerelease: Boolean,
)

private data class DrillRemoteAsset(
    val id: Long,
    val name: String,
    val state: String,
    val size: Long,
    val digest: String?,
)

/** Read-only post-publication verification for releases restored by a drill. */
@Singleton
class DisasterRecoveryDrillReleaseVerifier @Inject constructor(
    private val releaseRestoreService: GithubReleaseRestoreService,
) {
    fun verify(
        repositoryFullName: String,
        accessToken: String,
        repositoryDirectory: File,
    ): GithubReleaseRemoteVerificationResult {
        val repository = parseRepository(repositoryFullName)
        val releasesDirectory = File(
            repositoryDirectory,
            GithubReleaseBackupService.BUNDLED_RELEASES_DIRECTORY,
        )
        val expected = if (releasesDirectory.exists()) {
            releaseRestoreService.readBundledReleases(releasesDirectory)
        } else {
            emptyList()
        }
        val remote = listRemoteReleases(repository, accessToken)
        val expectedTags = expected.map { it.tagName }.toSet()
        val remoteTags = remote.map { it.tagName }.toSet()
        if (remoteTags != expectedTags) {
            val missing = expectedTags - remoteTags
            val unexpected = remoteTags - expectedTags
            throw IOException(
                buildString {
                    append("Published releases do not match the restored mirror")
                    if (missing.isNotEmpty()) append("; missing ${missing.take(5).joinToString()}")
                    if (unexpected.isNotEmpty()) append("; unexpected ${unexpected.take(5).joinToString()}")
                },
            )
        }

        var verifiedAssets = 0
        expected.forEach { bundled ->
            val published = remote.single { it.tagName == bundled.tagName }
            if (published.name != bundled.name ||
                published.body != bundled.body ||
                published.draft != bundled.draft ||
                published.prerelease != bundled.prerelease
            ) {
                throw IOException("Published release ${bundled.tagName} metadata does not match the restored mirror")
            }

            val assets = listRemoteAssets(repository, published.id, accessToken)
            val expectedNames = bundled.assets.map { it.name }.toSet()
            val remoteNames = assets.map { it.name }.toSet()
            if (expectedNames != remoteNames || assets.size != bundled.assets.size) {
                throw IOException("Published release ${bundled.tagName} assets do not match the restored mirror")
            }
            bundled.assets.forEach { expectedAsset ->
                val remoteAsset = assets.single { it.name == expectedAsset.name }
                if (remoteAsset.state != "uploaded") {
                    throw IOException("Published release asset ${expectedAsset.name} is not fully uploaded")
                }
                if (remoteAsset.size != expectedAsset.size) {
                    throw IOException("Published release asset ${expectedAsset.name} has a different size")
                }
                val digest = remoteAsset.digest
                if (digest != null && digest.startsWith(SHA256_PREFIX, ignoreCase = true)) {
                    if (!digest.removePrefix(SHA256_PREFIX).equals(expectedAsset.sha256, ignoreCase = true)) {
                        throw IOException("Published release asset ${expectedAsset.name} failed SHA-256 verification")
                    }
                } else {
                    val downloaded = downloadAssetSha256(repository, accessToken, remoteAsset.id)
                    if (!downloaded.equals(expectedAsset.sha256, ignoreCase = true)) {
                        throw IOException("Published release asset ${expectedAsset.name} failed downloaded SHA-256 verification")
                    }
                }
                verifiedAssets += 1
            }
        }

        return GithubReleaseRemoteVerificationResult(
            releaseCount = expected.size,
            assetCount = verifiedAssets,
        )
    }

    private fun listRemoteReleases(
        repository: DrillRepositoryName,
        accessToken: String,
    ): List<DrillRemoteRelease> = pagedJsonObjects(
        urlForPage = { page ->
            "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/releases?per_page=$PAGE_SIZE&page=$page"
        },
        accessToken = accessToken,
    ).map { json ->
        DrillRemoteRelease(
            id = json.getLong("id"),
            tagName = json.getString("tag_name"),
            name = remoteNullableString(json, "name"),
            body = remoteNullableString(json, "body"),
            draft = json.optBoolean("draft", false),
            prerelease = json.optBoolean("prerelease", false),
        )
    }

    private fun listRemoteAssets(
        repository: DrillRepositoryName,
        releaseId: Long,
        accessToken: String,
    ): List<DrillRemoteAsset> = pagedJsonObjects(
        urlForPage = { page ->
            "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/releases/$releaseId/assets?per_page=$PAGE_SIZE&page=$page"
        },
        accessToken = accessToken,
    ).map { json ->
        DrillRemoteAsset(
            id = json.getLong("id"),
            name = json.getString("name"),
            state = json.optString("state", ""),
            size = json.getLong("size"),
            digest = remoteNullableString(json, "digest"),
        )
    }

    private fun pagedJsonObjects(
        urlForPage: (Int) -> String,
        accessToken: String,
    ): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var page = 1
        while (true) {
            val connection = openApiConnection(urlForPage(page), accessToken)
            try {
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                if (code !in 200..299) {
                    throw IOException("GitHub drill verification failed (HTTP $code): ${messageFrom(text)}")
                }
                val array = JSONArray(text)
                for (index in 0 until array.length()) result += array.getJSONObject(index)
                if (array.length() < PAGE_SIZE) break
                page += 1
            } finally {
                connection.disconnect()
            }
        }
        return result
    }

    private fun downloadAssetSha256(
        repository: DrillRepositoryName,
        accessToken: String,
        assetId: Long,
    ): String = downloadAssetSha256(
        url = "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/releases/assets/$assetId",
        accessToken = accessToken,
        redirectsRemaining = MAX_REDIRECTS,
        includeGithubAuth = true,
    )

    private fun downloadAssetSha256(
        url: String,
        accessToken: String,
        redirectsRemaining: Int,
        includeGithubAuth: Boolean,
    ): String {
        val parsed = URL(url)
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            throw IOException("Release asset verification attempted to leave HTTPS")
        }
        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = ASSET_READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/octet-stream")
            if (includeGithubAuth) {
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            }
        }
        try {
            val code = connection.responseCode
            if (code in REDIRECT_CODES) {
                if (redirectsRemaining <= 0) throw IOException("Too many release asset verification redirects")
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("Release asset redirect did not include a Location header")
                val redirected = URL(parsed, location)
                return downloadAssetSha256(
                    url = redirected.toString(),
                    accessToken = accessToken,
                    redirectsRemaining = redirectsRemaining - 1,
                    includeGithubAuth = redirected.host.equals(parsed.host, ignoreCase = true) && includeGithubAuth,
                )
            }
            if (code !in 200..299) {
                val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Release asset verification failed (HTTP $code): ${messageFrom(text)}")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        } finally {
            connection.disconnect()
        }
    }

    private fun openApiConnection(url: String, accessToken: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
        }

    private fun parseRepository(value: String): DrillRepositoryName {
        val parts = value.trim().split('/')
        if (parts.size != 2 || parts.any { it.isBlank() }) {
            throw IOException("Invalid GitHub repository name")
        }
        return DrillRepositoryName(parts[0], parts[1])
    }

    private fun path(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun messageFrom(text: String): String =
        runCatching { JSONObject(text).optString("message") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: text.take(300).ifBlank { "No response body" }

    private data class DrillRepositoryName(val owner: String, val name: String)

    private companion object {
        const val API_BASE = "https://api.github.com"
        const val API_VERSION = "2026-03-10"
        const val SHA256_PREFIX = "sha256:"
        const val PAGE_SIZE = 100
        const val BUFFER_SIZE = 128 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
        const val ASSET_READ_TIMEOUT_MS = 5 * 60_000
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

private fun remoteNullableString(json: JSONObject, key: String): String? =
    if (!json.has(key) || json.isNull(key)) null else json.optString(key).takeIf { it.isNotEmpty() }
