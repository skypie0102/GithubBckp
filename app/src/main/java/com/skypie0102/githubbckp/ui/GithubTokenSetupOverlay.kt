package com.skypie0102.githubbckp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.skypie0102.githubbckp.github.GithubAuthManager
import kotlinx.coroutines.launch

@Composable
fun GithubTokenSetupOverlay(
    authManager: GithubAuthManager,
    canCancel: Boolean = false,
    onCancel: () -> Unit = {},
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
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (canCancel) "Replace GitHub token" else "Connect GitHub",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "GithubBckp only reads repositories. The token is validated first, then encrypted locally with Android Keystore.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    "Recommended: fine-grained personal access token",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Repository access: select every repository you want to back up.\n" +
                        "Repository permissions:\n" +
                        "• Contents — Read-only\n" +
                        "• Metadata — Read-only",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "No write, administration, Actions, issues, pull requests, or release permissions are required.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Text(
                    "Classic-token fallback",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "If a fine-grained token cannot cover the repositories you need, a classic PAT can use the repo scope. " +
                        "That scope is broader than this app needs, so fine-grained read-only access is preferred.",
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
                        Text(if (canCancel) "Validate and replace" else "Save and connect")
                    }
                }
                if (canCancel) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
