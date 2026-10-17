package org.beconscient.collector.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.beconscient.collector.queue.EventRow
import org.beconscient.collector.util.Uuid7

/**
 * Builds every event this collector sends, and nothing else. Each builder writes
 * only the fields contract/events.md allows for its type, with string limits
 * applied here so nothing longer leaves the device.
 */
object Events {
    const val USAGE = "android.usage"
    const val TZ_CHANGE = "android.tz_change"
    const val NOTIFICATION_POSTED = "android.notification_posted"
    const val NOTIFICATION_REMOVED = "android.notification_removed"
    const val SHORTS = "android.shorts"
    const val PAUSE = "android.pause"
    const val HEALTH = "health"

    /** Usage kinds the contract accepts. */
    val USAGE_KINDS = setOf(
        "ACTIVITY_RESUMED", "ACTIVITY_PAUSED", "ACTIVITY_STOPPED",
        "SCREEN_INTERACTIVE", "SCREEN_NON_INTERACTIVE",
        "KEYGUARD_SHOWN", "KEYGUARD_HIDDEN",
        "DEVICE_SHUTDOWN", "DEVICE_STARTUP",
    )
    private val ACTIVITY_KINDS = setOf("ACTIVITY_RESUMED", "ACTIVITY_PAUSED", "ACTIVITY_STOPPED")

    enum class TzReason { INITIAL, TIMEZONE_CHANGED, TIME_SET, BOOT }

    enum class RemovalReason(val wire: String) { CLICK("click"), CANCEL("cancel"), CANCEL_ALL("cancel_all"), APP_CANCEL("app_cancel"), OTHER("other") }

    enum class ShortsState(val wire: String) { ENTER("enter"), EXIT("exit"), UNCLASSIFIED("unclassified") }

    enum class PauseOutcome(val wire: String) { OPENED("opened"), BACKED_OUT("backed_out"), ALTERNATIVE("alternative") }

    enum class SensorState(val wire: String) { OK("ok"), STOPPED("stopped"), DISABLED("disabled"), UNKNOWN("unknown") }

    data class Sensor(val state: SensorState, val detail: String? = null, val lastEventAt: Long? = null)

    // ---- builders -----------------------------------------------------------

    fun usage(
        now: Long,
        kind: String,
        at: Long,
        packageName: String?,
        className: String?,
        instance: Int?,
    ): EventRow {
        require(kind in USAGE_KINDS) { "unknown usage kind $kind" }
        val activity = kind in ACTIVITY_KINDS
        val pkg = if (activity) packageName?.take(255) else null
        val cls = if (activity) className?.take(255) else null
        val id = Uuid7.generate(now)
        val payload = point(id, USAGE, at) {
            put("kind", kind)
            if (pkg != null) put("package", pkg)
            if (cls != null) put("class", cls)
            if (activity && instance != null) put("instance", instance)
        }
        // Same natural key as the server: kind, at, package, class.
        val key = "u|$kind|$at|${pkg.orEmpty()}|${cls.orEmpty()}"
        return EventRow(id = id, type = USAGE, time = at, naturalKey = key, payload = payload, queuedAt = now)
    }

    fun tzChange(now: Long, at: Long, zone: String, offsetMin: Int, reason: TzReason): EventRow {
        val id = Uuid7.generate(now)
        val payload = point(id, TZ_CHANGE, at) {
            put("zone", zone.take(64))
            put("offset_min", offsetMin)
            put("reason", reason.name)
        }
        return EventRow(id = id, type = TZ_CHANGE, time = at, payload = payload, queuedAt = now)
    }

    fun notificationPosted(
        now: Long,
        at: Long,
        packageName: String,
        ref: String,
        isUpdate: Boolean,
        isGroupSummary: Boolean,
        isOngoing: Boolean,
        importance: Int?,
        category: String?,
    ): EventRow {
        val id = Uuid7.generate(now)
        val payload = point(id, NOTIFICATION_POSTED, at) {
            put("package", packageName.take(255))
            put("ref", ref)
            put("is_update", isUpdate)
            put("is_group_summary", isGroupSummary)
            put("is_ongoing", isOngoing)
            if (importance != null && importance in -1..5) put("importance", importance)
            if (!category.isNullOrEmpty()) put("category", category.take(32))
        }
        return EventRow(id = id, type = NOTIFICATION_POSTED, time = at, payload = payload, queuedAt = now)
    }

    fun notificationRemoved(
        now: Long,
        at: Long,
        packageName: String,
        ref: String,
        reason: RemovalReason,
        postedAt: Long,
    ): EventRow {
        val id = Uuid7.generate(now)
        val payload = point(id, NOTIFICATION_REMOVED, at) {
            put("package", packageName.take(255))
            put("ref", ref)
            put("reason", reason.wire)
            put("posted_at", postedAt)
        }
        return EventRow(id = id, type = NOTIFICATION_REMOVED, time = at, payload = payload, queuedAt = now)
    }

    fun shorts(
        now: Long,
        at: Long,
        state: ShortsState,
        youtubeVersion: String?,
        youtubeVersionCode: Long?,
        rulesVersion: Int,
    ): EventRow {
        val id = Uuid7.generate(now)
        val payload = point(id, SHORTS, at) {
            put("state", state.wire)
            if (youtubeVersion != null) put("youtube_version", youtubeVersion.take(64))
            if (youtubeVersionCode != null) put("youtube_version_code", youtubeVersionCode)
            put("rules_version", rulesVersion)
        }
        return EventRow(id = id, type = SHORTS, time = at, payload = payload, queuedAt = now)
    }

    /** One launcher pause. The block's message never leaves the phone. */
    fun pause(
        now: Long,
        at: Long,
        packageName: String,
        outcome: PauseOutcome,
        block: String?,
        alternative: String?,
        waitMs: Long,
    ): EventRow {
        val id = Uuid7.generate(now)
        val payload = point(id, PAUSE, at) {
            put("package", packageName.take(255))
            put("outcome", outcome.wire)
            if (!block.isNullOrEmpty()) put("block", block.take(60))
            if (!alternative.isNullOrEmpty()) put("alternative", alternative.take(255))
            put("wait_ms", waitMs.coerceAtLeast(0))
        }
        return EventRow(id = id, type = PAUSE, time = at, payload = payload, queuedAt = now)
    }

    fun health(
        now: Long,
        clientVersion: String,
        sensors: Map<String, Sensor>,
        watermark: Long?,
        queueDepth: Int,
        oldestUnsentAgeMs: Long,
        homePackage: String?,
    ): EventRow {
        val id = Uuid7.generate(now)
        val payload = point(id, HEALTH, now) {
            put("client_version", clientVersion.take(32))
            putJsonObject("sensors") {
                for ((name, s) in sensors) {
                    putJsonObject(name) {
                        put("state", s.state.wire)
                        if (s.detail != null) put("detail", s.detail.take(120))
                        if (s.lastEventAt != null) put("last_event_at", s.lastEventAt)
                    }
                }
            }
            if (watermark != null) put("watermark", watermark)
            put("queue_depth", queueDepth)
            put("oldest_unsent_age_ms", oldestUnsentAgeMs)
            if (homePackage != null) put("home_package", homePackage.take(255))
        }
        return EventRow(id = id, type = HEALTH, time = now, payload = payload, queuedAt = now)
    }

    private inline fun point(id: String, type: String, at: Long, fields: JsonObjectBuilder.() -> Unit): String {
        val obj: JsonObject = buildJsonObject {
            put("id", id)
            put("type", type)
            put("at", at)
            fields()
        }
        return obj.toString()
    }
}
