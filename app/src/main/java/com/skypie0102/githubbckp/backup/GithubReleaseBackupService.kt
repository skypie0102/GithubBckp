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

data class GithubReleaseBackupResult(
    val releaseCount: Int,
    val assetCount: Int,
)

@Singleton
class GithubReleaseBackupService @Inject constructor() {
    fun backup(
        repository: RepositoryRef,
        accessToken: String,
        destination: File,
    ): GithubReleaseBackupResult? {
        destination.deleteRecursively()
        destination.mkdirs()
        return try {
            val releases = listReleases(repository, accessToken)
            if (releases.isEmpty()) {
                destination.deleteRecursively()
                return null
            }

            val manifestReleases = JSONArray()
            var assetCount = 0
            releases.forEach { release ->
                val releaseId = release.getLong("id")
                val assets = listReleaseAssets(repository, releaseId, accessToken)
                val manifestAssets = JSONArray()
                assets.forEach { asset ->
                    val assetId = asset.getLong("id")
                    val assetName = asset.getString("name")
                    val state = asset.optString("state", "uploaded")
                    if (state != "uploaded") {
                        throw IOException("Release asset $assetName is not fully uploaded (state=$state)")
                    }
                    val relativePath = "assets/release-$releaseId/$assetId-${safeFileName(assetName)}"
                    val output = File(destination, relativePath)
                    output.parentFile?.mkdirs()
                    val sha256 = downloadAsset(
                        initialUrl = "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/releases/assets/$assetId",
                        accessToken = accessToken,
                        destination = output,
                    )
                    val expectedSize = asset.getLong("size")
                    if (output.length() != expectedSize) {
                        throw IOException(
                            "Release asset $assetName size mismatch: expected $expectedSize, got ${output.length()}",
                        )
                    }
                    asset.optString("digest")
                        .takeIf { it.startsWith(SHA256_PREFIX, ignoreCase = true) }
                        ?.removePrefix(SHA256_PREFIX)
                        ?.let { expectedDigest ->
                            if (!sha256.equals(expectedDigest, ignoreCase = true)) {
                                throw IOException("Release asset $assetName failed GitHub SHA-256 verification")
                            }
                        }

                    manifestAssets.put(
                        JSONObject()
                            .put("sourceId", assetId)
                            .put("name", assetName)
                            .put("label", nullableString(asset, "label"))
                            .put("contentType", asset.optString("content_type", "application/octet-stream"))
                            .put("size", expectedSize)
                            .put("sha256", sha256)
                            .put("sourceDigest", nullableString(asset, "digest"))
                            .put("relativePath", relativePath),
                    )
                    assetCount += 1
                }

                manifestReleases.put(
                    JSONObject()
                        .put("sourceId", releaseId)
                        .put("tagName", release.getString("tag_name"))
                        .put("targetCommitish", release.optString("target_commitish", ""))
                        .put("name", nullableString(release, "name"))
                        .put("body", nullableString(release, "body"))
                        .put("draft", release.optBoolean("draft", false))
                        .put("prerelease", release.optBoolean("prerelease", false))
                        .put("immutable", release.optBoolean("immutable", false))
                        .put("createdAt", release.optString("created_at", ""))
                        .put("publishedAt", nullableString(release, "published_at"))
                        .put("assets", manifestAssets),
                )
            }

            val manifest = JSONObject()
                .put("formatVersion", FORMAT_VERSION)
                .put("createdAtEpochMs", System.currentTimeMillis())
                .put("releases", manifestReleases)
            File(destination, MANIFEST_FILE_NAME).writeText(manifest.toString())
            validateReleaseDirectory(destination)
        } catch (throwable: Throwable) {
            destination.deleteRecursively()
            throw throwable
        }
    }

    fun validateBundledReleases(repositoryDirectory: File): GithubReleaseBackupResult? {
        val releasesDirectory = File(repositoryDirectory, BUNDLED_RELEASES_DIRECTORY)
        if (!releasesDirectory.exists()) return null
        return validateReleaseDirectory(releasesDirectory)
    }

