package com.skypie0102.githubbckp.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RetentionPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun keepCount(): Int = preferences.getInt(KEY_KEEP_COUNT, KEEP_ALL)

    fun setKeepCount(value: Int) {
        require(value == KEEP_ALL || value in ALLOWED_KEEP_COUNTS) { "Unsupported retention count: $value" }
        preferences.edit().putInt(KEY_KEEP_COUNT, value).apply()
    }

    companion object {
        const val KEEP_ALL = 0
        val ALLOWED_KEEP_COUNTS = setOf(3, 5, 10)
        private const val PREFERENCES_NAME = "backup-retention"
        private const val KEY_KEEP_COUNT = "keep-count"
    }
}
