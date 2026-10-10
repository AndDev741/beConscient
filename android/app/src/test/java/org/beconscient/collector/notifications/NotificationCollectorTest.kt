package org.beconscient.collector.notifications

import android.service.notification.NotificationListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.beconscient.collector.events.Events
import org.beconscient.collector.queue.EventDb
import org.beconscient.collector.support.FakeClock
import org.beconscient.collector.support.memoryDb
import org.beconscient.collector.support.parse
import org.beconscient.collector.support.str
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
class NotificationCollectorTest {
    private lateinit var db: EventDb
    private val clock = FakeClock()
    private lateinit var collector: NotificationCollector

    private val whatsappKey = "0|com.whatsapp|1|Maria|10123"

    @Before
    fun setUp() {
        db = memoryDb()
        collector = NotificationCollector(db, clock)
    }

    @After
    fun tearDown() = db.close()

    private fun posted(
        key: String = whatsappKey,
        pkg: String = "com.whatsapp",
        at: Long = clock.now,
        summary: Boolean = false,
        ongoing: Boolean = false,
    ) = NotificationCollector.Posted(key, pkg, at, summary, ongoing, 4, "msg")

    private suspend fun payloads(type: String) = db.dao().eventsOfType(type).map { parse(it.payload) }

    @Test
    fun `AE6 a tapped WhatsApp message records only app, time and outcome`() = runBlocking {
        val t2114 = ZonedDateTime.of(2026, 10, 9, 21, 14, 0, 0, ZoneId.of("Europe/Lisbon")).toInstant().toEpochMilli()
        clock.now = t2114
        collector.onPosted(posted(at = t2114))
        clock.advance(20_000)
        collector.onRemoved(whatsappKey, "com.whatsapp", t2114, NotificationListenerService.REASON_CLICK)

        val post = payloads(Events.NOTIFICATION_POSTED).single()
        val removal = payloads(Events.NOTIFICATION_REMOVED).single()
        assertEquals("com.whatsapp", post.str("package"))
        assertEquals(t2114.toString(), post.str("at"))
        assertEquals("false", post.str("is_update"))
        assertEquals("click", removal.str("reason"))
        assertEquals(t2114.toString(), removal.str("posted_at"))
        assertEquals(post.str("ref"), removal.str("ref"))
        assertNotEquals(whatsappKey, post.str("ref"))
        for (row in db.dao().allEvents()) {
            assertFalse("the key never travels", row.payload.contains("Maria") || row.payload.contains(whatsappKey))
            val keys = parse(row.payload).keys
            assertTrue(keys.none { it in setOf("key", "tag", "title", "text", "sender", "channel") })
        }
        assertNull("the local mapping is gone after removal", db.dao().notificationRef(whatsappKey))
    }

    @Test
    fun `an update keeps the ref, is flagged, and repeated updates are throttled`() = runBlocking {
        collector.onPosted(posted())
        clock.advance(61_000)
        collector.onPosted(posted(at = clock.now))
        clock.advance(5_000)
        collector.onPosted(posted(at = clock.now))
        val posts = payloads(Events.NOTIFICATION_POSTED)
        assertEquals(listOf("false", "true"), posts.map { it.str("is_update") })
        assertEquals(1, posts.map { it.str("ref") }.toSet().size)
    }

    @Test
    fun `group summaries and ongoing notifications are flagged`() = runBlocking {
        collector.onPosted(posted(key = "k1", summary = true))
        collector.onPosted(posted(key = "k2", ongoing = true))
        val posts = payloads(Events.NOTIFICATION_POSTED)
        assertEquals("true", posts[0].str("is_group_summary"))
        assertEquals("false", posts[0].str("is_ongoing"))
        assertEquals("true", posts[1].str("is_ongoing"))
        assertEquals("4", posts[1].str("importance"))
        assertEquals("msg", posts[1].str("category"))
    }

    @Test
    fun `removal reasons map to the contract enum`() {
        val map = mapOf(
            NotificationListenerService.REASON_CLICK to "click",
            NotificationListenerService.REASON_CANCEL to "cancel",
            NotificationListenerService.REASON_CANCEL_ALL to "cancel_all",
            NotificationListenerService.REASON_APP_CANCEL to "app_cancel",
            NotificationListenerService.REASON_APP_CANCEL_ALL to "app_cancel",
            NotificationListenerService.REASON_TIMEOUT to "other",
            NotificationListenerService.REASON_SNOOZED to "other",
            NotificationListenerService.REASON_LISTENER_CANCEL to "other",
            NotificationListenerService.REASON_PACKAGE_BANNED to "other",
        )
        for ((reason, wire) in map) assertEquals(wire, NotificationCollector.mapReason(reason).wire)
    }

    @Test
    fun `listener off and on again records a gap with start and end and recovers what it can`() = runBlocking {
        collector.onConnected(emptyList())
        collector.onPosted(posted(key = "seen-then-gone"))
        clock.advance(60_000)
        val off = clock.now
        collector.onDisconnected()
        clock.advance(3_600_000)
        val newWhileOff = posted(key = "new-while-off", pkg = "com.signal", at = off + 600_000)
        collector.onConnected(listOf(newWhileOff))

        val gap = db.dao().lastGap(NotificationCollector.SENSOR)!!
        assertEquals(off, gap.fromMs)
        assertEquals(clock.now, gap.toMs)
        assertEquals(2, gap.recovered)
        val recoveredPost = payloads(Events.NOTIFICATION_POSTED).single { it.str("package") == "com.signal" }
        assertEquals((off + 600_000).toString(), recoveredPost.str("at"))
        val gone = payloads(Events.NOTIFICATION_REMOVED).single()
        assertEquals("other", gone.str("reason"))
        assertEquals("com.whatsapp", gone.str("package"))
    }

    @Test
    fun `first connection records no gap`() = runBlocking {
        collector.onConnected(listOf(posted()))
        assertNull(db.dao().lastGap(NotificationCollector.SENSOR))
        assertEquals(1, payloads(Events.NOTIFICATION_POSTED).size)
    }

    @Test
    fun `a removal without a known post still gets a fresh ref and the post time`() = runBlocking {
        collector.onRemoved("unknown", "com.x", 1234L, NotificationListenerService.REASON_CANCEL)
        val removal = payloads(Events.NOTIFICATION_REMOVED).single()
        assertEquals("cancel", removal.str("reason"))
        assertEquals(1234L, removal["posted_at"]!!.jsonPrimitive.content.toLong())
        assertEquals(36, removal.str("ref")!!.length)
    }
}
