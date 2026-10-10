package org.beconscient.collector.upload

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.beconscient.collector.events.Events
import org.beconscient.collector.pairing.InMemoryCredentialStore
import org.beconscient.collector.queue.EventDb
import org.beconscient.collector.queue.EventRow
import org.beconscient.collector.queue.EventState
import org.beconscient.collector.queue.Kv
import org.beconscient.collector.support.FakeClock
import org.beconscient.collector.support.FakeIngestServer
import org.beconscient.collector.support.FakeIngestServer.Twist
import org.beconscient.collector.support.TestKeys
import org.beconscient.collector.support.memoryDb
import org.beconscient.collector.support.str
import org.beconscient.collector.support.testCredentials
import org.beconscient.collector.util.Base64Url
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class UploaderTest {
    private lateinit var db: EventDb
    private lateinit var server: FakeIngestServer
    private val clock = FakeClock()
    private val creds = testCredentials()
    private val store = InMemoryCredentialStore(creds)
    private lateinit var uploader: Uploader

    @Before
    fun setUp() {
        db = memoryDb()
        server = FakeIngestServer()
        server.server.start()
        uploader = uploaderFor(server.url())
    }

    @After
    fun tearDown() {
        server.stop()
        db.close()
    }

    private fun uploaderFor(url: String) = Uploader(
        db.dao(), store, UrlConnectionTransport(allowCleartext = true, connectTimeoutMs = 2_000, readTimeoutMs = 5_000),
        TestKeys.config(url), clock, "0.1.0-test", { TimeZone.getTimeZone("Europe/Lisbon") },
    )

    private var n = 0
    private suspend fun queue(count: Int, at: Long = clock.now): List<EventRow> {
        val rows = (0 until count).map {
            n++
            Events.usage(clock.now, "ACTIVITY_RESUMED", at + n, "com.app$n", null, null)
        }
        db.dao().insertIgnore(rows)
        return rows
    }

    private suspend fun state(id: String) = db.dao().event(id)?.state

    @Test
    fun `a batch is sealed, receipted and applied per event`() = runBlocking {
        val rows = queue(3)
        server.verdict = { e ->
            if (e["id"]!!.jsonPrimitive.content == rows[2].id) "rejected" to "end_before_start" else "accepted" to null
        }

        val outcome = uploader.uploadAll()

        assertEquals(Uploader.Outcome.Done(1), outcome)
        val batch = server.batches().single()
        val header = batch["header"]!!.jsonObject
        assertEquals(creds.installationId, header.str("installation_id"))
        assertEquals(Base64Url.encode(creds.secret), header.str("credential"))
        assertEquals("1", header.str("seq"))
        assertEquals("1", header.str("contract_version"))
        assertEquals("Europe/Lisbon", header.str("tz"))
        assertEquals(clock.now.toString(), header.str("sent_at"))
        assertEquals(rows.map { it.id }, FakeIngestServer.eventIds(batch))

        assertEquals(EventState.ACKED, state(rows[0].id))
        assertEquals(EventState.ACKED, state(rows[1].id))
        assertNull("rejected events leave the queue", state(rows[2].id))
        val rejected = db.dao().allRejected().single()
        assertEquals(rows[2].id, rejected.id)
        assertEquals("end_before_start", rejected.reason)
        assertEquals(0, db.dao().countUnsent())
        assertEquals(2L, db.dao().long(Kv.NEXT_SEQ))
        assertEquals(clock.now, db.dao().long(Kv.UPLOAD_LAST_OK_AT))
    }

    @Test
    fun `a rejected event is never sent again`() = runBlocking {
        val rows = queue(1)
        server.verdict = { "rejected" to "invalid_enum" }
        uploader.uploadAll()
        db.dao().requeueAckedSince(0)
        queue(1)
        server.verdict = { "accepted" to null }
        uploader.uploadAll()
        val second = server.batches()[1]
        assertTrue(rows[0].id !in FakeIngestServer.eventIds(second))
    }

    @Test
    fun `a lost response resends the frozen batch unchanged and the stored receipt completes it`() = runBlocking {
        val first = queue(3)
        server.next(Twist.DropResponse)

        val lost = uploader.uploadAll()

        assertEquals("network", (lost as Uploader.Outcome.Retry).reason)
        assertTrue(first.all { runBlocking { state(it.id) } == EventState.IN_BATCH })
        // New events arrive while the batch waits; they must not join it.
        val later = queue(2)
        clock.advance(60_000)

        val retried = uploader.uploadAll()

        assertEquals(Uploader.Outcome.Done(2), retried)
        val batches = server.batches()
        assertEquals(3, batches.size)
        assertEquals("1", batches[0]["header"]!!.jsonObject.str("seq"))
        assertEquals("1", batches[1]["header"]!!.jsonObject.str("seq"))
        assertEquals(FakeIngestServer.eventIds(batches[0]), FakeIngestServer.eventIds(batches[1]))
        assertEquals(batches[0]["events"], batches[1]["events"])
        assertEquals("2", batches[2]["header"]!!.jsonObject.str("seq"))
        assertEquals(later.map { it.id }, FakeIngestServer.eventIds(batches[2]))
        assertTrue((first + later).all { runBlocking { state(it.id) } == EventState.ACKED })
    }

    @Test
    fun `a response that fails authentication deletes nothing`() = runBlocking {
        val rows = queue(2)
        for (twist in listOf(Twist.WrongKey, Twist.TamperedTag, Twist.CaptivePortal)) {
            server.next(twist)
            val outcome = uploader.uploadAll()
            assertTrue("$twist", outcome is Uploader.Outcome.Retry)
            assertTrue(rows.all { runBlocking { state(it.id) } == EventState.IN_BATCH })
            assertEquals(0, db.dao().countByState(EventState.ACKED))
            assertEquals(0, db.dao().countRejected())
            assertEquals(1L, db.dao().openBatch()!!.seq)
        }
        assertEquals(Uploader.Outcome.Done(1), uploader.uploadAll())
        assertEquals(setOf("1"), server.batches().map { it["header"]!!.jsonObject.str("seq") }.toSet())
        assertTrue(rows.all { runBlocking { state(it.id) } == EventState.ACKED })
    }

    @Test
    fun `a receipt for another sequence number changes nothing`() = runBlocking {
        val rows = queue(1)
        server.next(Twist.WrongSeq)
        val outcome = uploader.uploadAll()
        assertEquals("receipt_mismatch", (outcome as Uploader.Outcome.Retry).reason)
        assertEquals(EventState.IN_BATCH, state(rows[0].id))
    }

    @Test
    fun `ids that were not in the batch are ignored`() = runBlocking {
        val sent = queue(2)
        db.dao().freeze(1, creds.installationId, clock.now, 2000, Int.MAX_VALUE)
        val outsider = queue(1).single()
        server.next(Twist.ExtraResults(listOf(outsider.id to "accepted", "0192f3a1-dead-7000-8000-000000000000" to "rejected")))

        uploader.uploadAll()

        val first = server.batches().first()
        assertEquals(sent.map { it.id }, FakeIngestServer.eventIds(first))
        // The outsider went in the next batch on its own, not acknowledged by the extra result.
        assertEquals(2, server.batches().size)
        assertEquals(listOf(outsider.id), FakeIngestServer.eventIds(server.batches()[1]))
        assertEquals(0, db.dao().countRejected())
    }

    @Test
    fun `server errors and network failures retry without touching the queue`() = runBlocking {
        val rows = queue(2)
        server.next(Twist.Status(503))
        assertEquals("http_503", (uploader.uploadAll() as Uploader.Outcome.Retry).reason)
        server.next(Twist.Status(500, "internal"))
        assertEquals("http_500", (uploader.uploadAll() as Uploader.Outcome.Retry).reason)
        assertEquals("http_500", db.dao().kv(Kv.UPLOAD_LAST_ERROR))
        assertTrue(rows.all { runBlocking { state(it.id) } == EventState.IN_BATCH })
        assertEquals(2, db.dao().openBatch()!!.attempts)

        val offline = uploaderFor("http://127.0.0.1:9") // nothing listens there
        assertEquals("network", (offline.uploadAll() as Uploader.Outcome.Retry).reason)

        assertEquals(Uploader.Outcome.Done(1), uploader.uploadAll())
        assertNull(db.dao().kv(Kv.UPLOAD_LAST_ERROR))
    }

    @Test
    fun `unauthorized and sequence conflicts are kept as errors for the status screen`() = runBlocking {
        queue(1)
        server.next(Twist.Status(401, "unauthorized"))
        assertEquals("unauthorized", (uploader.uploadAll() as Uploader.Outcome.Retry).reason)
        server.next(Twist.Status(409, "sequence_conflict"))
        assertEquals("sequence_conflict", (uploader.uploadAll() as Uploader.Outcome.Retry).reason)
        assertEquals("sequence_conflict", db.dao().kv(Kv.UPLOAD_LAST_ERROR))
    }

    @Test
    fun `AE5 events queued offline go out later with their original time`() = runBlocking {
        val flightStart = clock.now
        val inFlight = queue(4, at = flightStart)
        val offline = uploaderFor("http://127.0.0.1:9")
        clock.advance(3 * 60 * 60 * 1000L)
        assertTrue(offline.uploadAll() is Uploader.Outcome.Retry)
        assertEquals(4, db.dao().countUnsent())

        assertEquals(Uploader.Outcome.Done(1), uploader.uploadAll())

        val sent = server.batches().single()["events"]!!.jsonArray.map { it.jsonObject }
        assertEquals(inFlight.map { it.time.toString() }, sent.map { it.str("at") })
        assertTrue(sent.all { it.str("at")!!.toLong() < flightStart + 1_000 })
    }

    @Test
    fun `large queues are split into batches of at most 2000 events`() = runBlocking {
        queue(2500)
        assertEquals(Uploader.Outcome.Done(2), uploader.uploadAll())
        val sizes = server.batches().map { FakeIngestServer.eventIds(it).size }
        assertEquals(listOf(2000, 500), sizes)
        assertEquals(listOf("1", "2"), server.batches().map { it["header"]!!.jsonObject.str("seq") })
        assertTrue(server.requests.all { it.raw.length < 1024 * 1024 })
    }

    @Test
    fun `a 413 re-freezes the same sequence number with fewer events`() = runBlocking {
        queue(10)
        server.next(Twist.Status(413, "too_large"))
        assertEquals(Uploader.Outcome.Done(2), uploader.uploadAll())
        val batches = server.batches()
        assertEquals(listOf(5, 5), batches.map { FakeIngestServer.eventIds(it).size })
        assertEquals(listOf("1", "2"), batches.map { it["header"]!!.jsonObject.str("seq") })
    }

    @Test
    fun `nothing is frozen before pairing`() = runBlocking {
        queue(2)
        store.clear()
        assertEquals(Uploader.Outcome.NotPaired, uploader.uploadAll())
        assertNull(db.dao().openBatch())
        assertEquals(2, db.dao().countByState(EventState.PENDING))
    }

    @Test
    fun `a new pairing releases the frozen batch and restarts the sequence`() = runBlocking {
        queue(2)
        server.next(Twist.Status(503))
        uploader.uploadAll()
        assertEquals(2L, db.dao().long(Kv.NEXT_SEQ))
        store.save(testCredentials(installationId = "0192f3a0-0000-7000-8000-00000000ffff"))
        assertEquals(Uploader.Outcome.Done(1), uploader.uploadAll())
        val last = server.batches().last()["header"]!!.jsonObject
        assertEquals("0192f3a0-0000-7000-8000-00000000ffff", last.str("installation_id"))
        assertEquals("1", last.str("seq"))
    }

    @Test
    fun `an empty queue sends nothing`() = runBlocking {
        assertEquals(Uploader.Outcome.Done(0), uploader.uploadAll())
        assertTrue(server.requests.isEmpty())
    }
}
