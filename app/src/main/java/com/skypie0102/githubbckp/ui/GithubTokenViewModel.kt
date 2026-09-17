package com.skypie0102.githubbckp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skypie0102.githubbckp.github.GithubAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GithubTokenUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val login: String? = null,
    val hasWorkflowScope: Boolean = false,
    val connectionVersion: Long = 0L,
)

@HiltViewModel
class GithubTokenViewModel @Inject constructor(
    private val authManager: GithubAuthManager,
) : ViewModel() {
    private val _state = MutableStateFlow(
        GithubTokenUiState(hasWorkflowScope = authManager.hasWorkflowScopeCached()),
    )
    val state: StateFlow<GithubTokenUiState> = _state.asStateFlow()

    fun connect(token: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                val validation = authManager.connectWithPersonalAccessToken(token)
                _state.update { current ->
                    current.copy(
                        busy = false,
                        message = if (authManager.hasWorkflowScopeCached()) {
                            "GitHub token connected"
                        } else {
                            "GitHub token connected. Backups are available; use a classic token with workflow scope for complete recovery."
                        },
                        login = validation.login,
                        hasWorkflowScope = authManager.hasWorkflowScopeCached(),
                        connectionVersion = current.connectionVersion + 1L,
                    )
                }
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = throwable.message ?: "Unable to connect GitHub token",
                    )
                }
            }
        }
    }
}
