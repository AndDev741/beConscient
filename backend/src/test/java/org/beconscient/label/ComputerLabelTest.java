package org.beconscient.label;

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

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.beconscient.support.Ev.lisbon;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestSecurity(user = "owner", roles = "owner")
class ComputerLabelTest {

    static final UUID VIDEO = UUID.fromString("00000000-0000-0000-0000-000000000011");
    static final UUID MESSAGING = UUID.fromString("00000000-0000-0000-0000-000000000012");

    @Inject
    TestData data;

    FakeCollector phone;
    FakeCollector agent;
    String study;

    @BeforeEach
    void reset() {
        data.reset();
        data.at("2026-10-10T12:00:00Z");
        phone = new FakeCollector().paired(data.device("Phone", "android").code(), "android");
        agent = new FakeCollector().paired(data.device("Laptop", "desktop").code(), "desktop");
        study = given().contentType(ContentType.JSON).body("{\"name\":\"Study\",\"color\":\"#3366cc\"}")
                .when().post("/api/categories").then().statusCode(201).extract().path("id");
    }

    static JsonPath day(String date) {
        return given().queryParam("date", date).when().get("/api/day").then().statusCode(200).extract().jsonPath();
    }

    static long cat(JsonPath d, Object id) {
        Integer m = d.get("byCategory.find { it.categoryId == '" + id + "' }.minutes");
        return m == null ? 0 : m;
    }

    static String body(String date, String start, String end, Object category) {
        return "{\"date\":\"" + date + "\",\"start\":\"" + start + "\",\"end\":\"" + end + "\",\"categoryId\":\"" + category + "\"}";
    }

    static JsonPath mark(String date, String start, String end, Object category) {
        return given().contentType(ContentType.JSON).body(body(date, start, end, category))
                .when().post("/api/computer-labels").then().statusCode(201).extract().jsonPath();
    }

    static int markStatus(String date, String start, String end, Object category) {
        return given().contentType(ContentType.JSON).body(body(date, start, end, category))
                .when().post("/api/computer-labels").then().extract().statusCode();
    }

    static void map(String subject, UUID category) {
        given().contentType(ContentType.JSON).body("{\"categoryId\":\"" + category + "\"}")
                .when().put("/api/subjects/{key}/category", subject).then().statusCode(200);
    }

    @Test
    void markedComputerTimeOutsideTheWorkWindowTakesTheCategory() {
        // Tuesday evening, outside the 09:00-18:00 window
        agent.ok(Ev.activity(Ev.id(), lisbon("2026-10-06T20:00"), lisbon("2026-10-06T22:30"), 1));
        data.derive();
        assertEquals(150, cat(day("2026-10-06"), Category.PERSONAL_COMPUTER));

        JsonPath label = mark("2026-10-06", "20:00", "21:45", study);
        assertEquals(lisbon("2026-10-06T20:00"), label.getLong("from"));
        assertEquals(lisbon("2026-10-06T21:45"), label.getLong("to"));
        assertEquals(study, label.getString("categoryId"));

        JsonPath d = day("2026-10-06");
        assertEquals(105, cat(d, study));
        assertEquals(45, cat(d, Category.PERSONAL_COMPUTER));
        assertEquals(150, d.getInt("totals.screenMinutes"));
        assertEquals(1, d.getList("computerLabels").size());
        assertEquals(label.getString("id"), d.getString("computerLabels[0].id"));
        assertEquals(105, d.getInt("computerLabels[0].minutes"));
        JsonPath hour = new JsonPath(d.prettify()).setRootPath("hours.find { it.label == '20:00' }");
        assertEquals("Study", hour.getString("segments[0].label"));
    }

    @Test
    void aMarkWinsOverTheWorkWindow() {
        agent.ok(Ev.activity(Ev.id(), lisbon("2026-10-06T10:00"), lisbon("2026-10-06T11:00"), 1));
        data.derive();
        mark("2026-10-06", "10:30", "12:00", Category.PERSONAL_COMPUTER);
        JsonPath d = day("2026-10-06");
        assertEquals(30, cat(d, Category.WORK));
        assertEquals(30, cat(d, Category.PERSONAL_COMPUTER));
        assertEquals(30, d.getInt("computerLabels[0].minutes"), "only the time the computer was in use counts");
    }

