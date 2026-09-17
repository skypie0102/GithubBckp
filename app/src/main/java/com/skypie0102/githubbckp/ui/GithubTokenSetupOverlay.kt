package com.skypie0102.githubbckp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.skypie0102.githubbckp.github.GithubAuthManager
import kotlinx.coroutines.launch

@Composable
fun GithubTokenSetupOverlay(
    authManager: GithubAuthManager,
    onConnected: (String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Connect GitHub", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Enter a GitHub personal access token. It is validated once, then encrypted locally with Android Keystore. Nothing is baked into the APK.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "The token needs access to every private repository you want to back up. Recovery also requires permission to create/update the target repository and its supported Git/LFS/release surfaces.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = {
                        token = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                    label = { Text("GitHub personal access token") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        error = null
                        scope.launch {
                            runCatching { authManager.connectPersonalAccessToken(token) }
                                .onSuccess { login ->
                                    token = ""
                                    onConnected(login)
                                }
                                .onFailure { throwable ->
                                    error = throwable.message ?: "Unable to validate GitHub token"
                                    busy = false
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && token.isNotBlank(),
                ) {
                    if (busy) {
                        CircularProgressIndicator()
                    } else {
                        Text("Save and connect")
                    }
                }
            }
        }
    }
}
