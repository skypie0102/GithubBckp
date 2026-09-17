package com.skypie0102.githubbckp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.skypie0102.githubbckp.github.GithubAuthManager
import com.skypie0102.githubbckp.ui.DisasterRecoveryDrillOverlay
import com.skypie0102.githubbckp.ui.GithubTokenSetupOverlay
import com.skypie0102.githubbckp.ui.HomeScreen
import com.skypie0102.githubbckp.ui.HomeViewModel
import com.skypie0102.githubbckp.ui.theme.GithubBckpTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var githubAuthManager: GithubAuthManager

    private val viewModel: HomeViewModel by viewModels()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionOnce()
        enableEdgeToEdge()
        setContent {
            GithubBckpTheme {
                var showTokenSetup by remember {
                    mutableStateOf(!githubAuthManager.isAuthenticated())
                }
                if (showTokenSetup) {
                    GithubTokenSetupOverlay(
                        authManager = githubAuthManager,
                        canCancel = githubAuthManager.isAuthenticated(),
                        onCancel = { showTokenSetup = false },
                        onConnected = {
                            showTokenSetup = false
                            recreate()
                        },
                    )
                } else {
                    Box {
                        HomeScreen(viewModel)
                        OutlinedButton(
                            onClick = { showTokenSetup = true },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                        ) {
                            Text("GitHub token")
                        }
                        DisasterRecoveryDrillOverlay(viewModel)
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }

        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)) return
        preferences.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val PREFERENCES_NAME = "app-permissions"
        const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification-permission-requested"
    }
}
