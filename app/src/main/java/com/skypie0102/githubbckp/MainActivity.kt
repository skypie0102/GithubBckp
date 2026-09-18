package com.skypie0102.githubbckp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.skypie0102.githubbckp.github.GithubAuthManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GithubBckpTheme {
                val showTokenSetup = remember {
                    mutableStateOf(!githubAuthManager.isAuthenticated())
                }
                if (showTokenSetup.value) {
                    GithubTokenSetupOverlay(
                        authManager = githubAuthManager,
                        canCancel = githubAuthManager.isAuthenticated(),
                        onCancel = { showTokenSetup.value = false },
                        onConnected = {
                            showTokenSetup.value = false
                            recreate()
                        },
                    )
                } else {
                    HomeScreen(
                        viewModel = viewModel,
                        onManageGithubToken = { showTokenSetup.value = true },
                    )
                }
            }
        }
    }
}
