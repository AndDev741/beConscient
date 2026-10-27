package org.beconscient.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.beconscient.category.Category;
import org.beconscient.support.Ev;
import org.beconscient.support.FakeCollector;
import org.beconscient.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.beconscient.support.Ev.HOUR;
import static org.beconscient.support.Ev.MIN;
import static org.beconscient.support.Ev.lisbon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestSecurity(user = "owner", roles = "owner")
class DayResourceTest {

    static final String WA = "com.whatsapp";
    static final UUID VIDEO = UUID.fromString("00000000-0000-0000-0000-000000000011");
    static final UUID MESSAGING = UUID.fromString("00000000-0000-0000-0000-000000000012");

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

    static List<ObjectNode> session(long start, long end, String pkg) {
        return List.of(Ev.screenOn(start), Ev.unlocked(start), Ev.resumed(start, pkg), Ev.paused(end, pkg), Ev.screenOff(end));
    }

    static JsonPath day(String date) {
        return given().queryParam("date", date).when().get("/api/day").then().statusCode(200).extract().jsonPath();
    }

    static long cat(JsonPath d, UUID id) {
        Integer m = d.get("byCategory.find { it.categoryId == '" + id + "' }.minutes");
        return m == null ? 0 : m;
    }

    static void map(String subject, UUID category) {
        given().contentType(ContentType.JSON).body("{\"categoryId\":\"" + category + "\"}")
                .when().put("/api/subjects/{key}/category", subject).then().statusCode(200);
    }

    @Test
    void ae1ComputerTimeOutsideTheWorkWindowIsPersonal() {
        // Tuesday 22:00 in Lisbon, window 09:00-18:00 on weekdays
        agent.ok(Ev.activity(Ev.id(), lisbon("2026-10-06T22:00"), lisbon("2026-10-06T23:00"), 1),
                Ev.activity(Ev.id(), lisbon("2026-10-06T17:30"), lisbon("2026-10-06T18:30"), 1));
        data.derive();
        JsonPath d = day("2026-10-06");
        assertEquals(90, cat(d, Category.PERSONAL_COMPUTER));
        assertEquals(30, cat(d, Category.WORK));
        assertEquals("Europe/Lisbon", d.getString("zone"));
    }

    @Test
    void ae2YoutubeInTheBrowserLeavesWork() {
        agent.ok(Ev.activity(Ev.id(), lisbon("2026-10-06T13:30"), lisbon("2026-10-06T15:00"), 1),
                Ev.site(Ev.id(), lisbon("2026-10-06T14:00"), lisbon("2026-10-06T14:20"), 1, "youtube", true));
        data.derive();
        map("site:youtube", VIDEO);
        JsonPath d = day("2026-10-06");
        assertEquals(70, cat(d, Category.WORK));
        assertEquals(20, cat(d, VIDEO));
        assertEquals("site:youtube", d.getString("topSubjects[0].subjectKey"));
        assertEquals(20, d.getInt("topSubjects[0].minutes"));
        assertEquals(90, d.getInt("totals.screenMinutes"));
    }

    @Test
    void ae8SimultaneousUseCountsOnceAndTheOverlapGoesToThePhone() {
        agent.ok(Ev.activity(Ev.id(), lisbon("2026-10-06T10:00"), lisbon("2026-10-06T11:00"), 1));
        phone.ok(session(lisbon("2026-10-06T10:20"), lisbon("2026-10-06T10:30"), WA));
        data.derive();
        map("app:" + WA, MESSAGING);
        JsonPath d = day("2026-10-06");
        assertEquals(60, d.getInt("totals.screenMinutes"));
        assertEquals(10, d.getInt("totals.overlapMinutes"));
        assertEquals(10, (int) d.get("totals.byDevice.find { it.name == 'Phone' }.minutes"));
        assertEquals(60, (int) d.get("totals.byDevice.find { it.name == 'Laptop' }.minutes"));
        assertEquals(50, cat(d, Category.WORK));
        assertEquals(10, cat(d, MESSAGING));
        JsonPath hour = new JsonPath(d.prettify()).setRootPath("hours.find { it.label == '10:00' }");
        assertEquals(50, (int) hour.get("byCategory.find { it.categoryId == '" + Category.WORK + "' }.minutes"));
        assertEquals(10, (int) hour.get("byCategory.find { it.categoryId == '" + MESSAGING + "' }.minutes"));
        assertTrue(hour.getList("segments").size() >= 2, "the hour lists the segments behind it");
    }

