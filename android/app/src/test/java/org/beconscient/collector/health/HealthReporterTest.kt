package org.beconscient.collector.health

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.beconscient.collector.events.Events
import org.beconscient.collector.queue.EventDb
import org.beconscient.collector.queue.Kv
import org.beconscient.collector.queue.SensorGapRow
import org.beconscient.collector.support.FakeClock
import org.beconscient.collector.support.FakeProbe
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

@RunWith(RobolectricTestRunner::class)
class HealthReporterTest {
    private lateinit var db: EventDb
    private val clock = FakeClock()
    private val probe = FakeProbe()
    private lateinit var reporter: HealthReporter

    @Before
    fun setUp() {
        db = memoryDb()
        reporter = HealthReporter(db.dao(), probe, clock, "0.1.0")
    }

    @After
    fun tearDown() = db.close()

    private fun JsonObject.sensor(name: String) = this["sensors"]!!.jsonObject[name]!!.jsonObject

    @Test
    fun `a healthy phone reports every sensor, the watermark, the queue and the launcher`() = runBlocking {
        db.dao().putLong(Kv.USAGE_WATERMARK, clock.now - 60_000)
        db.dao().insertIgnore(Events.usage(clock.now - 120_000, "SCREEN_INTERACTIVE", clock.now - 130_000, null, null, null))
        clock.advance(30_000)

        val obj = parse(reporter.record().payload)

        assertEquals("health", obj.str("type"))
        assertEquals("0.1.0", obj.str("client_version"))
        assertEquals(setOf("usage", "notifications", "shorts", "battery_exemption"), obj["sensors"]!!.jsonObject.keys)
        listOf("usage", "notifications", "shorts", "battery_exemption").forEach { assertEquals("ok", obj.sensor(it).str("state")) }
        assertEquals((clock.now - 90_000).toString(), obj.str("watermark"))
        assertEquals((clock.now - 90_000).toString(), obj.sensor("usage").str("last_event_at"))
        assertEquals("1", obj.str("queue_depth"))
        assertEquals("150000", obj.str("oldest_unsent_age_ms"))
        assertEquals("com.google.android.apps.nexuslauncher", obj.str("home_package"))
    }

    @Test
    fun `without usage access the usage sensor is stopped`() = runBlocking {
        probe.usage = false
        val obj = parse(reporter.record().payload)
        assertEquals("stopped", obj.sensor("usage").str("state"))
        assertEquals("usage access not granted", obj.sensor("usage").str("detail"))
    }

    @Test
    fun `permissions and services map to sensor states`() = runBlocking {
        probe.listener = false
        probe.accessibility = false
        probe.battery = false
        probe.home = null
        val obj = parse(reporter.record().payload)
        assertEquals("stopped", obj.sensor("notifications").str("state"))
        assertEquals("disabled", obj.sensor("shorts").str("state"))
        assertEquals("accessibility service off", obj.sensor("shorts").str("detail"))
        assertEquals("stopped", obj.sensor("battery_exemption").str("state"))
        assertFalse("home_package" in obj)
        probe.notifications = false
        assertEquals("notification access not granted", reporter.sensors()["notifications"]!!.detail)
    }

    @Test
    fun `a recent listener gap is reported in the detail`() = runBlocking {
        db.dao().insertGap(SensorGapRow(sensor = "notifications", fromMs = clock.now - 3_600_000, toMs = clock.now - 60_000, recovered = 2))
        val detail = reporter.sensors()["notifications"]!!.detail!!
        assertTrue(detail, detail.startsWith("gap ${clock.now - 3_600_000}-${clock.now - 60_000}"))
        assertTrue(detail.length <= 120)
    }
}
