package org.beconscient.collector.events

import org.beconscient.collector.queue.EventDao
import org.beconscient.collector.util.Clock
import java.util.TimeZone

/** Writes `android.tz_change` markers: at pairing, at boot, and when the zone or clock changes. */
class TzMarkers(
    private val dao: EventDao,
    private val clock: Clock,
    private val defaultZone: () -> TimeZone = { TimeZone.getDefault() },
) {
    suspend fun record(reason: Events.TzReason, zoneId: String? = null) {
        val now = clock.now()
        val zone = zoneId?.let { TimeZone.getTimeZone(it) }?.takeIf { it.id == zoneId } ?: defaultZone()
        val offsetMin = zone.getOffset(now) / 60_000
        dao.insertIgnore(Events.tzChange(now, now, zone.id, offsetMin, reason))
    }
}
