package org.beconscient.collector.launcher

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.beconscient.collector.events.Events
import org.beconscient.collector.queue.EventDao
import org.beconscient.collector.util.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/** What the pause screen shows before [packageName] opens. */
data class Pause(
    val packageName: String,
    val message: String,
    /** The block in force, or null outside any block. */
    val block: Block?,
    /** The block's alternative app, unless it is the app being opened. */
    val alternative: String?,
    /** Which pause of this app this is today, starting at 1. */
    val openCount: Int = 1,
    /** When this app's previous pause today appeared, or null. */
    val previousAt: Long? = null,
    /** How long opening the app waits. */
    val waitMs: Long = Pauses.WAIT_MS,
)

object Pauses {
    const val DEFAULT_MESSAGE = "Is this important?"
    const val WAIT_MS = 5_000L
    const val MAX_WAIT_MS = 60_000L

    /** 5 s for the first pause of an app on a day, doubling with each one after it, up to 60 s. */
    fun waitFor(openCount: Int): Long {
        val doublings = (openCount - 1).coerceIn(0, 10)
        return (WAIT_MS shl doublings).coerceAtMost(MAX_WAIT_MS)
    }

    /** Null when [packageName] opens straight away: only apps the owner chose to control get a pause. */
    fun decide(
        settings: LauncherSettings,
        packageName: String,
        at: LocalDateTime,
        today: PauseCounts.Today = PauseCounts.Today(0, null),
    ): Pause? {
        if (packageName !in settings.controlled) return null
        val block = settings.blockAt(at)
        val count = today.count + 1
        return Pause(
            packageName = packageName,
            message = block?.message?.trim()?.ifEmpty { null } ?: DEFAULT_MESSAGE,
            block = block,
            alternative = block?.alternative?.takeIf { it != packageName },
            openCount = count,
            previousAt = today.lastAt,
            waitMs = waitFor(count),
        )
    }
}

/**
 * How many pause screens each app got on the current local day, and when the
 * last one appeared. It stays on the phone; only the count goes into the event.
 */
class PauseCounts(private val prefs: SharedPreferences) {
    data class Today(val count: Int, val lastAt: Long?)

    @Synchronized
    fun today(pkg: String, day: LocalDate): Today {
        val o = read(day) ?: return Today(0, null)
        return Today((o.counts[pkg] ?: 0L).toInt(), o.last[pkg])
    }

    /** Records a pause of [pkg] shown at [at] on [day]. */
    @Synchronized
    fun shown(pkg: String, day: LocalDate, at: Long) {
        val cur = read(day) ?: Stored(day.toString(), emptyMap(), emptyMap())
        val next = cur.copy(counts = cur.counts + (pkg to (cur.counts[pkg] ?: 0L) + 1), last = cur.last + (pkg to at))
        prefs.edit {
            putString(KEY, buildJsonObject {
                put("day", next.day)
                putJsonObject("counts") { next.counts.forEach { (k, v) -> put(k, v) } }
                putJsonObject("last") { next.last.forEach { (k, v) -> put(k, v) } }
            }.toString())
        }
    }

    private data class Stored(val day: String, val counts: Map<String, Long>, val last: Map<String, Long>)

    /** The stored day, or null when it is another day or unreadable. */
    private fun read(day: LocalDate): Stored? {
        val o = prefs.getString(KEY, null)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        val stored = (o["day"] as? JsonPrimitive)?.contentOrNull
        if (stored != day.toString()) return null
        return Stored(stored, longs(o["counts"]), longs(o["last"]))
    }

    private fun longs(e: Any?): Map<String, Long> =
        (e as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }?.toMap().orEmpty()

    companion object {
        private const val KEY = "pause_counts"

        fun android(context: Context) = PauseCounts(context.getSharedPreferences("launcher", Context.MODE_PRIVATE))
    }
}

/** Queues one `android.pause` event per pause screen. */
class PauseLog(private val dao: EventDao, private val clock: Clock) {
    suspend fun record(pause: Pause, shownAt: Long, outcome: Events.PauseOutcome, intent: Events.PauseIntent? = null) {
        val now = clock.now()
        dao.insertIgnore(
            Events.pause(
                now = now,
                at = shownAt,
                packageName = pause.packageName,
                outcome = outcome,
                block = pause.block?.name,
                alternative = pause.alternative,
                waitMs = now - shownAt,
                intent = intent,
                requiredWaitMs = pause.waitMs,
                openCount = pause.openCount,
            ),
        )
    }
}
