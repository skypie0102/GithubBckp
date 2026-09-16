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

data class GithubDiscussionBackupResult(
    val issueCount: Int,
    val pullRequestCount: Int,
    val issueCommentCount: Int,
    val reviewCommentCount: Int,
    val reviewCount: Int,
)

private data class DiscussionDataset(
    val name: String,
    val fileName: String,
    val count: Int,
    val sha256: String,
)

private data class IssueDatasetWriteResult(
    val entryCount: Int,
    val issueCount: Int,
)

/**
 * Preserves GitHub discussion metadata as streaming JSONL datasets. The raw
 * REST objects are retained so newly added response fields survive without a
 * schema migration, while the manifest supplies stable counts and SHA-256
 * checksums for local restore validation.
 */
@Singleton
class GithubDiscussionBackupService @Inject constructor() {
    fun backup(
        repository: RepositoryRef,
        accessToken: String,
        destination: File,
    ): GithubDiscussionBackupResult? {
        destination.deleteRecursively()
        destination.mkdirs()

        return try {
            val issueFile = File(destination, ISSUES_FILE)
            var ordinaryIssueCount = 0
            val issueEntryCount = writePagedDataset(
                destination = issueFile,
                urlForPage = { page ->
                    "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/issues" +
                        "?state=all&sort=created&direction=asc&per_page=$PAGE_SIZE&page=$page"
                },
                accessToken = accessToken,
            ) { issue ->
                if (!issue.has("pull_request") || issue.isNull("pull_request")) ordinaryIssueCount += 1
                issue
            }

            val pullNumbers = mutableListOf<Int>()
            val pullFile = File(destination, PULLS_FILE)
            val pullCount = writePagedDataset(
                destination = pullFile,
                urlForPage = { page ->
                    "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/pulls" +
                        "?state=all&sort=created&direction=asc&per_page=$PAGE_SIZE&page=$page"
                },
                accessToken = accessToken,
            ) { pull ->
                pullNumbers += pull.getInt("number")
                pull
            }

            val issueCommentFile = File(destination, ISSUE_COMMENTS_FILE)
            val issueCommentCount = writePagedDataset(
                destination = issueCommentFile,
                urlForPage = { page ->
                    "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/issues/comments" +
                        "?sort=created&direction=asc&per_page=$PAGE_SIZE&page=$page"
                },
                accessToken = accessToken,
            ) { comment ->
                JSONObject(comment.toString()).put(
                    BACKUP_ISSUE_NUMBER,
                    numberFromApiUrl(comment.getString("issue_url"), "issue comment"),
                )
            }

            val reviewCommentFile = File(destination, REVIEW_COMMENTS_FILE)
            val reviewCommentCount = writePagedDataset(
                destination = reviewCommentFile,
                urlForPage = { page ->
                    "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/pulls/comments" +
                        "?sort=created&direction=asc&per_page=$PAGE_SIZE&page=$page"
                },
                accessToken = accessToken,
            ) { comment ->
                JSONObject(comment.toString()).put(
                    BACKUP_PULL_NUMBER,
                    numberFromApiUrl(comment.getString("pull_request_url"), "review comment"),
                )
            }

            val reviewFile = File(destination, REVIEWS_FILE)
            val reviewCount = writeReviews(
                destination = reviewFile,
                repository = repository,
                accessToken = accessToken,
                pullNumbers = pullNumbers,
            )

            if (issueEntryCount == 0 && pullCount == 0 && issueCommentCount == 0 &&
                reviewCommentCount == 0 && reviewCount == 0
            ) {
                destination.deleteRecursively()
                return null
            }

            val datasets = listOf(
                dataset(ISSUES_DATASET, issueFile, issueEntryCount),
                dataset(PULLS_DATASET, pullFile, pullCount),
                dataset(ISSUE_COMMENTS_DATASET, issueCommentFile, issueCommentCount),
                dataset(REVIEW_COMMENTS_DATASET, reviewCommentFile, reviewCommentCount),
                dataset(REVIEWS_DATASET, reviewFile, reviewCount),
            )
            val manifest = JSONObject()
                .put("formatVersion", FORMAT_VERSION)
                .put("createdAtEpochMs", System.currentTimeMillis())
                .put("repositoryFullName", repository.fullName)
                .put("issueEntryCount", issueEntryCount)
                .put("issueCount", ordinaryIssueCount)
                .put("pullRequestCount", pullCount)
                .put("issueCommentCount", issueCommentCount)
                .put("reviewCommentCount", reviewCommentCount)
                .put("reviewCount", reviewCount)
                .put(
                    "datasets",
                    JSONArray().apply {
                        datasets.forEach { dataset ->
                            put(
                                JSONObject()
                                    .put("name", dataset.name)
                                    .put("file", dataset.fileName)
                                    .put("count", dataset.count)
                                    .put("sha256", dataset.sha256),
                            )
                        }
                    },
                )
            File(destination, MANIFEST_FILE_NAME).writeText(manifest.toString())
            validateDiscussionDirectory(destination)
        } catch (throwable: Throwable) {
            destination.deleteRecursively()
            throw throwable
        }
    }