    @Test
    void ae10AWindowChangeAppliesFromItsDateOnwards() {
        data.at("2026-10-16T12:00:00Z");
        agent.ok(Ev.activity(Ev.id(), lisbon("2026-10-15T09:30"), lisbon("2026-10-15T10:00"), 1));
        data.update("""
                insert into window_settings (kind, valid_from, days, start_time, end_time, updated_at)
                values ('work', '2026-11-01', '1,2,3,4,5', '10:00', '19:00', now())""");
        data.at("2026-11-06T12:00:00Z");
        agent.ok(Ev.activity(Ev.id(), lisbon("2026-11-05T09:30"), lisbon("2026-11-05T10:00"), 1));
        data.derive();
        assertEquals(30, cat(day("2026-10-15"), Category.WORK), "15 October keeps 09:00-18:00");
        assertEquals(30, cat(day("2026-11-05"), Category.PERSONAL_COMPUTER), "5 November uses 10:00-19:00");
    }

    @Test
    void aNonWorkingDayIsPersonalComputer() {
        agent.ok(Ev.activity(Ev.id(), lisbon("2026-10-06T10:00"), lisbon("2026-10-06T11:00"), 1));
        data.derive();
        assertEquals(60, cat(day("2026-10-06"), Category.WORK));
        given().contentType(ContentType.JSON).body("{\"date\":\"2026-10-06\"}").when().post("/api/non-workdays")
                .then().statusCode(200);
        assertEquals(60, cat(day("2026-10-06"), Category.PERSONAL_COMPUTER));
        // Saturdays are not in the window either
        agent.ok(Ev.activity(Ev.id(), lisbon("2026-10-10T10:00"), lisbon("2026-10-10T10:30"), 1));
        data.derive();
        assertEquals(30, cat(day("2026-10-10"), Category.PERSONAL_COMPUTER));
    }

    @Test
    void ae7ANewAppIsUncategorizedAndClassifyingItFixesThePast() {
        phone.ok(session(lisbon("2026-10-06T10:00"), lisbon("2026-10-06T10:25"), "com.example.newapp"));
        data.derive();
        JsonPath before = day("2026-10-06");
        assertEquals(25, cat(before, Category.UNCATEGORIZED));
        assertEquals(Category.UNCATEGORIZED.toString(), before.getString("topSubjects[0].categoryId"));
        map("app:com.example.newapp", VIDEO);
        JsonPath after = day("2026-10-06");
        assertEquals(25, cat(after, VIDEO));
        assertEquals(0, cat(after, Category.UNCATEGORIZED));
    }

    @Test
    void theDayFollowsThePhonesZone() {
        phone.tz = "America/Sao_Paulo";
        phone.ok(Ev.tz(Ev.utc("2026-10-05T12:00:00Z"), "America/Sao_Paulo", -180));
        // 02:00 UTC on the 7th is 23:00 on the 6th in São Paulo
        agent.ok(Ev.activity(Ev.id(), Ev.utc("2026-10-07T02:00:00Z"), Ev.utc("2026-10-07T02:30:00Z"), 1));
        data.derive();
        JsonPath d = day("2026-10-06");
        assertEquals("America/Sao_Paulo", d.getString("zone"));
        assertEquals(30, cat(d, Category.PERSONAL_COMPUTER));
        assertEquals(0, day("2026-10-07").getInt("totals.screenMinutes"));
    }

    @Test
    void aLateZoneMarkerMovesTimeWithoutRederiving() {
        phone.tz = null;
        agent.ok(Ev.activity(Ev.id(), Ev.utc("2026-10-07T02:00:00Z"), Ev.utc("2026-10-07T02:30:00Z"), 1));
        data.derive();
        assertEquals(30, day("2026-10-07").getInt("totals.screenMinutes"), "Lisbon fallback: 03:00 on the 7th");
        phone.ok(Ev.tz(Ev.utc("2026-10-05T12:00:00Z"), "America/Sao_Paulo", -180));
        assertEquals(0, data.count("select count(*) from dirty_blocks"));
        assertEquals(30, day("2026-10-06").getInt("totals.screenMinutes"));
        assertEquals(0, day("2026-10-07").getInt("totals.screenMinutes"));
    }

