package com.skypie0102.githubbckp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skypie0102.githubbckp.R
import com.skypie0102.githubbckp.data.local.RepositoryEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepositorySelectionScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    val visibleRepositories = filterRepositories(state.repositories, query)
    val selectedCount = state.repositories.count { it.isAvailable && it.selectedForBackup }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Backup repositories")
                        Text(
                            if (selectedCount == 1) {
                                "1 repository selected"
                            } else {
                                "$selectedCount repositories selected"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::refreshRepositories,
                        enabled = !state.busy,
                    ) {
                        Text(if (state.busy) "Checking…" else "Refresh")
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                Text(
                    "Choose which repositories belong to the backup set. This page only changes selection; it does not start a backup or update.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search repositories") },
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { viewModel.setAllRepositoriesSelected(true) },
                        enabled = !state.busy,
                    ) {
                        Text("Select all")
                    }
                    TextButton(
                        onClick = { viewModel.setAllRepositoriesSelected(false) },
                        enabled = !state.busy,
                    ) {
                        Text("Clear")
                    }
                }
            }

            if (visibleRepositories.isEmpty()) {
                item {
                    Text(
                        if (state.repositories.isEmpty()) {
                            "No repositories loaded yet. Tap Refresh."
                        } else {
                            "No repositories match your search."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }

            items(
                items = visibleRepositories,
                key = { it.githubId },
            ) { repository ->
                RepositorySelectionRow(
                    repository = repository,
                    onSelectedChanged = {
                        viewModel.setRepositorySelected(repository.githubId, it)
                    },
                )
            }
        }
    }
}

@Composable
private fun RepositorySelectionRow(
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
                .padding(horizontal = 10.dp, vertical = 7.dp),
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
                    color = if (repository.isAvailable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}
