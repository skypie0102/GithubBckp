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
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val state by viewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    val driveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.completeDriveAuthorization(result.data)
        } else {
            viewModel.driveAuthorizationCancelled()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("GitHub Backup") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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

            item {
                ConnectionCard(
                    title = "Google Drive",
                    detail = if (state.driveConnected) "Connected" else "Not connected",
                    icon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                    action = if (state.driveConnected) "Refresh Drive access" else "Connect Drive",
                    enabled = !state.busy,
                    onClick = {
                        viewModel.connectDrive { pendingIntent ->
                            driveLauncher.launch(
                                IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                            )
                        }
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
                            Text("GitHub tokens are encrypted with Android Keystore. Google access tokens are refreshed through Google Play services.")
                        }
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
                    Text("${state.repositories.count { it.selectedForBackup }} selected")
                }
            }

            if (state.repositories.isEmpty()) {
                item {
                    Text("Connect GitHub and refresh to discover repositories.")
                }
            } else {
                items(state.repositories, key = { it.githubId }) { repository ->
                    RepositoryRow(
                        repository = repository,
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

            if (state.recentBackups.isNotEmpty()) {
                item {
                    Text("Recent backups", style = MaterialTheme.typography.titleLarge)
                }
                items(state.recentBackups.take(10), key = { it.id }) { backup ->
                    BackupRow(backup)
                }
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = repository.selectedForBackup,
                onCheckedChange = onSelectedChange,
            )
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
            OutlinedButton(onClick = onClick, enabled = enabled) {
                Text(action)
            }
        }
    }
}
