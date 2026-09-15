package com.skypie0102.githubbckp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text("GitHub Backup") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Keep a recoverable copy of your repositories outside GitHub.",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "The project shell is ready. Connect GitHub and Google Drive adapters next, then wire the backup engine to scheduled jobs.",
                style = MaterialTheme.typography.bodyLarge,
            )

            ConnectionCard(
                title = "GitHub",
                detail = "Not connected",
                icon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                action = "Connect GitHub",
            )
            ConnectionCard(
                title = "Google Drive",
                detail = "Not connected",
                icon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                action = "Connect Drive",
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.Security, contentDescription = null)
                    Column {
                        Text("Security-first scaffold", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("No OAuth client secrets or tokens are committed to the repository.")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    title: String,
    detail: String,
    icon: @Composable () -> Unit,
    action: String,
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
            Button(onClick = { /* TODO: Launch OAuth flow. */ }) {
                Text(action)
            }
        }
    }
}
