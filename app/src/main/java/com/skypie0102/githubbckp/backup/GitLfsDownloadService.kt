package com.skypie0102.githubbckp.backup

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class GitLfsDownloadService @Inject constructor(
    private val objectStore: GitLfsObjectStore,
) {
    fun downloadAll(
        repositoryFullName: String,
        accessToken: String,
        pointers: List<GitLfsPointer>,
        repositoryDirectory: File,
    ): Int {
        if (pointers.isEmpty()) return 0
        require(repositoryFullName.count { it == '/' } == 1) { "Invalid GitHub repository name" }
        require(repositoryDirectory.isDirectory) { "Mirror repository directory is missing" }

        val authorization = basicAuthorization(accessToken)
        val batchUrl = "https://github.com/$repositoryFullName.git/info/lfs/objects/batch"
        var downloaded = 0

        pointers.chunked(BATCH_SIZE).forEach { batch ->
            val actions = requestDownloadActions(batchUrl, authorization, batch)
            batch.forEach { pointer ->
                val action = actions[pointer.oidSha256]
                    ?: throw IOException("Git LFS batch response omitted ${pointer.oidSha256}")
                downloadAndStore(pointer, action, repositoryDirectory)
                downloaded += 1
            }
        }
        return downloaded
    }

    internal fun parseBatchResponse(
        responseText: String,
        requestedPointers: List<GitLfsPointer>,
    ): Map<String, GitLfsDownloadAction> {
        val json = JSONObject(responseText)
        val transfer = json.optString("transfer", "basic")
        if (transfer.isNotBlank() && transfer != "basic") {
            throw IOException("Unsupported Git LFS transfer adapter: $transfer")
        }
        val hashAlgorithm = json.optString("hash_algo", "sha256")
        if (hashAlgorithm.isNotBlank() && hashAlgorithm != "sha256") {
            throw IOException("Unsupported Git LFS hash algorithm: $hashAlgorithm")
        }

        val requested = requestedPointers.associateBy { it.oidSha256 }
        val objects = json.optJSONArray("objects")
            ?: throw IOException(json.optString("message", "Git LFS batch response contained no objects"))
        val result = linkedMapOf<String, GitLfsDownloadAction>()

        for (index in 0 until objects.length()) {
            val item = objects.getJSONObject(index)
            val oid = item.optString("oid")
            val expected = requested[oid]
                ?: throw IOException("Git LFS batch response returned an unexpected object: $oid")
            val responseSize = item.optLong("size", -1L)
            if (responseSize != expected.sizeBytes) {
                throw IOException("Git LFS size mismatch for $oid: expected ${expected.sizeBytes}, server reported $responseSize")
            }

            item.optJSONObject("error")?.let { error ->
                val code = error.optInt("code", 0)
                val message = error.optString("message", "Git LFS object unavailable")
                throw IOException("Git LFS object $oid failed${if (code > 0) " (HTTP $code)" else ""}: $message")
            }

            val download = item.optJSONObject("actions")?.optJSONObject("download")
                ?: throw IOException("Git LFS object $oid did not include a download action")
            val href = download.optString("href")
            if (href.isBlank()) throw IOException("Git LFS object $oid returned an empty download URL")
            result[oid] = GitLfsDownloadAction(
                href = href,
                headers = parseHeaders(download.optJSONObject("header")),
            )
        }

        if (result.size != requested.size) {
            val missing = requested.keys - result.keys
            throw IOException("Git LFS batch response omitted ${missing.size} requested object(s)")
        }
        return result
    }

    private fun requestDownloadActions(
        batchUrl: String,
        authorization: String,
        pointers: List<GitLfsPointer>,
    ): Map<String, GitLfsDownloadAction> {
        val requestBody = lfsBatchRequestBody("download", pointers)
        val connection = openConnection(batchUrl).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", LFS_JSON_MEDIA_TYPE)
            setRequestProperty("Content-Type", LFS_JSON_MEDIA_TYPE)
            setRequestProperty("Authorization", authorization)
            setFixedLengthStreamingMode(requestBody.size)
        }
        try {
            connection.outputStream.use { it.write(requestBody) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: text.take(300).ifBlank { "No response body" }
                throw IOException("Git LFS batch request failed (HTTP $code): $message")
            }
            return parseBatchResponse(text, pointers)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadAndStore(
        pointer: GitLfsPointer,
        action: GitLfsDownloadAction,
        repositoryDirectory: File,
    ) {
        val target = objectStore.objectFile(repositoryDirectory, pointer.oidSha256)
        target.parentFile?.mkdirs()
        if (target.isFile) {
            objectStore.verify(target, pointer)
            return
        }

        val temp = File(target.parentFile, ".${target.name}.part")
        temp.delete()
        try {
            downloadAction(action, temp, redirectsRemaining = MAX_REDIRECTS)
            objectStore.verify(temp, pointer)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            objectStore.verify(target, pointer)
        } finally {
            temp.delete()
        }
    }

    private fun downloadAction(
        action: GitLfsDownloadAction,
        destination: File,
        redirectsRemaining: Int,
    ) {
        val url = URL(action.href)
        require(url.protocol.equals("https", ignoreCase = true)) {
            "Git LFS download URL must use HTTPS"
        }
        val connection = openConnection(action.href).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = LFS_READ_TIMEOUT_MS
            action.headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            val code = connection.responseCode
            if (code in REDIRECT_CODES) {
                if (redirectsRemaining <= 0) throw IOException("Too many Git LFS download redirects")
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("Git LFS redirect did not include a Location header")
                val redirected = URL(url, location)
                if (!redirected.protocol.equals("https", ignoreCase = true)) {
                    throw IOException("Git LFS redirect attempted to leave HTTPS")
                }
                val redirectedHeaders = if (redirected.host.equals(url.host, ignoreCase = true)) {
                    action.headers
                } else {
                    action.headers.filterKeys { !it.equals("Authorization", ignoreCase = true) }
                }
                downloadAction(
                    action = GitLfsDownloadAction(redirected.toString(), redirectedHeaders),
                    destination = destination,
                    redirectsRemaining = redirectsRemaining - 1,
                )
                return
            }
            if (code !in 200..299) {
                val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Git LFS object download failed (HTTP $code): ${text.take(300)}")
            }
            connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                destination.outputStream().buffered(BUFFER_SIZE).use { output -> input.copyTo(output, BUFFER_SIZE) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        URL(url).openConnection() as HttpURLConnection

    private companion object {
        const val BATCH_SIZE = 100
        const val BUFFER_SIZE = 128 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
        const val LFS_READ_TIMEOUT_MS = 5 * 60_000
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

data class GitLfsDownloadAction(
    val href: String,
    val headers: Map<String, String>,
)

internal const val LFS_JSON_MEDIA_TYPE = "application/vnd.git-lfs+json"

internal fun basicAuthorization(accessToken: String): String {
    val credential = "x-access-token:$accessToken".toByteArray(StandardCharsets.UTF_8)
    return "Basic ${Base64.getEncoder().encodeToString(credential)}"
}

internal fun lfsBatchRequestBody(operation: String, pointers: List<GitLfsPointer>): ByteArray {
    val objects = JSONArray()
    pointers.forEach { pointer ->
        objects.put(JSONObject().put("oid", pointer.oidSha256).put("size", pointer.sizeBytes))
    }
    return JSONObject()
        .put("operation", operation)
        .put("transfers", JSONArray().put("basic"))
        .put("hash_algo", "sha256")
        .put("objects", objects)
        .toString()
        .toByteArray(StandardCharsets.UTF_8)
}

internal fun parseHeaders(json: JSONObject?): Map<String, String> {
    if (json == null) return emptyMap()
    val result = linkedMapOf<String, String>()
    json.keys().forEach { key -> result[key] = json.getString(key) }
    return result
}
