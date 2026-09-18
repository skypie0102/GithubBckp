package com.skypie0102.githubbckp.mirror

import java.io.File
import org.json.JSONObject

data class MirrorManifest(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val repositoryId: Long,
    val repositoryOwner: String,
    val repositoryName: String,
    val remoteUrl: String,
    val defaultBranch: String,
    val isPrivate: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val lastSuccessfulFetchAtEpochMs: Long,
    val refsDigest: String,
    val headCommit: String? = null,
    val lfsIncluded: Boolean,
    val appVersion: String,
) {
    val repositoryFullName: String = "$repositoryOwner/$repositoryName"

    fun writeTo(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(toJson().toString(2))
    }

    fun toJson(): JSONObject = JSONObject()
        .put("formatVersion", formatVersion)
        .put("repositoryId", repositoryId)
        .put("repositoryOwner", repositoryOwner)
        .put("repositoryName", repositoryName)
        .put("repositoryFullName", repositoryFullName)
        .put("remoteUrl", remoteUrl)
        .put("defaultBranch", defaultBranch)
        .put("private", isPrivate)
        .put("createdAtEpochMs", createdAtEpochMs)
        .put("updatedAtEpochMs", updatedAtEpochMs)
        .put("lastSuccessfulFetchAtEpochMs", lastSuccessfulFetchAtEpochMs)
        .put("refsDigest", refsDigest)
        .put("headCommit", headCommit ?: JSONObject.NULL)
        .put("lfsIncluded", lfsIncluded)
        .put("appVersion", appVersion)

    companion object {
        const val FILE_NAME = "manifest.json"
        const val CURRENT_FORMAT_VERSION = 1

        fun readFrom(file: File): MirrorManifest = fromJson(file.readText())

        fun readFromArchive(archive: File): MirrorManifest =
            fromJson(TarGzArchive.readTextEntry(archive, FILE_NAME))

        fun fromJson(text: String): MirrorManifest {
            val json = JSONObject(text)
            val formatVersion = json.getInt("formatVersion")
            require(formatVersion == CURRENT_FORMAT_VERSION) {
                "Unsupported mirror format version: $formatVersion"
            }

            return MirrorManifest(
                formatVersion = formatVersion,
                repositoryId = json.getLong("repositoryId"),
                repositoryOwner = json.getString("repositoryOwner"),
                repositoryName = json.getString("repositoryName"),
                remoteUrl = json.getString("remoteUrl"),
                defaultBranch = json.getString("defaultBranch"),
                isPrivate = json.getBoolean("private"),
                createdAtEpochMs = json.getLong("createdAtEpochMs"),
                updatedAtEpochMs = json.getLong("updatedAtEpochMs"),
                lastSuccessfulFetchAtEpochMs = json.getLong("lastSuccessfulFetchAtEpochMs"),
                refsDigest = json.getString("refsDigest"),
                headCommit = json.optNullableString("headCommit"),
                lfsIncluded = json.getBoolean("lfsIncluded"),
                appVersion = json.getString("appVersion"),
            )
        }

        private fun JSONObject.optNullableString(key: String): String? =
            if (!has(key) || isNull(key)) null else getString(key)
    }
}
