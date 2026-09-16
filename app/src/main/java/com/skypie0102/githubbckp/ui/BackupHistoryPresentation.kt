package com.skypie0102.githubbckp.ui

import com.skypie0102.githubbckp.backup.BackupOrigin
import com.skypie0102.githubbckp.data.local.BackupEntity

internal fun shortScheduledRunId(value: String?): String? =
    value?.trim()?.takeIf { it.isNotEmpty() }?.take(8)

internal fun BackupEntity.originDisplayText(): String = when (origin) {
    BackupOrigin.MANUAL -> "Manual"
    BackupOrigin.SCHEDULED -> shortScheduledRunId(scheduledRunId)
        ?.let { "Scheduled • run $it" }
        ?: "Scheduled • run ID unavailable"
    null -> "Origin unknown"
}
