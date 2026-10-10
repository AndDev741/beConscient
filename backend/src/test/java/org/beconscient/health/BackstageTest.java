package org.beconscient.health;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.beconscient.support.Ev;
import org.beconscient.support.FakeCollector;
import org.beconscient.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.beconscient.support.Ev.MIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestSecurity(user = "owner", roles = "owner")
class BackstageTest {

    static final long NOW = Ev.utc("2026-10-10T12:00:00Z");

    @Inject
    TestData data;

    FakeCollector phone;
    FakeCollector agent;

    @BeforeEach
    void reset() {
        data.reset();
        data.at("2026-10-10T12:00:00Z");
        phone = new FakeCollector().paired(data.device("Phone", "android").code(), "android");
        agent = new FakeCollector().paired(data.device("Laptop", "desktop").code(), "desktop");
    }

    static JsonPath backstage() {
        return given().when().get("/api/backstage").then().statusCode(200).extract().jsonPath();
    }

    static String state(JsonPath b, String device, String sensor) {
        return b.getString("devices.find { it.name == '" + device + "' }.sensors.find { it.name == '" + sensor + "' }.state");
    }

    @Test
    void sensorStatesFollowTheLatestHealthRecord() {
        ObjectNode h = Ev.phoneHealth(NOW - 10 * MIN, NOW - 10 * MIN);
        h.with("sensors").with("notifications").put("state", "stopped").put("detail", "listener permission off");
        phone.ok(h);
        agent.ok(Ev.desktopHealth(NOW - 2 * 60 * MIN, false));
        JsonPath b = backstage();
        assertEquals("active", state(b, "Phone", "usage"));
        assertEquals("stopped", state(b, "Phone", "notifications"));
        assertEquals("listener permission off",
                b.getString("devices.find { it.name == 'Phone' }.sensors.find { it.name == 'notifications' }.detail"));
        assertEquals(NOW - 10 * MIN, b.getLong("devices.find { it.name == 'Phone' }.sensors[0].lastContact"));
        assertEquals("silent", state(b, "Laptop", "activity"));

        phone.ok(Ev.phoneHealth(NOW - 30 * MIN + 1, NOW - 30 * MIN));
        data.update("delete from raw_events where ts = ?", NOW - 10 * MIN);
        assertEquals("late", state(backstage(), "Phone", "usage"));

        agent.ok(Ev.desktopHealth(NOW - 60 * MIN, true));
        assertEquals("expected_stop", state(backstage(), "Laptop", "activity"));
    }

    @Test
    void aDeviceThatNeverReportedIsSilent() {
        JsonPath b = backstage();
        assertEquals("silent", state(b, "Phone", "usage"));
        assertNull(b.get("devices.find { it.name == 'Phone' }.sensors[0].lastContact"));
        assertEquals(List.of("Firefox", "YouTube in the phone browser", "Computer leisure outside the browser"),
                b.getList("blindSpots"));
    }

    @Test
    void ae4ShortsDetectionShowsSinceWhenAndWhichVersionItStopped() {
        long first = Ev.utc("2026-10-05T20:00:00Z");
        long broken = Ev.utc("2026-10-08T21:00:00Z");
        phone.ok(Ev.shorts(first, "enter", "19.40.33"), Ev.shorts(first + MIN, "exit", "19.40.33"),
                Ev.shorts(broken, "unclassified", "19.41.10"), Ev.shorts(broken + MIN, "unclassified", "19.41.10"));
        JsonPath b = backstage();
        assertEquals("19.40.33", b.getString("devices.find { it.name == 'Phone' }.shorts.lastConfirmedVersion"));
        assertEquals("19.41.10", b.getString("devices.find { it.name == 'Phone' }.shorts.unconfirmedSinceVersion"));
        assertEquals(broken, b.getLong("devices.find { it.name == 'Phone' }.shorts.unconfirmedSince"));
        assertNull(b.get("devices.find { it.name == 'Laptop' }.shorts"));
    }