    @Test
    void daylightSavingDaysHave25And23Hours() {
        data.at("2026-10-27T12:00:00Z");
        JsonPath october = day("2026-10-25");
        List<String> labels = october.getList("hours.label");
        assertEquals(25, labels.size());
        assertEquals(2, labels.stream().filter("01:00"::equals).count());
        assertEquals(23, day("2026-03-29").getList("hours").size());
        assertEquals(24, day("2026-10-06").getList("hours").size());
    }

    @Test
    void theHeadlineIsAwakeTimeAwayFromScreens() {
        // Awake 07:00-23:00 (960 minutes) with 5 hours of screen inside
        phone.ok(session(lisbon("2026-10-06T09:00"), lisbon("2026-10-06T14:00"), WA));
        // One hour of Shorts at 01:00 is outside the awake window
        long s = lisbon("2026-10-06T01:00");
        phone.ok(Ev.screenOn(s), Ev.unlocked(s), Ev.resumed(s, PhoneShorts.YT), Ev.shorts(s, "enter", "19.40.33"),
                Ev.screenOff(s + HOUR));
        data.derive();
        JsonPath d = day("2026-10-06");
        assertEquals(960, d.getInt("headline.awakeMinutes"));
        assertEquals(300, d.getInt("headline.screenMinutes"));
        assertEquals(660, d.getInt("headline.awayMinutes"));
        assertEquals(60, d.getInt("headline.outsideAwakeScreenMinutes"));
        assertFalse(d.getBoolean("headline.elapsedOnly"));
        assertEquals(360, d.getInt("totals.screenMinutes"));
        assertEquals("app:com.google.android.youtube#shorts",
                d.getString("topSubjects.find { it.label == 'YouTube Shorts' }.subjectKey"));
    }

    @Test
    void anAwakeWindowCrossingMidnightKeepsItsTailOnTheDayItStarted() {
        data.update("update window_settings set start_time = '08:00', end_time = '01:00' where kind = 'awake'");
        phone.ok(session(lisbon("2026-10-07T00:30"), lisbon("2026-10-07T00:45"), WA));
        data.derive();
        JsonPath sixth = day("2026-10-06");
        assertEquals(17 * 60, sixth.getInt("headline.awakeMinutes"));
        assertEquals(15, sixth.getInt("headline.screenMinutes"));
        JsonPath seventh = day("2026-10-07");
        assertEquals(15, seventh.getInt("totals.screenMinutes"));
        assertEquals(0, seventh.getInt("headline.outsideAwakeScreenMinutes"));
        assertEquals(0, seventh.getInt("headline.screenMinutes"));
    }

    @Test
    void todayCountsOnlyTheAwakeHoursAlreadyPassed() {
        // 12:00 UTC is 13:00 in Lisbon: six awake hours have passed
        JsonPath d = day("2026-10-10");
        assertTrue(d.getBoolean("headline.elapsedOnly"));
        assertEquals(360, d.getInt("headline.awakeMinutes"));
        assertEquals("open", d.getString("state"));
        JsonPath future = day("2026-10-12");
        assertEquals(0, future.getInt("headline.awakeMinutes"));
    }

    @Test
    void ae11ASilentAgentInsideTheAwakeWindowMakesTheDayIncomplete() {
        List<ObjectNode> health = new ArrayList<>();
        for (long t = lisbon("2026-10-06T08:00"); t < lisbon("2026-10-07T00:00"); t += 5 * MIN) {
            if (t >= lisbon("2026-10-06T12:00") && t < lisbon("2026-10-06T15:00")) {
                continue;
            }
            health.add(Ev.desktopHealth(t, false));
        }
        agent.ok(health);
        JsonPath d = day("2026-10-06");
        assertTrue(d.getBoolean("headline.incomplete"));
        assertTrue(d.getList("headline.silentSensors").contains("activity (Laptop)"), d.getList("headline.silentSensors").toString());
        assertFalse(day("2026-10-05").getBoolean("headline.incomplete"), "no gap before the agent ever reported");
    }

    @Test
    void aSuspendAnnouncedByTheAgentIsAnExpectedGap() {
        List<ObjectNode> health = new ArrayList<>();
        for (long t = lisbon("2026-10-06T06:00"); t < lisbon("2026-10-07T00:00"); t += 5 * MIN) {
            if (t >= lisbon("2026-10-06T12:00") && t < lisbon("2026-10-06T15:00")) {
                continue;
            }
            health.add(Ev.desktopHealth(t, t == lisbon("2026-10-06T11:55")));
        }
        agent.ok(health);
        assertFalse(day("2026-10-06").getBoolean("headline.incomplete"));
    }

