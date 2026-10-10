package org.beconscient.collector.health

import org.beconscient.collector.events.Events
import org.beconscient.collector.events.Events.Sensor
import org.beconscient.collector.events.Events.SensorState
import org.beconscient.collector.notifications.NotificationCollector
import org.beconscient.collector.queue.EventDao
import org.beconscient.collector.queue.EventRow
import org.beconscient.collector.queue.Kv
import org.beconscient.collector.setup.SensorProbe
import org.beconscient.collector.util.Clock

/** Writes the `health` record the phone sends every 15 minutes (contract/events.md). */
class HealthReporter(
    private val dao: EventDao,
    private val probe: SensorProbe,
    private val clock: Clock,
    private val clientVersion: String,
) {
    /** Sensor states, shared by the health record and the status screen. */
    suspend fun sensors(): Map<String, Sensor> {
        val now = clock.now()
        val usage = if (probe.usageAccess()) {
            Sensor(SensorState.OK, lastEventAt = dao.long(Kv.USAGE_WATERMARK))
        } else {
            Sensor(SensorState.STOPPED, "usage access not granted")
        }
        val notifLast = dao.long(Kv.NOTIFICATIONS_LAST_EVENT_AT)
        val notifications = when {
            !probe.notificationAccess() -> Sensor(SensorState.STOPPED, "notification access not granted", notifLast)
            !probe.listenerConnected() -> Sensor(SensorState.STOPPED, "listener not connected", notifLast)
            else -> Sensor(SensorState.OK, recentGapDetail(now), notifLast)
        }
        val shortsLast = dao.long(Kv.SHORTS_LAST_EVENT_AT)
        val shorts = when {
            !probe.accessibilityEnabled() -> Sensor(SensorState.DISABLED, "accessibility service off", shortsLast)
            !probe.shortsRunning() -> Sensor(SensorState.STOPPED, "accessibility service not running", shortsLast)
            else -> Sensor(SensorState.OK, null, shortsLast)
        }
        val battery = if (probe.batteryExempt()) {
            Sensor(SensorState.OK)
        } else {
            Sensor(SensorState.STOPPED, "battery optimization is on")
        }
        return linkedMapOf(
            "usage" to usage,
            "notifications" to notifications,
            "shorts" to shorts,
            "battery_exemption" to battery,
        )
    }

    private suspend fun recentGapDetail(now: Long): String? {
        val gap = dao.lastGap(NotificationCollector.SENSOR) ?: return null
        if (now - gap.toMs > GAP_REPORT_MS) return null
        return "gap ${gap.fromMs}-${gap.toMs} recovered ${gap.recovered}"
    }

    suspend fun record(): EventRow {
        val now = clock.now()
        val oldest = dao.oldestUnsentQueuedAt()
        val row = Events.health(
            now = now,
            clientVersion = clientVersion,
            sensors = sensors(),
            watermark = dao.long(Kv.USAGE_WATERMARK),
            queueDepth = dao.countUnsent(),
            oldestUnsentAgeMs = if (oldest == null) 0 else (now - oldest).coerceAtLeast(0),
            homePackage = probe.homePackage(),
        )
        dao.insertWithStamp(row, Kv.HEALTH_LAST_AT, now)
        return row
    }

    companion object {
        const val PERIOD_MS = 15L * 60 * 1000
        private const val GAP_REPORT_MS = 24L * 60 * 60 * 1000
    }
}
