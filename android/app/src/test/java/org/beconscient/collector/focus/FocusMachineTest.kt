package org.beconscient.collector.focus

import org.beconscient.collector.events.Events.FocusKind
import org.beconscient.collector.events.Events.FocusNext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusMachineTest {
    private val t0 = 1_760_090_400_000L
    private val min = 60_000L
    private val none: (Long) -> LockingBlock? = { null }

    private fun manual(minutes: Long = 30) = FocusMachine.start(FocusPhase.Free, t0, FocusKind.MANUAL, minutes * min).phase

    @Test
    fun `a manual period locks until its end and then waits for the owner`() {
        val locked = manual()
        assertTrue(FocusMachine.locking(locked))
        assertEquals(locked, FocusMachine.advance(locked, t0 + 29 * min, none).phase)
        val step = FocusMachine.advance(locked, t0 + 31 * min, none)
        assertTrue(step.phase is FocusPhase.Deciding)
        assertTrue("nothing is sent before the owner chooses", step.finished.isEmpty())
        assertTrue("still locked while deciding", FocusMachine.locking(step.phase))
        assertEquals("deciding never moves on by itself", step.phase, FocusMachine.advance(step.phase, t0 + 300 * min, none).phase)
    }

    @Test
    fun `a break opens the phone for five minutes and then starts the next round at its planned time`() {
        val deciding = FocusMachine.advance(manual(), t0 + 31 * min, none).phase
        val brk = FocusMachine.decide(deciding, t0 + 31 * min, FocusNext.BREAK)
        val done = brk.finished.single()
        assertEquals(FocusNext.BREAK, done.next)
        assertEquals("a period ends at its planned end, not when the owner chose", t0 + 30 * min, done.endedAt)
        val b = brk.phase as FocusPhase.Break
        assertFalse(FocusMachine.locking(b))
        assertEquals(t0 + 36 * min, b.until)

        val next = FocusMachine.advance(b, t0 + 37 * min, none).phase as FocusPhase.Locked
        assertEquals(2, next.period.cycle)
        assertEquals(t0 + 36 * min, next.period.startedAt)
        assertEquals(t0 + 66 * min, next.period.endsAt)
    }

    @Test
    fun `a phone left alone through a break and a whole round waits for the owner`() {
        val deciding = FocusMachine.advance(manual(), t0 + 31 * min, none).phase
        val b = FocusMachine.decide(deciding, t0 + 31 * min, FocusNext.BREAK).phase
        val later = FocusMachine.advance(b, t0 + 200 * min, none).phase as FocusPhase.Deciding
        assertEquals(2, later.period.cycle)
    }

    @Test
    fun `stop ends the session`() {
        val deciding = FocusMachine.advance(manual(), t0 + 31 * min, none).phase
        val stop = FocusMachine.decide(deciding, t0 + 32 * min, FocusNext.STOP)
        assertEquals(FocusPhase.Free, stop.phase)
        assertEquals(FocusNext.STOP, stop.finished.single().next)
    }

    @Test
    fun `there is no way out of a period before it ends`() {
        val locked = manual()
        assertEquals(locked, FocusMachine.decide(locked, t0 + min, FocusNext.STOP).phase)
        assertEquals(locked, FocusMachine.start(locked, t0 + min, FocusKind.REST, FocusMachine.REST_MS).phase)
    }

    @Test
    fun `a locking block locks a free phone until it ends and finishes on its own`() {
        val block: (Long) -> LockingBlock? = { now -> if (now < t0 + 60 * min) LockingBlock("Before bed", t0 + 60 * min) else null }
        val locked = FocusMachine.advance(FocusPhase.Free, t0 + 10 * min, block).phase as FocusPhase.Locked
        assertEquals(FocusKind.SCHEDULED, locked.period.kind)
        assertEquals("Before bed", locked.period.block)
        assertEquals(50 * min, locked.period.plannedMs)
        val end = FocusMachine.advance(locked, t0 + 61 * min, block)
        assertEquals(FocusPhase.Free, end.phase)
        assertEquals(FocusNext.NONE, end.finished.single().next)
        assertEquals(t0 + 60 * min, end.finished.single().endedAt)
    }

    @Test
    fun `a rest ends on its own`() {
        val rest = FocusMachine.start(FocusPhase.Free, t0, FocusKind.REST, FocusMachine.REST_MS).phase
        val end = FocusMachine.advance(rest, t0 + 4 * min, none)
        assertEquals(FocusPhase.Free, end.phase)
        assertEquals(FocusKind.REST, end.finished.single().period.kind)
    }

    @Test
    fun `blocked opens and held notifications count only while locked`() {
        val locked = FocusMachine.held(FocusMachine.blocked(FocusMachine.blocked(manual()))) as FocusPhase.Locked
        assertEquals(2, locked.period.blockedOpens)
        assertEquals(1, locked.period.heldNotifications)
        assertEquals(FocusPhase.Free, FocusMachine.blocked(FocusPhase.Free))
        assertEquals(29 * min, FocusMachine.holdingFor(locked, t0 + min))
        val deciding = FocusMachine.advance(locked, t0 + 31 * min, none).phase
        assertEquals("notifications flow again once the time is up", 0L, FocusMachine.holdingFor(deciding, t0 + 31 * min))
    }
}
