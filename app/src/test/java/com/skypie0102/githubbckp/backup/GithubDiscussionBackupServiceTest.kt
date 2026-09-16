package com.skypie0102.githubbckp.backup

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GithubDiscussionBackupServiceTest {
    private val service = GithubDiscussionBackupService()

    @Test
    fun validatesVersionedDiscussionDatasets() {
        val root = Files.createTempDirectory("discussion-backup-test").toFile()
        try {
            val discussions = File(root, GithubDiscussionBackupService.BUNDLED_DISCUSSIONS_DIRECTORY)
            writeBundle(discussions)

            val result = service.validateBundledDiscussions(root)

            assertEquals(1, result?.issueCount)
            assertEquals(1, result?.pullRequestCount)
            assertEquals(1, result?.issueCommentCount)
            assertEquals(1, result?.reviewCommentCount)
            assertEquals(1, result?.reviewCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsTamperedDiscussionDataset() {
        val root = Files.createTempDirectory("discussion-tamper-test").toFile()
        try {
            val discussions = File(root, GithubDiscussionBackupService.BUNDLED_DISCUSSIONS_DIRECTORY)
            writeBundle(discussions)
            File(discussions, GithubDiscussionBackupService.ISSUES_FILE)
                .appendText(JSONObject().put("id", 99).put("number", 99).toString() + "\n")

            assertThrows(IOException::class.java) {
                service.validateBundledDiscussions(root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsDuplicateDiscussionIdentityEvenWithMatchingHash() {
        val root = Files.createTempDirectory("discussion-duplicate-test").toFile()
        try {
            val discussions = File(root, GithubDiscussionBackupService.BUNDLED_DISCUSSIONS_DIRECTORY)
            val duplicateIssues = listOf(
                JSONObject().put("id", 1).put("number", 1),
                JSONObject().put("id", 1).put("number", 2),
            )
            writeBundle(discussions, issues = duplicateIssues, ordinaryIssueCount = 2)

            assertThrows(IOException::class.java) {
                service.validateBundledDiscussions(root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeBundle(
        discussions: File,
        issues: List<JSONObject> = listOf(
            JSONObject().put("id", 1).put("number", 1).put("title", "Issue"),
            JSONObject()
                .put("id", 2)
                .put("number", 2)
                .put("title", "Pull request")
                .put("pull_request", JSONObject().put("url", "https://api.github.com/repos/o/r/pulls/2")),
        ),
        ordinaryIssueCount: Int = 1,
    ) {
        discussions.mkdirs()
        val pullRequests = listOf(
            JSONObject().put("id", 20).put("number", 2).put("title", "Pull request"),
        )
        val issueComments = listOf(
            JSONObject().put("id", 100).put("backupIssueNumber", 1).put("body", "Comment"),
        )
        val reviewComments = listOf(
            JSONObject().put("id", 101).put("backupPullRequestNumber", 2).put("body", "Review comment"),
        )
        val reviews = listOf(
            JSONObject().put("id", 102).put("backupPullRequestNumber", 2).put("state", "APPROVED"),
        )

        val datasetInputs = listOf(
            Triple("issues", GithubDiscussionBackupService.ISSUES_FILE, issues),
            Triple("pullRequests", GithubDiscussionBackupService.PULLS_FILE, pullRequests),
            Triple("issueComments", GithubDiscussionBackupService.ISSUE_COMMENTS_FILE, issueComments),
            Triple("reviewComments", GithubDiscussionBackupService.REVIEW_COMMENTS_FILE, reviewComments),
            Triple("reviews", GithubDiscussionBackupService.REVIEWS_FILE, reviews),
        )
        val datasets = JSONArray()
        datasetInputs.forEach { (name, fileName, records) ->
            val file = File(discussions, fileName)
            file.writeText(records.joinToString(separator = "\n", postfix = if (records.isEmpty()) "" else "\n") { it.toString() })
            datasets.put(
                JSONObject()
                    .put("name", name)
                    .put("file", fileName)
                    .put("count", records.size)
                    .put("sha256", sha256(file)),
            )
        }

        val manifest = JSONObject()
            .put("formatVersion", 1)
            .put("repositoryFullName", "owner/repository")
            .put("issueEntryCount", issues.size)
            .put("issueCount", ordinaryIssueCount)
            .put("pullRequestCount", pullRequests.size)
            .put("issueCommentCount", issueComments.size)
            .put("reviewCommentCount", reviewComments.size)
            .put("reviewCount", reviews.size)
            .put("datasets", datasets)
        File(discussions, GithubDiscussionBackupService.MANIFEST_FILE_NAME).writeText(manifest.toString())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
