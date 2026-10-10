package org.beconscient.collector.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Collection runs every 15 minutes, at boot and when the app opens. There is no
 * unlock trigger: ACTION_USER_PRESENT cannot be received by a manifest receiver
 * and the app has no foreground service. Uploads run on their own, need a
 * network, and back off exponentially after a failure.
 */
object Scheduling {
    const val PERIODIC = "collect-periodic"
    const val COLLECT_NOW = "collect-now"
    const val UPLOAD = "upload"

    const val UPLOAD_BACKOFF_SECONDS = 30L

    fun periodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<CollectWorker>(15, TimeUnit.MINUTES).build()

    fun uploadRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, UPLOAD_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

    fun ensurePeriodic(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodicRequest())
    }

    fun collectNow(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(COLLECT_NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<CollectWorker>().build())
    }

    /** [now] replaces a waiting upload, skipping its backoff; used by "Upload now". */
    fun upload(context: Context, now: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UPLOAD,
            if (now) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            uploadRequest(),
        )
    }
}
