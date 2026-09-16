package com.skypie0102.githubbckp.worker

import android.content.Context
import android.content.SharedPreferences
import com.skypie0102.githubbckp.backup.BackupType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class BackupCadence(val repeatHours: Long) {
    DAILY(24L),
    WEEKLY(24L * 7L),
}

data class BackupScheduleSettings(
    val enabled: Boolean = false,
    val cadence: BackupCadence = BackupCadence.DAILY,
    val backupType: BackupType = BackupType.GIT_MIRROR,
)

enum class ScheduledBackupRunOutcome {
    QUEUED,
    SKIPPED_NO_REPOSITORIES,
    SKIPPED_NOT_READY,
}

data class ScheduledBackupRunStatus(
    val completedAtEpochMs: Long,
    val outcome: ScheduledBackupRunOutcome,
    val repositoryCount: Int = 0,
    val blockReason: ScheduledBackupBlockReason? = null,
    val scheduledRunId: String? = null,
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

    fun runStatus(): ScheduledBackupRunStatus? {
        val completedAt = preferences.getLong(KEY_LAST_RUN_AT, 0L)
        if (completedAt <= 0L) return null
        val outcome = enumValueOrNull<ScheduledBackupRunOutcome>(
            preferences.getString(KEY_LAST_RUN_OUTCOME, null),
        ) ?: return null
        return ScheduledBackupRunStatus(
            completedAtEpochMs = completedAt,
            outcome = outcome,
            repositoryCount = preferences.getInt(KEY_LAST_RUN_REPOSITORY_COUNT, 0).coerceAtLeast(0),
            blockReason = enumValueOrNull<ScheduledBackupBlockReason>(
                preferences.getString(KEY_LAST_RUN_BLOCK_REASON, null),
            ),
            scheduledRunId = preferences.getString(KEY_LAST_RUN_ID, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
        )
    }

    fun saveRunStatus(status: ScheduledBackupRunStatus) {
        preferences.edit()
            .putLong(KEY_LAST_RUN_AT, status.completedAtEpochMs)
            .putString(KEY_LAST_RUN_OUTCOME, status.outcome.name)
            .putInt(KEY_LAST_RUN_REPOSITORY_COUNT, status.repositoryCount.coerceAtLeast(0))
            .apply {
                if (status.blockReason == null) {
                    remove(KEY_LAST_RUN_BLOCK_REASON)
                } else {
                    putString(KEY_LAST_RUN_BLOCK_REASON, status.blockReason.name)
                }
                if (status.scheduledRunId.isNullOrBlank()) {
                    remove(KEY_LAST_RUN_ID)
                } else {
                    putString(KEY_LAST_RUN_ID, status.scheduledRunId)
                }
            }
            .apply()
    }

    fun observeRunStatus(): Flow<ScheduledBackupRunStatus?> = callbackFlow {
        trySend(runStatus())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in RUN_STATUS_KEYS) trySend(runStatus())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        enumValueOrNull<T>(value) ?: default

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    private companion object {
        const val PREFERENCES_NAME = "backup-schedule"
        const val KEY_ENABLED = "enabled"
        const val KEY_CADENCE = "cadence"
        const val KEY_BACKUP_TYPE = "backup-type"
        const val KEY_LAST_RUN_AT = "last-run-at"
        const val KEY_LAST_RUN_OUTCOME = "last-run-outcome"
        const val KEY_LAST_RUN_REPOSITORY_COUNT = "last-run-repository-count"
        const val KEY_LAST_RUN_BLOCK_REASON = "last-run-block-reason"
        const val KEY_LAST_RUN_ID = "last-run-id"
        val RUN_STATUS_KEYS = setOf(
            KEY_LAST_RUN_AT,
            KEY_LAST_RUN_OUTCOME,
            KEY_LAST_RUN_REPOSITORY_COUNT,
            KEY_LAST_RUN_BLOCK_REASON,
            KEY_LAST_RUN_ID,
        )
    }
}
