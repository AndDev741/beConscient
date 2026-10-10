package org.beconscient.collector.upload

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.beconscient.collector.events.Events
import org.beconscient.collector.pairing.InMemoryCredentialStore
import org.beconscient.collector.queue.EventDb
import org.beconscient.collector.queue.EventState
import org.beconscient.collector.support.FakeClock
import org.beconscient.collector.support.FakeIngestServer
import org.beconscient.collector.support.TestKeys
import org.beconscient.collector.support.memoryDb
import org.beconscient.collector.support.str
import org.beconscient.collector.support.testCredentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueueMaintenanceTest {
    private lateinit var db: EventDb
    private lateinit var server: FakeIngestServer
    private val clock = FakeClock()
    private lateinit var uploader: Uploader
    private lateinit var maintenance: QueueMaintenance
    private val day = QueueMaintenance.DAY_MS

    @Before
    fun setUp() {
        db = memoryDb()
        server = FakeIngestServer().also { it.server.start() }
        uploader = Uploader(
            db.dao(), InMemoryCredentialStore(testCredentials()), UrlConnectionTransport(allowCleartext = true),
            TestKeys.config(server.url()), clock, "0.1.0-test",
        )
        maintenance = QueueMaintenance(db.dao(), clock)
    }

    @After
    fun tearDown() {
        server.stop()
        db.close()
    }

    private suspend fun event(at: Long) =
        Events.usage(clock.now, "ACTIVITY_RESUMED", at, "com.app.$at", null, null).also { db.dao().insertIgnore(it) }

    @Test
    fun `acknowledged events stay 14 days after the receipt, then go`() = runBlocking {
        val acked = event(clock.now - day)
        uploader.uploadAll()
        val pending = event(clock.now)

        clock.advance(13 * day)
        maintenance.purge()
        assertNotNull(db.dao().event(acked.id))

        clock.advance(2 * day)
        assertEquals(1, maintenance.purge().acked)
        assertNull(db.dao().event(acked.id))
        assertNotNull("unsent events are never purged", db.dao().event(pending.id))
    }

    @Test
    fun `resend since re-queues acknowledged events from that time and the server answers duplicate`() = runBlocking {
        val old = event(clock.now - 5 * day)
        val recent = (1..3).map { event(clock.now - day + it) }
        uploader.uploadAll()
        assertEquals(4, db.dao().countByState(EventState.ACKED))

        val since = clock.now - 2 * day
        assertEquals(3, maintenance.countResendable(since))
        assertEquals(3, maintenance.resendSince(since))
        assertEquals(EventState.ACKED, db.dao().event(old.id)!!.state)
        recent.forEach { assertEquals(EventState.PENDING, db.dao().event(it.id)!!.state) }

        clock.advance(1_000)
        uploader.uploadAll()
        val resent = server.batches().last()
        assertEquals("2", resent["header"]!!.jsonObject.str("seq"))
        assertEquals(recent.map { it.id }, FakeIngestServer.eventIds(resent))
        assertEquals(4, db.dao().countByState(EventState.ACKED))
    }
}
