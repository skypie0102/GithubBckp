package com.skypie0102.githubbckp.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * One worker = one repository backup attempt.
 *
 * Keep the scheduler separate so a large account does not become one giant job.
 */
class RepositoryBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        // TODO: Resolve repo input, create archive/mirror, upload, verify, persist status.
        return Result.failure()
    }
}
