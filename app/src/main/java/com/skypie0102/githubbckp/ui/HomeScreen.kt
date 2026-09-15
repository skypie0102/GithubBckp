package com.skypie0102.githubbckp.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.skypie0102.githubbckp.backup.BackupType
import com.skypie0102.githubbckp.backup.MirrorRestoreRecord
import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.storage.StorageDestination
import com.skypie0102.githubbckp.worker.BackupCadence

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val state by viewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    val driveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.completeDriveAuthorization(result.data)
        else viewModel.driveAuthorizationCancelled()
    }
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.chooseBackupFolder(uri)
        else viewModel.backupFolderSelectionCancelled()
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.restoreMirrorArchive(uri)
        else viewModel.restoreArchiveSelectionCancelled()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("GitHub Backup") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "Keep a recoverable copy of your repositories outside GitHub.",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            state.message?.let { message ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(message, modifier = Modifier.padding(16.dp))
                    }
                }
            }

            item {
                ConnectionCard(
                    title = "GitHub",
                    detail = when {
                        !state.githubConfigured -> "OAuth client ID not configured"
                        state.githubConnected -> "Connected"
                        else -> "Not connected"
                    },
                    icon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                    action = if (state.githubConnected) "Refresh repositories" else "Connect GitHub",
                    enabled = state.githubConfigured && !state.busy,
                    onClick = {
                        if (state.githubConnected) viewModel.refreshRepositories() else viewModel.connectGithub()
                    },
                )
            }

            state.githubDeviceSession?.let { session ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Authorize GitHub", style = MaterialTheme.typography.titleMedium)
                            Text("Enter this one-time code on GitHub:")
                            Text(session.userCode, style = MaterialTheme.typography.headlineMedium)
                            Button(onClick = { uriHandler.openUri(session.verificationUri) }) {
                                Text("Open GitHub authorization")
                            }
                        }
                    }
                }
            }

            item { Text("Backup destination", style = MaterialTheme.typography.titleLarge) }

            item {
                val driveSelected = state.storageDestination == StorageDestination.GOOGLE_DRIVE
                ConnectionCard(
                    title = "Google Drive",
                    detail = buildString {
                        append(if (state.driveConnected) "Connected" else "Not connected")
                        if (driveSelected) append(" • selected")
                    },
                    icon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                    action = when {
                        !state.driveConnected -> "Connect Drive"
                        !driveSelected -> "Use Google Drive"
                        else -> "Refresh Drive access"
                    },
                    enabled = !state.busy,
                    onClick = {
                        when {
                            !state.driveConnected || driveSelected -> viewModel.connectDrive { pendingIntent ->
                                driveLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                            }
                            else -> viewModel.useGoogleDrive()
                        }
                    },
                )
            }

            item {
                val folderSelected = state.storageDestination == StorageDestination.DOCUMENT_TREE
                ConnectionCard(
                    title = "Backup folder",
                    detail = buildString {
                        append(state.documentTreeName ?: if (state.documentTreeConfigured) "Configured" else "Not selected")
                        if (folderSelected) append(" • selected")
                    },
                    icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                    action = when {
                        state.documentTreeConfigured && !folderSelected -> "Use backup folder"
                        state.documentTreeConfigured -> "Change backup folder"
                        else -> "Choose backup folder"
                    },
                    enabled = !state.busy,
                    onClick = {
                        if (state.documentTreeConfigured && !folderSelected) viewModel.useBackupFolder()
                        else folderLauncher.launch(null)
                    },
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Outlined.Security, contentDescription = null)
                        Column {
                            Text("Credential safety", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text("GitHub tokens are encrypted with Android Keystore. Folder access is granted by Android's system picker and can persist across restarts.")
                        }
                    }
                }
            }

            if (state.busy) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            item { Text("Backup format", style = MaterialTheme.typography.titleLarge) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.backupType == BackupType.SOURCE_ARCHIVE,
                        onClick = { viewModel.setBackupType(BackupType.SOURCE_ARCHIVE) },
                        label = { Text("Source snapshot") },
                    )
                    FilterChip(
                        selected = state.backupType == BackupType.GIT_MIRROR,
                        onClick = { viewModel.setBackupType(BackupType.GIT_MIRROR) },
                        label = { Text("Git mirror") },
                    )
                }
            }
            item {
                Text(
                    text = if (state.backupType == BackupType.GIT_MIRROR) {
                        "Git mirror preserves Git refs and history. Git LFS objects are not included yet."
                    } else {
                        "Source snapshot is smaller, but contains only the selected branch snapshot and is not a full Git backup."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Repositories", style = MaterialTheme.typography.titleLarge)
                    Text("${state.repositories.count { it.selectedForBackup }} selected")
                }
            }

            if (state.repositories.isEmpty()) {
                item { Text("Connect GitHub and refresh to discover repositories.") }
            } else {
                items(state.repositories, key = { it.githubId }) { repository ->
                    RepositoryRow(
                        repository = repository,
                        onSelectedChange = { selected -> viewModel.setRepositorySelected(repository.githubId, selected) },
                    )
                }
                item {
                    Button(
                        onClick = viewModel::backupSelectedRepositories,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Back up selected repositories")
                    }
                }
            }

            item { Text("Automatic backups", style = MaterialTheme.typography.titleLarge) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Scheduled backups", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Uses the repositories selected when the schedule runs.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = state.scheduleEnabled,
                            onCheckedChange = viewModel::setScheduleEnabled,
                            enabled = !state.busy,
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.scheduleCadence == BackupCadence.DAILY,
                        onClick = { viewModel.setScheduleCadence(BackupCadence.DAILY) },
                        label = { Text("Daily") },
                    )
                    FilterChip(
                        selected = state.scheduleCadence == BackupCadence.WEEKLY,
                        onClick = { viewModel.setScheduleCadence(BackupCadence.WEEKLY) },
                        label = { Text("Weekly") },
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.scheduledBackupType == BackupType.SOURCE_ARCHIVE,
                        onClick = { viewModel.setScheduledBackupType(BackupType.SOURCE_ARCHIVE) },
                        label = { Text("Snapshot schedule") },
                    )
                    FilterChip(
                        selected = state.scheduledBackupType == BackupType.GIT_MIRROR,
                        onClick = { viewModel.setScheduledBackupType(BackupType.GIT_MIRROR) },
                        label = { Text("Mirror schedule") },
                    )
                }
            }
            item {
                Text(
                    "Android runs periodic work opportunistically rather than at an exact clock time. Scheduled backups require unmetered connectivity and adequate battery/storage.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            item { Text("Restore Git mirror", style = MaterialTheme.typography.titleLarge) }
            item {
                Text(
                    "Choose a Git mirror ZIP created by this app. It is copied into private app storage, extracted with path-traversal protection, and every advertised ref tip is verified before the restored copy is kept.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        restoreLauncher.launch(
                            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                        )
                    },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Choose mirror archive to restore")
                }
            }
            if (state.restoredMirrors.isNotEmpty()) {
                item { Text("Restored mirrors", style = MaterialTheme.typography.titleMedium) }
                items(state.restoredMirrors, key = { it.id }) { restore ->
                    RestoredMirrorRow(
                        restore = restore,
                        onDelete = { viewModel.deleteRestoredMirror(restore.id) },
                    )
                }
            }

            if (state.recentBackups.isNotEmpty()) {
                item { Text("Recent backups", style = MaterialTheme.typography.titleLarge) }
                items(state.recentBackups.take(10), key = { it.id }) { backup -> BackupRow(backup) }
            }
        }
    }
}

@Composable
private fun RepositoryRow(
    repository: RepositoryEntity,
    onSelectedChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = repository.selectedForBackup, onCheckedChange = onSelectedChange)
            Column(modifier = Modifier.weight(1f)) {
                Text("${repository.owner}/${repository.name}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${repository.defaultBranch} • ${if (repository.isPrivate) "private" else "public"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RestoredMirrorRow(
    restore: MirrorRestoreRecord,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(restore.archiveName, style = MaterialTheme.typography.titleSmall)
            Text("${restore.refCount} refs • ${restore.referencedObjectsVerified} ref-tip objects verified")
            Text(
                "Stored privately on this device for a future push/export step.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onDelete) { Text("Delete restored copy") }
        }
    }
}

@Composable
private fun BackupRow(backup: BackupEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Repository #${backup.repositoryId}", style = MaterialTheme.typography.titleSmall)
            Text("${backup.type.name.replace('_', ' ')} • ${backup.status.name}")
            backup.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ConnectionCard(
    title: String,
    detail: String,
    icon: @Composable () -> Unit,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                icon()
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(detail, style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedButton(onClick = onClick, enabled = enabled) { Text(action) }
        }
    }
}
