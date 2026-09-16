package com.skypie0102.githubbckp.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.skypie0102.githubbckp.MainActivity
import com.skypie0102.githubbckp.backup.BackupType
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupProblemNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun notifyBackupFailure(
        repository: RepositoryEntity,
        type: BackupType,
        errorMessage: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        if (!repository.isAvailable || !repository.selectedForBackup) return
        if (!notificationsEnabled()) return

        val key = failureTimestampKey(repository.githubId, type)
        val lastNotifiedAt = preferences.getLong(key, 0L).takeIf { it > 0L }
        if (!shouldNotifyBackupFailure(lastNotifiedAt, nowEpochMs)) return

        ensureChannel()
        val detail = errorMessage
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(160)
        val contentText = buildString {
            append(repository.owner)
            append('/')
            append(repository.name)
            append(" • ")
            append(type.displayName())
            if (detail != null) {
                append(" • ")
                append(detail)
            }
        }

        NotificationManagerCompat.from(context).notify(
            failureNotificationId(repository.githubId),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Backup failed")
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(mainActivityPendingIntent(repository.githubId.hashCode()))
                .build(),
        )
        preferences.edit().putLong(key, nowEpochMs).apply()
    }

    fun notifyOverdueBackups(overdue: List<OverdueBackupRepository>) {
        val currentIds = overdue.mapTo(linkedSetOf()) { it.repositoryId.toString() }
        if (currentIds.isEmpty()) {
            preferences.edit().remove(KEY_OVERDUE_REPOSITORIES).apply()
            NotificationManagerCompat.from(context).cancel(OVERDUE_NOTIFICATION_ID)
            return
        }
        if (!notificationsEnabled()) return

        val previousIds = preferences.getStringSet(KEY_OVERDUE_REPOSITORIES, emptySet())
            ?.toSet()
            .orEmpty()
        val newlyOverdueIds = currentIds - previousIds
        preferences.edit().putStringSet(KEY_OVERDUE_REPOSITORIES, currentIds).apply()
        if (newlyOverdueIds.isEmpty()) return

        ensureChannel()
        val newlyOverdue = overdue.filter { it.repositoryId.toString() in newlyOverdueIds }
        val title = if (overdue.size == 1) "Backup overdue" else "${overdue.size} backups overdue"
        val summary = when {
            overdue.size == 1 -> overdue.single().fullName
            newlyOverdue.size == 1 -> "${newlyOverdue.single().fullName} is newly overdue"
            else -> "${newlyOverdue.size} repositories are newly overdue"
        }
        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle(title)
            .setSummaryText("Open GitHub Backup to review backup health")
        overdue.take(MAX_OVERDUE_LINES).forEach { style.addLine(it.fullName) }

        NotificationManagerCompat.from(context).notify(
            OVERDUE_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(summary)
                .setStyle(style)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(mainActivityPendingIntent(OVERDUE_NOTIFICATION_ID))
                .build(),
        )
    }

    private fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Backup health",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Backup failures and overdue backup health warnings"
            },
        )
    }

    private fun mainActivityPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun failureTimestampKey(repositoryId: Long, type: BackupType): String =
        "failure-$repositoryId-${type.name}"

    private fun failureNotificationId(repositoryId: Long): Int =
        FAILURE_NOTIFICATION_BASE xor repositoryId.hashCode()

    private fun BackupType.displayName(): String = when (this) {
        BackupType.SOURCE_ARCHIVE -> "source snapshot"
        BackupType.GIT_MIRROR -> "Git mirror"
    }

    private companion object {
        const val PREFERENCES_NAME = "backup-problem-notifications"
        const val CHANNEL_ID = "backup-health"
        const val KEY_OVERDUE_REPOSITORIES = "overdue-repositories"
        const val FAILURE_NOTIFICATION_BASE = 0x4B000000
        const val OVERDUE_NOTIFICATION_ID = 0x4B000001
        const val MAX_OVERDUE_LINES = 5
    }
}
