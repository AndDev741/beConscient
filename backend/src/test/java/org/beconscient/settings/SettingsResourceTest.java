package org.beconscient.settings;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.beconscient.category.Category;
import org.beconscient.support.Ev;
import org.beconscient.support.FakeCollector;
import org.beconscient.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@TestSecurity(user = "owner", roles = "owner")
class SettingsResourceTest {

    @Inject
    TestData data;

    @BeforeEach
    void reset() {
        data.reset();
        data.at("2026-10-10T12:00:00Z");
    }

    @Test
    void theDefaultsAreAWeekdayWorkWindowAndAnAwakeWindow() {
        given().when().get("/api/settings").then().statusCode(200)
                .body("workWindow.days", is(List.of(1, 2, 3, 4, 5)))
                .body("workWindow.start", is("09:00")).body("workWindow.end", is("18:00"))
                .body("awakeWindow.start", is("07:00")).body("awakeWindow.end", is("23:00"))
                .body("history", hasSize(2)).body("nonWorkdays", hasSize(0));
    }

    @Test
    void anEditIsAVersionFromTodayAndASecondEditTheSameDayReplacesIt() {
        given().contentType(ContentType.JSON).body("{\"days\":[1,2,3,4],\"start\":\"10:00\",\"end\":\"19:00\"}")
                .when().put("/api/settings/work-window").then().statusCode(200)
                .body("workWindow.validFrom", is("2026-10-10")).body("workWindow.days", is(List.of(1, 2, 3, 4)));
        given().contentType(ContentType.JSON).body("{\"days\":[1,2,3,4,5],\"start\":\"08:30\",\"end\":\"17:30\"}")
                .when().put("/api/settings/work-window").then().statusCode(200)
                .body("workWindow.start", is("08:30"))
                .body("history.findAll { it.kind == 'work' }", hasSize(2))
                .body("history.find { it.kind == 'work' && it.validFrom == '2026-10-10' }.value.start", is("08:30"))
                .body("history.find { it.kind == 'work' && it.validFrom == '2026-10-10' }.value.days", is(List.of(1, 2, 3, 4, 5)));
        // The next day gets its own version
        data.at("2026-10-11T12:00:00Z");
        given().contentType(ContentType.JSON).body("{\"start\":\"08:00\",\"end\":\"01:00\"}")
                .when().put("/api/settings/awake-window").then().statusCode(200)
                .body("awakeWindow.end", is("01:00")).body("awakeWindow.validFrom", is("2026-10-11"))
                .body("history", hasSize(4));
    }

    @Test
    void pastDaysKeepTheirOwnVersion() {
        FakeCollector agent = new FakeCollector().paired(data.device("Laptop", "desktop").code(), "desktop");
        agent.ok(Ev.activity(Ev.id(), Ev.lisbon("2026-10-06T09:30"), Ev.lisbon("2026-10-06T10:00"), 1));
        data.derive();
        given().contentType(ContentType.JSON).body("{\"days\":[1,2,3,4,5],\"start\":\"10:00\",\"end\":\"19:00\"}")
                .when().put("/api/settings/work-window").then().statusCode(200);
        given().queryParam("date", "2026-10-06").when().get("/api/day").then().statusCode(200)
                .body("byCategory.find { it.categoryId == '" + Category.WORK + "' }.minutes", is(30));
    }

    @Test
    void nonWorkdaysAreAddedAndRemoved() {
        given().contentType(ContentType.JSON).body("{\"date\":\"2026-12-25\"}").when().post("/api/non-workdays")
                .then().statusCode(200).body("nonWorkdays", is(List.of("2026-12-25")));
        given().contentType(ContentType.JSON).body("{\"date\":\"2026-12-25\"}").when().post("/api/non-workdays")
                .then().statusCode(200).body("nonWorkdays", hasSize(1));
        given().when().delete("/api/non-workdays/2026-12-25").then().statusCode(200).body("nonWorkdays", hasSize(0));
    }

    @Test
    void badInputIsRefused() {
        given().contentType(ContentType.JSON).body("{\"days\":[0,8],\"start\":\"09:00\",\"end\":\"18:00\"}")
                .when().put("/api/settings/work-window").then().statusCode(400).body("error", is("bad_request"));
        given().contentType(ContentType.JSON).body("{\"days\":[1],\"start\":\"9am\",\"end\":\"18:00\"}")
                .when().put("/api/settings/work-window").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"start\":\"07:00\",\"end\":\"07:00\"}")
                .when().put("/api/settings/awake-window").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"date\":\"25/12/2026\"}").when().post("/api/non-workdays")
                .then().statusCode(400);
    }
}
