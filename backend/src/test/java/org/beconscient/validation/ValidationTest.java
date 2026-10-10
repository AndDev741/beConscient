package org.beconscient.validation;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.beconscient.support.Ev;
import org.beconscient.support.FakeCollector;
import org.beconscient.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.beconscient.support.Ev.MIN;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestSecurity(user = "owner", roles = "owner")
class ValidationTest {

    @Inject
    TestData data;

    FakeCollector phone;

    @BeforeEach
    void reset() {
        data.reset();
        data.at("2026-10-10T12:00:00Z");
        phone = new FakeCollector().paired(data.device("Phone", "android").code(), "android");
        // Coverage from the 4th; 270 minutes on the 6th and 260 on the 7th
        phone.ok(Ev.screenOn(Ev.lisbon("2026-10-04T12:00")), Ev.screenOff(Ev.lisbon("2026-10-04T12:00")));
        session(Ev.lisbon("2026-10-06T10:00"), 270);
        session(Ev.lisbon("2026-10-07T10:00"), 260);
        data.derive();
    }

    void session(long s, int minutes) {
        phone.ok(Ev.screenOn(s), Ev.unlocked(s), Ev.resumed(s, "com.whatsapp"), Ev.paused(s + minutes * MIN, "com.whatsapp"),
                Ev.screenOff(s + minutes * MIN));
    }

    void caughtUp() {
        long now = Ev.utc("2026-10-10T11:55:00Z");
        phone.ok(Ev.phoneHealth(now, now));
    }

    static void set(String date, int minutes, int status) {
        given().contentType(ContentType.JSON).body("{\"minutes\":" + minutes + "}")
                .when().put("/api/validation/" + date).then().statusCode(status);
    }

    static JsonPath rows() {
        return given().queryParam("from", "2026-10-01").queryParam("to", "2026-10-09")
                .when().get("/api/validation").then().statusCode(200).extract().jsonPath();
    }

    @Test
    void aDifferenceAboveTenPercentIsFlagged() {
        caughtUp();
        set("2026-10-06", 240, 200);
        set("2026-10-07", 240, 200);
        JsonPath r = rows();
        assertEquals("2026-10-05", r.getString("[0].date"), "days before the phone's coverage are skipped");
        String six = "find { it.date == '2026-10-06' }.";
        assertEquals(270, r.getInt(six + "measuredMinutes"));
        assertEquals(240, r.getInt(six + "settingsMinutes"));
        assertEquals(12.5, r.getDouble(six + "diffPct"), 1e-9);
        assertTrue(r.getBoolean(six + "flagged"));
        assertTrue(r.getBoolean(six + "closed"));
        String seven = "find { it.date == '2026-10-07' }.";
        assertEquals(8.3, r.getDouble(seven + "diffPct"), 1e-9);
        assertFalse(r.getBoolean(seven + "flagged"));
        assertNull(r.get("find { it.date == '2026-10-08' }.diffPct"));
    }

    @Test
    void theFlagIsRecomputedWhenTheSegmentsChange() {
        caughtUp();
        set("2026-10-07", 240, 200);
        assertFalse(rows().getBoolean("find { it.date == '2026-10-07' }.flagged"));
        // A late session read from the phone history makes the day longer
        session(Ev.lisbon("2026-10-07T20:00"), 20);
        data.derive();
        JsonPath r = rows();
        assertEquals(280, r.getInt("find { it.date == '2026-10-07' }.measuredMinutes"));
        assertTrue(r.getBoolean("find { it.date == '2026-10-07' }.flagged"));
    }

    @Test
    void onlyClosedPastDaysAcceptInput() {
        given().contentType(ContentType.JSON).body("{\"minutes\":100}").when().put("/api/validation/2026-10-06")
                .then().statusCode(409).body("error", is("day_not_closed"));
        caughtUp();
        given().contentType(ContentType.JSON).body("{\"minutes\":100}").when().put("/api/validation/2026-10-10")
                .then().statusCode(409).body("error", is("day_not_closed"));
        given().contentType(ContentType.JSON).body("{\"minutes\":100}").when().put("/api/validation/2026-10-06")
                .then().statusCode(200).body("settingsMinutes", is(100)).body("closed", is(true));
        given().contentType(ContentType.JSON).body("{\"minutes\":-1}").when().put("/api/validation/2026-10-06")
                .then().statusCode(400);
    }

    @Test
    void rawAndClippedTotalsSitSideBySide() {
        long s = Ev.lisbon("2026-10-08T09:00");
        // Thirty minutes of an app behind the lock screen count as raw only
        phone.ok(Ev.screenOn(s), Ev.locked(s), Ev.resumed(s, "com.example.maps"), Ev.unlocked(s + 30 * MIN),
                Ev.paused(s + 60 * MIN, "com.example.maps"), Ev.screenOff(s + 60 * MIN));
        data.derive();
        JsonPath r = rows();
        assertEquals(30, r.getInt("find { it.date == '2026-10-08' }.measuredMinutes"));
        assertEquals(60, r.getInt("find { it.date == '2026-10-08' }.rawMinutes"));
        given().queryParam("from", "2026-10-09").queryParam("to", "2026-10-01").when().get("/api/validation")
                .then().statusCode(400);
        given().when().get("/api/validation").then().statusCode(200).body("[0].settingsMinutes", nullValue());
    }
}
