package com.skypie0102.githubbckp.storage.drive

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.skypie0102.githubbckp.auth.SecureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class GoogleDriveAuthManager @Inject constructor(
    @ApplicationContext context: Context,
    private val secureStore: SecureStore,
) {
    private val client: AuthorizationClient = Identity.getAuthorizationClient(context)
    private val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
        .setOptOutIncludingGrantedScopes(true)
        .build()

    fun isAuthenticated(): Boolean = secureStore.get(KEY_CONNECTED) == "true"

    suspend fun beginAuthorization(): AuthorizationResult {
        val result = client.authorize(request).awaitValue()
        if (!result.hasResolution()) {
            remember(result)
        }
        return result
    }

    fun completeAuthorization(data: Intent): String {
        val result = client.getAuthorizationResultFromIntent(data)
        return remember(result)
    }

    suspend fun requireAccessToken(): String {
        val result = client.authorize(request).awaitValue()
        if (result.hasResolution()) {
            throw IOException("Google Drive authorization requires user interaction")
        }
        return remember(result)
    }

    private fun remember(result: AuthorizationResult): String {
        val token = result.accessToken
            ?: throw IOException("Google Drive did not return an access token")
        secureStore.put(KEY_CONNECTED, "true")
        return token
    }

    private suspend fun <T> Task<T>.awaitValue(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { throwable ->
            if (continuation.isActive) continuation.resumeWithException(throwable)
        }
    }

    private companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val KEY_CONNECTED = "drive.connected"
    }
}
