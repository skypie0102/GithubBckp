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

            val problems = summary.problemRepositories
            if (problems.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                problems.take(MAX_PROBLEMS_SHOWN).forEach { health ->
                    Text(
                        text = "${health.fullName} • ${health.detailText()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (problems.size > MAX_PROBLEMS_SHOWN) {
                    Text(
                        text = "+${problems.size - MAX_PROBLEMS_SHOWN} more selected repositories need attention",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun BackupHealthSummary.headlineText(): String = when {
    selectedCount == 0 -> "No repositories are currently selected for backup."
    attentionCount == 0 -> "$protectedCount of $selectedCount selected repositories have a current verified backup."
    else -> "$protectedCount of $selectedCount selected repositories are protected; $attentionCount need attention."
}

private fun RepositoryBackupHealth.detailText(): String = when (state) {
    RepositoryBackupHealthState.PROTECTED -> "protected"
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

private const val MAX_PROBLEMS_SHOWN = 5
