package com.skypie0102.githubbckp.worker

import android.content.Context
import com.skypie0102.githubbckp.backup.BackupType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class BackupCadence(val repeatHours: Long) {
    DAILY(24L),
    WEEKLY(24L * 7L),
}

data class BackupScheduleSettings(
    val enabled: Boolean = false,
    val cadence: BackupCadence = BackupCadence.DAILY,
    val backupType: BackupType = BackupType.GIT_MIRROR,
)

@Singleton
class BackupSchedulePreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun settings(): BackupScheduleSettings = BackupScheduleSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        cadence = enumValueOrDefault(
            value = preferences.getString(KEY_CADENCE, null),
            default = BackupCadence.DAILY,
        ),
        backupType = enumValueOrDefault(
            value = preferences.getString(KEY_BACKUP_TYPE, null),
            default = BackupType.GIT_MIRROR,
        ),
    )

    fun save(settings: BackupScheduleSettings) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_CADENCE, settings.cadence.name)
            .putString(KEY_BACKUP_TYPE, settings.backupType.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        const val PREFERENCES_NAME = "backup-schedule"
        const val KEY_ENABLED = "enabled"
        const val KEY_CADENCE = "cadence"
        const val KEY_BACKUP_TYPE = "backup-type"
    }
}