    fun validateBundledDiscussions(repositoryDirectory: File): GithubDiscussionBackupResult? {
        val discussionsDirectory = File(repositoryDirectory, BUNDLED_DISCUSSIONS_DIRECTORY)
        if (!discussionsDirectory.exists()) return null
        return validateDiscussionDirectory(discussionsDirectory)
    }

    internal fun validateDiscussionDirectory(
        discussionsDirectory: File,
    ): GithubDiscussionBackupResult {
        val root = discussionsDirectory.canonicalFile
        val manifestFile = File(root, MANIFEST_FILE_NAME)
        if (!manifestFile.isFile) {
            throw IOException("Bundled discussion metadata is missing $MANIFEST_FILE_NAME")
        }
        val manifest = JSONObject(manifestFile.readText())
        if (manifest.optInt("formatVersion", -1) != FORMAT_VERSION) {
            throw IOException("Unsupported bundled discussion manifest version")
        }

        val datasets = manifest.optJSONArray("datasets")
            ?: throw IOException("Bundled discussion manifest contains no datasets")
        val entries = linkedMapOf<String, JSONObject>()
        for (index in 0 until datasets.length()) {
            val entry = datasets.getJSONObject(index)
            val name = entry.optString("name")
            if (name.isBlank() || entries.put(name, entry) != null) {
                throw IOException("Bundled discussion manifest contains duplicate or empty dataset names")
            }
        }

        var ordinaryIssueCount = 0
        val issueIds = mutableSetOf<Long>()
        val issueNumbers = mutableSetOf<Int>()
        val issueEntryCount = validateDataset(
            root = root,
            manifestEntry = requireDataset(entries, ISSUES_DATASET, ISSUES_FILE),
        ) { issue ->
            val id = positiveId(issue, "issue")
            val number = positiveNumber(issue, "number", "issue")
            if (!issueIds.add(id) || !issueNumbers.add(number)) {
                throw IOException("Bundled issue metadata contains duplicate identity")
            }
            if (!issue.has("pull_request") || issue.isNull("pull_request")) ordinaryIssueCount += 1
        }

        val pullIds = mutableSetOf<Long>()
        val pullNumbers = mutableSetOf<Int>()
        val pullCount = validateDataset(
            root = root,
            manifestEntry = requireDataset(entries, PULLS_DATASET, PULLS_FILE),
        ) { pull ->
            val id = positiveId(pull, "pull request")
            val number = positiveNumber(pull, "number", "pull request")
            if (!pullIds.add(id) || !pullNumbers.add(number)) {
                throw IOException("Bundled pull request metadata contains duplicate identity")
            }
        }

        val issueCommentIds = mutableSetOf<Long>()
        val issueCommentCount = validateDataset(
            root = root,
            manifestEntry = requireDataset(entries, ISSUE_COMMENTS_DATASET, ISSUE_COMMENTS_FILE),
        ) { comment ->
            val id = positiveId(comment, "issue comment")
            positiveNumber(comment, BACKUP_ISSUE_NUMBER, "issue comment")
            if (!issueCommentIds.add(id)) {
                throw IOException("Bundled issue comments contain duplicate IDs")
            }
        }

        val reviewCommentIds = mutableSetOf<Long>()
        val reviewCommentCount = validateDataset(
            root = root,
            manifestEntry = requireDataset(entries, REVIEW_COMMENTS_DATASET, REVIEW_COMMENTS_FILE),
        ) { comment ->
            val id = positiveId(comment, "review comment")
            positiveNumber(comment, BACKUP_PULL_NUMBER, "review comment")
            if (!reviewCommentIds.add(id)) {
                throw IOException("Bundled review comments contain duplicate IDs")
            }
        }

        val reviewIds = mutableSetOf<Long>()
        val reviewCount = validateDataset(
            root = root,
            manifestEntry = requireDataset(entries, REVIEWS_DATASET, REVIEWS_FILE),
        ) { review ->
            val id = positiveId(review, "pull request review")
            positiveNumber(review, BACKUP_PULL_NUMBER, "pull request review")
            if (!reviewIds.add(id)) {
                throw IOException("Bundled pull request reviews contain duplicate IDs")
            }
        }

        requireManifestCount(manifest, "issueEntryCount", issueEntryCount)
        requireManifestCount(manifest, "issueCount", ordinaryIssueCount)
        requireManifestCount(manifest, "pullRequestCount", pullCount)
        requireManifestCount(manifest, "issueCommentCount", issueCommentCount)
        requireManifestCount(manifest, "reviewCommentCount", reviewCommentCount)
        requireManifestCount(manifest, "reviewCount", reviewCount)

        return GithubDiscussionBackupResult(
            issueCount = ordinaryIssueCount,
            pullRequestCount = pullCount,
            issueCommentCount = issueCommentCount,
            reviewCommentCount = reviewCommentCount,
            reviewCount = reviewCount,
        )
    }

