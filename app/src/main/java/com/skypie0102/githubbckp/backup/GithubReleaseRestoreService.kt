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

data class GithubReleaseRestoreResult(
    val releaseCount: Int,
    val assetCount: Int,
    val immutableReleaseCount: Int,
)

internal data class BundledGithubRelease(
    val tagName: String,
    val targetCommitish: String,
    val name: String?,
    val body: String?,
    val draft: Boolean,
    val prerelease: Boolean,
    val immutable: Boolean,
    val assets: List<BundledGithubReleaseAsset>,
)

internal data class BundledGithubReleaseAsset(
    val name: String,
    val label: String?,
    val contentType: String,
    val size: Long,
    val sha256: String,
    val relativePath: String,
)

private data class RemoteRelease(
    val id: Long,
    val tagName: String,
    val name: String?,
    val body: String?,
    val draft: Boolean,
    val prerelease: Boolean,
)

internal data class RemoteReleaseAsset(
    val id: Long,
    val name: String,
    val label: String?,
    val state: String,
    val size: Long,
    val digest: String?,
)

internal enum class ExistingAssetAction {
    REUSE,
    DELETE_STARTER_AND_UPLOAD,
    UPLOAD,
}

internal data class ExistingAssetDecision(
    val action: ExistingAssetAction,
    val assetId: Long? = null,
)

/**
 * Publishes locally validated release manifests/assets after the Git recovery
 * transaction has reached GIT_PUBLISHED. The operation is intentionally
 * idempotent: retries reconcile matching releases/assets instead of blindly
 * recreating them, and conflicting uploaded state is never overwritten.
 */
