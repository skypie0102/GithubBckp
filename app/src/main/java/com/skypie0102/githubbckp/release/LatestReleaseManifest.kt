package com.skypie0102.githubbckp.release

import com.skypie0102.githubbckp.mirror.TarGzArchive
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class LatestReleaseAssetManifest(
    val id: Long,
    val originalName: String,
    val storedName: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class LatestReleaseManifest(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val repositoryId: Long,
    val repositoryOwner: String,
    val repositoryName: String,
    val releaseId: Long,
    val tagName: String,
    val releaseName: String?,
    val releaseBody: String?,
    val htmlUrl: String,
    val publishedAt: String?,
    val updatedAt: String,
    val sourceFileName: String,
    val sourceSizeBytes: Long,
    val sourceSha256: String,
    val assets: List<LatestReleaseAssetManifest>,
    val appVersion: String,
) {
    fun toJson(): String {
        val assetsJson = JSONArray()
        assets.forEach { asset ->
            assetsJson.put(
                JSONObject()
                    .put("id", asset.id)
                    .put("originalName", asset.originalName)
                    .put("storedName", asset.storedName)
                    .put("sizeBytes", asset.sizeBytes)
                    .put("sha256", asset.sha256),
            )
        }

        return JSONObject()
            .put("formatVersion", formatVersion)
            .put("repositoryId", repositoryId)
            .put("repositoryOwner", repositoryOwner)
            .put("repositoryName", repositoryName)
            .put("releaseId", releaseId)
            .put("tagName", tagName)
            .put("releaseName", releaseName)
            .put("releaseBody", releaseBody)
            .put("htmlUrl", htmlUrl)
            .put("publishedAt", publishedAt)
            .put("updatedAt", updatedAt)
            .put("sourceFileName", sourceFileName)
            .put("sourceSizeBytes", sourceSizeBytes)
            .put("sourceSha256", sourceSha256)
            .put("assets", assetsJson)
            .put("appVersion", appVersion)
            .toString(2)
    }

    fun writeTo(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(toJson())
    }

    companion object {
        const val FILE_NAME = "release.json"
        const val CURRENT_FORMAT_VERSION = 1

        fun fromJson(value: String): LatestReleaseManifest {
            val json = JSONObject(value)
            val assetsJson = json.optJSONArray("assets") ?: JSONArray()
            val assets = buildList {
                for (index in 0 until assetsJson.length()) {
                    val item = assetsJson.getJSONObject(index)
                    add(
                        LatestReleaseAssetManifest(
                            id = item.getLong("id"),
                            originalName = item.getString("originalName"),
                            storedName = item.getString("storedName"),
                            sizeBytes = item.getLong("sizeBytes"),
                            sha256 = item.getString("sha256"),
                        ),
                    )
                }
            }

            return LatestReleaseManifest(
                formatVersion = json.getInt("formatVersion"),
                repositoryId = json.getLong("repositoryId"),
                repositoryOwner = json.getString("repositoryOwner"),
                repositoryName = json.getString("repositoryName"),
                releaseId = json.getLong("releaseId"),
                tagName = json.getString("tagName"),
                releaseName = json.optString("releaseName").takeIf { it.isNotBlank() },
                releaseBody = json.optString("releaseBody").takeIf { it.isNotBlank() },
                htmlUrl = json.getString("htmlUrl"),
                publishedAt = json.optString("publishedAt").takeIf { it.isNotBlank() },
                updatedAt = json.getString("updatedAt"),
                sourceFileName = json.getString("sourceFileName"),
                sourceSizeBytes = json.getLong("sourceSizeBytes"),
                sourceSha256 = json.getString("sourceSha256"),
                assets = assets,
                appVersion = json.getString("appVersion"),
            )
        }

        fun readFromArchive(archive: File): LatestReleaseManifest =
            fromJson(TarGzArchive.readTextEntry(archive, FILE_NAME))
    }
}
