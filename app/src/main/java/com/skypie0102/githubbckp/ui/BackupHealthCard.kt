package com.skypie0102.githubbckp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun BackupHealthCard(
    summary: BackupHealthSummary,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Backup health", style = MaterialTheme.typography.titleMedium)
            Text(
                text = summary.headlineText(),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (summary.repositories.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                summary.repositories.forEach { health ->
                    Text(
                        text = "${health.fullName} • ${health.detailText()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun BackupHealthSummary.headlineText(): String = when {
    selectedCount == 0 -> "No repositories are currently selected for backup."
    attentionCount == 0 -> "$verifiedCount of $selectedCount selected repositories have a healthy local mirror."
    else -> "$verifiedCount of $selectedCount selected repositories have a local mirror; $attentionCount need attention."
}

private fun RepositoryBackupHealth.detailText(): String {
    val status = when (state) {
    RepositoryBackupHealthState.HEALTHY -> buildString {
        append("healthy")
        latestCheckedAtEpochMs?.let { append(" • checked ${formatTimestamp(it)}") }
        latestChangedAtEpochMs?.let { append(" • changed ${formatTimestamp(it)}") }
    }
    RepositoryBackupHealthState.UPDATING -> "update in progress"
    RepositoryBackupHealthState.FAILED -> errorMessage
        ?.takeIf { it.isNotBlank() }
        ?.let { "latest update failed: $it" }
        ?: "latest update failed"
    RepositoryBackupHealthState.STALE -> latestCheckedAtEpochMs
        ?.let { "last checked ${formatTimestamp(it)}; scheduled check is overdue" }
        ?: "scheduled check is overdue"
    RepositoryBackupHealthState.MISSING -> "mirror metadata exists but mirror.tar.gz is missing"
    RepositoryBackupHealthState.BLOCKED -> warningMessage
        ?.takeIf { it.isNotBlank() }
        ?.let { "blocked: $it" }
        ?: "backup is blocked"
    RepositoryBackupHealthState.NEVER_BACKED_UP -> "no verified local mirror yet"
    }
    return buildString {
        append(status)
        archiveSizeBytes?.takeIf { it > 0L }?.let {
            append(" • ")
            append(formatBytes(it))
        }
        sourceHead?.takeIf { it.isNotBlank() }?.let {
            append(" • HEAD ")
            append(it.take(10))
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val mib = bytes / (1024.0 * 1024.0)
    val gib = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) "%.2f GiB".format(gib) else "%.1f MiB".format(mib)
}

private fun formatTimestamp(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))
