package com.skypie0102.githubbckp.worker

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class BackupCadence(val repeatHours: Long) {
    DAILY(24),
    WEEKLY(24 * 7),
}

data class BackupScheduleSettings(
    val enabled: Boolean,
    val cadence: BackupCadence,
)

@Singleton
class BackupSchedulePreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun settings(): BackupScheduleSettings = BackupScheduleSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        cadence = runCatching {
            BackupCadence.valueOf(preferences.getString(KEY_CADENCE, BackupCadence.DAILY.name)!!)
        }.getOrDefault(BackupCadence.DAILY),
    )

    fun save(settings: BackupScheduleSettings) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_CADENCE, settings.cadence.name)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "mirror-schedule"
        const val KEY_ENABLED = "enabled"
        const val KEY_CADENCE = "cadence"
    }
}
