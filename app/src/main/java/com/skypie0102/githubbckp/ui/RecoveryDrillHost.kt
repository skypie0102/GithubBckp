package com.skypie0102.githubbckp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.skypie0102.githubbckp.backup.MirrorRestoreRecord
import com.skypie0102.githubbckp.backup.RecoveryDrillRecord
import com.skypie0102.githubbckp.backup.RecoveryDrillStatus
import com.skypie0102.githubbckp.backup.auditFileName
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryDrillHost(
    homeViewModel: HomeViewModel,
    drillViewModel: RecoveryDrillViewModel = hiltViewModel(),
) {
    val state by drillViewModel.state.collectAsState()
    var sheetOpen by remember { mutableStateOf(false) }
    var pendingAuditId by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val auditLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val drillId = pendingAuditId
        pendingAuditId = null
        if (uri != null && drillId != null) drillViewModel.exportAudit(drillId, uri)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HomeScreen(homeViewModel)
        Button(
            onClick = {
                drillViewModel.refresh()
                sheetOpen = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp),
        ) {
            Text("Recovery drill")
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = {
                if (!state.busy) {
                    drillViewModel.cancelDrill()
                    sheetOpen = false
                }
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Guided disaster-recovery drill", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "A drill publishes a validated mirror only to a private GitHub target, then independently checks the published Git refs, LFS availability/representative bytes, and release assets. Wiki and discussion data remain archival-only. The app never deletes the drill target automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                state.message?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
                state.lastRepositoryUrl?.let { url ->
                    OutlinedButton(onClick = { uriHandler.openUri(url) }, enabled = !state.busy) {
                        Text("Open last drill target")
                    }
                }

                val selectedRestore = state.selectedRestore
                if (selectedRestore == null) {
                    Text("Choose a validated mirror", style = MaterialTheme.typography.titleMedium)
                    if (state.restores.isEmpty()) {
                        Text(
                            "No imported mirrors are available yet. Import and validate a Git mirror from the main recovery section first, then reopen this drill panel.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        state.restores.forEach { restore ->
                            DrillRestoreCard(
                                restore = restore,
                                busy = state.busy,
                                onSelect = { drillViewModel.beginDrill(restore) },
                            )
                        }
                    }
                } else {
                    RecoveryDrillTargetCard(
                        restore = selectedRestore,
                        targetMode = state.targetMode,
                        repositoryName = state.repositoryName,
                        existingRepository = state.existingRepository,
                        busy = state.busy,
                        onTargetModeChange = drillViewModel::setTargetMode,
                        onRepositoryNameChange = drillViewModel::setRepositoryName,
                        onExistingRepositoryChange = drillViewModel::setExistingRepository,
                        onRun = drillViewModel::runDrill,
                        onCancel = drillViewModel::cancelDrill,
                    )
                }

                if (state.drills.isNotEmpty()) {
                    Text("Drill history", style = MaterialTheme.typography.titleMedium)
                    state.drills.take(10).forEach { drill ->
                        RecoveryDrillResultCard(
                            drill = drill,
                            busy = state.busy,
                            onOpenTarget = drill.repositoryUrl?.let { url -> { uriHandler.openUri(url) } },
                            onExportAudit = {
                                pendingAuditId = drill.id
                                auditLauncher.launch(drill.auditFileName())
                            },
                        )
                    }
                }

                if (state.busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Running recovery checks…")
                    }
                }
            }
        }
    }
}

@Composable
private fun DrillRestoreCard(
    restore: MirrorRestoreRecord,
    busy: Boolean,
    onSelect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(restore.archiveName, style = MaterialTheme.typography.titleSmall)
            Text(
                "Automatic: ${restore.refCount} Git refs • ${restore.lfsObjectCount} LFS objects • " +
                    "${restore.releaseCount} releases / ${restore.releaseAssetCount} assets",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Archival only: ${restore.wikiRefCount} wiki refs • ${restore.discussionRecordCount()} discussion records",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onSelect, enabled = !busy) { Text("Use for recovery drill") }
        }
    }
}

@Composable
private fun RecoveryDrillTargetCard(
    restore: MirrorRestoreRecord,
    targetMode: GithubRestoreTargetMode,
    repositoryName: String,
    existingRepository: String,
    busy: Boolean,
    onTargetModeChange: (GithubRestoreTargetMode) -> Unit,
    onRepositoryNameChange: (String) -> Unit,
    onExistingRepositoryChange: (String) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    val creatingNew = targetMode == GithubRestoreTargetMode.NEW_REPOSITORY
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Drill target", style = MaterialTheme.typography.titleMedium)
            Text(restore.archiveName, style = MaterialTheme.typography.bodySmall)
            Text(
                "Automatically republished: main Git refs, Git LFS, releases/assets. Preserved but not republished: wiki and discussion metadata.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "The target must be private and new or provably empty. Existing recovery safety checks remain in force, and the target is left in place for your inspection after the drill.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = creatingNew,
                    onClick = { onTargetModeChange(GithubRestoreTargetMode.NEW_REPOSITORY) },
                    label = { Text("Create private") },
                    enabled = !busy,
                )
                FilterChip(
                    selected = !creatingNew,
                    onClick = { onTargetModeChange(GithubRestoreTargetMode.EXISTING_EMPTY_REPOSITORY) },
                    label = { Text("Use private empty") },
                    enabled = !busy,
                )
            }
            if (creatingNew) {
                OutlinedTextField(
                    value = repositoryName,
                    onValueChange = onRepositoryNameChange,
                    label = { Text("Private drill repository name") },
                    supportingText = { Text("The app creates this repository as private.") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = existingRepository,
                    onValueChange = onExistingRepositoryChange,
                    label = { Text("Private empty owner/repository") },
                    supportingText = {
                        Text("The target must be private, advertise no Git refs, and contain no releases when the drill starts.")
                    },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = onRun,
                enabled = !busy && if (creatingNew) repositoryName.isNotBlank() else existingRepository.isNotBlank(),
            ) {
                Text(if (creatingNew) "Create private target and run drill" else "Verify target and run drill")
            }
            OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Choose another mirror") }
        }
    }
}

@Composable
private fun RecoveryDrillResultCard(
    drill: RecoveryDrillRecord,
    busy: Boolean,
    onOpenTarget: (() -> Unit)?,
    onExportAudit: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(drill.completedAtEpochMs))
            Text(
                "${drill.status.name} • $timestamp",
                style = MaterialTheme.typography.titleSmall,
                color = if (drill.status == RecoveryDrillStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(drill.repositoryFullName ?: drill.targetInput)
            if (drill.status == RecoveryDrillStatus.VERIFIED) {
                Text(
                    "Verified: ${drill.verifiedRefCount} Git refs • ${drill.lfsObjectCount} LFS available " +
                        "(${drill.lfsRepresentativeDownloads} re-downloaded) • " +
                        "${drill.releaseCount} releases / ${drill.releaseAssetCount} assets",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Archival only: ${drill.archivalWikiRefCount} wiki refs • " +
                        "${drill.archivalDiscussionRecordCount} discussion records",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(drill.message, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onOpenTarget?.let {
                    OutlinedButton(onClick = it, enabled = !busy) { Text("Open target") }
                }
                OutlinedButton(onClick = onExportAudit, enabled = !busy) { Text("Export audit JSON") }
            }
        }
    }
}

private fun MirrorRestoreRecord.discussionRecordCount(): Int =
    issueCount + pullRequestCount + issueCommentCount + reviewCommentCount + reviewCount
