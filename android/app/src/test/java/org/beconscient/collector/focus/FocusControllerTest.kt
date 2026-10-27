package org.beconscient.collector.focus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.beconscient.collector.events.Events
import org.beconscient.collector.events.Events.FocusKind
import org.beconscient.collector.events.Events.FocusNext
import org.beconscient.collector.launcher.Block
import org.beconscient.collector.launcher.LauncherStore
import org.beconscient.collector.queue.EventDb
import org.beconscient.collector.queue.EventRow
import org.beconscient.collector.support.FakeClock
import org.beconscient.collector.support.context
import org.beconscient.collector.support.memoryDb
import org.beconscient.collector.support.parse
import org.beconscient.collector.support.str
import org.beconscient.collector.util.SerialQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class FocusControllerTest {
    private val whatsapp = "com.whatsapp"
    private val maps = "com.google.android.apps.maps"
    private val gms = "com.google.android.gms"
    private val min = 60_000L

    private class Apps : FocusApps {
        override fun launchable() = setOf("com.whatsapp", "com.google.android.apps.maps", "com.android.settings", "org.beconscient.collector")
        override fun defaults() = setOf("com.google.android.apps.maps")
        override fun always() = setOf("org.beconscient.collector", "com.android.systemui")
    }

    private lateinit var db: EventDb
    private lateinit var scope: CoroutineScope
    private lateinit var launcher: LauncherStore
    private val clock = FakeClock()
    private val prefs get() = context().getSharedPreferences("focus-test", 0)

    private fun controller() = FocusController(prefs, launcher, Apps(), db.dao(), clock, SerialQueue(scope, "test"), { ZoneOffset.UTC })

    @Before
    fun setUp() {
        db = memoryDb()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        prefs.edit().clear().commit()
        launcher = LauncherStore(context().getSharedPreferences("launcher-focus-test", 0).also { it.edit().clear().commit() })
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun queued(): List<EventRow> = runBlocking {
        repeat(50) {
            val rows = db.dao().pendingOldestFirst(10)
            if (rows.isNotEmpty()) return@runBlocking rows
            delay(20)
        }
        emptyList()
    }

    @Test
    fun `only launchable apps outside the allowed list are blocked, and only while locked`() {
        val focus = controller()
        assertFalse("a free phone blocks nothing", focus.blocks(whatsapp))
        focus.startManual(30)
        assertTrue(focus.blocks(whatsapp))
        assertTrue("settings is blocked so the period cannot be undone", focus.blocks("com.android.settings"))
        assertFalse("allowed by default", focus.blocks(maps))
        assertFalse("the collector itself", focus.blocks("org.beconscient.collector"))
        assertFalse("system screens without a launcher entry always work", focus.blocks(gms))
        focus.callShown(whatsapp)
        assertFalse("a call can be answered", focus.blocks(whatsapp))
        focus.callGone(whatsapp)
        assertTrue(focus.blocks(whatsapp))
    }

    @Test
    fun `the owner's list replaces the defaults once edited`() {
        val focus = controller()
        launcher.toggleFocusAllowed(whatsapp, focus.allowed())
        assertEquals(setOf(maps, whatsapp), focus.allowed())
        focus.startManual(30)
        assertFalse(focus.blocks(whatsapp))
    }

    @Test
    fun `notifications from blocked apps are held for the rest of the period and counted once`() {
        val focus = controller()
        assertEquals("nothing is held on a free phone", 0L, focus.holdFor("k1", whatsapp, false, "msg"))
        focus.startManual(30)
        clock.advance(10 * min)
        assertEquals(20 * min, focus.holdFor("k1", whatsapp, false, "msg"))
        assertEquals(20 * min, focus.holdFor("k1", whatsapp, false, "msg"))
        assertEquals("allowed apps pass", 0L, focus.holdFor("k2", maps, false, null))
        assertEquals("ongoing ones pass", 0L, focus.holdFor("k3", whatsapp, true, null))
        assertEquals("calls pass", 0L, focus.holdFor("k4", whatsapp, false, "call"))
        assertEquals(1, (focus.phase.value as FocusPhase.Locked).period.heldNotifications)
    }

    @Test
    fun `a finished round is queued as one focus event and counted for today`() {
        val focus = controller()
        val startedAt = clock.now
        focus.startManual(30)
        focus.onBlocked(whatsapp)
        focus.onBlocked(whatsapp) // the same open reports several window changes
        clock.advance(5 * min)
        focus.onBlocked(whatsapp)
        clock.advance(26 * min)
        assertTrue(focus.refresh() is FocusPhase.Deciding)
        focus.decide(FocusNext.STOP)

        val row = queued().single()
        assertEquals(Events.FOCUS, row.type)
        assertEquals(startedAt, row.time)
        val o = parse(row.payload)
        assertEquals("manual", o.str("kind"))
        assertEquals("stop", o.str("next"))
        assertEquals((startedAt + 30 * min).toString(), o["ended_at"].toString())
        assertEquals("2", o["blocked_opens"].toString())
        assertFalse("the allowed and blocked apps stay on the phone", row.payload.contains(whatsapp) || row.payload.contains(maps))
        assertEquals(FocusToday(rounds = 1, minutes = 30, rests = 0), focus.today())
    }

    @Test
    fun `the phase survives a restart and a broken file frees the phone`() {
        controller().startManual(45)
        val again = controller().phase.value as FocusPhase.Locked
        assertEquals(FocusKind.MANUAL, again.period.kind)
        assertEquals(45 * min, again.period.plannedMs)
        prefs.edit().putString("phase", "{not json").commit()
        assertEquals(FocusPhase.Free, controller().phase.value)
    }

    @Test
    fun `a locking block in force locks the phone until the block ends`() {
        // FakeClock.T0 is 2025-10-10T10:00Z, a Friday
        launcher.saveBlock(Block("w", "Deep work", DayOfWeek.entries.toSet(), LocalTime.of(9, 30), LocalTime.of(11, 0), "", locks = true))
        val focus = controller()
        val p = (focus.refresh() as FocusPhase.Locked).period
        assertEquals(FocusKind.SCHEDULED, p.kind)
        assertEquals("Deep work", p.block)
        assertEquals(clock.now + 60 * min, p.endsAt)
        clock.advance(61 * min)
        assertEquals(FocusPhase.Free, focus.refresh())
        assertEquals("none", parse(queued().single().payload).str("next"))
    }

    @Test
    fun `phases round-trip through their stored form`() {
        val period = Period(FocusKind.SCHEDULED, 1, 2, 1, 3, "Bed", 4, 5)
        for (phase in listOf(FocusPhase.Free, FocusPhase.Locked(period), FocusPhase.Deciding(period), FocusPhase.Break(9, 8, 2))) {
            assertEquals(phase, FocusController.decodePhase(FocusController.encodePhase(phase)))
        }
    }

    @Test
    fun `today counts follow the owner's zone`() {
        val far = FocusController(prefs, launcher, Apps(), db.dao(), clock, SerialQueue(scope, "test"), { ZoneId.of("Pacific/Kiritimati") })
        far.startRest()
        clock.advance(4 * min)
        far.refresh()
        assertEquals(FocusToday(rounds = 0, minutes = 0, rests = 1), far.today())
    }
}