    @Test
    void countersLagSkewAndEventsPerDay() {
        String id = Ev.id();
        long s = NOW - 3 * 60 * MIN;
        ObjectNode site = Ev.site(Ev.id(), s, s + MIN, 1, "linkedin", false);
        site.put("title", "dropped");
        agent.ok(Ev.activity(id, s, s + 10 * MIN, 1), Ev.activity(id, s, s + 5 * MIN, 1),
                Ev.activity(id, s + MIN, s + 5 * MIN, 2), site, Ev.activity(Ev.id(), s + 5, s, 1));
        JsonPath b = backstage();
        String laptop = "devices.find { it.name == 'Laptop' }.";
        assertEquals(1, b.getInt(laptop + "duplicates") - b.getInt(laptop + "intervalConflicts"));
        assertEquals(1, b.getInt(laptop + "intervalConflicts"));
        assertEquals(1, b.getInt(laptop + "rejected"));
        assertEquals(1, b.getInt(laptop + "droppedFields"));
        assertEquals(NOW - agent.sentAt, b.getLong(laptop + "clockSkewMs"));
        assertEquals(14, b.getList(laptop + "eventsPerDay").size());
        assertEquals("2026-10-10", b.getString(laptop + "eventsPerDay[13].date"));
        assertEquals(2, b.getInt(laptop + "eventsPerDay[13].count"));
        assertTrue(b.getLong(laptop + "lagMs.p50") >= 2 * 60 * MIN);
        assertEquals(1, b.getInt("derivation.version"));
        assertFalse(b.getBoolean("derivation.rebuilding"));
        assertTrue(b.getLong("database.sizeBytes") > 0);
        assertEquals(10737418240L, b.getLong("database.budgetBytes"));
    }

    @Test
    void theDatabaseAlertStartsAtSeventyPercent() {
        assertFalse(BackstageService.alert(700, 1000));
        assertTrue(BackstageService.alert(701, 1000));
    }

    @Test
    void gapsAndInvariantViolationsAreShown() {
        agent.ok(Ev.desktopHealth(NOW - 5 * 60 * MIN, false), Ev.desktopHealth(NOW - 4 * 60 * MIN, true),
                Ev.desktopHealth(NOW - 2 * 60 * MIN, false), Ev.desktopHealth(NOW - 5 * MIN, false));
        data.update("""
                insert into blocks (device_id, day, version, segment_count, invariant_violations, derived_at)
                select device_id, '2026-10-01', 1, 0, 2, now() from installations where id = ?""", agent.installationId);
        JsonPath b = backstage();
        String laptop = "devices.find { it.name == 'Laptop' }.";
        assertEquals(2, b.getInt(laptop + "invariantViolations"));
        List<Boolean> expected = b.getList(laptop + "gaps.expected");
        assertEquals(List.of(false, true, false), expected);
        assertEquals("activity", b.getString(laptop + "gaps[0].sensor"));
        assertEquals(NOW - 4 * 60 * MIN, b.getLong(laptop + "gaps[1].from"));
    }

    @Test
    void aTraceShowsTheRawEventsAndRulesBehindASegment() {
        long s = Ev.utc("2026-10-08T10:00:00Z");
        ObjectNode resumed = Ev.resumed(s + 1000, "com.whatsapp");
        phone.ok(Ev.screenOn(s), Ev.unlocked(s), resumed, Ev.paused(s + 10 * MIN, "com.whatsapp"), Ev.screenOff(s + 10 * MIN));
        data.derive();
        long segmentId = data.count("select id from segments where subject = 'app:com.whatsapp'");
        JsonPath t = given().queryParam("segmentId", segmentId).when().get("/api/backstage/trace")
                .then().statusCode(200).extract().jsonPath();
        assertEquals(Long.toString(segmentId), t.getString("segment.id"));
        assertEquals("app:com.whatsapp", t.getString("segment.subjectKey"));
        assertEquals("com.whatsapp", t.getString("segment.label"));
        assertEquals("00000000-0000-0000-0000-000000000003", t.getString("segment.categoryId"));
        assertEquals(1, t.getInt("segment.derivationVersion"));
        assertTrue(t.getList("events.id").contains(resumed.get("id").asText()));
        assertEquals("ACTIVITY_RESUMED", t.getString("events.find { it.id == '" + resumed.get("id").asText() + "' }.kind"));
        assertTrue(t.getList("rules", String.class).stream().anyMatch(r -> r.contains("screen was on and unlocked")));
        given().queryParam("segmentId", "999999").when().get("/api/backstage/trace").then().statusCode(404);
    }
}
