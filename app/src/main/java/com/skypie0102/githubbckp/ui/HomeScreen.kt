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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skypie0102.githubbckp.R
import com.skypie0102.githubbckp.worker.ScheduledBackupRunOutcome

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenRepositorySelection: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val setupComplete =
        state.githubConnected && state.documentTreeConfigured && state.notificationsReady
    val selectedCount = state.repositories.count { it.isAvailable && it.selectedForBackup }
    val updateCount = state.backupHealth.updateAvailableCount
    val missingMirrorCount = state.backupHealth.repositories.count {
        it.state == RepositoryBackupHealthState.NEVER_BACKED_UP ||
            it.state == RepositoryBackupHealthState.MISSING
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
                    IconButton(onClick = onOpenRepositorySelection) {
                        Icon(
                            painter = painterResource(R.drawable.ic_repository_select),
                            contentDescription = "Select backup repositories",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
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
            if (setupComplete && selectedCount > 0) {
                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    Button(
                        onClick = viewModel::updateAvailableRepositories,
                        enabled = !state.busy && updateCount > 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(updateAvailableActionLabel(updateCount))
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

            item {
                BackupSetCard(
                    selectedCount = selectedCount,
                    busy = state.busy,
                    onManage = onOpenRepositorySelection,
                    onRefresh = viewModel::refreshRepositories,
                )
            }

            if (state.backupHealth.selectedCount > 0) {
                item {
                    BackupHealthCard(
                        summary = state.backupHealth,
                        latestReleases = state.latestReleases,
                    )
                }
            }

            if (setupComplete && missingMirrorCount > 0) {
                item {
                    OutlinedButton(
                        onClick = viewModel::backupRepositoriesWithoutMirror,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(missingMirrorActionLabel(missingMirrorCount))
                    }
                }
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
private fun BackupSetCard(
    selectedCount: Int,
    busy: Boolean,
    onManage: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Backup set", style = MaterialTheme.typography.titleMedium)
            Text(
                if (selectedCount == 1) {
                    "1 repository selected for backup"
                } else {
                    "$selectedCount repositories selected for backup"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onManage) {
                    Text("Manage repositories")
                }
                TextButton(onClick = onRefresh, enabled = !busy) {
                    Text(if (busy) "Checking…" else "Check GitHub")
                }
            }
        }
    }
}

internal fun updateAvailableActionLabel(count: Int): String = when (count) {
    0 -> "No updates available"
    1 -> "Update 1 available repository"
    else -> "Update $count available repositories"
}

internal fun missingMirrorActionLabel(count: Int): String =
    if (count == 1) {
        "Back up 1 repository without a mirror"
    } else {
        "Back up $count repositories without a mirror"
    }
