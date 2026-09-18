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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.worker.BackupCadence
import com.skypie0102.githubbckp.worker.ScheduledBackupRunOutcome

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onManageGithubToken: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val setupComplete =
        state.githubConnected && state.documentTreeConfigured && state.notificationsReady
    val selectedCount = state.repositories.count { it.isAvailable && it.selectedForBackup }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) viewModel.backupFolderSelectionCancelled()
        else viewModel.chooseBackupFolder(uri)
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
                        enabled = !state.busy && selectedCount > 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            if (selectedCount == 1) {
                                "Back up 1 selected repository"
                            } else {
                                "Back up $selectedCount selected repositories"
                            },
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

            item {
                if (setupComplete) {
                    SetupSummaryCard(
                        state = state,
                        onManageGithubToken = onManageGithubToken,
                        onChooseFolder = { folderLauncher.launch(null) },
                    )
                } else {
                    OnboardingCard(
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

                            if (
                                !permissionGranted &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            ) {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS,
                                )
                            } else {
                                notificationSettingsLauncher.launch(
                                    notificationSettingsIntent(context),
                                )
                            }
                        },
                    )
                }
            }

            if (setupComplete) {
                if (state.backupHealth.selectedCount > 0) {
                    item { BackupHealthCard(summary = state.backupHealth) }
                }

                item {
                    ScheduleCard(
                        enabled = state.scheduleEnabled,
                        cadence = state.scheduleCadence,
                        onEnabledChanged = viewModel::setScheduleEnabled,
                        onCadenceChanged = viewModel::setScheduleCadence,
                    )
                }

                item {
                    RepositoryHeader(
                        busy = state.busy,
                        repositories = state.repositories,
                        selectedCount = selectedCount,
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
private fun SetupSummaryCard(
    state: HomeUiState,
    onManageGithubToken: () -> Unit,
    onChooseFolder: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                "Ready",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
            )
            CompactSettingRow(
                label = "GitHub",
                value = "Connected",
                action = "Token",
                onAction = onManageGithubToken,
            )
            HorizontalDivider()
            CompactSettingRow(
                label = "Backup folder",
                value = state.documentTreeName ?: "Selected",
                action = "Change",
                onAction = onChooseFolder,
            )
            HorizontalDivider()
            CompactSettingRow(
                label = "Notifications",
                value = "Ready",
            )
        }
    }
}

@Composable
private fun CompactSettingRow(
    label: String,
    value: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun OnboardingCard(
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
            Text("Finish setup", style = MaterialTheme.typography.titleMedium)
            Text(
                "Complete these once, then the setup panel becomes compact.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SetupStep(
                number = 1,
                title = "GitHub account",
                detail = if (state.githubConnected) "Connected" else "Personal access token required",
                action = if (state.githubConnected) "Change token" else "Connect",
                enabled = true,
                onAction = onManageGithubToken,
            )
            SetupStep(
                number = 2,
                title = "Backup folder",
                detail = state.documentTreeName ?: "Choose a local folder",
                action = if (state.documentTreeConfigured) "Change" else "Choose",
                enabled = state.githubConnected,
                onAction = onChooseFolder,
            )
            SetupStep(
                number = 3,
                title = "Notifications",
                detail = if (state.notificationsReady) {
                    "Ready"
                } else {
                    "Required while backups are running"
                },
                action = "Enable",
                enabled = state.documentTreeConfigured && !state.notificationsReady,
                onAction = onEnableNotifications,
            )
        }
    }
}

@Composable
private fun SetupStep(
    number: Int,
    title: String,
    detail: String,
    action: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("$number. $title", fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onAction,
            enabled = enabled,
        ) {
            Text(action)
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
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Automatic updates", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !enabled,
                    onClick = { onEnabledChanged(false) },
                    label = { Text("Off") },
                )
                FilterChip(
                    selected = enabled && cadence == BackupCadence.DAILY,
                    onClick = {
                        onCadenceChanged(BackupCadence.DAILY)
                        onEnabledChanged(true)
                    },
                    label = { Text("Daily") },
                )
                FilterChip(
                    selected = enabled && cadence == BackupCadence.WEEKLY,
                    onClick = {
                        onCadenceChanged(BackupCadence.WEEKLY)
                        onEnabledChanged(true)
                    },
                    label = { Text("Weekly") },
                )
            }
            Text(
                "Android runs these as approximate background intervals.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
                Text("Refresh")
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
                .padding(horizontal = 10.dp, vertical = 4.dp),
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
                    buildString {
                        append(repository.defaultBranch)
                        if (repository.isPrivate) append(" • private")
                        if (!repository.isAvailable) append(" • unavailable with current token")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
