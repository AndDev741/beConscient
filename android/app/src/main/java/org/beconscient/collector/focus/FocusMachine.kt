package org.beconscient.collector.focus

import org.beconscient.collector.events.Events.FocusKind
import org.beconscient.collector.events.Events.FocusNext

/** One focus period. Times are epoch milliseconds. */
data class Period(
    val kind: FocusKind,
    val startedAt: Long,
    val endsAt: Long,
    val plannedMs: Long,
    /** 1 for the first period of a manual session, one more after each break. */
    val cycle: Int = 1,
    /** The locking block's name, for scheduled periods. */
    val block: String? = null,
    val blockedOpens: Int = 0,
    val heldNotifications: Int = 0,
)

/** Where the phone stands. */
sealed interface FocusPhase {
    /** Every app opens. */
    data object Free : FocusPhase

    /** Only allowed apps open, and notifications from the others wait. */
    data class Locked(val period: Period) : FocusPhase

    /** A manual period ended; the phone stays locked until the owner chooses what comes next. */
    data class Deciding(val period: Period) : FocusPhase

    /** Free use until [until], then another manual period of [plannedMs] starts. */
    data class Break(val until: Long, val plannedMs: Long, val cycle: Int) : FocusPhase
}

/** A period that is over, with what followed it. It becomes one `android.focus` event. */
data class Finished(val period: Period, val endedAt: Long, val next: FocusNext)

/** A locking block in force: its name and when this occurrence ends. */
data class LockingBlock(val name: String, val endsAt: Long)

/**
 * The focus rules, without clocks or Android: every function takes the current
 * phase and returns the next one with the periods that finished on the way.
 */
object FocusMachine {
    const val BREAK_MS = 5 * 60_000L
    const val REST_MS = 3 * 60_000L
    val MANUAL_MINUTES = listOf(15, 30, 45, 60, 90)

    data class Step(val phase: FocusPhase, val finished: List<Finished> = emptyList())

    /** The phone is locked to the allowed apps. */
    fun locking(phase: FocusPhase): Boolean = phase is FocusPhase.Locked || phase is FocusPhase.Deciding

    /** Milliseconds during which notifications from blocked apps are held, or 0. */
    fun holdingFor(phase: FocusPhase, now: Long): Long =
        if (phase is FocusPhase.Locked) (phase.period.endsAt - now).coerceAtLeast(0) else 0

    /**
     * Moves time forward to [now]: periods past their end finish, a break past its
     * end starts the next period, and a locking block in force locks a free phone.
     * Times come from the plan, not from when the phone noticed.
     */
    fun advance(phase: FocusPhase, now: Long, lockingBlock: (Long) -> LockingBlock?): Step {
        var p = phase
        val finished = mutableListOf<Finished>()
        while (true) {
            p = when (val cur = p) {
                is FocusPhase.Locked -> when {
                    now < cur.period.endsAt -> break
                    cur.period.kind == FocusKind.MANUAL -> FocusPhase.Deciding(cur.period)
                    else -> {
                        finished += Finished(cur.period, cur.period.endsAt, FocusNext.NONE)
                        FocusPhase.Free
                    }
                }
                is FocusPhase.Break -> {
                    if (now < cur.until) break
                    FocusPhase.Locked(
                        Period(FocusKind.MANUAL, cur.until, cur.until + cur.plannedMs, cur.plannedMs, cur.cycle + 1),
                    )
                }
                FocusPhase.Free -> {
                    val block = lockingBlock(now)?.takeIf { it.endsAt > now } ?: break
                    FocusPhase.Locked(Period(FocusKind.SCHEDULED, now, block.endsAt, block.endsAt - now, block = block.name))
                }
                is FocusPhase.Deciding -> break
            }
        }
        return Step(p, finished)
    }

    /** The owner starts a manual period or a rest. Only a free phone can start one. */
    fun start(phase: FocusPhase, now: Long, kind: FocusKind, plannedMs: Long): Step {
        if (phase != FocusPhase.Free || kind == FocusKind.SCHEDULED || plannedMs <= 0) return Step(phase)
        return Step(FocusPhase.Locked(Period(kind, now, now + plannedMs, plannedMs)))
    }

    /** The owner's choice after a manual period: a break and another round, or stop. */
    fun decide(phase: FocusPhase, now: Long, next: FocusNext): Step {
        if (phase !is FocusPhase.Deciding || next == FocusNext.NONE) return Step(phase)
        val p = phase.period
        val done = Finished(p, p.endsAt, next)
        return when (next) {
            FocusNext.BREAK -> Step(FocusPhase.Break(now + BREAK_MS, p.plannedMs, p.cycle), listOf(done))
            else -> Step(FocusPhase.Free, listOf(done))
        }
    }

    /** A blocked app came to the front during a period. */
    fun blocked(phase: FocusPhase): FocusPhase =
        if (phase is FocusPhase.Locked) phase.copy(period = phase.period.copy(blockedOpens = phase.period.blockedOpens + 1)) else phase

    /** A notification from a blocked app was held. */
    fun held(phase: FocusPhase): FocusPhase =
        if (phase is FocusPhase.Locked) {
            phase.copy(period = phase.period.copy(heldNotifications = phase.period.heldNotifications + 1))
        } else {
            phase
        }
}
