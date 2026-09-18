package com.skypie0102.githubbckp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.skypie0102.githubbckp.backup.MirrorStatus
import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.worker.BackupCadence
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onManageGithubToken: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var repositorySearch by remember { mutableStateOf("") }
    val visibleRepositories = remember(state.repositories, repositorySearch) {
        val query = repositorySearch.trim()
        if (query.isBlank()) state.repositories
        else state.repositories.filter {
            "${it.owner}/${it.name}".contains(query, ignoreCase = true)
        }
    }
    val mirrorByRepository = remember(state.mirrors) {
        state.mirrors.associateBy { it.repositoryId }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.chooseBackupFolder(uri)
        else viewModel.backupFolderSelectionCancelled()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("GitHub Backup") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "One local tar.gz mirror per selected GitHub repository.",
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

            item { BackupHealthCard(state.backupHealth) }

            item { Text("GitHub", style = MaterialTheme.typography.titleLarge) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            if (state.githubConnected) "Connected" else "Not connected",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "The app only reads repositories. It never writes back to GitHub.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = viewModel::refreshRepositories,
                                enabled = state.githubConnected && !state.busy,
                            ) {
                                Text("Refresh repositories")
                            }
                            OutlinedButton(
                                onClick = onManageGithubToken,
                                enabled = !state.busy,
                            ) {
                                Text("Manage token")
                            }
                        }
                    }
                }
            }

            item { Text("Local backup folder", style = MaterialTheme.typography.titleLarge) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            state.storageName ?: "No folder selected",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Final backups are stored in a GitHub Mirrors folder as owner--repository.tar.gz.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = { folderLauncher.launch(null) },
                            enabled = !state.busy,
                        ) {
                            Text(if (state.storageConfigured) "Change folder" else "Choose folder")
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("How updates work", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "First backup: clone the repository, then compress it to tar.gz and remove the loose temporary files.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Later backups: extract the existing tar.gz, fetch only new Git objects, prune deleted remote refs, hard-reset the working tree so changed/deleted files match GitHub, then recompress to the same single tar.gz mirror.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (state.busy) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
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
                item { Text("Refresh GitHub to load repositories.") }
            } else {
                item {
                    OutlinedTextField(
                        value = repositorySearch,
                        onValueChange = { repositorySearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Search repositories") },
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

                items(visibleRepositories, key = { it.githubId }) { repository ->
                    RepositoryRow(
                        repository = repository,
                        mirror = mirrorByRepository[repository.githubId],
                        onSelectedChange = { selected ->
                            viewModel.setRepositorySelected(repository.githubId, selected)
                        },
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
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Scheduled mirror updates", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Each scheduled run refreshes the same local tar.gz mirror.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = state.scheduleEnabled,
                                onCheckedChange = viewModel::setScheduleEnabled,
                                enabled = !state.busy,
                            )
                        }
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
                        Text(
                            "Android schedules periodic work opportunistically. Every active repository backup runs as foreground work and shows an ongoing device notification.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RepositoryRow(
    repository: RepositoryEntity,
    mirror: MirrorEntity?,
    onSelectedChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = repository.selectedForBackup,
                onCheckedChange = onSelectedChange,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("${repository.owner}/${repository.name}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${repository.defaultBranch} • ${if (repository.isPrivate) "private" else "public"}",
                    style = MaterialTheme.typography.bodySmall,
                )

                val status = mirror?.status ?: MirrorStatus.IDLE
                Text(
                    when (status) {
                        MirrorStatus.IDLE -> "No backup yet"
                        MirrorStatus.QUEUED -> "Queued"
                        MirrorStatus.RUNNING -> "Backup running"
                        MirrorStatus.COMPLETED -> "Current mirror ready"
                        MirrorStatus.FAILED -> "Latest update failed"
                        MirrorStatus.CANCELLED -> "Latest update was interrupted"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status == MirrorStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )

                mirror?.lastSuccessfulAtEpochMs?.let { timestamp ->
                    Text(
                        "Last success: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                mirror?.archiveName?.let { name ->
                    val size = mirror.archiveSizeBytes?.let(::formatBytes).orEmpty()
                    Text("$name $size".trim(), style = MaterialTheme.typography.bodySmall)
                }
                mirror?.lastError?.takeIf { status == MirrorStatus.FAILED || status == MirrorStatus.CANCELLED }?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
