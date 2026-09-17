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
import androidx.core.content.ContextCompat
import com.skypie0102.githubbckp.ui.HomeViewModel
import com.skypie0102.githubbckp.ui.RecoveryDrillHost
import com.skypie0102.githubbckp.ui.theme.GithubBckpTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
                RecoveryDrillHost(viewModel)
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
