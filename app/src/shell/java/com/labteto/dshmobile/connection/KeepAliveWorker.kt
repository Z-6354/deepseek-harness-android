package com.labteto.dshmobile.connection

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Tombstone for persisted WorkManager rows. Never schedules itself. */
class KeepAliveWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = Result.success()
}
