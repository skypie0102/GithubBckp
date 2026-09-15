package com.skypie0102.githubbckp.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StoragePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun destination(): StorageDestination = runCatching {
        StorageDestination.valueOf(
            preferences.getString(KEY_DESTINATION, StorageDestination.GOOGLE_DRIVE.name)
                ?: StorageDestination.GOOGLE_DRIVE.name,
        )
    }.getOrDefault(StorageDestination.GOOGLE_DRIVE)

    fun setDestination(destination: StorageDestination) {
        preferences.edit().putString(KEY_DESTINATION, destination.name).apply()
    }

    fun documentTreeUri(): Uri? =
        preferences.getString(KEY_DOCUMENT_TREE_URI, null)?.let(Uri::parse)

    fun documentTreeDisplayName(): String? = documentTreeUri()?.let { uri ->
        DocumentFile.fromTreeUri(context, uri)?.name
    }

    fun isDocumentTreeConfigured(): Boolean = documentTreeUri() != null

    fun persistDocumentTree(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        preferences.edit()
            .putString(KEY_DOCUMENT_TREE_URI, uri.toString())
            .putString(KEY_DESTINATION, StorageDestination.DOCUMENT_TREE.name)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "storage-destination"
        const val KEY_DESTINATION = "destination"
        const val KEY_DOCUMENT_TREE_URI = "document-tree-uri"
    }
}
