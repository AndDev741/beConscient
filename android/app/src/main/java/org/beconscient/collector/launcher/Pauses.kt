package org.beconscient.collector.launcher

import org.beconscient.collector.events.Events
import org.beconscient.collector.queue.EventDao
import org.beconscient.collector.util.Clock
import java.time.LocalDateTime

/** What the pause screen shows before [packageName] opens. */
data class Pause(
    val packageName: String,
    val message: String,
    /** The block in force, or null outside any block. */
    val block: Block?,
    /** The block's alternative app, unless it is the app being opened. */
    val alternative: String?,
)

object Pauses {
    const val DEFAULT_MESSAGE = "Is this important?"
    const val WAIT_MS = 5_000L

    /** Null when [packageName] opens straight away: only apps the owner chose to control get a pause. */
    fun decide(settings: LauncherSettings, packageName: String, at: LocalDateTime): Pause? {
        if (packageName !in settings.controlled) return null
        val block = settings.blockAt(at)
        return Pause(
            packageName = packageName,
            message = block?.message?.trim()?.ifEmpty { null } ?: DEFAULT_MESSAGE,
            block = block,
            alternative = block?.alternative?.takeIf { it != packageName },
        )
    }
}

/** Queues one `android.pause` event per pause screen. */
class PauseLog(private val dao: EventDao, private val clock: Clock) {
    suspend fun record(pause: Pause, shownAt: Long, outcome: Events.PauseOutcome) {
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
            ),
        )
    }
}
