package com.skypie0102.githubbckp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BackupHealthCard(summary: BackupHealthSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Mirror health", style = MaterialTheme.typography.titleMedium)
            if (summary.selectedCount == 0) {
                Text("Select at least one repository to begin.")
                return@Column
            }

            Text("${summary.healthyCount} of ${summary.selectedCount} selected repositories are healthy.")
            if (summary.runningCount > 0) {
                Text("${summary.runningCount} backup job${if (summary.runningCount == 1) "" else "s"} running")
            }
            if (summary.needsFirstBackupCount > 0) {
                Text("${summary.needsFirstBackupCount} need a first backup")
            }
            if (summary.failedCount > 0) {
                Text(
                    "${summary.failedCount} have a failed latest update",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (summary.staleCount > 0) {
                Text("${summary.staleCount} are stale for the selected schedule")
            }
        }
    }
}
