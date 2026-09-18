package com.skypie0102.githubbckp.storage.drive

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.skypie0102.githubbckp.auth.SecureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class GoogleDriveAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStore: SecureStore,
) {
    // Do not initialize Google Play services unless Drive is actually used. This
    // keeps document-tree backups independent from the optional Drive connector.
    private val client: AuthorizationClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Identity.getAuthorizationClient(context)
    }
    private val request: AuthorizationRequest by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .setOptOutIncludingGrantedScopes(true)
            .build()
    }

    fun isAuthenticated(): Boolean = secureStore.get(KEY_CONNECTED) == "true"

    suspend fun beginAuthorization(): AuthorizationResult {
        val result = try {
            client.authorize(request).awaitValue()
        } catch (throwable: Throwable) {
            throw authorizationException(throwable)
        }
        if (!result.hasResolution()) {
            remember(result)
        }
        return result
    }

    fun completeAuthorization(data: Intent): String {
        val result = try {
            client.getAuthorizationResultFromIntent(data)
        } catch (throwable: Throwable) {
            throw authorizationException(throwable)
        }
        return remember(result)
    }

    suspend fun requireAccessToken(): String {
        val result = try {
            client.authorize(request).awaitValue()
        } catch (throwable: Throwable) {
            throw authorizationException(throwable)
        }
        if (result.hasResolution()) {
            secureStore.put(KEY_CONNECTED, "false")
            throw IOException("Google Drive authorization expired. Open the app and reconnect Drive.")
        }
        return remember(result)
    }

    fun oauthConfigurationHint(): String = buildString {
        append("package=")
        append(context.packageName)
        signingCertificateSha1()?.let { sha1 ->
            append(", SHA-1=")
            append(sha1)
        }
    }

    private fun remember(result: AuthorizationResult): String {
        val token = result.accessToken
            ?: throw IOException(
                "Google Drive did not return an access token. Check the Android OAuth client (${oauthConfigurationHint()}).",
            )
        secureStore.put(KEY_CONNECTED, "true")
        return token
    }

    private fun authorizationException(throwable: Throwable): IOException {
        val detail = if (throwable is ApiException) {
            "Google Play services status ${throwable.statusCode}: ${throwable.message.orEmpty()}"
        } else {
            throwable.message ?: throwable.javaClass.simpleName
        }
        return IOException(
            "Google Drive authorization failed. $detail. Check that the Android OAuth client matches ${oauthConfigurationHint()}.",
            throwable,
        )
    }

    @Suppress("DEPRECATION")
    private fun signingCertificateSha1(): String? = runCatching {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES,
            )
        }
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            packageInfo.signatures?.firstOrNull()
        } ?: return@runCatching null
        MessageDigest.getInstance("SHA-1")
            .digest(signature.toByteArray())
            .joinToString(":") { byte -> "%02X".format(byte) }
    }.getOrNull()

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