    private fun writeReviews(
        destination: File,
        repository: RepositoryRef,
        accessToken: String,
        pullNumbers: List<Int>,
    ): Int {
        destination.parentFile?.mkdirs()
        var count = 0
        destination.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            pullNumbers.forEach { pullNumber ->
                pagedJsonObjects(
                    urlForPage = { page ->
                        "$API_BASE/repos/${path(repository.owner)}/${path(repository.name)}/pulls/$pullNumber/reviews" +
                            "?per_page=$PAGE_SIZE&page=$page"
                    },
                    accessToken = accessToken,
                ) { review ->
                    val record = JSONObject(review.toString()).put(BACKUP_PULL_NUMBER, pullNumber)
                    writer.append(record.toString()).append('\n')
                    count += 1
                }
            }
        }
        return count
    }

    private fun writePagedDataset(
        destination: File,
        urlForPage: (Int) -> String,
        accessToken: String,
        transform: (JSONObject) -> JSONObject,
    ): Int {
        destination.parentFile?.mkdirs()
        var count = 0
        destination.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            pagedJsonObjects(urlForPage, accessToken) { item ->
                writer.append(transform(item).toString()).append('\n')
                count += 1
            }
        }
        return count
    }

    private fun pagedJsonObjects(
        urlForPage: (Int) -> String,
        accessToken: String,
        onObject: (JSONObject) -> Unit,
    ) {
        var page = 1
        while (true) {
            val array = getJsonArray(urlForPage(page), accessToken)
            for (index in 0 until array.length()) onObject(array.getJSONObject(index))
            if (array.length() < PAGE_SIZE) return
            page += 1
        }
    }

    private fun getJsonArray(url: String, accessToken: String): JSONArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
        }
        return try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw IOException("GitHub discussion API HTTP $code: ${text.take(300).ifBlank { "No response body" }}")
            }
            JSONArray(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun validateDataset(
        root: File,
        manifestEntry: JSONObject,
        validateRecord: (JSONObject) -> Unit,
    ): Int {
        val fileName = manifestEntry.getString("file")
        val file = File(root, fileName).canonicalFile
        val allowedPrefix = root.path + File.separator
        if (file == root || !file.path.startsWith(allowedPrefix)) {
            throw IOException("Unsafe bundled discussion dataset path: $fileName")
        }
        if (!file.isFile) throw IOException("Bundled discussion dataset is missing: $fileName")

        val expectedSha256 = manifestEntry.getString("sha256")
        val actualSha256 = sha256(file)
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            throw IOException("Bundled discussion dataset $fileName failed SHA-256 verification")
        }

        var count = 0
        file.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) throw IOException("Bundled discussion dataset $fileName contains a blank record")
                val record = try {
                    JSONObject(line)
                } catch (throwable: Throwable) {
                    throw IOException("Bundled discussion dataset $fileName contains invalid JSON", throwable)
                }
                validateRecord(record)
                count += 1
            }
        }
        val expectedCount = manifestEntry.getInt("count")
        if (count != expectedCount) {
            throw IOException("Bundled discussion dataset $fileName count mismatch: expected $expectedCount, got $count")
        }
        return count
    }

    private fun requireDataset(
        entries: Map<String, JSONObject>,
        name: String,
        expectedFileName: String,
    ): JSONObject {
        val entry = entries[name]
            ?: throw IOException("Bundled discussion manifest is missing dataset $name")
        if (entry.optString("file") != expectedFileName) {
            throw IOException("Bundled discussion dataset $name uses an unexpected file name")
        }
        return entry
    }

    private fun requireManifestCount(manifest: JSONObject, key: String, actual: Int) {
        val expected = manifest.optInt(key, -1)
        if (expected != actual) {
            throw IOException("Bundled discussion manifest $key mismatch: expected $expected, got $actual")
        }
    }

    private fun positiveId(record: JSONObject, kind: String): Long {
        val id = record.optLong("id", 0L)
        if (id <= 0L) throw IOException("Bundled $kind is missing a valid ID")
        return id
    }

    private fun positiveNumber(record: JSONObject, key: String, kind: String): Int {
        val number = record.optInt(key, 0)
        if (number <= 0) throw IOException("Bundled $kind is missing a valid number")
        return number
    }

    private fun dataset(name: String, file: File, count: Int): DiscussionDataset =
        DiscussionDataset(name, file.name, count, sha256(file))

    private fun numberFromApiUrl(url: String, kind: String): Int =
        url.substringAfterLast('/').toIntOrNull()?.takeIf { it > 0 }
            ?: throw IOException("GitHub $kind response contained an invalid parent URL")

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
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun path(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val BUNDLED_DISCUSSIONS_DIRECTORY = "github-backup/discussions"
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val ISSUES_FILE = "issues.jsonl"
        const val PULLS_FILE = "pull-requests.jsonl"
        const val ISSUE_COMMENTS_FILE = "issue-comments.jsonl"
        const val REVIEW_COMMENTS_FILE = "review-comments.jsonl"
        const val REVIEWS_FILE = "reviews.jsonl"

        private const val ISSUES_DATASET = "issues"
        private const val PULLS_DATASET = "pullRequests"
        private const val ISSUE_COMMENTS_DATASET = "issueComments"
        private const val REVIEW_COMMENTS_DATASET = "reviewComments"
        private const val REVIEWS_DATASET = "reviews"
        private const val BACKUP_ISSUE_NUMBER = "backupIssueNumber"
        private const val BACKUP_PULL_NUMBER = "backupPullRequestNumber"
        private const val FORMAT_VERSION = 1
        private const val PAGE_SIZE = 100
        private const val API_BASE = "https://api.github.com"
        private const val API_VERSION = "2026-03-10"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 128 * 1024
    }
}