    @Test
    void aPhoneSwitchedOffIsNotAGap() {
        List<ObjectNode> events = new ArrayList<>();
        for (long t = lisbon("2026-10-06T06:00"); t < lisbon("2026-10-07T00:00"); t += 15 * MIN) {
            if (t > lisbon("2026-10-06T10:00") && t < lisbon("2026-10-06T14:00")) {
                continue;
            }
            events.add(Ev.phoneHealth(t, t));
        }
        events.add(Ev.shutdown(lisbon("2026-10-06T10:05")));
        events.add(Ev.startup(lisbon("2026-10-06T13:55")));
        phone.ok(events);
        assertFalse(day("2026-10-06").getBoolean("headline.incomplete"));
        data.update("delete from raw_events where payload->>'kind' in ('DEVICE_SHUTDOWN', 'DEVICE_STARTUP')");
        JsonPath d = day("2026-10-06");
        assertTrue(d.getBoolean("headline.incomplete"));
        assertTrue(d.getList("headline.silentSensors").contains("usage (Phone)"));
    }

    @Test
    void aPastDayClosesWhenEveryActiveInstallationCaughtUp() {
        assertEquals("open", day("2026-10-10").getString("state"));
        assertEquals("arriving", day("2026-10-06").getString("state"));
        long end = lisbon("2026-10-07T00:00");
        phone.ok(Ev.phoneHealth(end + MIN, end - MIN));
        assertEquals("arriving", day("2026-10-06").getString("state"), "the phone's watermark has not passed the end");
        phone.ok(Ev.phoneHealth(end + 2 * MIN, end + MIN));
        assertEquals("arriving", day("2026-10-06").getString("state"), "the computer has not caught up");
        // The computer announced a stop with an empty queue before the end of the day
        agent.ok(Ev.desktopHealth(end - HOUR, true));
        assertEquals("closed", day("2026-10-06").getString("state"));
        // A revoked installation never holds a day open
        FakeCollector other = new FakeCollector().paired(data.device("Old laptop", "desktop").code(), "desktop");
        assertEquals("arriving", day("2026-10-06").getString("state"));
        given().when().delete("/api/installations/" + other.installationId).then().statusCode(204);
        assertEquals("closed", day("2026-10-06").getString("state"));
    }

    @Test
    void unlocksAndNotificationsAreCountedForTheDay() {
        long a = lisbon("2026-10-06T21:14");
        phone.ok(Ev.screenOn(a), Ev.unlocked(a), Ev.resumed(a + 10_000, WA), Ev.paused(a + 5 * MIN, WA), Ev.screenOff(a + 5 * MIN),
                Ev.screenOn(a + HOUR), Ev.unlocked(a + HOUR), Ev.screenOff(a + HOUR + 2 * MIN));
        // An update reuses the ref of r1 with a later time: stored, never counted as a new post
        ObjectNode update = Ev.posted(a - 30_000, WA, "r1");
        update.put("is_update", true);
        ObjectNode summary = Ev.posted(a - 3 * MIN, WA, "r-sum");
        summary.put("is_group_summary", true);
        ObjectNode ongoing = Ev.posted(a - 3 * MIN, "com.spotify.music", "r-ong");
        ongoing.put("is_ongoing", true);
        phone.ok(Ev.posted(a - MIN, WA, "r1"), Ev.posted(a - MIN, WA, "r2"), Ev.posted(a - MIN, WA, "r3"),
                update, summary, ongoing,
                Ev.removed(a, WA, "r1", "click"), Ev.removed(a, WA, "r2", "cancel"), Ev.removed(a, WA, "r3", "cancel_all"),
                Ev.removed(a, WA, "r-sum", "click"), Ev.removed(a, WA, "r-other", "app_cancel"));
        data.derive();
        JsonPath d = day("2026-10-06");
        assertEquals(2, d.getInt("unlocks.count"));
        assertEquals("app:" + WA, d.getString("unlocks.firstApps[0].subjectKey"));
        assertEquals(1, d.getInt("unlocks.firstApps[0].count"));
        assertEquals(3, (int) d.get("notifications.find { it.subjectKey == 'app:com.whatsapp' }.posted"));
        assertEquals(1, (int) d.get("notifications.find { it.subjectKey == 'app:com.whatsapp' }.tapped"));
        assertEquals(2, (int) d.get("notifications.find { it.subjectKey == 'app:com.whatsapp' }.dismissed"));
        org.junit.jupiter.api.Assertions.assertNull(d.get("notifications.find { it.subjectKey == 'app:com.spotify.music' }"));
    }

