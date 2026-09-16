package com.skypie0102.githubbckp.backup

import org.json.JSONArray
import org.json.JSONObject

data class RestoreAuditSnapshot(
    val id: String,
    val archiveName: String,
    val createdAtEpochMs: Long,
    val detailsAvailable: Boolean,
    val refCount: Int,
    val referencedObjectsVerified: Int,
    val lfsObjectCount: Int,
    val wikiRefCount: Int,
    val wikiReferencedObjectsVerified: Int,
    val releaseCount: Int,
    val releaseAssetCount: Int,
    val issueCount: Int,
    val pullRequestCount: Int,
    val issueCommentCount: Int,
    val reviewCommentCount: Int,
    val reviewCount: Int,
)

fun MirrorRestoreRecord.auditReportFileName(): String {
    val stem = archiveName
        .removeSuffix(".mirror.zip")
        .removeSuffix(".zip")
        .replace(Regex("[^A-Za-z0-9._-]"), "-")
        .trim('-', '.')
        .take(100)
        .ifBlank { "restored-mirror" }
    return "$stem-restore-audit.json"
}

fun MirrorRestoreRecord.toAuditSnapshot(): RestoreAuditSnapshot = RestoreAuditSnapshot(
    id = id,
    archiveName = archiveName,
    createdAtEpochMs = createdAtEpochMs,
    detailsAvailable = detailsAvailable,
    refCount = refCount,
    referencedObjectsVerified = referencedObjectsVerified,
    lfsObjectCount = lfsObjectCount,
    wikiRefCount = wikiRefCount,
    wikiReferencedObjectsVerified = wikiReferencedObjectsVerified,
    releaseCount = releaseCount,
    releaseAssetCount = releaseAssetCount,
    issueCount = issueCount,
    pullRequestCount = pullRequestCount,
    issueCommentCount = issueCommentCount,
    reviewCommentCount = reviewCommentCount,
    reviewCount = reviewCount,
)

fun RestoreAuditSnapshot.toAuditJson(generatedAtEpochMs: Long = System.currentTimeMillis()): JSONObject =
    JSONObject()
        .put("formatVersion", AUDIT_FORMAT_VERSION)
        .put("generatedAtEpochMs", generatedAtEpochMs)
        .put("restoreId", id)
        .put("archiveName", archiveName)
        .put("importedAtEpochMs", createdAtEpochMs)
        .put("moduleDetailsAvailable", detailsAvailable)
        .put(
            "mainGit",
            JSONObject()
                .put("refCount", refCount)
                .put("refTipObjectsVerified", referencedObjectsVerified)
                .put("locallyValidated", true)
                .put("githubPublication", "supported for a new or transaction-bound empty target"),
        )
        .put(
            "gitLfs",
            JSONObject()
                .put("objectCount", lfsObjectCount)
                .put("locallyValidated", detailsAvailable)
                .put("githubPublication", "supported before Git ref publication"),
        )
        .put(
            "wiki",
            JSONObject()
                .put("present", wikiRefCount > 0)
                .put("refCount", wikiRefCount)
                .put("refTipObjectsVerified", wikiReferencedObjectsVerified)
                .put("locallyValidated", detailsAvailable)
                .put("githubPublication", "not automated"),
        )
        .put(
            "releases",
            JSONObject()
                .put("present", releaseCount > 0)
                .put("releaseCount", releaseCount)
                .put("assetCount", releaseAssetCount)
                .put("locallyValidated", detailsAvailable)
                .put("githubPublication", "supported transactionally")
                .put(
                    "limitations",
                    JSONArray()
                        .put("source latest-release selection is not recreated")
                        .put("source publication timestamps are not recreated")
                        .put("immutable-release state is not recreated"),
                ),
        )
        .put(
            "discussions",
            JSONObject()
                .put("present", discussionRecordCount() > 0)
                .put("issueCount", issueCount)
                .put("pullRequestCount", pullRequestCount)
                .put("issueCommentCount", issueCommentCount)
                .put("reviewCommentCount", reviewCommentCount)
                .put("reviewCount", reviewCount)
                .put("locallyValidated", detailsAvailable)
                .put("githubPublication", "not automated")
                .put(
                    "limitations",
                    JSONArray()
                        .put("timeline events are not bundled")
                        .put("referenced attachment bytes are not bundled")
                        .put("original authors and server timestamps are not recreated"),
                ),
        )
        .put(
            "notes",
            JSONArray().apply {
                if (!detailsAvailable) {
                    put(
                        "This restore predates detailed module metadata. Zero optional-module counts mean unknown/not recorded, not proof that the module was absent.",
                    )
                }
            },
        )

private fun RestoreAuditSnapshot.discussionRecordCount(): Int =
    issueCount + pullRequestCount + issueCommentCount + reviewCommentCount + reviewCount

private const val AUDIT_FORMAT_VERSION = 1
