package com.skypie0102.githubbckp.worker

import androidx.work.Constraints
import androidx.work.NetworkType

object BackupScheduler {
    fun defaultConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()
}