    @Test
    void hoursAddUpToTheDayAndCategoriesToTheTotal() {
        agent.ok(Ev.activity(Ev.id(), lisbon("2026-10-06T08:47"), lisbon("2026-10-06T12:13"), 1));
        phone.ok(session(lisbon("2026-10-06T09:59"), lisbon("2026-10-06T10:41"), WA));
        phone.ok(session(lisbon("2026-10-06T19:03"), lisbon("2026-10-06T19:29"), "com.example.app"));
        data.derive();
        JsonPath d = day("2026-10-06");
        int total = d.getInt("totals.screenMinutes");
        List<Integer> cats = d.getList("byCategory.minutes", Integer.class);
        assertEquals(total, cats.stream().mapToInt(Integer::intValue).sum());
        int hourSum = 0;
        for (int i = 0; i < 24; i++) {
            List<Integer> m = d.getList("hours[" + i + "].byCategory.minutes", Integer.class);
            hourSum += m.stream().mapToInt(Integer::intValue).sum();
        }
        assertTrue(Math.abs(hourSum - total) <= 2, hourSum + " vs " + total);
        assertEquals(lisbon("2026-10-06T00:00"), d.getLong("hours[0].start"));
    }

    @Test
    void theDayShowsARebuildInProgress() {
        data.update("update derivation_state set rebuilding = true, target_version = 1, total_blocks = 4, done_blocks = 1");
        JsonPath d = day("2026-10-06");
        assertTrue(d.getBoolean("derivation.rebuilding"));
        assertEquals(0.25, d.getDouble("derivation.progress"), 1e-9);
    }

    @Test
    void aMissingDateMeansTodayAndABadDateIsRefused() {
        given().when().get("/api/day").then().statusCode(200).body("date", org.hamcrest.Matchers.is("2026-10-10"));
        given().queryParam("date", "10/06/2026").when().get("/api/day").then().statusCode(400)
                .body("error", org.hamcrest.Matchers.is("bad_request"));
    }

