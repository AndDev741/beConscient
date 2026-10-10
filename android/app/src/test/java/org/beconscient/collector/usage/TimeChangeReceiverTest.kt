package org.beconscient.collector.usage

import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.beconscient.collector.AppGraph
import org.beconscient.collector.events.Events
import org.beconscient.collector.events.TzMarkers
import org.beconscient.collector.pairing.InMemoryCredentialStore
import org.beconscient.collector.queue.EventDb
import org.beconscient.collector.support.FakeClock
import org.beconscient.collector.support.FakeProbe
import org.beconscient.collector.support.TestKeys
import org.beconscient.collector.support.context
import org.beconscient.collector.support.eventually
import org.beconscient.collector.support.memoryDb
import org.beconscient.collector.support.parse
import org.beconscient.collector.support.str
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class TimeChangeReceiverTest {
    private lateinit var db: EventDb
    private val clock = FakeClock()

    @Before
    fun setUp() {
        db = memoryDb()
        AppGraph.install(
            AppGraph(
                context(), clock = clock, db = db, config = TestKeys.config("http://127.0.0.1:1"),
                credentials = InMemoryCredentialStore(), probe = FakeProbe(),
            ),
        )
    }

    @After
    fun tearDown() {
        AppGraph.install(null)
        db.close()
    }

    @Test
    fun `a zone change writes a marker with the new zone and offset`() {
        val intent = Intent(Intent.ACTION_TIMEZONE_CHANGED).putExtra(TimeChangeReceiver.EXTRA_TIME_ZONE, "Asia/Tokyo")
        TimeChangeReceiver().onReceive(context(), intent)
        eventually {
            val marker = runBlocking { db.dao().eventsOfType(Events.TZ_CHANGE) }.single()
            val obj = parse(marker.payload)
            assertEquals("Asia/Tokyo", obj.str("zone"))
            assertEquals("540", obj.str("offset_min"))
            assertEquals("TIMEZONE_CHANGED", obj.str("reason"))
            assertEquals(clock.now.toString(), obj.str("at"))
        }
    }

    @Test
    fun `a clock change writes a TIME_SET marker in the current zone`() = runBlocking {
        val markers = TzMarkers(db.dao(), clock) { TimeZone.getTimeZone("America/Sao_Paulo") }
        markers.record(Events.TzReason.TIME_SET)
        val obj = parse(db.dao().eventsOfType(Events.TZ_CHANGE).single().payload)
        assertEquals("America/Sao_Paulo", obj.str("zone"))
        assertEquals("-180", obj.str("offset_min"))
        assertEquals("TIME_SET", obj.str("reason"))
    }
}
