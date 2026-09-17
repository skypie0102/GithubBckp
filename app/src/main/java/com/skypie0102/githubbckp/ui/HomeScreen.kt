package com.skypie0102.githubbckp.ui

import android.app.Activity
import android.widget.Toast
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.skypie0102.githubbckp.backup.BackupReverificationStatus
import com.skypie0102.githubbckp.backup.BackupStatus
import com.skypie0102.githubbckp.backup.MirrorRestoreRecord
import com.skypie0102.githubbckp.backup.auditReportFileName
import com.skypie0102.githubbckp.backup.backupAuditReportFileName
import com.skypie0102.githubbckp.backup.canReverifyBackup
import com.skypie0102.githubbckp.backup.repositoryDisplayName
import com.skypie0102.githubbckp.backup.toAuditJson
import com.skypie0102.githubbckp.backup.toAuditSnapshot
import com.skypie0102.githubbckp.backup.toBackupAuditJson
import com.skypie0102.githubbckp.backup.toBackupAuditSnapshot
import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.storage.StorageDestination
import com.skypie0102.githubbckp.worker.BackupCadence
import com.skypie0102.githubbckp.worker.ScheduledBackupBlockReason
import com.skypie0102.githubbckp.worker.ScheduledBackupRunOutcome
import com.skypie0102.githubbckp.worker.ScheduledBackupRunStatus
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onManageGithubToken: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val reverificationViewModel: BackupReverificationViewModel = hiltViewModel()
    val reverificationState by reverificationViewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var pendingAuditRestore by remember { mutableStateOf<MirrorRestoreRecord?>(null) }
    var pendingBackupAudit by remember { mutableStateOf<Pair<BackupEntity, RepositoryEntity?>?>(null) }
    var repositorySearchQuery by remember { mutableStateOf("") }
    val visibleRepositories = remember(state.repositories, repositorySearchQuery) {
        filterRepositories(state.repositories, repositorySearchQuery)
    }

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
    val restoreAuditLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val restore = pendingAuditRestore
        pendingAuditRestore = null
        if (uri != null && restore != null) {
            runCatching {
                val report = restore.toAuditSnapshot().toAuditJson().toString(2)
                context.contentResolver.openOutputStream(uri, "wt")
                    ?.bufferedWriter()
                    ?.use { it.write(report) }
                    ?: error("Unable to create audit report")
            }.onSuccess {
                Toast.makeText(context, "Restore audit report exported", Toast.LENGTH_SHORT).show()
            }.onFailure { throwable ->
                Toast.makeText(
                    context,
                    throwable.message ?: "Unable to export audit report",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val backupAuditLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val pending = pendingBackupAudit
        pendingBackupAudit = null
        if (uri != null && pending != null) {
            runCatching {
                val report = pending.first
                    .toBackupAuditSnapshot(pending.second)
                    .toBackupAuditJson()
                    .toString(2)
                context.contentResolver.openOutputStream(uri, "wt")
                    ?.bufferedWriter()
                    ?.use { it.write(report) }
                    ?: error("Unable to create backup audit report")
            }.onSuccess {
                Toast.makeText(context, "Backup audit report exported", Toast.LENGTH_SHORT).show()
            }.onFailure { throwable ->
                Toast.makeText(
                    context,
                    throwable.message ?: "Unable to export backup audit report",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("GitHub Backup") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "One recoverable Git mirror per repository. Manual and automatic backups update that same copy.",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            state.message?.let { message ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(message)
                            state.lastPublishedRepositoryUrl?.let { url ->
                                OutlinedButton(onClick = { uriHandler.openUri(url) }) {
                                    Text("Open restored repository")
                                }
                            }
                        }
                    }
                }
            }

            if (state.backupHealth.selectedCount > 0) {
                item { BackupHealthCard(summary = state.backupHealth) }
            }

            item { Text("GitHub", style = MaterialTheme.typography.titleLarge) }
            item {
                ConnectionCard(
                    title = "Repository access",
                    detail = if (state.githubConnected) {
                        "Connected with a personal access token"
                    } else {
                        "Not connected"
                    },
                    action = if (state.githubConnected) "Refresh repositories" else "Set up GitHub token",
                    enabled = !state.busy,
                    onClick = {
                        if (state.githubConnected) viewModel.refreshRepositories()
                        else onManageGithubToken()
                    },
                )
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
                    action = when {
                        !state.driveConnected -> "Connect Drive"
                        !driveSelected -> "Use Google Drive"
                        else -> "Refresh Drive access"
                    },
                    enabled = !state.busy,
                    onClick = {
                        when {
                            !state.driveConnected || driveSelected -> viewModel.connectDrive { pendingIntent ->
                                driveLauncher.launch(
                                    IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                                )
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
                        append(
                            state.documentTreeName
                                ?: if (state.documentTreeConfigured) "Configured" else "Not selected",
                        )
                        if (folderSelected) append(" • selected")
                    },
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

            if (state.busy) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Git mirror", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "The mirror preserves Git refs/history and bundles verified Git LFS objects plus supported wiki, release, and discussion metadata. Source snapshots and multi-copy retention are no longer created.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Repositories", style = MaterialTheme.typography.titleLarge)
                    Text("${state.repositories.count { it.selectedForBackup }} of ${state.repositories.size} selected")
                }
            }

            if (state.repositories.isEmpty()) {
                item { Text("Refresh GitHub to discover repositories.") }
            } else {
                item {
                    OutlinedTextField(
                        value = repositorySearchQuery,
                        onValueChange = { repositorySearchQuery = it },
                        label = { Text("Search repositories") },
                        supportingText = { Text("${visibleRepositories.size} shown") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.setAllRepositoriesSelected(true) },
                            enabled = !state.busy,
                        ) { Text("Select all") }
                        OutlinedButton(
                            onClick = { viewModel.setAllRepositoriesSelected(false) },
                            enabled = !state.busy,
                        ) { Text("Select none") }
                    }
                }
                if (visibleRepositories.isEmpty()) {
                    item { Text("No repositories match the current search.") }
                } else {
                    items(visibleRepositories, key = { it.githubId }) { repository ->
                        RepositoryRow(
                            repository = repository,
                            onSelectedChange = { selected ->
                                viewModel.setRepositorySelected(repository.githubId, selected)
                            },
                        )
                    }
                }
                item {
                    Button(
                        onClick = viewModel::backupSelectedRepositories,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Update selected mirrors")
                    }
                }
            }

            item { Text("Automatic mirror updates", style = MaterialTheme.typography.titleLarge) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Scheduled updates", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Updates the same mirror for each selected repository.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            state.scheduledRunStatus?.let { status ->
                                Spacer(Modifier.height(4.dp))
                                Text(status.displayText(), style = MaterialTheme.typography.bodySmall)
                            }
                            state.scheduledRunProgress?.let { progress ->
                                Spacer(Modifier.height(2.dp))
                                Text(progress.progressDisplayText(), style = MaterialTheme.typography.bodySmall)
                            }
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
                Text(
                    "Android schedules periodic work opportunistically. Automatic updates require unmetered connectivity and adequate battery/storage.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            item { Text("Recovery", style = MaterialTheme.typography.titleLarge) }
            item {
                Text(
                    "Import a mirror created by this app to validate it locally before restoring it to GitHub.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        restoreLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                            ),
                        )
                    },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Choose mirror archive to restore")
                }
            }
            if (state.restoredMirrors.isNotEmpty()) {
                item { Text("Validated mirrors", style = MaterialTheme.typography.titleMedium) }
                items(state.restoredMirrors, key = { it.id }) { restore ->
                    RestoredMirrorRow(
                        restore = restore,
                        busy = state.busy,
                        onPublish = { viewModel.beginGithubPublish(restore) },
                        onExportAudit = {
                            pendingAuditRestore = restore
                            restoreAuditLauncher.launch(restore.auditReportFileName())
                        },
                        onDelete = { viewModel.deleteRestoredMirror(restore.id) },
                    )
                }
            }

            if (state.githubPublishRestoreId != null) {
                item {
                    GithubPublishCard(
                        targetMode = state.githubRestoreTargetMode,
                        repositoryName = state.githubPublishRepositoryName,
                        existingRepository = state.githubPublishExistingRepository,
                        isPrivate = state.githubPublishPrivate,
                        busy = state.busy,
                        onTargetModeChange = viewModel::setGithubRestoreTargetMode,
                        onRepositoryNameChange = viewModel::setGithubPublishRepositoryName,
                        onExistingRepositoryChange = viewModel::setGithubPublishExistingRepository,
                        onPrivateChange = viewModel::setGithubPublishPrivate,
                        onPublish = viewModel::publishRestoreToGithub,
                        onCancel = viewModel::cancelGithubPublish,
                    )
                }
            }

            if (state.recentBackups.isNotEmpty()) {
                item { Text("Recent activity", style = MaterialTheme.typography.titleLarge) }
                items(state.recentBackups.take(10), key = { it.id }) { backup ->
                    val repository = state.repositories.firstOrNull { it.githubId == backup.repositoryId }
                    BackupRow(
                        backup = backup,
                        repository = repository,
                        reverifyBusy = reverificationState.busyBackupId == backup.id,
                        onReverify = if (backup.canReverifyBackup()) {
                            { reverificationViewModel.reverifyBackup(backup.id) }
                        } else {
                            null
                        },
                        onExportAudit = if (backup.status == BackupStatus.COMPLETED) {
                            {
                                val snapshot = backup.toBackupAuditSnapshot(repository)
                                pendingBackupAudit = backup to repository
                                backupAuditLauncher.launch(snapshot.backupAuditReportFileName())
                            }
                        } else {
                            null
                        },
                    )
                }
            }

            item { Text("Account", style = MaterialTheme.typography.titleLarge) }
            item {
                OutlinedButton(
                    onClick = onManageGithubToken,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Manage GitHub token")
                }
            }
        }
    }
}