    @Test
    void theLauncherIsCountedPerLocalDayFromRawEvents() {
        String insta = "com.instagram.android";
        ObjectNode first = Ev.focus(lisbon("2026-10-06T09:00"), "manual", lisbon("2026-10-06T09:45"));
        first.put("next", "break");
        first.put("blocked_opens", 3);
        first.put("held_notifications", 5);
        ObjectNode second = Ev.focus(lisbon("2026-10-06T09:50"), "manual", lisbon("2026-10-06T10:35"));
        second.put("cycle", 2);
        second.put("blocked_opens", 1);
        second.put("held_notifications", 2);
        ObjectNode scheduled = Ev.focus(lisbon("2026-10-06T21:00"), "scheduled", lisbon("2026-10-06T23:30"));
        scheduled.put("block", "Before bed");
        JsonNode receipt = phone.ok(first, second, scheduled,
                Ev.focus(lisbon("2026-10-06T14:00"), "rest", lisbon("2026-10-06T14:03")),
                // Runs past midnight: counts, minutes and all, on the day it started
                Ev.focus(lisbon("2026-10-06T23:40"), "manual", lisbon("2026-10-07T00:20")),
                Ev.focus(lisbon("2026-10-07T00:30"), "manual", lisbon("2026-10-07T01:00")),
                Ev.focus(lisbon("2026-10-02T10:00"), "manual", lisbon("2026-10-02T10:30")),
                Ev.pause(lisbon("2026-10-06T08:00"), insta, "opened", "reply"),
                Ev.pause(lisbon("2026-10-06T12:00"), insta, "opened", "habit"),
                Ev.pause(lisbon("2026-10-06T12:30"), insta, "opened"),
                Ev.pause(lisbon("2026-10-06T13:00"), insta, "backed_out"),
                Ev.pause(lisbon("2026-10-06T13:59"), insta, "rest"),
                Ev.pause(lisbon("2026-10-06T22:00"), insta, "alternative"));
        for (int i = 0; i < receipt.get("results").size(); i++) {
            assertEquals("accepted", FakeCollector.status(receipt, i));
        }

        JsonPath d = day("2026-10-06");
        // The rest shows only under byKind, as on the phone
        assertEquals(4, d.getInt("launcher.focus.periods"));
        assertEquals(3, d.getInt("launcher.focus.byKind.manual"));
        assertEquals(1, d.getInt("launcher.focus.byKind.scheduled"));
        assertEquals(1, d.getInt("launcher.focus.byKind.rest"));
        assertEquals(45 + 45 + 150 + 40, d.getInt("launcher.focus.minutes"));
        assertEquals(4, d.getInt("launcher.focus.blockedOpens"));
        assertEquals(7, d.getInt("launcher.focus.heldNotifications"));
        assertEquals(6, d.getInt("launcher.pauses.shown"));
        assertEquals(3, d.getInt("launcher.pauses.byOutcome.opened"));
        assertEquals(1, d.getInt("launcher.pauses.byOutcome.backedOut"));
        assertEquals(1, d.getInt("launcher.pauses.byOutcome.alternative"));
        assertEquals(1, d.getInt("launcher.pauses.byOutcome.rest"));
        assertEquals(1, d.getInt("launcher.pauses.byIntent.reply"));
        assertEquals(0, d.getInt("launcher.pauses.byIntent.specific"));
        assertEquals(1, d.getInt("launcher.pauses.byIntent.habit"));
        // Seven days ending on the shown one
        assertEquals(List.of("2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06"),
                d.getList("launcher.recent.date"));
        assertEquals(List.of(0, 0, 1, 0, 0, 0, 4), d.getList("launcher.recent.focusPeriods", Integer.class));
        assertEquals(30, d.getInt("launcher.recent[2].focusMinutes"));
        assertEquals(1, day("2026-10-07").getInt("launcher.focus.periods"));
        assertEquals(30, day("2026-10-07").getInt("launcher.focus.minutes"));

        // Pauses and focus periods never become screen time
        data.derive();
        assertEquals(0, day("2026-10-06").getInt("totals.screenMinutes"));

        // Suspect events and excluded ones are left out, as for notifications
        data.update("update raw_events set suspect = true where type = 'android.focus' and payload->>'kind' = 'rest'");
        String phoneId = data.jdbc().one("select id::text from devices where name = 'Phone'", rs -> rs.getString(1)).orElseThrow();
        given().contentType(ContentType.JSON)
                .body("{\"deviceId\":\"" + phoneId + "\",\"type\":\"android.pause\",\"from\":" + lisbon("2026-10-06T12:00")
                        + ",\"to\":" + lisbon("2026-10-06T13:00") + ",\"note\":\"lent the phone\"}")
                .when().post("/api/corrections/exclusions").then().statusCode(200);
        JsonPath after = day("2026-10-06");
        assertEquals(4, after.getInt("launcher.focus.periods"));
        assertEquals(0, after.getInt("launcher.focus.byKind.rest"));
        assertEquals(3, after.getInt("launcher.pauses.shown"));
        assertEquals(1, after.getInt("launcher.pauses.byOutcome.opened"));
        assertEquals(0, after.getInt("launcher.pauses.byIntent.habit"));
    }

    @Test
    void theLauncherFollowsThePhonesZone() {
        phone.tz = "America/Sao_Paulo";
        phone.ok(Ev.tz(Ev.utc("2026-10-05T12:00:00Z"), "America/Sao_Paulo", -180));
        // 02:00 UTC on the 7th is 23:00 on the 6th in São Paulo
        phone.ok(Ev.focus(Ev.utc("2026-10-07T02:00:00Z"), "manual", Ev.utc("2026-10-07T02:25:00Z")));
        assertEquals(1, day("2026-10-06").getInt("launcher.focus.periods"));
        JsonPath seventh = day("2026-10-07");
        assertEquals(0, seventh.getInt("launcher.focus.periods"));
        assertEquals(List.of(1, 0), seventh.getList("launcher.recent.focusPeriods", Integer.class).subList(5, 7));
        assertEquals(0, day("2026-10-01").getInt("launcher.pauses.shown"), "an empty day has zeros, not nulls");
    }

    /** Names used by the headline test. */
    static final class PhoneShorts {
        static final String YT = "com.google.android.youtube";
    }
}
