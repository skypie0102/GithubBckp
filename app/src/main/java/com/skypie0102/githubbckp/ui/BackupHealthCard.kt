package com.skypie0102.githubbckp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skypie0102.githubbckp.data.local.LatestReleaseEntity
import com.skypie0102.githubbckp.release.LatestReleaseAttemptStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun BackupHealthCard(
    summary: BackupHealthSummary,
    latestReleases: Map<Long, LatestReleaseEntity> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val updateAvailable = summary.updateAvailableRepositories
    val problems = summary.problemRepositories
    val current = summary.repositories.filter {
        it.state == RepositoryBackupHealthState.HEALTHY ||
            it.state == RepositoryBackupHealthState.UPDATING
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Backup health", style = MaterialTheme.typography.titleMedium)
            Text(
                text = summary.headlineText(),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (updateAvailable.isNotEmpty()) {
                HealthSection(
                    title = "Updates available",
                    count = updateAvailable.size,
                    repositories = updateAvailable,
                )
            }

            if (problems.isNotEmpty()) {
                HealthSection(
                    title = "Needs attention",
                    count = problems.size,
                    repositories = problems,
                )
            }

            if (current.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    "Up to date / active (${current.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                current.forEach { health ->
                    HealthRow(
                        health = health,
                        latestRelease = latestReleases[health.repositoryId],
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthSection(
    title: String,
    count: Int,
    repositories: List<RepositoryBackupHealth>,
) {
    HorizontalDivider()
    Text(
        "$title ($count)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    repositories.forEach { health ->
        HealthRow(
                        health = health,
                        latestRelease = latestReleases[health.repositoryId],
                    )
    }
}

@Composable
private fun HealthRow(
    health: RepositoryBackupHealth,
    latestRelease: LatestReleaseEntity?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                health.fullName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                health.shortStatus(),
                style = MaterialTheme.typography.labelMedium,
                color = when (health.state) {
                    RepositoryBackupHealthState.UPDATE_AVAILABLE ->
                        MaterialTheme.colorScheme.primary
                    RepositoryBackupHealthState.FAILED,
                    RepositoryBackupHealthState.MISSING,
                    RepositoryBackupHealthState.BLOCKED ->
                        MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(
            health.supportingText(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        latestRelease?.let { release ->
            Text(
                latestReleaseSupportingText(release),
                style = MaterialTheme.typography.bodySmall,
                color = if (
                    release.lastAttemptStatus == LatestReleaseAttemptStatus.FAILED.name
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private fun BackupHealthSummary.headlineText(): String = when {
    selectedCount == 0 -> "No repositories are selected."
    updateAvailableCount > 0 -> {
        val noun = if (updateAvailableCount == 1) "repository has" else "repositories have"
        "$updateAvailableCount $noun changes on GitHub and should be updated."
    }
    problemRepositories.isNotEmpty() -> {
        val count = problemRepositories.size
        val noun = if (count == 1) "repository needs" else "repositories need"
        "$count $noun attention."
    }
    else -> "All $selectedCount selected repositories are up to date."
}

private fun RepositoryBackupHealth.shortStatus(): String = when (state) {
    RepositoryBackupHealthState.HEALTHY -> "Up to date"
    RepositoryBackupHealthState.UPDATE_AVAILABLE -> "Update available"
    RepositoryBackupHealthState.UPDATING -> "Updating"
    RepositoryBackupHealthState.FAILED -> "Failed"
    RepositoryBackupHealthState.STALE -> "Check overdue"
    RepositoryBackupHealthState.MISSING -> "Missing"
    RepositoryBackupHealthState.BLOCKED -> "Blocked"
    RepositoryBackupHealthState.NEVER_BACKED_UP -> "Not backed up"
}

private fun RepositoryBackupHealth.supportingText(): String = when (state) {
    RepositoryBackupHealthState.HEALTHY -> buildString {
        latestCheckedAtEpochMs?.let { append("Last mirror check ${formatTimestamp(it)}") }
        archiveSizeBytes?.takeIf { it > 0L }?.let {
            if (isNotEmpty()) append(" • ")
            append(formatBytes(it))
        }
        sourceHead?.takeIf { it.isNotBlank() }?.let {
            if (isNotEmpty()) append(" • ")
            append("HEAD ${it.take(10)}")
        }
        if (isEmpty()) append("Verified local mirror")
    }
    RepositoryBackupHealthState.UPDATE_AVAILABLE ->
        "GitHub refs differ from the saved mirror. Run an update to bring it current."
    RepositoryBackupHealthState.UPDATING -> "Mirror update is currently running."
    RepositoryBackupHealthState.FAILED -> errorMessage
        ?.takeIf { it.isNotBlank() }
        ?.let { "Latest update failed: $it" }
        ?: "The latest update failed; the previous verified mirror is retained."
    RepositoryBackupHealthState.STALE -> latestCheckedAtEpochMs
        ?.let { "Last checked ${formatTimestamp(it)}; the scheduled check is overdue." }
        ?: "The scheduled mirror check is overdue."
    RepositoryBackupHealthState.MISSING ->
        "The app has mirror metadata, but the archive is missing from storage."
    RepositoryBackupHealthState.BLOCKED -> warningMessage
        ?.takeIf { it.isNotBlank() }
        ?: "Backup is blocked by a setup or access problem."
    RepositoryBackupHealthState.NEVER_BACKED_UP ->
        "No verified local mirror exists yet."
}

private fun formatBytes(bytes: Long): String {
    val mib = bytes / (1024.0 * 1024.0)
    val gib = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) "%.2f GiB".format(gib) else "%.1f MiB".format(mib)
}

private fun formatTimestamp(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))


private fun latestReleaseSupportingText(release: LatestReleaseEntity): String = when (
    release.lastAttemptStatus
) {
    LatestReleaseAttemptStatus.CHECKING.name -> "Latest release • checking"
    LatestReleaseAttemptStatus.DOWNLOADING.name -> "Latest release • downloading"
    LatestReleaseAttemptStatus.NO_RELEASE.name ->
        release.tagName?.let { "Latest release $it • retained locally; none currently published" }
            ?: "Latest release • none published"
    LatestReleaseAttemptStatus.FAILED.name ->
        release.tagName?.let { "Latest release $it • backup failed" }
            ?: "Latest release • backup failed"
    else -> release.tagName?.let { "Latest release $it • backed up" }
        ?: "Latest release • not backed up yet"
}