@Composable
private fun GithubPublishCard(
    targetMode: GithubRestoreTargetMode,
    repositoryName: String,
    existingRepository: String,
    isPrivate: Boolean,
    busy: Boolean,
    onTargetModeChange: (GithubRestoreTargetMode) -> Unit,
    onRepositoryNameChange: (String) -> Unit,
    onExistingRepositoryChange: (String) -> Unit,
    onPrivateChange: (Boolean) -> Unit,
    onPublish: () -> Unit,
    onCancel: () -> Unit,
) {
    val creatingNew = targetMode == GithubRestoreTargetMode.NEW_REPOSITORY
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Restore mirror to GitHub", style = MaterialTheme.typography.titleMedium)
            Text(
                "Recovery only starts with a new or provably empty target. It restores supported LFS, Git refs, and release data without force-pushing an existing repository.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = creatingNew,
                    onClick = { onTargetModeChange(GithubRestoreTargetMode.NEW_REPOSITORY) },
                    label = { Text("Create new") },
                )
                FilterChip(
                    selected = !creatingNew,
                    onClick = { onTargetModeChange(GithubRestoreTargetMode.EXISTING_EMPTY_REPOSITORY) },
                    label = { Text("Use empty existing") },
                )
            }

            if (creatingNew) {
                OutlinedTextField(
                    value = repositoryName,
                    onValueChange = onRepositoryNameChange,
                    label = { Text("New repository name") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Private repository")
                        Text("Recommended for recovery tests.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = isPrivate, onCheckedChange = onPrivateChange, enabled = !busy)
                }
            } else {
                OutlinedTextField(
                    value = existingRepository,
                    onValueChange = onExistingRepositoryChange,
                    label = { Text("Existing owner/repository") },
                    supportingText = { Text("The target must be empty when recovery starts.") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val targetReady = if (creatingNew) repositoryName.isNotBlank() else existingRepository.isNotBlank()
            Button(onClick = onPublish, enabled = !busy && targetReady) {
                Text(if (creatingNew) "Create repository and restore" else "Verify empty target and restore")
            }
            OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
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
    busy: Boolean,
    onPublish: () -> Unit,
    onExportAudit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(restore.archiveName, style = MaterialTheme.typography.titleSmall)
            Text("${restore.refCount} refs • ${restore.lfsObjectCount} LFS objects")
            if (restore.releaseCount > 0) {
                Text(
                    "${restore.releaseCount} releases • ${restore.releaseAssetCount} assets",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("Validated and stored privately on this device.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onPublish, enabled = !busy) { Text("Restore to GitHub") }
            OutlinedButton(onClick = onExportAudit, enabled = !busy) { Text("Export audit JSON") }
            OutlinedButton(onClick = onDelete, enabled = !busy) { Text("Delete restored copy") }
        }
    }
}

@Composable
private fun BackupRow(
    backup: BackupEntity,
    repository: RepositoryEntity?,
    reverifyBusy: Boolean,
    onReverify: (() -> Unit)?,
    onExportAudit: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(backup.repositoryDisplayName(repository), style = MaterialTheme.typography.titleSmall)
            Text("Git mirror • ${backup.status.name}")
            Text(backup.originDisplayText(), style = MaterialTheme.typography.bodySmall)
            backup.storageProvider?.let { provider ->
                Text(
                    if (backup.remoteDeletedAtEpochMs == null) {
                        "Current object via ${provider.displayName()}"
                    } else {
                        "Superseded history entry"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            backup.lastReverifiedAtEpochMs?.let { timestamp ->
                val formatted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(timestamp))
                val label = when (backup.lastReverificationStatus) {
                    BackupReverificationStatus.VERIFIED -> "Re-verified $formatted"
                    BackupReverificationStatus.FAILED -> "Re-verification failed $formatted"
                    null -> "Re-verification checked $formatted"
                }
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (backup.lastReverificationStatus == BackupReverificationStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            backup.warningMessage?.let { warning ->
                Text(warning, style = MaterialTheme.typography.bodySmall)
            }
            backup.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            onReverify?.let {
                OutlinedButton(onClick = it, enabled = !reverifyBusy) {
                    Text(if (reverifyBusy) "Re-verifying…" else "Re-verify stored mirror")
                }
            }
            onExportAudit?.let {
                OutlinedButton(onClick = it, enabled = !reverifyBusy) { Text("Export audit JSON") }
            }
        }
    }
}

private fun ScheduledBackupRunStatus.displayText(): String {
    val timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(completedAtEpochMs))
    val detail = when (outcome) {
        ScheduledBackupRunOutcome.QUEUED ->
            "queued $repositoryCount repositor${if (repositoryCount == 1) "y" else "ies"}"
        ScheduledBackupRunOutcome.SKIPPED_NO_REPOSITORIES ->
            "skipped because no selected repositories were found"
        ScheduledBackupRunOutcome.SKIPPED_NOT_READY -> when (blockReason) {
            ScheduledBackupBlockReason.GITHUB_DISCONNECTED -> "skipped because GitHub is disconnected"
            ScheduledBackupBlockReason.DRIVE_DISCONNECTED -> "skipped because Google Drive needs authorization"
            ScheduledBackupBlockReason.DOCUMENT_TREE_MISSING -> "skipped because the backup folder is not configured"
            null -> "skipped because backup prerequisites were unavailable"
        }
    }
    val runSuffix = shortScheduledRunId(scheduledRunId)?.let { " • run $it" }.orEmpty()
    return "Last automatic check $timestamp: $detail$runSuffix."
}

private fun StorageDestination.displayName(): String = when (this) {
    StorageDestination.GOOGLE_DRIVE -> "Google Drive"
    StorageDestination.DOCUMENT_TREE -> "backup folder"
}

@Composable
private fun ConnectionCard(
    title: String,
    detail: String,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = onClick, enabled = enabled) { Text(action) }
        }
    }
}
