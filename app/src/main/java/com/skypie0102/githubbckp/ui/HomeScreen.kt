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
import com.skypie0102.githubbckp.backup.BackupStatus
import com.skypie0102.githubbckp.backup.BackupType
import com.skypie0102.githubbckp.backup.MirrorRestoreRecord
import com.skypie0102.githubbckp.backup.RetentionPreferences
import com.skypie0102.githubbckp.backup.auditReportFileName
import com.skypie0102.githubbckp.backup.backupAuditReportFileName
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
fun HomeScreen(viewModel: HomeViewModel) {
    val state by viewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var pendingAuditRestore by remember { mutableStateOf<MirrorRestoreRecord?>(null) }
    var pendingBackupAudit by remember { mutableStateOf<Pair<BackupEntity, RepositoryEntity?>?>(null) }
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
                    ?.use { writer -> writer.write(report) }
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
                    ?.use { writer -> writer.write(report) }
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
                    text = "Keep a recoverable copy of your repositories outside GitHub.",
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

            item {
                ConnectionCard(
                    title = "GitHub",
                    detail = when {
                        !state.githubConfigured -> "OAuth client ID not configured"
                        state.githubConnected && state.githubWorkflowPermission -> "Connected • full recovery permission"
                        state.githubConnected -> "Connected • update permissions for workflow recovery"
                        else -> "Not connected"
                    },
                    icon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                    action = when {
                        !state.githubConnected -> "Connect GitHub"
                        !state.githubWorkflowPermission -> "Update GitHub permissions"
                        else -> "Refresh repositories"
                    },
                    enabled = state.githubConfigured && !state.busy,
                    onClick = {
                        when {
                            !state.githubConnected || !state.githubWorkflowPermission -> viewModel.connectGithub()
                            else -> viewModel.refreshRepositories()
                        }
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
                        "Git mirror preserves Git refs/history and bundles every detected Git LFS object after size + SHA-256 verification. It also preserves supported wiki, release, and discussion metadata modules."
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
                            Text("Uses the repositories selected when the schedule runs.", style = MaterialTheme.typography.bodySmall)
                            state.scheduledRunStatus?.let { status ->
                                Spacer(Modifier.height(4.dp))
                                Text(status.displayText(), style = MaterialTheme.typography.bodySmall)
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

            item { Text("Retention", style = MaterialTheme.typography.titleLarge) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RetentionChip("Keep all", RetentionPreferences.KEEP_ALL, state.retentionKeepCount, viewModel::setRetentionKeepCount)
                    RetentionChip("Keep 3", 3, state.retentionKeepCount, viewModel::setRetentionKeepCount)
                    RetentionChip("Keep 5", 5, state.retentionKeepCount, viewModel::setRetentionKeepCount)
                    RetentionChip("Keep 10", 10, state.retentionKeepCount, viewModel::setRetentionKeepCount)
                }
            }
            item {
                Text(
                    "Retention is applied per repository and backup format after a newly verified backup completes. Deletion is routed through the storage provider that created each artifact. Backups created before provider metadata existed are never deleted automatically.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            item { Text("Restore Git mirror", style = MaterialTheme.typography.titleLarge) }
            item {
                Text(
                    "Choose a Git mirror ZIP created by this app. It is copied into private app storage, extracted with path-traversal protection, and Git refs, referenced LFS objects, plus bundled metadata modules are verified before the restored copy is kept.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        restoreLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
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
                item { Text("Recent backups", style = MaterialTheme.typography.titleLarge) }
                items(state.recentBackups.take(10), key = { it.id }) { backup ->
                    val repository = state.repositories.firstOrNull { it.githubId == backup.repositoryId }
                    BackupRow(
                        backup = backup,
                        repository = repository,
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
                "Recovery requires GitHub workflow permission and only starts with an empty Git/release target. It restores LFS objects, Git refs, then releases/assets; main refs are never force-pushed and GitHub read-only refs/pull/* are skipped.",
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
                        Text("Recommended for disaster recovery.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = isPrivate, onCheckedChange = onPrivateChange, enabled = !busy)
                }
            } else {
                OutlinedTextField(
                    value = existingRepository,
                    onValueChange = onExistingRepositoryChange,
                    label = { Text("Existing owner/repository") },
                    supportingText = {
                        Text("The target must exist, advertise no Git refs, and contain no GitHub releases when recovery starts.")
                    },
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
private fun RetentionChip(
    label: String,
    count: Int,
    selectedCount: Int,
    onSelected: (Int) -> Unit,
) {
    FilterChip(
        selected = count == selectedCount,
        onClick = { onSelected(count) },
        label = { Text(label) },
    )
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
            Text("${restore.refCount} refs • ${restore.referencedObjectsVerified} ref-tip objects • ${restore.lfsObjectCount} LFS objects")
            if (restore.detailsAvailable) {
                if (restore.wikiRefCount > 0) {
                    Text(
                        "Wiki: ${restore.wikiRefCount} refs • ${restore.wikiReferencedObjectsVerified} ref-tip objects",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (restore.releaseCount > 0) {
                    Text(
                        "Releases: ${restore.releaseCount} releases • ${restore.releaseAssetCount} assets",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val discussionCount = restore.issueCount + restore.pullRequestCount + restore.issueCommentCount +
                    restore.reviewCommentCount + restore.reviewCount
                if (discussionCount > 0) {
                    Text(
                        "Discussions: ${restore.issueCount} issues • ${restore.pullRequestCount} PRs • " +
                            "${restore.issueCommentCount} comments • ${restore.reviewCommentCount} review comments • " +
                            "${restore.reviewCount} reviews",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Text(
                    "Imported by an older app version; optional module counts were not recorded.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("Stored privately on this device.", style = MaterialTheme.typography.bodySmall)
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
    onExportAudit: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                repository?.let { "${it.owner}/${it.name}" } ?: "Repository #${backup.repositoryId}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text("${backup.type.name.replace('_', ' ')} • ${backup.status.name}")
            backup.storageProvider?.let { provider ->
                Text(
                    if (backup.remoteDeletedAtEpochMs == null) {
                        "Stored via ${provider.displayName()}"
                    } else {
                        "Remote artifact pruned from ${provider.displayName()}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            backup.warningMessage?.let { warning ->
                Text("Completeness: $warning", style = MaterialTheme.typography.bodySmall)
            }
            backup.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            onExportAudit?.let {
                OutlinedButton(onClick = it) { Text("Export audit JSON") }
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
            "skipped because no selected, available repositories were found"
        ScheduledBackupRunOutcome.SKIPPED_NOT_READY -> when (blockReason) {
            ScheduledBackupBlockReason.GITHUB_DISCONNECTED -> "skipped because GitHub is disconnected"
            ScheduledBackupBlockReason.DRIVE_DISCONNECTED -> "skipped because Google Drive needs authorization"
            ScheduledBackupBlockReason.DOCUMENT_TREE_MISSING -> "skipped because the backup folder is not configured"
            null -> "skipped because backup prerequisites were unavailable"
        }
    }
    return "Last automatic check $timestamp: $detail."
}

private fun StorageDestination.displayName(): String = when (this) {
    StorageDestination.GOOGLE_DRIVE -> "Google Drive"
    StorageDestination.DOCUMENT_TREE -> "backup folder"
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
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
