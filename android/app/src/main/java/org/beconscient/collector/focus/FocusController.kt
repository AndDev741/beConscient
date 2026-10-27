package org.beconscient.collector.focus

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.beconscient.collector.events.Events
import org.beconscient.collector.events.Events.FocusKind
import org.beconscient.collector.events.Events.FocusNext
import org.beconscient.collector.launcher.LauncherStore
import org.beconscient.collector.queue.EventDao
import org.beconscient.collector.util.Clock
import org.beconscient.collector.util.SerialQueue
import java.time.Instant
import java.time.ZoneId

/** A finished period, kept on the phone for the home screen's counts. */
data class FocusRecord(val kind: FocusKind, val startedAt: Long, val endedAt: Long)

/** Today's focus on this phone. */
data class FocusToday(val rounds: Int, val minutes: Long, val rests: Int)

/** An app sent back to the home screen, for the home screen to name. */
data class BlockedApp(val packageName: String, val at: Long)

/**
 * Owns the focus phase: it persists it, moves it forward, queues one
 * `android.focus` event per finished period and answers what is blocked.
 * Every caller (home screen, accessibility service, notification listener)
 * goes through here, so they all see the same phase.
 */
class FocusController(
    private val prefs: SharedPreferences,
    private val launcher: LauncherStore,
    private val apps: FocusApps,
    private val dao: EventDao,
    private val clock: Clock,
    private val writes: SerialQueue,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val _phase = MutableStateFlow(decodePhase(prefs.getString(KEY_PHASE, null)))
    val phase: StateFlow<FocusPhase> = _phase.asStateFlow()

    private val _history = MutableStateFlow(decodeHistory(prefs.getString(KEY_HISTORY, null)))
    val history: StateFlow<List<FocusRecord>> = _history.asStateFlow()

    private val _lastBlocked = MutableStateFlow<BlockedApp?>(null)
    val lastBlocked: StateFlow<BlockedApp?> = _lastBlocked.asStateFlow()

    private val heldKeys = HashSet<String>()
    private var heldFor = 0L
    private val calls = HashSet<String>()

    /** Moves the phase forward to now. Cheap: callers run it on every window change. */
    fun refresh(now: Long = clock.now()): FocusPhase = apply { FocusMachine.advance(it, now, ::lockingBlockAt) }

    fun startManual(minutes: Int): FocusPhase {
        refresh()
        return apply { FocusMachine.start(it, clock.now(), FocusKind.MANUAL, minutes * 60_000L) }
    }

    fun startRest(): FocusPhase {
        refresh()
        return apply { FocusMachine.start(it, clock.now(), FocusKind.REST, FocusMachine.REST_MS) }
    }

    fun decide(next: FocusNext): FocusPhase {
        refresh()
        return apply { FocusMachine.decide(it, clock.now(), next) }
    }

    /** The allowed list in use: the owner's, or the phone's defaults until they edit it. */
    fun allowed(): Set<String> = launcher.settings.value.focusAllowed ?: apps.defaults()

    /** True when [pkg] must not come to the front now. */
    fun blocks(pkg: String?): Boolean {
        if (pkg == null || !FocusMachine.locking(refresh())) return false
        if (pkg in apps.always() || pkg in allowed()) return false
        synchronized(this) { if (pkg in calls) return false }
        return pkg in apps.launchable()
    }

    /** [pkg] came to the front during a period and was sent home. A burst of window changes counts once. */
    fun onBlocked(pkg: String) {
        val now = clock.now()
        val last = _lastBlocked.value
        _lastBlocked.value = BlockedApp(pkg, now)
        if (last != null && last.packageName == pkg && now - last.at < SAME_OPEN_MS) return
        apply { Step(FocusMachine.blocked(it)) }
    }

    /**
     * How long to snooze a new notification, or 0 to let it through: only during
     * a period, only from apps that are blocked, never calls, alarms or ongoing ones.
     */
    fun holdFor(key: String, pkg: String, ongoing: Boolean, category: String?): Long {
        val phase = refresh()
        val ms = FocusMachine.holdingFor(phase, clock.now())
        if (ms < MIN_HOLD_MS || ongoing || category in PASS_CATEGORIES || !blocks(pkg)) return 0
        val period = (phase as FocusPhase.Locked).period.startedAt
        val first = synchronized(this) {
            if (heldFor != period) {
                heldKeys.clear()
                heldFor = period
            }
            heldKeys.add(key)
        }
        if (first) apply { Step(FocusMachine.held(it)) }
        return ms
    }

    /** An app with a call notification up stays usable, so a call through it can be answered. */
    fun callShown(pkg: String) = synchronized(this) { calls.add(pkg) }

    fun callGone(pkg: String) = synchronized(this) { calls.remove(pkg) }

    fun today(now: Long = clock.now()): FocusToday {
        val z = zone()
        val day = Instant.ofEpochMilli(now).atZone(z).toLocalDate()
        val mine = _history.value.filter { Instant.ofEpochMilli(it.startedAt).atZone(z).toLocalDate() == day }
        val rounds = mine.filter { it.kind != FocusKind.REST }
        return FocusToday(
            rounds = rounds.size,
            minutes = rounds.sumOf { (it.endedAt - it.startedAt).coerceAtLeast(0) } / 60_000,
            rests = mine.count { it.kind == FocusKind.REST },
        )
    }

    private fun lockingBlockAt(now: Long): LockingBlock? {
        val z = zone()
        val at = Instant.ofEpochMilli(now).atZone(z).toLocalDateTime()
        val block = launcher.settings.value.lockingBlockAt(at) ?: return null
        return LockingBlock(block.name, block.endAfter(at).atZone(z).toInstant().toEpochMilli())
    }

    private fun apply(change: (FocusPhase) -> FocusMachine.Step): FocusPhase = synchronized(this) {
        val step = change(_phase.value)
        if (step.finished.isNotEmpty()) record(step.finished)
        if (step.phase != _phase.value) {
            prefs.edit { putString(KEY_PHASE, encodePhase(step.phase)) }
            _phase.value = step.phase
        }
        step.phase
    }

    private fun record(done: List<Finished>) {
        val now = clock.now()
        val keepFrom = now - HISTORY_MS
        val history = (_history.value + done.map { FocusRecord(it.period.kind, it.period.startedAt, it.endedAt) })
            .filter { it.startedAt >= keepFrom }
        prefs.edit { putString(KEY_HISTORY, encodeHistory(history)) }
        _history.value = history
        for (f in done) {
            val p = f.period
            val row = Events.focus(
                now = now,
                startedAt = p.startedAt,
                endedAt = f.endedAt,
                kind = p.kind,
                plannedMs = p.plannedMs,
                next = f.next,
                cycle = p.cycle,
                blockedOpens = p.blockedOpens,
                heldNotifications = p.heldNotifications,
                block = p.block,
            )
            writes.submit { dao.insertIgnore(row) }
        }
    }

    companion object {
        private const val FILE = "focus"
        private const val KEY_PHASE = "phase"
        private const val KEY_HISTORY = "history"
        private const val HISTORY_MS = 8 * 86_400_000L
        private const val MIN_HOLD_MS = 5_000L
        private const val SAME_OPEN_MS = 3_000L
        private val PASS_CATEGORIES = setOf("call", "alarm", "navigation", "transport")

        fun android(
            context: Context,
            launcher: LauncherStore,
            dao: EventDao,
            clock: Clock,
            writes: SerialQueue,
        ) = FocusController(
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE),
            launcher,
            AndroidFocusApps(context.applicationContext),
            dao,
            clock,
            writes,
        )

        fun encodePhase(phase: FocusPhase): String = buildJsonObject {
            when (phase) {
                FocusPhase.Free -> put("phase", "free")
                is FocusPhase.Locked -> {
                    put("phase", "locked")
                    putPeriod(phase.period)
                }
                is FocusPhase.Deciding -> {
                    put("phase", "deciding")
                    putPeriod(phase.period)
                }
                is FocusPhase.Break -> {
                    put("phase", "break")
                    put("until", phase.until)
                    put("planned_ms", phase.plannedMs)
                    put("cycle", phase.cycle)
                }
            }
        }.toString()

        /** Anything unreadable is a free phone: a broken file must never lock the owner out. */
        fun decodePhase(text: String?): FocusPhase {
            val o = text?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return FocusPhase.Free
            return runCatching {
                when (o.string("phase")) {
                    "locked" -> FocusPhase.Locked(period(o))
                    "deciding" -> FocusPhase.Deciding(period(o))
                    "break" -> FocusPhase.Break(o.long("until")!!, o.long("planned_ms")!!, o.int("cycle") ?: 1)
                    else -> FocusPhase.Free
                }
            }.getOrDefault(FocusPhase.Free)
        }

        private fun JsonObjectBuilder.putPeriod(p: Period) {
            put("kind", p.kind.wire)
            put("started_at", p.startedAt)
            put("ends_at", p.endsAt)
            put("planned_ms", p.plannedMs)
            put("cycle", p.cycle)
            if (p.block != null) put("block", p.block)
            put("blocked_opens", p.blockedOpens)
            put("held_notifications", p.heldNotifications)
        }

        private fun period(o: JsonObject) = Period(
            kind = FocusKind.entries.first { it.wire == o.string("kind") },
            startedAt = o.long("started_at")!!,
            endsAt = o.long("ends_at")!!,
            plannedMs = o.long("planned_ms")!!,
            cycle = o.int("cycle") ?: 1,
            block = o.string("block"),
            blockedOpens = o.int("blocked_opens") ?: 0,
            heldNotifications = o.int("held_notifications") ?: 0,
        )

        private fun encodeHistory(records: List<FocusRecord>): String = buildJsonArray {
            for (r in records) {
                add(buildJsonObject {
                    put("kind", r.kind.wire)
                    put("started_at", r.startedAt)
                    put("ended_at", r.endedAt)
                })
            }
        }.toString()

        private fun decodeHistory(text: String?): List<FocusRecord> {
            val arr = text?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: return emptyList()
            return arr.mapNotNull { e ->
                runCatching {
                    val o = e.jsonObject
                    FocusRecord(FocusKind.entries.first { it.wire == o.string("kind") }, o.long("started_at")!!, o.long("ended_at")!!)
                }.getOrNull()
            }
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    }
}

private typealias Step = FocusMachine.Step
