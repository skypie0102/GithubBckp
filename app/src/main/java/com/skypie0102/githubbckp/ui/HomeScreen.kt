package com.skypie0102.githubbckp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skypie0102.githubbckp.worker.BackupCadence
import com.skypie0102.githubbckp.worker.ScheduledBackupRunOutcome

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onManageGithubToken: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val setupComplete =
        state.githubConnected && state.documentTreeConfigured && state.notificationsReady

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            viewModel.backupFolderSelectionCancelled()
        } else {
            viewModel.chooseBackupFolder(uri)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refreshReadiness()
    }

    val notificationSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshReadiness()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.width(1.dp))
            Text(
                "GitHub Backup",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                "One current local .tar.gz mirror per selected repository.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        state.message?.let { message ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(message, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = viewModel::clearMessage) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }

        item {
            SetupCard(
                state = state,
                onManageGithubToken = onManageGithubToken,
                onChooseFolder = { folderLauncher.launch(null) },
                onEnableNotifications = {
                    val permissionGranted =
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) == PackageManager.PERMISSION_GRANTED

                    if (!permissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        notificationSettingsLauncher.launch(notificationSettingsIntent(context))
                    }
                },
            )
        }

        if (setupComplete) {
            item {
                RepositoryActions(
                    busy = state.busy,
                    hasRepositories = state.repositories.isNotEmpty(),
                    onRefresh = viewModel::refreshRepositories,
                    onSelectAll = { viewModel.setAllRepositoriesSelected(true) },
                    onClearAll = { viewModel.setAllRepositoriesSelected(false) },
                    onUpdateSelected = viewModel::backupSelectedRepositories,
                )
            }

            items(
                items = state.repositories,
                key = { it.githubId },
            ) { repository ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = repository.selectedForBackup,
                            onCheckedChange = {
                                viewModel.setRepositorySelected(repository.githubId, it)
                            },
                            enabled = repository.isAvailable,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${repository.owner}/${repository.name}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                buildString {
                                    append(repository.defaultBranch)
                                    if (repository.isPrivate) append(" • private")
                                    if (!repository.isAvailable) append(" • unavailable")
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (state.repositories.isNotEmpty()) {
                item {
                    ScheduleCard(
                        enabled = state.scheduleEnabled,
                        cadence = state.scheduleCadence,
                        onEnabledChanged = viewModel::setScheduleEnabled,
                        onCadenceChanged = viewModel::setScheduleCadence,
                    )
                }
            }

            if (state.backupHealth.selectedCount > 0) {
                item {
                    BackupHealthCard(summary = state.backupHealth)
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
                    Text(summary, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            item {
                Text(
                    "Complete setup before choosing repositories or enabling automatic updates.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item { Spacer(Modifier.width(1.dp)) }
    }
}

@Composable
private fun SetupCard(
    state: HomeUiState,
    onManageGithubToken: () -> Unit,
    onChooseFolder: () -> Unit,
    onEnableNotifications: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Setup", style = MaterialTheme.typography.titleMedium)
            Text(
                "Complete these steps in order before repository selection.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                "1. GitHub — " + if (state.githubConnected) "connected" else "token required",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onManageGithubToken) {
                Text(if (state.githubConnected) "Manage GitHub token" else "Connect GitHub")
            }

            HorizontalDivider()

            Text(
                "2. Local backup folder — " +
                    if (state.documentTreeConfigured) {
                        state.documentTreeName ?: "selected"
                    } else {
                        "required"
                    },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "The app stores one mirror.tar.gz per repository in this folder.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onChooseFolder,
                enabled = state.githubConnected,
            ) {
                Text(if (state.documentTreeConfigured) "Change folder" else "Choose folder")
            }

            HorizontalDivider()

            Text(
                "3. Active-job notifications — " +
                    if (state.notificationsReady) "ready" else "required",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Every running mirror job must remain visible in Android notifications, including its stage, progress when measurable, and Cancel action.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!state.notificationsReady) {
                OutlinedButton(
                    onClick = onEnableNotifications,
                    enabled = state.documentTreeConfigured,
                ) {
                    Text("Enable notifications")
                }
            }

            HorizontalDivider()

            Text(
                "4. Repositories — " +
                    if (
                        state.githubConnected &&
                        state.documentTreeConfigured &&
                        state.notificationsReady
                    ) {
                        "ready to choose"
                    } else {
                        "available after steps 1–3"
                    },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ScheduleCard(
    enabled: Boolean,
    cadence: BackupCadence,
    onEnabledChanged: (Boolean) -> Unit,
    onCadenceChanged: (BackupCadence) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Automatic updates", style = MaterialTheme.typography.titleMedium)

            ScheduleOption(
                selected = !enabled,
                label = "Off",
                onClick = { onEnabledChanged(false) },
            )
            ScheduleOption(
                selected = enabled && cadence == BackupCadence.DAILY,
                label = "Daily",
                onClick = {
                    onCadenceChanged(BackupCadence.DAILY)
                    onEnabledChanged(true)
                },
            )
            ScheduleOption(
                selected = enabled && cadence == BackupCadence.WEEKLY,
                label = "Weekly",
                onClick = {
                    onCadenceChanged(BackupCadence.WEEKLY)
                    onEnabledChanged(true)
                },
            )
            Text(
                "Android schedules background work opportunistically; daily and weekly are intervals, not exact clock times.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ScheduleOption(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun RepositoryActions(
    busy: Boolean,
    hasRepositories: Boolean,
    onRefresh: () -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onUpdateSelected: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Repositories", style = MaterialTheme.typography.titleMedium)
            Text(
                "Refresh verifies actual Git read access before a repository can be selected.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !busy,
                ) {
                    Text("Refresh")
                }
                if (hasRepositories) {
                    OutlinedButton(onClick = onSelectAll, enabled = !busy) {
                        Text("Select all")
                    }
                    OutlinedButton(onClick = onClearAll, enabled = !busy) {
                        Text("Clear")
                    }
                }
            }
            Button(
                onClick = onUpdateSelected,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && hasRepositories,
            ) {
                Text("Update selected mirrors")
            }
        }
    }
}

private fun notificationSettingsIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    }
