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

    fun documentTreeUri(): Uri? =
        preferences.getString(KEY_DOCUMENT_TREE_URI, null)?.let(Uri::parse)

    fun documentTreeDisplayName(): String? = documentTreeUri()?.let { uri ->
        DocumentFile.fromTreeUri(context, uri)?.name
    }

    fun isDocumentTreeConfigured(): Boolean {
        val uri = documentTreeUri() ?: return false
        val root = DocumentFile.fromTreeUri(context, uri) ?: return false
        return root.canRead() && root.canWrite()
    }

    fun persistDocumentTree(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        preferences.edit()
            .putString(KEY_DOCUMENT_TREE_URI, uri.toString())
            .remove(KEY_LEGACY_DESTINATION)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "storage-destination"
        const val KEY_DOCUMENT_TREE_URI = "document-tree-uri"
        const val KEY_LEGACY_DESTINATION = "destination"
    }
}
