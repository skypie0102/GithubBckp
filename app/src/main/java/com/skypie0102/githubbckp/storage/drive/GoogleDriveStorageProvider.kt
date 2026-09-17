package com.skypie0102.githubbckp.storage.drive

import com.skypie0102.githubbckp.backup.BackupArtifact
import com.skypie0102.githubbckp.storage.RemoteBackup
import com.skypie0102.githubbckp.storage.StorageDestination
import com.skypie0102.githubbckp.storage.StorageProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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

@Singleton
class GoogleDriveStorageProvider @Inject constructor(
    private val authManager: GoogleDriveAuthManager,
) : StorageProvider {
    override suspend fun upload(
        artifact: BackupArtifact,
        existing: RemoteBackup?,
        onProgress: suspend (uploadedBytes: Long, totalBytes: Long) -> Unit,
    ): RemoteBackup = withContext(Dispatchers.IO) {
        val token = authManager.requireAccessToken()
        val mimeType = mimeType(artifact)
        val metadata = JSONObject()
            .put("name", artifact.file.name)
            .put("mimeType", mimeType)
            .put(
                "appProperties",
                JSONObject()
                    .put("sha256", artifact.checksumSha256)
                    .put("repository", artifact.repository.fullName)
                    .put("backupType", artifact.type.name),
            )

        // Always create the replacement as a distinct Drive object. The previous
        // verified object remains untouched until BackupCoordinator verifies and
        // commits this new object, then cleanup retires the old ID. This avoids an
        // interrupted in-place PATCH corrupting the only known-good mirror.
        @Suppress("UNUSED_VARIABLE")
        val previous = existing
        val sessionUrl = createResumableSession(
            token = token,
            metadata = metadata,
            fileLength = artifact.file.length(),
            mimeType = mimeType,
        )
        uploadToSession(
            sessionUrl = sessionUrl,
            token = token,
            artifact = artifact,
            mimeType = mimeType,
            onProgress = onProgress,
        )
    }

    override suspend fun verify(remoteBackup: RemoteBackup): Boolean = withContext(Dispatchers.IO) {
        val token = authManager.requireAccessToken()
        val fields = "id,name,size,md5Checksum,appProperties"
        val url = "$FILES_URL/${path(remoteBackup.id)}?fields=${query(fields)}"
        val connection = open(url, "GET", token)
        try {
            val json = readJson(connection)
            val sha256 = json.optJSONObject("appProperties")?.optString("sha256")
            val md5 = json.optString("md5Checksum")
            val size = json.optString("size").toLongOrNull()
            sha256 == remoteBackup.checksumSha256 &&
                md5.equals(remoteBackup.checksumMd5, ignoreCase = true) &&
                size == remoteBackup.sizeBytes
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun download(remoteBackup: RemoteBackup, destination: File): Unit = withContext(Dispatchers.IO) {
        val token = authManager.requireAccessToken()
        val connection = open("$FILES_URL/${path(remoteBackup.id)}?alt=media", "GET", token)
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Drive download HTTP $code: ${error.take(300)}")
            }
            destination.parentFile?.mkdirs()
            connection.inputStream.buffered().use { source ->
                FileOutputStream(destination).buffered().use { output ->
                    source.copyTo(output, bufferSize = BUFFER_SIZE)
                }
            }
        } finally {
            connection.disconnect()
        }
        Unit
    }

    override suspend fun delete(remoteBackup: RemoteBackup) = withContext(Dispatchers.IO) {
        val token = authManager.requireAccessToken()
        val connection = open("$FILES_URL/${path(remoteBackup.id)}", "DELETE", token)
        try {
            val code = connection.responseCode
            // Cleanup may be retried after the file was already removed (for
            // example, if deletion succeeded but marking the old Room row did not).
            // Treat an already-absent file as successful deletion so cleanup can
            // converge and retire the historical row on a later pass.
            if (code != HttpURLConnection.HTTP_NOT_FOUND && code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Drive delete HTTP $code: ${error.take(300)}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun createResumableSession(
        token: String,
        metadata: JSONObject,
        fileLength: Long,
        mimeType: String,
    ): String {
        val connection = open(RESUMABLE_CREATE_URL, "POST", token).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", mimeType)
            setRequestProperty("X-Upload-Content-Length", fileLength.toString())
        }
        return try {
            val payload = metadata.toString().toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Drive resumable-session HTTP $code: ${error.take(300)}")
            }
            connection.getHeaderField("Location")
                ?: throw IOException("Drive did not return a resumable upload URL")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun uploadToSession(
        sessionUrl: String,
        token: String,
        artifact: BackupArtifact,
        mimeType: String,
        onProgress: suspend (uploadedBytes: Long, totalBytes: Long) -> Unit,
    ): RemoteBackup {
        val totalBytes = artifact.file.length()
        val connection = open(sessionUrl, "PUT", token).apply {
            doOutput = true
            setRequestProperty("Content-Type", mimeType)
            setFixedLengthStreamingMode(totalBytes)
        }
        try {
            var uploaded = 0L
            connection.outputStream.buffered().use { output ->
                FileInputStream(artifact.file).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        uploaded += count
                        onProgress(uploaded, totalBytes)
                    }
                }
            }
            val json = readJson(connection)
            return RemoteBackup(
                id = json.getString("id"),
                name = json.optString("name", artifact.file.name),
                sizeBytes = json.optString("size").toLongOrNull() ?: totalBytes,
                checksumSha256 = artifact.checksumSha256,
                checksumMd5 = artifact.checksumMd5,
                provider = StorageDestination.GOOGLE_DRIVE,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IOException("Drive API HTTP $code: ${text.take(300)}")
        }
        if (text.isBlank()) throw IOException("Drive API returned an empty response")
        return JSONObject(text)
    }

    private fun open(url: String, method: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun mimeType(artifact: BackupArtifact): String = when {
        artifact.file.name.endsWith(".zip", ignoreCase = true) -> "application/zip"
        artifact.file.name.endsWith(".gz", ignoreCase = true) -> "application/gzip"
        else -> "application/octet-stream"
    }

    private fun path(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun query(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private companion object {
        const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        const val RESUMABLE_CREATE_URL =
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,size,md5Checksum,appProperties"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000
        const val BUFFER_SIZE = 256 * 1024
    }
}