    @Test
    void sitesAndThePhoneKeepTheirOwnCategories() {
        agent.ok(Ev.activity(Ev.id(), lisbon("2026-10-06T20:00"), lisbon("2026-10-06T21:00"), 1),
                Ev.site(Ev.id(), lisbon("2026-10-06T20:10"), lisbon("2026-10-06T20:20"), 1, "youtube", true));
        phone.ok(List.of(Ev.screenOn(lisbon("2026-10-06T20:40")), Ev.unlocked(lisbon("2026-10-06T20:40")),
                Ev.resumed(lisbon("2026-10-06T20:40"), "com.whatsapp"), Ev.paused(lisbon("2026-10-06T20:50"), "com.whatsapp"),
                Ev.screenOff(lisbon("2026-10-06T20:50"))));
        data.derive();
        map("site:youtube", VIDEO);
        map("app:com.whatsapp", MESSAGING);
        mark("2026-10-06", "20:00", "21:00", study);
        JsonPath d = day("2026-10-06");
        assertEquals(40, cat(d, study));
        assertEquals(10, cat(d, VIDEO));
        assertEquals(10, cat(d, MESSAGING));
        assertEquals(40, d.getInt("computerLabels[0].minutes"));
    }

    @Test
    void aStretchEndingBeforeItStartsCrossesMidnight() {
        agent.ok(Ev.activity(Ev.id(), lisbon("2026-10-06T22:30"), lisbon("2026-10-07T01:30"), 1));
        data.derive();
        JsonPath label = mark("2026-10-06", "23:00", "01:00", study);
        assertEquals(lisbon("2026-10-07T01:00"), label.getLong("to"));
        JsonPath tue = day("2026-10-06");
        assertEquals(60, cat(tue, study));
        assertEquals(30, cat(tue, Category.PERSONAL_COMPUTER));
        assertEquals(60, tue.getInt("computerLabels[0].minutes"));
        JsonPath wed = day("2026-10-07");
        assertEquals(60, cat(wed, study));
        assertEquals(30, cat(wed, Category.PERSONAL_COMPUTER));
        assertEquals(60, wed.getInt("computerLabels[0].minutes"));
    }

    @Test
    void removingAMarkPutsTheTimeBack() {
        agent.ok(Ev.activity(Ev.id(), lisbon("2026-10-06T20:00"), lisbon("2026-10-06T21:00"), 1));
        data.derive();
        String id = mark("2026-10-06", "20:00", "21:00", study).getString("id");
        assertEquals(60, cat(day("2026-10-06"), study));

        given().when().delete("/api/categories/{id}", study).then().statusCode(409);
        given().when().delete("/api/computer-labels/{id}", id).then().statusCode(204);
        JsonPath d = day("2026-10-06");
        assertEquals(60, cat(d, Category.PERSONAL_COMPUTER));
        assertEquals(0, d.getList("computerLabels").size());
        given().when().delete("/api/computer-labels/{id}", id).then().statusCode(404);
        given().when().delete("/api/categories/{id}", study).then().statusCode(204);
    }

    @Test
    void refusesOverlapsAndCategoriesWithoutAMeaning() {
        mark("2026-10-06", "20:00", "21:00", study);
        assertEquals(409, markStatus("2026-10-06", "20:30", "22:00", study));
        assertEquals(409, markStatus("2026-10-06", "19:00", "20:01", study));
        assertEquals(201, markStatus("2026-10-06", "21:00", "22:00", study), "touching is not overlapping");
        assertEquals(400, markStatus("2026-10-06", "10:00", "11:00", Category.UNATTRIBUTED));
        assertEquals(400, markStatus("2026-10-06", "10:00", "11:00", Category.UNCATEGORIZED));
        assertEquals(400, markStatus("2026-10-06", "10:00", "10:00", study));
        assertEquals(400, markStatus("2026-10-06", "9:00", "10:00", study));
        assertEquals(400, markStatus("06/10/2026", "09:00", "10:00", study));
        assertEquals(404, markStatus("2026-10-06", "09:00", "10:00", UUID.randomUUID()));
    }
}
