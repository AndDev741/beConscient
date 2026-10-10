package org.beconscient.collector.usage

import org.beconscient.collector.events.Events
import org.beconscient.collector.queue.EventDao
import org.beconscient.collector.queue.Kv
import org.beconscient.collector.util.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads usage events from the persisted watermark (minus a small overlap) up to
 * now. Each window's events and the advanced watermark are written in one
 * transaction, so a crash never loses events nor skips a stretch. Re-read
 * events are dropped by the local natural-key index.
 */
class UsageReader(
    private val dao: EventDao,
    private val source: UsageSource,
    private val clock: Clock,
) {
    sealed interface Result {
        data object NoAccess : Result
        data class Read(val newEvents: Int, val watermark: Long) : Result
    }

    suspend fun read(): Result = lock.withLock {
        if (!source.hasAccess()) return Result.NoAccess
        val now = clock.now()
        var watermark = dao.long(Kv.USAGE_WATERMARK) ?: (now - BACKFILL_MS)
        var cursor = maxOf(0L, watermark - OVERLAP_MS)
        var added = 0
        while (cursor < now) {
            val end = minOf(cursor + WINDOW_MS, now)
            val raw = source.query(cursor, end)
            val rows = raw.mapNotNull { r ->
                val kind = UsageMapping.kindOf(r.eventType) ?: return@mapNotNull null
                Events.usage(now, kind, r.timeStamp, r.packageName, r.className, r.instanceId)
            }
            raw.maxOfOrNull { it.timeStamp }?.let { if (it > watermark) watermark = it }
            added += dao.insertUsage(rows, watermark, now)
            cursor = end
        }
        Result.Read(added, watermark)
    }

    companion object {
        /** First read after install or pairing reaches back about a week. */
        const val BACKFILL_MS = 7L * 24 * 60 * 60 * 1000
        /** Re-read a little before the watermark; late-written events land here. */
        const val OVERLAP_MS = 5L * 60 * 1000
        /** Query in slices so a week of back-fill never sits in memory at once. */
        const val WINDOW_MS = 6L * 60 * 60 * 1000

        private val lock = Mutex()
    }
}
