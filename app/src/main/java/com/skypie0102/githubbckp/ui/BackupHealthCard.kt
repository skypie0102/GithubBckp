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
    attentionCount == 0 -> "$verifiedCount of $selectedCount selected repositories have a current verified backup."
    else -> "$verifiedCount of $selectedCount selected repositories have a current verified backup; $attentionCount need attention."
}

private fun RepositoryBackupHealth.detailText(): String = when (state) {
    RepositoryBackupHealthState.PROTECTED -> latestVerifiedAtEpochMs
        ?.let { "protected • last verified ${formatTimestamp(it)}" }
        ?: "protected"
    RepositoryBackupHealthState.WARNING -> warningMessage
        ?.takeIf { it.isNotBlank() }
        ?.let { "verified with warning: $it" }
        ?: "latest attempt was cancelled"
    RepositoryBackupHealthState.FAILED -> errorMessage
        ?.takeIf { it.isNotBlank() }
        ?.let { "latest attempt failed: $it" }
        ?: "latest attempt failed after the last verified backup"
    RepositoryBackupHealthState.STALE -> latestVerifiedAtEpochMs
        ?.let { "last verified ${formatTimestamp(it)}; scheduled backup is overdue" }
        ?: "scheduled backup is overdue"
    RepositoryBackupHealthState.NEVER_BACKED_UP -> "no current verified backup"
}

private fun formatTimestamp(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))
