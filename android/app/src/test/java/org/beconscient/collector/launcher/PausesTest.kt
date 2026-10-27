package org.beconscient.collector.launcher

import kotlinx.coroutines.runBlocking
import org.beconscient.collector.events.Events
import org.beconscient.collector.queue.EventDb
import org.beconscient.collector.support.FakeClock
import org.beconscient.collector.support.context
import org.beconscient.collector.support.memoryDb
import org.beconscient.collector.support.parse
import org.beconscient.collector.support.str
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class PausesTest {
    private val youtube = "com.google.android.youtube"
    private val kindle = "com.amazon.kindle"
    private val bed = Block("b", "Before bed", DayOfWeek.entries.toSet(), LocalTime.of(21, 0), LocalTime.of(22, 0), "Kindle, not Shorts.", kindle)
    private val settings = LauncherSettings(controlled = setOf(youtube, kindle), blocks = listOf(bed))
    private val evening = LocalDateTime.of(2026, 10, 16, 21, 30)
    private val noon = LocalDateTime.of(2026, 10, 16, 12, 0)

    private lateinit var db: EventDb
    private val clock = FakeClock()

    @Before
    fun setUp() {
        db = memoryDb()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `apps the owner did not choose open straight away`() {
        assertNull(Pauses.decide(settings, "com.whatsapp", evening))
    }

    @Test
    fun `inside a block the pause shows its message and alternative`() {
        val p = Pauses.decide(settings, youtube, evening)!!
        assertEquals("Kindle, not Shorts.", p.message)
        assertEquals("b", p.block!!.id)
        assertEquals(kindle, p.alternative)
    }

    @Test
    fun `outside every block the pause asks the default question`() {
        val p = Pauses.decide(settings, youtube, noon)!!
        assertEquals(Pauses.DEFAULT_MESSAGE, p.message)
        assertNull(p.block)
        assertNull(p.alternative)
    }

    @Test
    fun `a blank message falls back to the default question`() {
        val quiet = settings.copy(blocks = listOf(bed.copy(message = "  ")))
        assertEquals(Pauses.DEFAULT_MESSAGE, Pauses.decide(quiet, youtube, evening)!!.message)
    }

    @Test
    fun `the alternative is never the app being opened`() {
        assertNull(Pauses.decide(settings, kindle, evening)!!.alternative)
    }

    @Test
    fun `the wait doubles with each pause of the day up to a minute`() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), (1..6).map { Pauses.waitFor(it) })
        val third = Pauses.decide(settings, youtube, noon, PauseCounts.Today(2, 1_000L))!!
        assertEquals(3, third.openCount)
        assertEquals(20_000L, third.waitMs)
        assertEquals(1_000L, third.previousAt)
    }

    @Test
    fun `pause counts start again on a new day`() {
        val counts = PauseCounts(context().getSharedPreferences("pause-counts-test", 0).also { it.edit().clear().commit() })
        val day = noon.toLocalDate()
        counts.shown(youtube, day, 1L)
        counts.shown(youtube, day, 2L)
        counts.shown(kindle, day, 3L)
        assertEquals(PauseCounts.Today(2, 2L), counts.today(youtube, day))
        assertEquals(PauseCounts.Today(0, null), counts.today(youtube, day.plusDays(1)))
    }

    @Test
    fun `the pause screen says how often the app was opened today`() {
        val first = Pauses.decide(settings, youtube, noon)!!
        assertEquals("First time today", timesToday(first, 0))
        val fourth = Pauses.decide(settings, youtube, noon, PauseCounts.Today(3, 0L))!!
        assertEquals("4th time today, the last one 12 min ago", timesToday(fourth, 12 * 60_000L))
        assertEquals("2nd time today, the last one just now", timesToday(fourth.copy(openCount = 2), 30_000L))
    }

    @Test
    fun `an opened app is queued with the reason and the imposed wait`() = runBlocking {
        val log = PauseLog(db.dao(), clock)
        val pause = Pauses.decide(settings, youtube, noon, PauseCounts.Today(1, 0L))!!
        val shownAt = clock.now
        clock.advance(11_000)
        log.record(pause, shownAt, Events.PauseOutcome.OPENED, Events.PauseIntent.SPECIFIC)
        val o = parse(db.dao().pendingOldestFirst(10).single().payload)
        assertEquals("opened", o.str("outcome"))
        assertEquals("specific", o.str("intent"))
        assertEquals("10000", o["required_wait_ms"].toString())
        assertEquals("2", o["open_count"].toString())
    }

    @Test
    fun `a pause is queued with its outcome and wait but not its message`() = runBlocking {
        val log = PauseLog(db.dao(), clock)
        val shownAt = clock.now
        clock.advance(3_200)
        log.record(Pauses.decide(settings, youtube, evening)!!, shownAt, Events.PauseOutcome.ALTERNATIVE)
        val row = db.dao().pendingOldestFirst(10).single()
        assertEquals(Events.PAUSE, row.type)
        assertEquals(shownAt, row.time)
        val o = parse(row.payload)
        assertEquals(youtube, o.str("package"))
        assertEquals("alternative", o.str("outcome"))
        assertEquals("Before bed", o.str("block"))
        assertEquals(kindle, o.str("alternative"))
        assertEquals("3200", o["wait_ms"].toString())
        assertFalse("the message stays on the phone", row.payload.contains("Kindle, not Shorts."))
    }

    @Test
    fun `settings persist across store instances`() {
        val prefs = context().getSharedPreferences("launcher-test", 0)
        val store = LauncherStore(prefs)
        store.togglePinned("com.whatsapp")
        store.toggleControlled(youtube)
        store.saveBlock(bed)
        store.saveBlock(bed.copy(name = "Night"))
        val again = LauncherStore(prefs).settings.value
        assertEquals(listOf("com.whatsapp"), again.pinned)
        assertEquals(setOf(youtube), again.controlled)
        assertEquals(listOf("Night"), again.blocks.map { it.name })
        store.toggleControlled(youtube)
        store.deleteBlock("b")
        assertEquals(LauncherSettings(pinned = listOf("com.whatsapp")), LauncherStore(prefs).settings.value)
    }
}
