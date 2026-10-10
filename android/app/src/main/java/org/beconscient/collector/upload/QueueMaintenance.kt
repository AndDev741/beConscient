package org.beconscient.collector.upload

import org.beconscient.collector.queue.EventDao
import org.beconscient.collector.util.Clock

/** Retention and the "resend since" recovery path. */
class QueueMaintenance(private val dao: EventDao, private val clock: Clock) {

    data class Purged(val acked: Int, val rejected: Int, val gaps: Int, val refs: Int)

    suspend fun purge(): Purged {
        val now = clock.now()
        return Purged(
            acked = dao.purgeAcked(now - ACKED_RETENTION_MS),
            rejected = dao.purgeRejected(now - LOCAL_RETENTION_MS),
            gaps = dao.purgeGaps(now - LOCAL_RETENTION_MS),
            refs = dao.purgeNotificationRefs(now - LOCAL_RETENTION_MS),
        )
    }

    suspend fun countResendable(since: Long): Int = dao.countAckedSince(since)

    /** Re-queues acknowledged events observed at or after [since]. The server answers `duplicate` for those it has. */
    suspend fun resendSince(since: Long): Int = dao.requeueAckedSince(since)

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val ACKED_RETENTION_MS = 14 * DAY_MS
        const val LOCAL_RETENTION_MS = 30 * DAY_MS
    }
}
