package org.beconscient.collector.notifications

import android.service.notification.NotificationListenerService
import androidx.room.withTransaction
import org.beconscient.collector.events.Events
import org.beconscient.collector.queue.EventDb
import org.beconscient.collector.queue.Kv
import org.beconscient.collector.queue.NotificationRefRow
import org.beconscient.collector.queue.SensorGapRow
import org.beconscient.collector.util.Clock
import org.beconscient.collector.util.Uuid7

/**
 * Turns notification listener callbacks into `android.notification_posted` and
 * `android.notification_removed` events. Only the package, times, flags,
 * importance and category are kept. The notification key maps to a random
 * local `ref`; the key, tag, title, text and sender never enter an event.
 */
class NotificationCollector(
    private val db: EventDb,
    private val clock: Clock,
) {
    private val dao = db.dao()

    /** What the listener extracts from a StatusBarNotification. [key] stays on the device. */
    data class Posted(
        val key: String,
        val packageName: String,
        val postTime: Long,
        val isGroupSummary: Boolean,
        val isOngoing: Boolean,
        val importance: Int?,
        val category: String?,
    )

    suspend fun onPosted(p: Posted) {
        val now = clock.now()
        val at = if (p.postTime > 0) p.postTime else now
        db.withTransaction {
            val existing = dao.notificationRef(p.key)
            if (existing == null) {
                val ref = Uuid7.random4()
                dao.putNotificationRef(NotificationRefRow(p.key, ref, p.packageName, at, now, now))
                dao.insertIgnore(posted(now, at, p, ref, isUpdate = false))
            } else {
                // Progress bars and media sessions re-post many times a minute: keep at most one update per window.
                val send = now - existing.lastUpdateSentAt >= UPDATE_THROTTLE_MS
                if (send) dao.insertIgnore(posted(now, at, p, existing.ref, isUpdate = true))
                dao.putNotificationRef(
                    existing.copy(seenAt = now, lastUpdateSentAt = if (send) now else existing.lastUpdateSentAt),
                )
            }
            stamp(now)
        }
    }

    suspend fun onRemoved(key: String, packageName: String, postTime: Long, reason: Int) {
        val now = clock.now()
        db.withTransaction {
            val row = dao.notificationRef(key)
            val ref = row?.ref ?: Uuid7.random4()
            val postedAt = row?.postedAt ?: postTime.takeIf { it > 0 } ?: now
            dao.insertIgnore(Events.notificationRemoved(now, now, packageName, ref, mapReason(reason), postedAt))
            if (row != null) dao.deleteNotificationRef(key)
            stamp(now)
        }
    }

    /**
     * On (re)connection: notifications still showing that we never saw are
     * recorded as posted at their post time; ones we knew that are gone are
     * recorded as removed with reason `other`. The blind stretch is kept as a gap.
     */
    suspend fun onConnected(active: List<Posted>) {
        val now = clock.now()
        db.withTransaction {
            val gapStart = dao.long(Kv.NOTIFICATIONS_DISCONNECTED_AT) ?: dao.long(Kv.NOTIFICATIONS_LAST_ALIVE_AT)
            val known = dao.allNotificationRefs().associateBy { it.key }
            val activeKeys = HashSet<String>()
            var recovered = 0
            for (p in active) {
                activeKeys += p.key
                if (p.key in known) continue
                val at = if (p.postTime > 0) p.postTime else now
                val ref = Uuid7.random4()
                dao.putNotificationRef(NotificationRefRow(p.key, ref, p.packageName, at, now, now))
                dao.insertIgnore(posted(now, at, p, ref, isUpdate = false))
                recovered++
            }
            for (row in known.values) {
                if (row.key in activeKeys) continue
                dao.insertIgnore(
                    Events.notificationRemoved(now, now, row.packageName, row.ref, Events.RemovalReason.OTHER, row.postedAt),
                )
                dao.deleteNotificationRef(row.key)
                recovered++
            }
            if (gapStart != null && gapStart < now) {
                dao.insertGap(SensorGapRow(sensor = SENSOR, fromMs = gapStart, toMs = now, recovered = recovered))
            }
            dao.deleteKv(Kv.NOTIFICATIONS_DISCONNECTED_AT)
            dao.putLong(Kv.NOTIFICATIONS_LAST_ALIVE_AT, now)
            if (recovered > 0) dao.putLong(Kv.NOTIFICATIONS_LAST_EVENT_AT, now)
        }
    }

    suspend fun onDisconnected() {
        dao.putLong(Kv.NOTIFICATIONS_DISCONNECTED_AT, clock.now())
    }

    /** Called by the periodic work while the listener is connected, to bound a gap after a crash. */
    suspend fun heartbeat() {
        dao.putLong(Kv.NOTIFICATIONS_LAST_ALIVE_AT, clock.now())
    }

    private suspend fun stamp(now: Long) {
        dao.putLong(Kv.NOTIFICATIONS_LAST_EVENT_AT, now)
        dao.putLong(Kv.NOTIFICATIONS_LAST_ALIVE_AT, now)
    }

    private fun posted(now: Long, at: Long, p: Posted, ref: String, isUpdate: Boolean) =
        Events.notificationPosted(now, at, p.packageName, ref, isUpdate, p.isGroupSummary, p.isOngoing, p.importance, p.category)

    companion object {
        const val SENSOR = "notifications"
        const val UPDATE_THROTTLE_MS = 60_000L

        fun mapReason(reason: Int): Events.RemovalReason = when (reason) {
            NotificationListenerService.REASON_CLICK -> Events.RemovalReason.CLICK
            NotificationListenerService.REASON_CANCEL -> Events.RemovalReason.CANCEL
            NotificationListenerService.REASON_CANCEL_ALL -> Events.RemovalReason.CANCEL_ALL
            NotificationListenerService.REASON_APP_CANCEL,
            NotificationListenerService.REASON_APP_CANCEL_ALL,
            -> Events.RemovalReason.APP_CANCEL
            else -> Events.RemovalReason.OTHER
        }
    }
}