@Singleton
class GithubReleaseRestoreService @Inject constructor(
    private val backupService: GithubReleaseBackupService,
) {
    fun requireNoExistingReleases(
        repositoryFullName: String,
        accessToken: String,
    ) {
        val repository = parseRepository(repositoryFullName)
        val existing = listRemoteReleases(repository, accessToken)
        if (existing.isNotEmpty()) {
            val preview = existing.take(5).joinToString { it.tagName }
            throw IOException(
                "Restore target already has ${existing.size} GitHub release(s): $preview",
            )
        }
    }

    fun publishBundledReleases(
        repositoryFullName: String,
        accessToken: String,
        repositoryDirectory: File,
    ): GithubReleaseRestoreResult {
        val releasesDirectory = File(
            repositoryDirectory,
            GithubReleaseBackupService.BUNDLED_RELEASES_DIRECTORY,
        )
        if (!releasesDirectory.exists()) {
            return GithubReleaseRestoreResult(0, 0, 0)
        }

        backupService.validateBundledReleases(repositoryDirectory)
        val bundled = readBundledReleases(releasesDirectory)
        val repository = parseRepository(repositoryFullName)
        var remoteReleases = listRemoteReleases(repository, accessToken)

        val expectedTags = bundled.map { it.tagName }.toSet()
        val unexpectedTags = remoteReleases.map { it.tagName }.filterNot(expectedTags::contains)
        if (unexpectedTags.isNotEmpty()) {
            throw IOException(
                "Recovery target contains unexpected release tag(s): ${unexpectedTags.take(5).joinToString()}",
            )
        }

        var restoredAssets = 0
        bundled.forEach { expectedRelease ->
            val existing = remoteReleases.singleOrNull { it.tagName == expectedRelease.tagName }
            val remoteRelease = if (existing == null) {
                createRelease(repository, accessToken, expectedRelease).also { created ->
                    remoteReleases = remoteReleases + created
                }
            } else {
                requireReleaseMatches(existing, expectedRelease)
                existing
            }

            restoredAssets += restoreAssets(
                repository = repository,
                accessToken = accessToken,
                releaseId = remoteRelease.id,
                releasesDirectory = releasesDirectory,
                expectedAssets = expectedRelease.assets,
            )
        }

        return GithubReleaseRestoreResult(
            releaseCount = bundled.size,
            assetCount = restoredAssets,
            immutableReleaseCount = bundled.count { it.immutable },
        )
    }

    internal fun readBundledReleases(releasesDirectory: File): List<BundledGithubRelease> {
        val manifestFile = File(releasesDirectory, GithubReleaseBackupService.MANIFEST_FILE_NAME)
        if (!manifestFile.isFile) throw IOException("Bundled releases are missing manifest.json")
        val manifest = JSONObject(manifestFile.readText())
        if (manifest.optInt("formatVersion", -1) != 1) {
            throw IOException("Unsupported bundled release manifest version")
        }
        val releases = manifest.optJSONArray("releases")
            ?: throw IOException("Bundled release manifest contains no releases array")
        val result = ArrayList<BundledGithubRelease>(releases.length())
        val seenTags = mutableSetOf<String>()
        for (releaseIndex in 0 until releases.length()) {
            val release = releases.getJSONObject(releaseIndex)
            val tagName = release.optString("tagName")
            if (tagName.isBlank()) throw IOException("Bundled release is missing its tag name")
            if (!seenTags.add(tagName)) throw IOException("Bundled release tag is duplicated: $tagName")
            val assetsJson = release.optJSONArray("assets")
                ?: throw IOException("Bundled release $tagName contains no assets array")
            val assets = ArrayList<BundledGithubReleaseAsset>(assetsJson.length())
            for (assetIndex in 0 until assetsJson.length()) {
                val asset = assetsJson.getJSONObject(assetIndex)
                assets += BundledGithubReleaseAsset(
                    name = asset.getString("name"),
                    label = nullableString(asset, "label"),
                    contentType = asset.optString("contentType", "application/octet-stream")
                        .ifBlank { "application/octet-stream" },
                    size = asset.getLong("size"),
                    sha256 = asset.getString("sha256").lowercase(),
                    relativePath = asset.getString("relativePath"),
                )
            }
            result += BundledGithubRelease(
                tagName = tagName,
                targetCommitish = release.optString("targetCommitish", ""),
                name = nullableString(release, "name"),
                body = nullableString(release, "body"),
                draft = release.optBoolean("draft", false),
                prerelease = release.optBoolean("prerelease", false),
                immutable = release.optBoolean("immutable", false),
                assets = assets,
            )
        }
        return result
    }

    internal fun decideExistingAsset(
        expected: BundledGithubReleaseAsset,
        remoteAssets: List<RemoteReleaseAsset>,
        usedAssetIds: Set<Long>,
    ): ExistingAssetDecision {
        val available = remoteAssets.filterNot { it.id in usedAssetIds }
        val exactName = available.filter { it.name == expected.name }
        if (exactName.size > 1) {
            throw IOException("Release contains duplicate asset name ${expected.name}")
        }
        exactName.singleOrNull()?.let { remote ->
            if (remote.state == "starter") {
                return ExistingAssetDecision(ExistingAssetAction.DELETE_STARTER_AND_UPLOAD, remote.id)
            }
            if (remote.state != "uploaded") {
                throw IOException("Release asset ${expected.name} is in unexpected state ${remote.state}")
            }
            requireRemoteAssetMetadataMatches(expected, remote)
            return ExistingAssetDecision(ExistingAssetAction.REUSE, remote.id)
        }

        val digestMatches = available.filter { remote ->
            remote.state == "uploaded" &&
                remote.size == expected.size &&
                remote.digest?.removePrefix(SHA256_PREFIX)?.equals(expected.sha256, ignoreCase = true) == true
        }
        if (digestMatches.size == 1) {
            return ExistingAssetDecision(ExistingAssetAction.REUSE, digestMatches.single().id)
        }
        if (digestMatches.size > 1) {
            throw IOException(
                "Release asset ${expected.name} has multiple remote SHA-256 matches; refusing ambiguous resume",
            )
        }
        return ExistingAssetDecision(ExistingAssetAction.UPLOAD)
    }

    private fun restoreAssets(
        repository: RepositoryName,
        accessToken: String,
        releaseId: Long,
        releasesDirectory: File,
        expectedAssets: List<BundledGithubReleaseAsset>,
    ): Int {
        var remoteAssets = listRemoteAssets(repository, releaseId, accessToken)
        val usedIds = mutableSetOf<Long>()

        expectedAssets.forEach { expected ->
            val file = resolveAssetFile(releasesDirectory, expected)
            val decision = decideExistingAsset(expected, remoteAssets, usedIds)
            when (decision.action) {
                ExistingAssetAction.REUSE -> {
                    val assetId = checkNotNull(decision.assetId)
                    val remote = remoteAssets.first { it.id == assetId }
                    verifyRemoteAsset(repository, accessToken, expected, remote)
                    usedIds += assetId
                }
                ExistingAssetAction.DELETE_STARTER_AND_UPLOAD -> {
                    val starterId = checkNotNull(decision.assetId)
                    deleteRemoteAsset(repository, accessToken, starterId)
                    remoteAssets = remoteAssets.filterNot { it.id == starterId }
                    val uploaded = uploadAsset(repository, accessToken, releaseId, expected, file)
                    verifyRemoteAsset(repository, accessToken, expected, uploaded)
                    remoteAssets = remoteAssets + uploaded
                    usedIds += uploaded.id
                }
                ExistingAssetAction.UPLOAD -> {
                    val uploaded = uploadAsset(repository, accessToken, releaseId, expected, file)
                    verifyRemoteAsset(repository, accessToken, expected, uploaded)
                    remoteAssets = remoteAssets + uploaded
                    usedIds += uploaded.id
                }
            }
        }

        val unexpected = remoteAssets.filterNot { it.id in usedIds }
        if (unexpected.isNotEmpty()) {
            val preview = unexpected.take(5).joinToString { it.name }
            throw IOException("Release contains unexpected remote asset(s): $preview")
        }
        return expectedAssets.size
    }

    private fun resolveAssetFile(
        releasesDirectory: File,
        expected: BundledGithubReleaseAsset,
    ): File {
        val root = releasesDirectory.canonicalFile
        val file = File(root, expected.relativePath).canonicalFile
        val allowedPrefix = root.path + File.separator
        if (file == root || !file.path.startsWith(allowedPrefix)) {
            throw IOException("Unsafe bundled release asset path: ${expected.relativePath}")
        }
        if (!file.isFile) throw IOException("Bundled release asset is missing: ${expected.name}")
        if (file.length() != expected.size) {
            throw IOException("Bundled release asset ${expected.name} failed size verification")
        }
        return file
    }

    private fun requireReleaseMatches(remote: RemoteRelease, expected: BundledGithubRelease) {
        if (remote.name != expected.name ||
            remote.body != expected.body ||
            remote.draft != expected.draft ||
            remote.prerelease != expected.prerelease
        ) {
            throw IOException(
                "Existing release ${expected.tagName} does not match the bundled release metadata",
            )
        }
    }

    private fun requireRemoteAssetMetadataMatches(
        expected: BundledGithubReleaseAsset,
        remote: RemoteReleaseAsset,
    ) {
        if (remote.size != expected.size) {
            throw IOException("Existing release asset ${expected.name} has a different size")
        }
        remote.digest?.takeIf { it.startsWith(SHA256_PREFIX, ignoreCase = true) }?.let { digest ->
            if (!digest.removePrefix(SHA256_PREFIX).equals(expected.sha256, ignoreCase = true)) {
                throw IOException("Existing release asset ${expected.name} has a different SHA-256")
            }
        }
    }

    private fun verifyRemoteAsset(
        repository: RepositoryName,
        accessToken: String,
        expected: BundledGithubReleaseAsset,
        remote: RemoteReleaseAsset,
    ) {
        if (remote.state != "uploaded") {
            throw IOException("Release asset ${remote.name} is not fully uploaded (state=${remote.state})")
        }
        if (remote.size != expected.size) {
            throw IOException("Release asset ${remote.name} failed remote size verification")
        }
        val digest = remote.digest
        if (digest != null && digest.startsWith(SHA256_PREFIX, ignoreCase = true)) {
            if (!digest.removePrefix(SHA256_PREFIX).equals(expected.sha256, ignoreCase = true)) {
                throw IOException("Release asset ${remote.name} failed remote SHA-256 verification")
            }
            return
        }
        val downloadedSha256 = downloadRemoteAssetSha256(repository, accessToken, remote.id)
        if (!downloadedSha256.equals(expected.sha256, ignoreCase = true)) {
            throw IOException("Release asset ${remote.name} failed downloaded SHA-256 verification")
        }
    }

    private fun createRelease(
        repository: RepositoryName,
        accessToken: String,
        expected: BundledGithubRelease,
    ): RemoteRelease {
        val body = JSONObject()
            .put("tag_name", expected.tagName)
            .put("draft", expected.draft)
            .put("prerelease", expected.prerelease)
            .put("generate_release_notes", false)
            .put("make_latest", "false")
        if (expected.targetCommitish.isNotBlank()) body.put("target_commitish", expected.targetCommitish)
        expected.name?.let { body.put("name", it) }
        expected.body?.let { body.put("body", it) }

        val json = requestJsonObject(
            url = "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/releases",
            method = "POST",
            accessToken = accessToken,
            body = body,
            expectedStatus = HttpURLConnection.HTTP_CREATED,
            failurePrefix = "GitHub release ${expected.tagName} creation failed",
        )
        val remote = parseRemoteRelease(json)
        requireReleaseMatches(remote, expected)
        return remote
    }

    private fun listRemoteReleases(
        repository: RepositoryName,
        accessToken: String,
    ): List<RemoteRelease> = pagedJsonObjects(
        urlForPage = { page ->
            "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/releases?per_page=$PAGE_SIZE&page=$page"
        },
        accessToken = accessToken,
    ).map(::parseRemoteRelease)

    private fun listRemoteAssets(
        repository: RepositoryName,
        releaseId: Long,
        accessToken: String,
    ): List<RemoteReleaseAsset> = pagedJsonObjects(
        urlForPage = { page ->
            "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/releases/$releaseId/assets?per_page=$PAGE_SIZE&page=$page"
        },
        accessToken = accessToken,
    ).map(::parseRemoteAsset)

    private fun uploadAsset(
        repository: RepositoryName,
        accessToken: String,
        releaseId: Long,
        expected: BundledGithubReleaseAsset,
        file: File,
    ): RemoteReleaseAsset {
        val query = buildString {
            append("name=").append(path(expected.name))
            expected.label?.let { append("&label=").append(path(it)) }
        }
        val url = URL(
            "$UPLOADS_BASE/repos/${path(repository.owner)}/${path(repository.name)}/releases/$releaseId/assets?$query",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = ASSET_TRANSFER_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            setRequestProperty("Content-Type", expected.contentType)
            setFixedLengthStreamingMode(file.length())
        }
        try {
            file.inputStream().buffered(BUFFER_SIZE).use { input ->
                connection.outputStream.buffered(BUFFER_SIZE).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code != HttpURLConnection.HTTP_CREATED) {
                throw IOException(
                    "GitHub release asset ${expected.name} upload failed (HTTP $code): ${messageFrom(text)}",
                )
            }
            return parseRemoteAsset(JSONObject(text))
        } finally {
            connection.disconnect()
        }
    }

    private fun deleteRemoteAsset(
        repository: RepositoryName,
        accessToken: String,
        assetId: Long,
    ) {
        requestNoContent(
            url = "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/releases/assets/$assetId",
            method = "DELETE",
            accessToken = accessToken,
            failurePrefix = "GitHub starter release asset deletion failed",
        )
    }

    private fun downloadRemoteAssetSha256(
        repository: RepositoryName,
        accessToken: String,
        assetId: Long,
    ): String {
        var url = URL(
            "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/releases/assets/$assetId",
        )
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (!url.protocol.equals("https", ignoreCase = true)) {
                throw IOException("Release asset verification attempted to leave HTTPS")
            }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = ASSET_TRANSFER_TIMEOUT_MS
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
                        connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count > 0) digest.update(buffer, 0, count)
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
                        if (redirectCount >= MAX_REDIRECTS) {
                            throw IOException("Too many release asset verification redirects")
                        }
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Release asset verification redirect omitted Location")
                        url = URL(url, location)
                    }
                    else -> {
                        val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        throw IOException(
                            "Release asset verification failed (HTTP $code): ${messageFrom(text)}",
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Unable to verify release asset")
    }

    private fun pagedJsonObjects(
        urlForPage: (Int) -> String,
        accessToken: String,
    ): List<JSONObject> = buildList {
        var page = 1
        while (true) {
            val array = requestJsonArray(urlForPage(page), accessToken)
            for (index in 0 until array.length()) add(array.getJSONObject(index))
            if (array.length() < PAGE_SIZE) break
            page += 1
        }
    }

    private fun requestJsonArray(url: String, accessToken: String): JSONArray {
        val connection = openApiConnection(URL(url), accessToken).apply { requestMethod = "GET" }
        return try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw IOException("GitHub releases API HTTP $code: ${messageFrom(text)}")
            }
            JSONArray(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestJsonObject(
        url: String,
        method: String,
        accessToken: String,
        body: JSONObject,
        expectedStatus: Int,
        failurePrefix: String,
    ): JSONObject {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        val connection = openApiConnection(URL(url), accessToken).apply {
            requestMethod = method
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setFixedLengthStreamingMode(bytes.size)
        }
        try {
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code != expectedStatus) {
                throw IOException("$failurePrefix (HTTP $code): ${messageFrom(text)}")
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestNoContent(
        url: String,
        method: String,
        accessToken: String,
        failurePrefix: String,
    ) {
        val connection = openApiConnection(URL(url), accessToken).apply { requestMethod = method }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_NO_CONTENT) {
                val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("$failurePrefix (HTTP $code): ${messageFrom(text)}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openApiConnection(url: URL, accessToken: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = API_READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
        }

    private fun parseRemoteRelease(json: JSONObject): RemoteRelease = RemoteRelease(
        id = json.getLong("id"),
        tagName = json.getString("tag_name"),
        name = nullableString(json, "name"),
        body = nullableString(json, "body"),
        draft = json.optBoolean("draft", false),
        prerelease = json.optBoolean("prerelease", false),
    )

    private fun parseRemoteAsset(json: JSONObject): RemoteReleaseAsset = RemoteReleaseAsset(
        id = json.getLong("id"),
        name = json.getString("name"),
        label = nullableString(json, "label"),
        state = json.optString("state", "uploaded"),
        size = json.getLong("size"),
        digest = nullableString(json, "digest"),
    )

    private fun parseRepository(fullName: String): RepositoryName {
        val parts = fullName.trim().split('/')
        require(parts.size == 2 && parts.all { it.isNotBlank() }) {
            "GitHub repository must be entered as owner/repository"
        }
        return RepositoryName(parts[0], parts[1])
    }

    private fun nullableString(json: JSONObject, key: String): String? =
        if (!json.has(key) || json.isNull(key)) null else json.optString(key)

    private fun path(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun messageFrom(text: String): String = runCatching {
        JSONObject(text).optString("message")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: text.take(300).ifBlank { "No response body" }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private data class RepositoryName(val owner: String, val name: String)

    private companion object {
        const val API_BASE = "https://api.github.com"
        const val UPLOADS_BASE = "https://uploads.github.com"
        const val API_HOST = "api.github.com"
        const val API_VERSION = "2026-03-10"
        const val PAGE_SIZE = 100
        const val BUFFER_SIZE = 128 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val API_READ_TIMEOUT_MS = 30_000
        const val ASSET_TRANSFER_TIMEOUT_MS = 5 * 60_000
        const val MAX_REDIRECTS = 5
        const val SHA256_PREFIX = "sha256:"
    }
}
