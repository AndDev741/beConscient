package org.beconscient.correction;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.beconscient.support.Ev;
import org.beconscient.support.FakeCollector;
import org.beconscient.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.beconscient.support.Ev.HOUR;
import static org.beconscient.support.Ev.MIN;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestSecurity(user = "owner", roles = "owner")
class CorrectionTest {

    @Inject
    TestData data;

    FakeCollector agent;
    String agentId;
    long s = Ev.lisbon("2026-10-06T14:00");

    @BeforeEach
    void reset() {
        data.reset();
        data.at("2026-10-10T12:00:00Z");
        var d = data.device("Laptop", "desktop");
        agentId = d.device().id();
        agent = new FakeCollector().paired(d.code(), "desktop");
        agent.ok(Ev.activity(Ev.id(), s, s + HOUR, 1), Ev.site(Ev.id(), s, s + 20 * MIN, 1, "youtube", true),
                Ev.site(Ev.id(), s + 30 * MIN, s + 40 * MIN, 1, "youtube", true));
        data.derive();
    }

    int siteMinutes() {
        return given().queryParam("date", "2026-10-06").when().get("/api/day").then().statusCode(200)
                .extract().jsonPath().getInt("totals.screenMinutes")
                - (int) given().queryParam("date", "2026-10-06").when().get("/api/day").then()
                .extract().jsonPath().getInt("byCategory.find { it.name == 'Work' }.minutes");
    }

    @Test
    void redactingYoutubeTitlesRemovesTheFieldAuditsWithoutContentAndMarksBlocks() {
        given().contentType(ContentType.JSON)
                .body("{\"deviceId\":\"" + agentId + "\",\"type\":\"browser.site\",\"from\":" + s + ",\"to\":" + (s + HOUR)
                        + ",\"field\":\"title\"}")
                .when().post("/api/corrections/redactions").then().statusCode(200)
                .body("field", is("title")).body("affected", is(2)).body("from", is(s)).body("deviceId", is(agentId));
        assertEquals(0, data.count("select count(*) from raw_events where payload::text like '%A video title%'"));
        assertEquals(1, data.count("select count(*) from dirty_blocks"));
        assertEquals(0, data.count("select count(*) from redactions where field <> 'title'"));
        given().when().get("/api/corrections").then().statusCode(200)
                .body("redactions", hasSize(1)).body("redactions[0].type", is("browser.site"))
                .body("exclusions", hasSize(0));
    }

    @Test
    void redactingWithoutAFieldDeletesTheEvents() {
        given().contentType(ContentType.JSON)
                .body("{\"deviceId\":\"" + agentId + "\",\"type\":\"browser.site\",\"from\":\"2026-10-06\",\"to\":\"2026-10-06\",\"field\":null}")
                .when().post("/api/corrections/redactions").then().statusCode(200)
                .body("affected", is(2)).body("field", nullValue());
        data.derive();
        assertEquals(0, data.count("select count(*) from segments where kind = 'site'"));
        assertEquals(60, data.count("select sum(end_ts - start_ts) / 60000 from segments"));
    }

    @Test
    void anExclusionHidesEventsFromDerivationUntilItIsRemoved() {
        assertEquals(30, siteMinutes());
        String id = given().contentType(ContentType.JSON)
                .body("{\"deviceId\":\"" + agentId + "\",\"type\":\"browser.site\",\"from\":" + (s + 25 * MIN) + ",\"to\":"
                        + (s + HOUR) + ",\"note\":\"tab left open\"}")
                .when().post("/api/corrections/exclusions").then().statusCode(200)
                .body("note", is("tab left open")).extract().path("id");
        data.derive();
        assertEquals(20, siteMinutes());
        given().when().get("/api/corrections").then().body("exclusions", hasSize(1));
        given().when().delete("/api/corrections/exclusions/" + id).then().statusCode(204);
        data.derive();
        assertEquals(30, siteMinutes());
        given().when().delete("/api/corrections/exclusions/" + id).then().statusCode(404);
    }

    @Test
    void badRequestsAreRefused() {
        given().contentType(ContentType.JSON)
                .body("{\"deviceId\":\"" + agentId + "\",\"type\":\"browser.site\",\"from\":1,\"to\":2,\"field\":\"id\"}")
                .when().post("/api/corrections/redactions").then().statusCode(400);
        given().contentType(ContentType.JSON)
                .body("{\"deviceId\":\"00000000-0000-0000-0000-000000000000\",\"type\":\"browser.site\",\"from\":1,\"to\":2}")
                .when().post("/api/corrections/exclusions").then().statusCode(404);
        given().contentType(ContentType.JSON)
                .body("{\"deviceId\":\"" + agentId + "\",\"type\":\"browser.site\",\"from\":5,\"to\":2}")
                .when().post("/api/corrections/exclusions").then().statusCode(400);
        given().contentType(ContentType.JSON)
                .body("{\"deviceId\":\"" + agentId + "\",\"type\":\"android.location\",\"from\":1,\"to\":2}")
                .when().post("/api/corrections/exclusions").then().statusCode(400);
    }
}
