package com.skypie0102.githubbckp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun GithubTokenOverlay(
    homeViewModel: HomeViewModel,
    tokenViewModel: GithubTokenViewModel = hiltViewModel(),
) {
    val homeState by homeViewModel.state.collectAsState()
    val tokenState by tokenViewModel.state.collectAsState()
    var showDialog by remember { mutableStateOf(!homeState.githubConnected) }
    var token by remember { mutableStateOf("") }

    LaunchedEffect(homeState.githubConnected) {
        if (!homeState.githubConnected) showDialog = true
    }
    LaunchedEffect(tokenState.connectionVersion) {
        if (tokenState.connectionVersion > 0L) {
            token = ""
            showDialog = false
            homeViewModel.refreshRepositories()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (homeState.githubConnected && !showDialog) {
            OutlinedButton(
                onClick = { showDialog = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 72.dp, end = 16.dp),
            ) {
                Text("GitHub token")
            }
        }
    }

    if (!showDialog) return

    AlertDialog(
        onDismissRequest = {
            if (homeState.githubConnected && !tokenState.busy) showDialog = false
        },
        title = {
            Text(if (homeState.githubConnected) "Replace GitHub token" else "Connect GitHub")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter a GitHub personal access token. It is validated with GitHub first, then encrypted in Android Keystore-backed app storage. It is never baked into the APK.",
                )
                Text(
                    "For the simplest full backup/recovery setup, use a classic PAT with repo and workflow scopes.",
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("GitHub personal access token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !tokenState.busy,
                )
                tokenState.message?.let { Text(it) }
            }
        },
        confirmButton = {
            Button(
                onClick = { tokenViewModel.connect(token) },
                enabled = token.isNotBlank() && !tokenState.busy,
            ) {
                Text(if (tokenState.busy) "Checking…" else "Save token")
            }
        },
        dismissButton = {
            if (homeState.githubConnected) {
                TextButton(
                    onClick = { showDialog = false },
                    enabled = !tokenState.busy,
                ) {
                    Text("Cancel")
                }
            }
        },
    )
}