    internal fun validateReleaseDirectory(releasesDirectory: File): GithubReleaseBackupResult {
        val manifestFile = File(releasesDirectory, MANIFEST_FILE_NAME)
        if (!manifestFile.isFile) throw IOException("Bundled releases are missing $MANIFEST_FILE_NAME")
        val manifest = JSONObject(manifestFile.readText())
        if (manifest.optInt("formatVersion", -1) != FORMAT_VERSION) {
            throw IOException("Unsupported bundled release manifest version")
        }
        val releases = manifest.optJSONArray("releases")
            ?: throw IOException("Bundled release manifest contains no releases array")
        val root = releasesDirectory.canonicalFile
        var assetCount = 0
        for (releaseIndex in 0 until releases.length()) {
            val release = releases.getJSONObject(releaseIndex)
            if (release.optString("tagName").isBlank()) {
                throw IOException("Bundled release is missing its tag name")
            }
            val assets = release.optJSONArray("assets")
                ?: throw IOException("Bundled release ${release.optString("tagName")} contains no assets array")
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                val relativePath = asset.getString("relativePath")
                val file = File(root, relativePath).canonicalFile
                val allowedPrefix = root.path + File.separator
                if (file == root || !file.path.startsWith(allowedPrefix)) {
                    throw IOException("Unsafe bundled release asset path: $relativePath")
                }
                if (!file.isFile) throw IOException("Bundled release asset is missing: ${asset.optString("name")}")
                val expectedSize = asset.getLong("size")
                if (file.length() != expectedSize) {
                    throw IOException("Bundled release asset ${asset.optString("name")} failed size verification")
                }
                val actualSha256 = sha256(file)
                val expectedSha256 = asset.getString("sha256")
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    throw IOException("Bundled release asset ${asset.optString("name")} failed SHA-256 verification")
                }
                assetCount += 1
            }
        }
        return GithubReleaseBackupResult(releaseCount = releases.length(), assetCount = assetCount)
    }

    private fun listReleases(repository: RepositoryRef, accessToken: String): List<JSONObject> =
        pagedJsonObjects(
            urlForPage = { page ->
                "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/releases?per_page=$PAGE_SIZE&page=$page"
            },
            accessToken = accessToken,
        )

    private fun listReleaseAssets(
        repository: RepositoryRef,
        releaseId: Long,
        accessToken: String,
    ): List<JSONObject> = pagedJsonObjects(
        urlForPage = { page ->
            "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/releases/$releaseId/assets?per_page=$PAGE_SIZE&page=$page"
        },
        accessToken = accessToken,
    )

    private fun pagedJsonObjects(
        urlForPage: (Int) -> String,
        accessToken: String,
    ): List<JSONObject> = buildList {
        var page = 1
        while (true) {
            val array = getJsonArray(urlForPage(page), accessToken)
            for (index in 0 until array.length()) add(array.getJSONObject(index))
            if (array.length() < PAGE_SIZE) break
            page += 1
        }
    }

    private fun getJsonArray(url: String, accessToken: String): JSONArray {
        val connection = openApiConnection(URL(url), accessToken).apply { requestMethod = "GET" }
        return try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw IOException("GitHub releases API HTTP $code: ${text.take(300)}")
            }
            JSONArray(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadAsset(
        initialUrl: String,
        accessToken: String,
        destination: File,
    ): String {
        var url = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (!url.protocol.equals("https", ignoreCase = true)) {
                throw IOException("Release asset download attempted to leave HTTPS")
            }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = ASSET_READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/octet-stream")
                if (url.host.equals(API_HOST, ignoreCase = true)) {
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("X-GitHub-Api-Version", API_VERSION)
                }
            }
            try {
                when (val code = connection.responseCode) {
                    in 200..299 -> {
                        val digest = MessageDigest.getInstance("SHA-256")
                        destination.outputStream().buffered(BUFFER_SIZE).use { output ->
                            connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read > 0) {
                                        output.write(buffer, 0, read)
                                        digest.update(buffer, 0, read)
                                    }
                                }
                            }
                        }
                        return digest.digest().toHex()
                    }
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308,
                    -> {
                        if (redirectCount >= MAX_REDIRECTS) throw IOException("Too many release asset redirects")
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Release asset redirect did not include Location")
                        url = URL(url, location)
                    }
                    else -> {
                        val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        throw IOException("Release asset download HTTP $code: ${error.take(300)}")
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Unable to download release asset")
    }

    private fun openApiConnection(url: URL, accessToken: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = API_READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun nullableString(json: JSONObject, key: String): Any =
        if (json.isNull(key)) JSONObject.NULL else json.optString(key, "")

    private fun safeFileName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(120)
        .ifBlank { "asset.bin" }

    private fun path(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val BUNDLED_RELEASES_DIRECTORY = "github-backup/releases"
        const val MANIFEST_FILE_NAME = "manifest.json"
        private const val FORMAT_VERSION = 1
        private const val PAGE_SIZE = 100
        private const val API_HOST = "api.github.com"
        private const val API_BASE = "https://api.github.com"
        private const val API_VERSION = "2026-03-10"
        private const val SHA256_PREFIX = "sha256:"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val API_READ_TIMEOUT_MS = 30_000
        private const val ASSET_READ_TIMEOUT_MS = 5 * 60_000
        private const val BUFFER_SIZE = 128 * 1024
        private const val MAX_REDIRECTS = 5
    }
}
