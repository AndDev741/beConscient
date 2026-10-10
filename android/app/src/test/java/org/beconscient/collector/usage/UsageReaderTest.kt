package org.beconscient.collector.usage

import android.app.usage.UsageEvents
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.beconscient.collector.queue.EventDb
import org.beconscient.collector.queue.EventState
import org.beconscient.collector.queue.Kv
import org.beconscient.collector.support.FakeClock
import org.beconscient.collector.support.memoryDb
import org.beconscient.collector.support.parse
import org.beconscient.collector.support.str
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class FakeUsageSource(var access: Boolean = true) : UsageSource {
    val events = mutableListOf<RawUsageEvent>()
    val queries = mutableListOf<Pair<Long, Long>>()
    override fun hasAccess() = access
    override fun query(from: Long, to: Long): List<RawUsageEvent> {
        queries += from to to
        return events.filter { it.timeStamp in from until to }.sortedBy { it.timeStamp }
    }
}

@RunWith(RobolectricTestRunner::class)
class UsageReaderTest {
    private lateinit var db: EventDb
    private val clock = FakeClock()
    private val source = FakeUsageSource()
    private lateinit var reader: UsageReader

    private val min = 60_000L
    private val hour = 60 * min

    @Before
    fun setUp() {
        db = memoryDb()
        reader = UsageReader(db.dao(), source, clock)
    }

    @After
    fun tearDown() = db.close()

    private fun resumed(at: Long, pkg: String = "com.whatsapp") =
        RawUsageEvent(UsageEvents.Event.ACTIVITY_RESUMED, at, pkg, "$pkg.Main")

    @Test
    fun `reads from the watermark minus the overlap and advances the watermark`() = runBlocking {
        val tenAm = clock.now - 2 * hour
        db.dao().putLong(Kv.USAGE_WATERMARK, tenAm)
        source.events += resumed(tenAm - 30 * min)          // before the overlap: not read
        source.events += resumed(tenAm - 2 * min, "a.b")    // inside the overlap: read
        source.events += resumed(tenAm + 10 * min, "c.d")
        source.events += resumed(tenAm + 50 * min, "e.f")

        val r = reader.read() as UsageReader.Result.Read

        assertEquals(tenAm - UsageReader.OVERLAP_MS, source.queries.first().first)
        assertEquals(clock.now, source.queries.last().second)
        assertEquals(3, r.newEvents)
        assertEquals(tenAm + 50 * min, db.dao().long(Kv.USAGE_WATERMARK))
        val stored = db.dao().allEvents().map { it.time }
        assertEquals(listOf(tenAm - 2 * min, tenAm + 10 * min, tenAm + 50 * min), stored)
    }

    @Test
    fun `re-reading the same stretch adds no duplicates`() = runBlocking {
        db.dao().putLong(Kv.USAGE_WATERMARK, clock.now - hour)
        source.events += resumed(clock.now - 10 * min)
        source.events += RawUsageEvent(UsageEvents.Event.SCREEN_NON_INTERACTIVE, clock.now - 5 * min, "android", null)
        reader.read()
        // The watermark is forced back, as after a crash between write and upload.
        db.dao().putLong(Kv.USAGE_WATERMARK, clock.now - hour)
        clock.advance(min)
        val r = reader.read() as UsageReader.Result.Read
        assertEquals(0, r.newEvents)
        assertEquals(2, db.dao().allEvents().size)
    }

    @Test
    fun `first read back-fills about seven days`() = runBlocking {
        source.events += resumed(clock.now - 6 * 24 * hour)
        source.events += resumed(clock.now - 8 * 24 * hour, "too.old")
        reader.read()
        assertEquals(clock.now - UsageReader.BACKFILL_MS - UsageReader.OVERLAP_MS, source.queries.first().first)
        assertEquals(listOf("com.whatsapp"), db.dao().allEvents().map { parse(it.payload).str("package") })
        assertTrue("queried in slices", source.queries.size > 7)
    }

    @Test
    fun `maps kinds, keeps class and instance for activities only, ignores other types`() = runBlocking {
        db.dao().putLong(Kv.USAGE_WATERMARK, clock.now - hour)
        val t = clock.now - 30 * min
        source.events += RawUsageEvent(UsageEvents.Event.ACTIVITY_RESUMED, t, "com.whatsapp", "com.whatsapp.Home", 7)
        source.events += RawUsageEvent(UsageEvents.Event.ACTIVITY_PAUSED, t + 1, "com.whatsapp", "com.whatsapp.Home", null)
        source.events += RawUsageEvent(UsageEvents.Event.KEYGUARD_HIDDEN, t + 2, "android", "x", 3)
        source.events += RawUsageEvent(UsageEvents.Event.CONFIGURATION_CHANGE, t + 3, "android", null)
        source.events += RawUsageEvent(UsageEvents.Event.DEVICE_SHUTDOWN, t + 4, "android", null)
        reader.read()
        val rows = db.dao().allEvents().map { parse(it.payload) }
        assertEquals(listOf("ACTIVITY_RESUMED", "ACTIVITY_PAUSED", "KEYGUARD_HIDDEN", "DEVICE_SHUTDOWN"), rows.map { it.str("kind") })
        assertEquals(7, rows[0]["instance"]!!.jsonPrimitive.content.toInt())
        assertEquals("com.whatsapp.Home", rows[0].str("class"))
        assertFalse("instance" in rows[1])
        assertFalse("package" in rows[2] || "class" in rows[2] || "instance" in rows[2])
        assertTrue(rows.all { it.str("type") == "android.usage" })
        assertEquals(t, rows[0]["at"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `without usage access nothing is read and the watermark stays`() = runBlocking {
        source.access = false
        assertEquals(UsageReader.Result.NoAccess, reader.read())
        assertEquals(null, db.dao().long(Kv.USAGE_WATERMARK))
        assertTrue(source.queries.isEmpty())
    }

    @Test
    fun `new events are pending and keep their original time`() = runBlocking {
        db.dao().putLong(Kv.USAGE_WATERMARK, clock.now - hour)
        source.events += resumed(clock.now - 40 * min)
        reader.read()
        val row = db.dao().allEvents().single()
        assertEquals(EventState.PENDING, row.state)
        assertEquals(clock.now - 40 * min, row.time)
        assertEquals(clock.now, row.queuedAt)
    }
}
