package org.beconscient.collector.pairing

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.beconscient.collector.crypto.Fingerprint
import org.beconscient.collector.events.Events
import org.beconscient.collector.events.TzMarkers
import org.beconscient.collector.queue.EventDb
import org.beconscient.collector.queue.Kv
import org.beconscient.collector.support.FakeClock
import org.beconscient.collector.support.FakeIngestServer
import org.beconscient.collector.support.FakeIngestServer.Twist
import org.beconscient.collector.support.TestKeys
import org.beconscient.collector.support.memoryDb
import org.beconscient.collector.support.parse
import org.beconscient.collector.support.str
import org.beconscient.collector.upload.UrlConnectionTransport
import org.beconscient.collector.util.Base64Url
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PairingClientTest {
    private lateinit var db: EventDb
    private lateinit var server: FakeIngestServer
    private val clock = FakeClock()
    private val store = InMemoryCredentialStore()
    private lateinit var client: PairingClient

    @Before
    fun setUp() {
        db = memoryDb()
        server = FakeIngestServer().also { it.server.start() }
        client = PairingClient(
            TestKeys.config(server.url()), UrlConnectionTransport(allowCleartext = true), store,
            db.dao(), TzMarkers(db.dao(), clock), clock, "0.1.0",
        )
    }

    @After
    fun tearDown() {
        server.stop()
        db.close()
    }

    @Test
    fun `pairing sends a fresh id and secret and stores them with a matching fingerprint`() = runBlocking {
        server.pairResponse = { req ->
            val fp = Fingerprint.of(req.str("installation_id")!!, Base64Url.decode(req.str("secret")!!))
            buildJsonObject {
                put("device_id", "dev-1")
                put("device_name", "Phone")
                put("fingerprint", fp)
            }
        }
        db.dao().putLong(Kv.NEXT_SEQ, 9)

        val result = client.pair("7kq2 m9xd", "Pixel 7") as PairingClient.Result.Paired

        val sent = server.requests.single().plaintext
        assertEquals("7KQ2-M9XD", sent.str("code"))
        assertEquals("android", sent.str("kind"))
        assertEquals("Pixel 7", sent.str("name"))
        assertEquals("0.1.0", sent.str("client_version"))
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7").containsMatchIn(sent.str("installation_id")!!))
        assertEquals(32, Base64Url.decode(sent.str("secret")!!).size)

        assertTrue(result.fingerprintsMatch)
        val saved = store.load()!!
        assertEquals(sent.str("installation_id"), saved.installationId)
        assertEquals("dev-1", saved.deviceId)
        assertEquals("Phone", saved.deviceName)
        assertEquals(Fingerprint.of(saved.installationId, saved.secret), saved.fingerprint)
        assertEquals(1L, db.dao().long(Kv.NEXT_SEQ))
        val marker = parse(db.dao().eventsOfType(Events.TZ_CHANGE).single().payload)
        assertEquals("INITIAL", marker.str("reason"))
    }

    @Test
    fun `a used code is reported as rejected and stores nothing`() = runBlocking {
        server.next(Twist.Status(401, "unauthorized"))
        assertEquals(PairingClient.Result.CodeRejected, client.pair("7KQ2-M9XD", "Pixel"))
        assertNull(store.load())
    }

    @Test
    fun `a fingerprint mismatch is reported`() = runBlocking {
        val result = client.pair("7KQ2-M9XD", "Pixel") as PairingClient.Result.Paired
        assertFalse(result.fingerprintsMatch)
    }

    @Test
    fun `an answer that is not from the server stores nothing`() = runBlocking {
        server.next(Twist.CaptivePortal)
        assertEquals(PairingClient.Result.Failed("bad_response"), client.pair("7KQ2-M9XD", "Pixel"))
        assertNull(store.load())
    }

    @Test
    fun `a malformed code never reaches the network`() = runBlocking {
        assertEquals(PairingClient.Result.InvalidCode, client.pair("7KQ2-M9X", "Pixel"))
        assertEquals(PairingClient.Result.InvalidCode, client.pair("7KQ2-M9XU", "Pixel"))
        assertTrue(server.requests.isEmpty())
    }
}
