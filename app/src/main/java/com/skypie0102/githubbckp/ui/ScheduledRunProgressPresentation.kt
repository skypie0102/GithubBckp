package com.skypie0102.githubbckp.ui

import com.skypie0102.githubbckp.worker.ScheduledBackupRunProgress

internal fun ScheduledBackupRunProgress.progressDisplayText(): String {
    val parts = buildList {
        if (completedCount > 0) add("$completedCount completed")
        if (failedCount > 0) add("$failedCount failed")
        if (cancelledCount > 0) add("$cancelledCount cancelled")
        if (activeCount > 0) add("$activeCount active")
        if (unobservedCount > 0) add("$unobservedCount not started or deduplicated")
    }
    val prefix = if (finished) "Run result" else "Progress"
    return if (parts.isEmpty()) {
        "$prefix: no child backups observed yet."
    } else {
        "$prefix: ${parts.joinToString(" • ")}."
    }
}
