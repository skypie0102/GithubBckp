package com.skypie0102.githubbckp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.skypie0102.githubbckp.github.GithubAuthManager
import com.skypie0102.githubbckp.ui.GithubTokenSetupOverlay
import com.skypie0102.githubbckp.ui.HomeScreen
import com.skypie0102.githubbckp.ui.HomeViewModel
import com.skypie0102.githubbckp.ui.RepositorySelectionScreen
import com.skypie0102.githubbckp.ui.SettingsScreen
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
                var page by remember {
                    mutableStateOf(
                        if (githubAuthManager.isAuthenticated()) {
                            AppPage.HOME
                        } else {
                            AppPage.SETTINGS
                        },
                    )
                }
                var showTokenSetup by remember { mutableStateOf(false) }

                if (page != AppPage.HOME) {
                    BackHandler { page = AppPage.HOME }
                }

                when (page) {
                    AppPage.HOME -> HomeScreen(
                        viewModel = viewModel,
                        onOpenRepositorySelection = { page = AppPage.REPOSITORIES },
                        onOpenSettings = { page = AppPage.SETTINGS },
                    )
                    AppPage.REPOSITORIES -> RepositorySelectionScreen(
                        viewModel = viewModel,
                        onBack = { page = AppPage.HOME },
                    )
                    AppPage.SETTINGS -> SettingsScreen(
                        viewModel = viewModel,
                        onBack = { page = AppPage.HOME },
                        onManageGithubToken = { showTokenSetup = true },
                    )
                }

                if (showTokenSetup) {
                    GithubTokenSetupOverlay(
                        authManager = githubAuthManager,
                        canCancel = true,
                        onCancel = { showTokenSetup = false },
                        onConnected = {
                            showTokenSetup = false
                            viewModel.refreshReadiness()
                            viewModel.refreshRepositories()
                        },
                    )
                }
            }
        }
    }
}

private enum class AppPage {
    HOME,
    REPOSITORIES,
    SETTINGS,
}
