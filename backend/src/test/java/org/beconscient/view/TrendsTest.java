package org.beconscient.view;

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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.beconscient.support.Ev.MIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestSecurity(user = "owner", roles = "owner")
class TrendsTest {

    @Inject
    TestData data;

    FakeCollector phone;

    /** Day i from 28 September has 10 + i minutes of WhatsApp at 10:00. */
    @BeforeEach
    void reset() {
        data.reset();
        // Wednesday 7 October, 12:00 in Lisbon
        data.at("2026-10-07T11:00:00Z");
        phone = new FakeCollector().paired(data.device("Phone", "android").code(), "android");
        List<ObjectNode> events = new ArrayList<>();
        // Coverage starts the evening before Monday 28 September
        events.add(Ev.screenOn(Ev.lisbon("2026-09-27T23:00")));
        events.add(Ev.screenOff(Ev.lisbon("2026-09-27T23:00")));
        LocalDate d = LocalDate.of(2026, 9, 28);
        for (int i = 0; !d.isAfter(LocalDate.of(2026, 10, 7)); i++, d = d.plusDays(1)) {
            long s = Ev.lisbon(d + "T10:00");
            events.add(Ev.screenOn(s));
            events.add(Ev.unlocked(s));
            events.add(Ev.resumed(s, "com.whatsapp"));
            events.add(Ev.paused(s + (10 + i) * MIN, "com.whatsapp"));
            events.add(Ev.screenOff(s + (10 + i) * MIN));
        }
        phone.ok(events);
        data.derive();
    }

    void caughtUp() {
        long now = Ev.utc("2026-10-07T10:55:00Z");
        phone.ok(Ev.phoneHealth(now, now));
    }

    static JsonPath trends(String period, String anchor) {
        return given().queryParam("period", period).queryParam("anchor", anchor)
                .when().get("/api/trends").then().statusCode(200).extract().jsonPath();
    }

    @Test
    void aWeekInProgressIsComparedWithTheSameElapsedSpan() {
        caughtUp();
        JsonPath t = trends("week", "2026-10-07");
        assertEquals("week", t.getString("period"));
        assertEquals("2026-10-05", t.getString("current.from"));
        assertEquals("2026-10-07", t.getString("current.to"));
        // Monday and Tuesday count; Wednesday (today) is left out of both sides
        assertEquals(17 + 18, t.getInt("current.screenMinutes"));
        assertEquals((960 - 17) + (960 - 18), t.getInt("current.awayMinutes"));
        assertEquals("2026-09-28", t.getString("previous.from"));
        assertEquals("2026-09-30", t.getString("previous.to"));
        assertEquals(10 + 11, t.getInt("previous.screenMinutes"));
        assertEquals(3, t.getList("series").size());
        assertTrue(t.getBoolean("series[0].complete"));
        assertFalse(t.getBoolean("series[2].complete"));
        assertEquals(19, t.getInt("series[2].screenMinutes"), "today still shows in the series");
        assertEquals(35, (int) t.get("current.byCategory.find { it.name == 'Uncategorized' }.minutes"));
    }

    @Test
    void previousIsNullWithoutAFullPreviousSpanOfCoveredDays() {
        caughtUp();
        JsonPath t = trends("week", "2026-10-01");
        assertNull(t.get("previous"));
        assertEquals("2026-09-28", t.getString("current.from"));
        assertEquals("2026-10-04", t.getString("current.to"));
        assertEquals(10 + 11 + 12 + 13 + 14 + 15 + 16, t.getInt("current.screenMinutes"));
        JsonPath month = trends("month", "2026-10-07");
        assertNull(month.get("previous"), "September 1-7 is before coverage");
        assertEquals(13 + 14 + 15 + 16 + 17 + 18, month.getInt("current.screenMinutes"));
        assertEquals(7, month.getList("series").size());
    }

    @Test
    void dayPeriodComparesWithTheDayBefore() {
        caughtUp();
        JsonPath t = trends("day", "2026-10-06");
        assertEquals(18, t.getInt("current.screenMinutes"));
        assertEquals(17, t.getInt("previous.screenMinutes"));
        assertEquals("2026-10-05", t.getString("previous.from"));
    }

    @Test
    void daysThatAreNotClosedAreLeftOut() {
        // The phone has not caught up: every past day is still arriving
        JsonPath t = trends("week", "2026-10-07");
        assertEquals(0, t.getInt("current.screenMinutes"));
        assertNull(t.get("previous"));
        assertFalse(t.getBoolean("series[0].complete"));
        assertEquals(17, t.getInt("series[0].screenMinutes"));
    }

    @Test
    void anUnknownPeriodIsRefused() {
        given().queryParam("period", "year").when().get("/api/trends").then().statusCode(400);
    }
}
