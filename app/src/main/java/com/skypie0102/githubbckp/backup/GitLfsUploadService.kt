package com.skypie0102.githubbckp.backup

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class GitLfsUploadService @Inject constructor(
    private val objectStore: GitLfsObjectStore,
) {
    fun uploadAll(
        repositoryFullName: String,
        accessToken: String,
        pointers: List<GitLfsPointer>,
        repositoryDirectory: File,
    ): Int {
        if (pointers.isEmpty()) return 0
        require(repositoryFullName.count { it == '/' } == 1) { "Invalid GitHub repository name" }
        require(repositoryDirectory.isDirectory) { "Restored mirror directory is missing" }

        val localObjects = pointers.associate { pointer ->
            pointer.oidSha256 to objectStore.requireVerifiedObject(repositoryDirectory, pointer)
        }
        val authorization = basicAuthorization(accessToken)
        val batchUrl = "https://github.com/$repositoryFullName.git/info/lfs/objects/batch"

        pointers.chunked(BATCH_SIZE).forEach { batch ->
            val plans = requestUploadPlans(batchUrl, authorization, batch)
            batch.forEach { pointer ->
                val plan = plans[pointer.oidSha256]
                    ?: throw IOException("Git LFS upload response omitted ${pointer.oidSha256}")
                val uploadAction = plan.upload
                if (uploadAction != null) {
                    uploadObject(localObjects.getValue(pointer.oidSha256), pointer, uploadAction)
                    plan.verify?.let { verifyAction -> verifyRemote(pointer, verifyAction) }
                }
            }
        }
        return pointers.size
    }

    internal fun parseBatchResponse(
        responseText: String,
        requestedPointers: List<GitLfsPointer>,
    ): Map<String, GitLfsUploadPlan> {
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
            ?: throw IOException(json.optString("message", "Git LFS upload response contained no objects"))
        val result = linkedMapOf<String, GitLfsUploadPlan>()

        for (index in 0 until objects.length()) {
            val item = objects.getJSONObject(index)
            val oid = item.optString("oid")
            val expected = requested[oid]
                ?: throw IOException("Git LFS upload response returned an unexpected object: $oid")
            val responseSize = item.optLong("size", -1L)
            if (responseSize != expected.sizeBytes) {
                throw IOException("Git LFS size mismatch for $oid: expected ${expected.sizeBytes}, server reported $responseSize")
            }

            item.optJSONObject("error")?.let { error ->
                val code = error.optInt("code", 0)
                val message = error.optString("message", "Git LFS upload unavailable")
                throw IOException("Git LFS object $oid failed${if (code > 0) " (HTTP $code)" else ""}: $message")
            }

            val actions = item.optJSONObject("actions")
            if (actions == null) {
                result[oid] = GitLfsUploadPlan(upload = null, verify = null)
                continue
            }
            val upload = actions.optJSONObject("upload")
                ?: throw IOException("Git LFS object $oid returned actions without an upload action")
            result[oid] = GitLfsUploadPlan(
                upload = parseAction(upload, oid, "upload"),
                verify = actions.optJSONObject("verify")?.let { parseAction(it, oid, "verify") },
            )
        }

        if (result.size != requested.size) {
            val missing = requested.keys - result.keys
            throw IOException("Git LFS upload response omitted ${missing.size} requested object(s)")
        }
        return result
    }

    private fun requestUploadPlans(
        batchUrl: String,
        authorization: String,
        pointers: List<GitLfsPointer>,
    ): Map<String, GitLfsUploadPlan> {
        val requestBody = lfsBatchRequestBody("upload", pointers)
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
                throw IOException("Git LFS upload batch request failed (HTTP $code): $message")
            }
            return parseBatchResponse(text, pointers)
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadObject(
        file: File,
        pointer: GitLfsPointer,
        action: GitLfsAction,
    ) {
        requireHttps(action.href, "upload")
        val connection = openConnection(action.href).apply {
            requestMethod = "PUT"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = LFS_TRANSFER_TIMEOUT_MS
            action.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (action.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                setRequestProperty("Content-Type", "application/octet-stream")
            }
            setFixedLengthStreamingMode(pointer.sizeBytes)
        }
        try {
            file.inputStream().buffered(BUFFER_SIZE).use { input ->
                connection.outputStream.buffered(BUFFER_SIZE).use { output -> input.copyTo(output, BUFFER_SIZE) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Git LFS object ${pointer.oidSha256} upload failed (HTTP $code): ${text.take(300)}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyRemote(pointer: GitLfsPointer, action: GitLfsAction) {
        requireHttps(action.href, "verify")
        val body = JSONObject()
            .put("oid", pointer.oidSha256)
            .put("size", pointer.sizeBytes)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val connection = openConnection(action.href).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            action.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (action.headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
                setRequestProperty("Accept", LFS_JSON_MEDIA_TYPE)
            }
            if (action.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                setRequestProperty("Content-Type", LFS_JSON_MEDIA_TYPE)
            }
            setFixedLengthStreamingMode(body.size)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Git LFS object ${pointer.oidSha256} verification failed (HTTP $code): ${text.take(300)}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseAction(json: JSONObject, oid: String, kind: String): GitLfsAction {
        val href = json.optString("href")
        if (href.isBlank()) throw IOException("Git LFS object $oid returned an empty $kind URL")
        return GitLfsAction(href = href, headers = parseHeaders(json.optJSONObject("header")))
    }

    private fun requireHttps(url: String, kind: String) {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true)) {
            "Git LFS $kind URL must use HTTPS"
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        URL(url).openConnection() as HttpURLConnection

    private companion object {
        const val BATCH_SIZE = 100
        const val BUFFER_SIZE = 128 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
        const val LFS_TRANSFER_TIMEOUT_MS = 5 * 60_000
    }
}

data class GitLfsUploadPlan(
    val upload: GitLfsAction?,
    val verify: GitLfsAction?,
)

data class GitLfsAction(
    val href: String,
    val headers: Map<String, String>,
)
