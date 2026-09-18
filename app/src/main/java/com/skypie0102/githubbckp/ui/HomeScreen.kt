package com.skypie0102.githubbckp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skypie0102.githubbckp.R
import com.skypie0102.githubbckp.data.local.LatestReleaseEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.release.LatestReleaseAttemptStatus
import com.skypie0102.githubbckp.worker.ScheduledBackupRunOutcome

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val setupComplete =
        state.githubConnected && state.documentTreeConfigured && state.notificationsReady
    val selectedRepositories = state.repositories.filter {
        it.isAvailable && it.selectedForBackup
    }
    val healthByRepository = state.backupHealth.repositories.associateBy { it.repositoryId }
    val mirroredSelectedCount = selectedRepositories.count { repository ->
        healthByRepository[repository.githubId]?.hasMirror == true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GitHub Backup")
                        Text(
                            "Local repository mirrors",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (setupComplete && state.repositories.isNotEmpty()) {
                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    Button(
                        onClick = viewModel::backupSelectedRepositories,
                        enabled = !state.busy && selectedRepositories.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            selectedRepositoryActionLabel(
                                selectedCount = selectedRepositories.size,
                                mirroredSelectedCount = mirroredSelectedCount,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.message?.let { message ->
                item {
                    MessageCard(message = message, onDismiss = viewModel::clearMessage)
                }
            }

            if (!setupComplete) {
                item {
                    SetupRequiredCard(onOpenSettings = onOpenSettings)
                }
            }

            if (state.backupHealth.selectedCount > 0) {
                item {
                    BackupHealthCard(summary = state.backupHealth)
                }
            }

            if (setupComplete) {
                item {
                    RepositoryHeader(
                        busy = state.busy,
                        repositories = state.repositories,
                        selectedCount = selectedRepositories.size,
                        onRefresh = viewModel::refreshRepositories,
                        onSelectAll = { viewModel.setAllRepositoriesSelected(true) },
                        onClearAll = { viewModel.setAllRepositoriesSelected(false) },
                    )
                }

                items(
                    items = state.repositories,
                    key = { it.githubId },
                ) { repository ->
                    RepositoryRow(
                        repository = repository,
                        health = healthByRepository[repository.githubId],
                        latestRelease = state.latestReleases[repository.githubId],
                        onSelectedChanged = {
                            viewModel.setRepositorySelected(repository.githubId, it)
                        },
                    )
                }

                state.scheduledRunStatus?.let { status ->
                    item {
                        val summary = when (status.outcome) {
                            ScheduledBackupRunOutcome.QUEUED ->
                                "Last scheduled run queued ${status.repositoryCount} repositories."
                            ScheduledBackupRunOutcome.SKIPPED_NO_REPOSITORIES ->
                                "Last scheduled run had no selected repositories."
                            ScheduledBackupRunOutcome.SKIPPED_NOT_READY ->
                                "Last scheduled run was blocked: ${status.blockReason ?: "not ready"}."
                        }
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageCard(
    message: String,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun SetupRequiredCard(
    onOpenSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Setup required", style = MaterialTheme.typography.titleMedium)
            Text(
                "Open Settings to configure GitHub access, the backup folder, notifications, and automatic updates.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenSettings) {
                Text("Open settings")
            }
        }
    }
}

@Composable
private fun RepositoryHeader(
    busy: Boolean,
    repositories: List<RepositoryEntity>,
    selectedCount: Int,
    onRefresh: () -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Repositories", style = MaterialTheme.typography.titleLarge)
                Text(
                    "$selectedCount selected • ${repositories.size} found",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRefresh, enabled = !busy) {
                Text(if (busy) "Checking…" else "Check GitHub")
            }
        }
        if (repositories.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onSelectAll, enabled = !busy) { Text("Select all") }
                TextButton(onClick = onClearAll, enabled = !busy) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun RepositoryRow(
    repository: RepositoryEntity,
    health: RepositoryBackupHealth?,
    latestRelease: LatestReleaseEntity?,
    onSelectedChanged: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = repository.selectedForBackup,
                onCheckedChange = onSelectedChanged,
                enabled = repository.isAvailable,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${repository.owner}/${repository.name}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    repositorySupportingText(repository, health),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (health?.state) {
                        RepositoryBackupHealthState.UPDATE_AVAILABLE ->
                            MaterialTheme.colorScheme.primary
                        RepositoryBackupHealthState.FAILED,
                        RepositoryBackupHealthState.MISSING,
                        RepositoryBackupHealthState.BLOCKED ->
                            MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
    }
}

private fun repositorySupportingText(
    repository: RepositoryEntity,
    health: RepositoryBackupHealth?,
): String = buildString {
    append(repository.defaultBranch)
    if (repository.isPrivate) append(" • private")
    if (!repository.isAvailable) {
        append(" • unavailable")
        return@buildString
    }
    if (!repository.selectedForBackup) {
        append(" • not selected")
        return@buildString
    }
    append(" • ")
    append(
        when (health?.state) {
            RepositoryBackupHealthState.HEALTHY -> "up to date"
            RepositoryBackupHealthState.UPDATE_AVAILABLE -> "update available"
            RepositoryBackupHealthState.UPDATING -> "updating"
            RepositoryBackupHealthState.FAILED -> "last update failed"
            RepositoryBackupHealthState.STALE -> "check overdue"
            RepositoryBackupHealthState.MISSING -> "backup missing"
            RepositoryBackupHealthState.BLOCKED -> "blocked"
            RepositoryBackupHealthState.NEVER_BACKED_UP -> "not backed up yet"
            null -> "checking status"
        },
    )
}

internal fun selectedRepositoryActionLabel(
    selectedCount: Int,
    mirroredSelectedCount: Int,
): String {
    if (selectedCount <= 0) return "Select repositories"
    val noun = if (selectedCount == 1) "repository" else "repositories"
    return when {
        mirroredSelectedCount <= 0 -> "Back up $selectedCount selected $noun"
        mirroredSelectedCount >= selectedCount -> "Update $selectedCount selected $noun"
        else -> "Back up & update $selectedCount selected $noun"
    }
}


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
