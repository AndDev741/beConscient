package org.beconscient.collector.upload

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.beconscient.collector.AppGraph
import org.beconscient.collector.notifications.NotificationListener
import org.beconscient.collector.queue.Kv

/** Reads usage, writes the health record, purges old rows, then asks for an upload. */
class CollectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        // Each step runs even if an earlier one fails: a usage read that throws
        // must not also cost the health record that would report it.
        step("usage") { graph.usageReader.read() }
        // A listener dropped while access is still granted is bound again within one run
        step("rebind") {
            if (!NotificationListener.connected && graph.probe.notificationAccess()) {
                NotificationListener.rebind(applicationContext)
            }
        }
        step("heartbeat") { if (NotificationListener.connected) graph.notificationCollector.heartbeat() }
        step("health") {
            val lastHealth = graph.db.dao().long(Kv.HEALTH_LAST_AT) ?: 0L
            if (graph.clock.now() - lastHealth >= MIN_HEALTH_SPACING_MS) graph.health.record()
        }
        step("purge") { graph.maintenance.purge() }
        Scheduling.upload(applicationContext)
        return Result.success()
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("CollectWorker", "$name failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        /** Extra runs (app open, boot) do not crowd the 15-minute health records. */
        const val MIN_HEALTH_SPACING_MS = 5L * 60 * 1000
    }
}

/** Sends frozen batches until the queue is empty; any failure retries with exponential backoff. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        return when (graph.uploader.uploadAll()) {
            is Uploader.Outcome.Retry -> Result.retry()
            else -> Result.success()
        }
    }
}
