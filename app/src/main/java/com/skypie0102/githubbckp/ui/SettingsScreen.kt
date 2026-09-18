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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skypie0102.githubbckp.R
import com.skypie0102.githubbckp.worker.BackupCadence

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit,
    onManageGithubToken: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.message?.let { message ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(message, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::clearMessage) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }

            item {
                SettingsCard(
                    title = "GitHub token",
                    summary = if (state.githubConnected) {
                        "Connected. Used only for repository discovery and read access."
                    } else {
                        "Not connected. Add a read-only GitHub token to continue."
                    },
                ) {
                    OutlinedButton(onClick = onManageGithubToken) {
                        Text(if (state.githubConnected) "Manage token" else "Connect GitHub")
                    }
                }
            }

            item {
                SettingsCard(
                    title = "Backup folder",
                    summary = if (state.documentTreeConfigured) {
                        state.documentTreeName ?: "Local folder selected"
                    } else {
                        "Choose where repository mirrors are stored."
                    },
                ) {
                    OutlinedButton(
                        onClick = { folderLauncher.launch(null) },
                        enabled = state.githubConnected,
                    ) {
                        Text(if (state.documentTreeConfigured) "Change folder" else "Choose folder")
                    }
                }
            }

            item {
                SettingsCard(
                    title = "Notifications",
                    summary = if (state.notificationsReady) {
                        "Ready. Every active backup will remain visible."
                    } else {
                        "Required so every running backup remains visible."
                    },
                ) {
                    OutlinedButton(
                        onClick = {
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
                        enabled = state.documentTreeConfigured,
                    ) {
                        Text(
                            if (state.notificationsReady) {
                                "Notification settings"
                            } else {
                                "Enable notifications"
                            },
                        )
                    }
                }
            }

            item {
                SettingsCard(
                    title = "Automatic updates",
                    summary = if (!state.scheduleEnabled) {
                        "Off"
                    } else {
                        when (state.scheduleCadence) {
                            BackupCadence.DAILY -> "Daily"
                            BackupCadence.WEEKLY -> "Weekly"
                        }
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = !state.scheduleEnabled,
                            onClick = { viewModel.setScheduleEnabled(false) },
                            label = { Text("Off") },
                        )
                        FilterChip(
                            selected = state.scheduleEnabled &&
                                state.scheduleCadence == BackupCadence.DAILY,
                            onClick = {
                                viewModel.setScheduleCadence(BackupCadence.DAILY)
                                viewModel.setScheduleEnabled(true)
                            },
                            label = { Text("Daily") },
                        )
                        FilterChip(
                            selected = state.scheduleEnabled &&
                                state.scheduleCadence == BackupCadence.WEEKLY,
                            onClick = {
                                viewModel.setScheduleCadence(BackupCadence.WEEKLY)
                                viewModel.setScheduleEnabled(true)
                            },
                            label = { Text("Weekly") },
                        )
                    }
                    Text(
                        "Android runs daily and weekly schedules as approximate background intervals.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    summary: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
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
