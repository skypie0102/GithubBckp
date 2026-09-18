package com.skypie0102.githubbckp.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.skypie0102.githubbckp.mirror.MirrorStage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveBackupNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isReady(): Boolean {
        ensureChannel()
        val permissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        val appNotificationsEnabled =
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        val channelEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)
            channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
        } else {
            true
        }
        return activeBackupNotificationsReady(
            permissionGranted = permissionGranted,
            appNotificationsEnabled = appNotificationsEnabled,
            channelEnabled = channelEnabled,
        )
    }

    fun foregroundInfo(
        workId: UUID,
        repositoryId: Long,
        owner: String,
        name: String,
        stage: MirrorStage? = null,
        progressPercent: Int? = null,
    ): ForegroundInfo {
        ensureChannel()
        val normalizedProgress = progressPercent?.coerceIn(0, 100)
        val stageText = when (stage) {
            null -> "Preparing…"
            MirrorStage.CHECKING_REMOTE -> "Checking GitHub…"
            MirrorStage.EXTRACTING -> "Extracting mirror…"
            MirrorStage.CLONING -> "Creating mirror…"
            MirrorStage.FETCHING -> "Fetching changes…"
            MirrorStage.DOWNLOADING_LFS -> "Downloading Git LFS objects…"
            MirrorStage.OPTIMIZING -> "Optimizing repository…"
            MirrorStage.PACKAGING -> "Compressing backup…"
            MirrorStage.VERIFYING -> "Verifying backup…"
            MirrorStage.COMMITTING -> normalizedProgress
                ?.let { "Saving backup… $it%" }
                ?: "Saving backup…"
            MirrorStage.CHECKING_RELEASE -> "Checking latest release…"
            MirrorStage.DOWNLOADING_RELEASE -> normalizedProgress
                ?.let { "Downloading release… $it%" }
                ?: "Downloading latest release…"
            MirrorStage.PACKAGING_RELEASE -> "Packaging latest release…"
            MirrorStage.VERIFYING_RELEASE -> "Verifying latest release…"
            MirrorStage.COMMITTING_RELEASE -> normalizedProgress
                ?.let { "Saving latest release… $it%" }
                ?: "Saving latest release…"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("$owner/$name")
            .setContentText(stageText)
            .setSubText("GitHub Backup")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(GROUP_KEY)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                WorkManager.getInstance(context).createCancelPendingIntent(workId),
            )

        if (normalizedProgress != null) {
            builder.setProgress(100, normalizedProgress, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        val notification = builder.build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId(repositoryId),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId(repositoryId), notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Active backups",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing GitHub mirror backup jobs"
            },
        )
    }

    private fun notificationId(repositoryId: Long): Int =
        FOREGROUND_NOTIFICATION_BASE xor repositoryId.hashCode()

    private companion object {
        const val CHANNEL_ID = "active-backups"
        const val GROUP_KEY = "githubbckp.active-mirrors"
        const val FOREGROUND_NOTIFICATION_BASE = 0x4A000000
    }
}

internal fun backupProgressPercent(completedBytes: Long, totalBytes: Long): Int? {
    if (completedBytes < 0L || totalBytes <= 0L) return null
    if (completedBytes >= totalBytes) return 100
    return ((completedBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
}


internal fun activeBackupNotificationsReady(
    permissionGranted: Boolean,
    appNotificationsEnabled: Boolean,
    channelEnabled: Boolean,
): Boolean = permissionGranted && appNotificationsEnabled && channelEnabled
