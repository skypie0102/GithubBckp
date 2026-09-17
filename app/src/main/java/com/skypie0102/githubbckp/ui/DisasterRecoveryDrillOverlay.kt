package com.skypie0102.githubbckp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.skypie0102.githubbckp.backup.DisasterRecoveryDrillResult
import com.skypie0102.githubbckp.backup.MirrorRestoreRecord
import com.skypie0102.githubbckp.backup.toDisasterRecoveryDrillPlan
import java.text.DateFormat
import java.util.Date

@Composable
fun DisasterRecoveryDrillOverlay(viewModel: HomeViewModel) {
    val state by viewModel.state.collectAsState()
    var open by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        OutlinedButton(
            onClick = { open = true },
            enabled = !state.busy,
            modifier = Modifier.padding(20.dp),
        ) {
            Text("Recovery drill")
        }
    }

    if (!open) return

    Dialog(
        onDismissRequest = {
            if (!state.busy) {
                if (state.githubPublishIsDrill) viewModel.cancelGithubPublish()
                open = false
            }
        },
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp),
        ) {
            LazyColumn(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("Guided disaster-recovery drill", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Use a validated mirror to rehearse recovery into a new or provably empty private GitHub repository. The drill never deletes the target and never bypasses the normal recovery safety checks.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (state.restoredMirrors.isEmpty()) {
                    item {
                        Text(
                            "Import and validate a Git mirror from the Home screen first. It will appear here once the private restored copy is ready.",
                        )
                    }
                } else {
                    item { Text("1. Choose a validated mirror", style = MaterialTheme.typography.titleMedium) }
                    items(state.restoredMirrors, key = { it.id }) { restore ->
                        RecoveryDrillRestoreCard(
                            restore = restore,
                            result = state.disasterRecoveryDrillResults[restore.id],
                            selected = state.githubPublishIsDrill && state.githubPublishRestoreId == restore.id,
                            busy = state.busy,
                            onSelect = { viewModel.beginDisasterRecoveryDrill(restore) },
                        )
                    }
                }

                val selectedRestore = state.restoredMirrors.firstOrNull {
                    state.githubPublishIsDrill && it.id == state.githubPublishRestoreId
                }
                if (selectedRestore != null) {
                    item {
                        RecoveryDrillTargetCard(
                            restore = selectedRestore,
                            state = state,
                            onTargetModeChange = viewModel::setGithubRestoreTargetMode,
                            onRepositoryNameChange = viewModel::setGithubPublishRepositoryName,
                            onExistingRepositoryChange = viewModel::setGithubPublishExistingRepository,
                            onRun = viewModel::publishRestoreToGithub,
                            onCancel = viewModel::cancelGithubPublish,
                        )
                    }
                }

                item {
                    OutlinedButton(
                        onClick = {
                            if (state.githubPublishIsDrill) viewModel.cancelGithubPublish()
                            open = false
                        },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryDrillRestoreCard(
    restore: MirrorRestoreRecord,
    result: DisasterRecoveryDrillResult?,
    selected: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
) {
    val plan = restore.toDisasterRecoveryDrillPlan()
    val uriHandler = LocalUriHandler.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(restore.archiveName, style = MaterialTheme.typography.titleSmall)
            Text(
                "Validated mirror: ${restore.refCount} refs • ${restore.lfsObjectCount} LFS objects • " +
                    "${restore.releaseCount} releases / ${restore.releaseAssetCount} assets",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Republished automatically: ${plan.automaticallyRepublished.joinToString().ifBlank { "Git refs/history" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (plan.archivalOnly.isEmpty()) {
                    "Archival-only modules: none recorded"
                } else {
                    "Archival-only modules: ${plan.archivalOnly.joinToString()}"
                },
                style = MaterialTheme.typography.bodySmall,
            )

            result?.let { previous ->
                val timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(previous.completedAtEpochMs))
                Text("Last drill passed $timestamp", style = MaterialTheme.typography.bodySmall)
                Text(
                    buildString {
                        append("Post-publication: ${previous.verifiedGitRefCount} Git refs matched")
                        if (previous.verifiedLfsObjectCount > 0) {
                            append(" • ${previous.verifiedLfsObjectCount} LFS objects available")
                            if (previous.verifiedLfsRepresentativeDownloadCount > 0) {
                                append(" (${previous.verifiedLfsRepresentativeDownloadCount} re-downloaded + SHA-256 checked)")
                            }
                        }
                        if (previous.verifiedReleaseCount > 0 || previous.verifiedReleaseAssetCount > 0) {
                            append(" • ${previous.verifiedReleaseCount} releases / ${previous.verifiedReleaseAssetCount} assets re-verified")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { uriHandler.openUri(previous.repositoryUrl) }, enabled = !busy) {
                    Text("Open last drill target")
                }
            }

            Button(onClick = onSelect, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (selected) "Selected for drill" else "Use this mirror for drill")
            }
        }
    }
}

@Composable
private fun RecoveryDrillTargetCard(
    restore: MirrorRestoreRecord,
    state: HomeUiState,
    onTargetModeChange: (GithubRestoreTargetMode) -> Unit,
    onRepositoryNameChange: (String) -> Unit,
    onExistingRepositoryChange: (String) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    val creatingNew = state.githubRestoreTargetMode == GithubRestoreTargetMode.NEW_REPOSITORY
    val plan = restore.toDisasterRecoveryDrillPlan()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("2. Choose a private drill target", style = MaterialTheme.typography.titleMedium)
            Text(
                "The normal recovery engine publishes ${plan.automaticallyRepublished.joinToString()}. After publication the drill independently compares every writable Git ref, checks every referenced LFS object is downloadable and re-hashes a bounded sample, then re-reads releases/assets and verifies asset SHA-256.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (plan.archivalOnly.isNotEmpty()) {
                Text(
                    "Preserved but not republished: ${plan.archivalOnly.joinToString()}.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = creatingNew,
                    onClick = { onTargetModeChange(GithubRestoreTargetMode.NEW_REPOSITORY) },
                    label = { Text("Create new private") },
                )
                FilterChip(
                    selected = !creatingNew,
                    onClick = { onTargetModeChange(GithubRestoreTargetMode.EXISTING_EMPTY_REPOSITORY) },
                    label = { Text("Use empty private") },
                )
            }

            if (creatingNew) {
                OutlinedTextField(
                    value = state.githubPublishRepositoryName,
                    onValueChange = onRepositoryNameChange,
                    label = { Text("New private repository name") },
                    supportingText = { Text("Drill repositories are always created private.") },
                    enabled = !state.busy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = state.githubPublishExistingRepository,
                    onValueChange = onExistingRepositoryChange,
                    label = { Text("Existing owner/repository") },
                    supportingText = {
                        Text("The repository must already be private and must be empty across Git refs and releases.")
                    },
                    enabled = !state.busy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                "The app will not delete this repository after the drill. Remove it yourself only after reviewing the result.",
                style = MaterialTheme.typography.bodySmall,
            )

            val targetReady = if (creatingNew) {
                state.githubPublishRepositoryName.isNotBlank()
            } else {
                state.githubPublishExistingRepository.isNotBlank()
            }
            Button(
                onClick = onRun,
                enabled = !state.busy && targetReady,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.busy) "Running drill…" else "Run recovery drill")
            }
            OutlinedButton(onClick = onCancel, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Choose another mirror")
            }
        }
    }
}
