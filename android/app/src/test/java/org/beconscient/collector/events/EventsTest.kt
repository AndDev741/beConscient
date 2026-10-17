package org.beconscient.collector.events

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.beconscient.collector.support.Contract
import org.beconscient.collector.support.FakeClock
import org.beconscient.collector.support.parse
import org.beconscient.collector.support.str
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every builder emits only the fields contract/events.md allows for its type. */
class EventsTest {
    private val now = FakeClock.T0
    private val common = setOf("id", "type", "at")
    private val allowed = mapOf(
        Events.USAGE to common + setOf("kind", "package", "class", "instance"),
        Events.TZ_CHANGE to common + setOf("zone", "offset_min", "reason"),
        Events.NOTIFICATION_POSTED to common + setOf("package", "ref", "is_update", "is_group_summary", "is_ongoing", "importance", "category"),
        Events.NOTIFICATION_REMOVED to common + setOf("package", "ref", "reason", "posted_at"),
        Events.SHORTS to common + setOf("state", "youtube_version", "youtube_version_code", "rules_version"),
        Events.PAUSE to common + setOf("package", "outcome", "block", "alternative", "wait_ms"),
        Events.HEALTH to common + setOf("client_version", "sensors", "watermark", "queue_depth", "oldest_unsent_age_ms", "home_package"),
    )

    private val samples = listOf(
        Events.usage(now, "ACTIVITY_RESUMED", now, "com.whatsapp", "com.whatsapp.HomeActivity", 7),
        Events.usage(now, "SCREEN_INTERACTIVE", now, "android", null, null),
        Events.tzChange(now, now, "Europe/Lisbon", 60, Events.TzReason.INITIAL),
        Events.notificationPosted(now, now, "com.whatsapp", "5d3c2b1a-0000-4000-8000-000000000001", false, false, false, 4, "msg"),
        Events.notificationRemoved(now, now, "com.whatsapp", "5d3c2b1a-0000-4000-8000-000000000001", Events.RemovalReason.CLICK, now - 1),
        Events.shorts(now, now, Events.ShortsState.ENTER, "19.40.33", 1549000000, 1),
        Events.pause(now, now - 3200, "com.google.android.youtube", Events.PauseOutcome.ALTERNATIVE, "Before bed", "com.amazon.kindle", 3200),
        Events.health(
            now, "0.1.0",
            mapOf("usage" to Events.Sensor(Events.SensorState.OK, lastEventAt = now)),
            now, 3, 100, "com.google.android.apps.nexuslauncher",
        ),
    )

    @Test
    fun `builders emit only allowed fields`() {
        for (row in samples) {
            val obj = parse(row.payload)
            assertEquals(row.type, obj.str("type"))
            assertEquals(row.id, obj.str("id"))
            val extra = obj.keys - allowed.getValue(row.type)
            assertTrue("${row.type} has $extra", extra.isEmpty())
        }
    }

    @Test
    fun `our android events carry the same field set as the contract fixture`() {
        val fixture = Json.parseToJsonElement(Contract.read("fixtures/valid.json")).jsonArray.map { it.jsonObject }
        for (row in samples.filter { it.type.startsWith("android.") || it.type == "health" }) {
            val ours = parse(row.payload).keys
            val theirs = fixture.filter { it.str("type") == row.type }.flatMap { it.keys }.toSet()
            assertTrue("${row.type}: ${ours - theirs} not in fixture", (ours - theirs).isEmpty())
        }
    }

    @Test
    fun `non-activity usage kinds carry no package or class`() {
        val obj = parse(samples[1].payload)
        assertFalse("package" in obj)
        assertFalse("class" in obj)
    }

    @Test
    fun `string limits are applied`() {
        val row = Events.notificationPosted(now, now, "p".repeat(400), "r", false, false, false, 99, "c".repeat(50))
        val obj = parse(row.payload)
        assertEquals(255, obj.str("package")!!.length)
        assertEquals(32, obj.str("category")!!.length)
        assertFalse("importance outside -1..5 is dropped", "importance" in obj)
        val p = parse(Events.pause(now, now, "p".repeat(400), Events.PauseOutcome.OPENED, "b".repeat(80), "a".repeat(300), -5).payload)
        assertEquals(255, p.str("package")!!.length)
        assertEquals(60, p.str("block")!!.length)
        assertEquals(255, p.str("alternative")!!.length)
        assertEquals("a wait is never negative", "0", p["wait_ms"]!!.jsonPrimitive.content)
        val h = Events.health(now, "v".repeat(40), mapOf("usage" to Events.Sensor(Events.SensorState.STOPPED, "d".repeat(200))), null, 0, 0, null)
        val ho = parse(h.payload)
        assertEquals(32, ho.str("client_version")!!.length)
        assertEquals(120, ho["sensors"]!!.jsonObject["usage"]!!.jsonObject["detail"]!!.jsonPrimitive.content.length)
    }
}
