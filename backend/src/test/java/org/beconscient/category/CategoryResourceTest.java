package org.beconscient.category;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.beconscient.support.Ev;
import org.beconscient.support.FakeCollector;
import org.beconscient.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestSecurity(user = "owner", roles = "owner")
class CategoryResourceTest {

    @Inject
    TestData data;

    @BeforeEach
    void reset() {
        data.reset();
        data.at("2026-10-10T12:00:00Z");
    }

    @Test
    void builtInsAndSeedsAreListed() {
        given().when().get("/api/categories").then().statusCode(200)
                .body("name", hasItems("Work", "Personal computer", "Uncategorized", "Unattributed",
                        "Video", "Messaging", "Social", "Music", "Browsing"))
                .body("find { it.name == 'Work' }.builtin", is(true))
                .body("find { it.name == 'Work' }.id", is(Category.WORK.toString()))
                .body("find { it.name == 'Video' }.builtin", is(false));
    }

    @Test
    void createRenameAndDelete() {
        String id = given().contentType(ContentType.JSON).body("{\"name\":\"Reading\",\"color\":\"#00AA88\"}")
                .when().post("/api/categories").then().statusCode(201)
                .body("name", is("Reading")).body("color", is("#00aa88")).body("builtin", is(false))
                .extract().path("id");
        given().contentType(ContentType.JSON).body("{\"name\":\"reading\",\"color\":\"#000000\"}")
                .when().post("/api/categories").then().statusCode(409).body("error", is("category_exists"));
        given().contentType(ContentType.JSON).body("{\"name\":\"Books\",\"color\":\"red\"}")
                .when().post("/api/categories").then().statusCode(400).body("error", is("bad_request"));
        given().contentType(ContentType.JSON).body("{\"name\":\"Books\",\"color\":\"#123456\"}")
                .when().put("/api/categories/" + id).then().statusCode(200).body("name", is("Books"));
        given().when().delete("/api/categories/" + id).then().statusCode(204);
        given().when().delete("/api/categories/" + id).then().statusCode(404);
    }

    @Test
    void builtInsKeepTheirNamesAndCannotBeDeleted() {
        given().contentType(ContentType.JSON).body("{\"name\":\"Job\",\"color\":\"#5b6cff\"}")
                .when().put("/api/categories/" + Category.WORK).then().statusCode(409).body("error", is("builtin_category"));
        given().contentType(ContentType.JSON).body("{\"name\":\"Work\",\"color\":\"#111111\"}")
                .when().put("/api/categories/" + Category.WORK).then().statusCode(200).body("color", is("#111111"));
        given().when().delete("/api/categories/" + Category.UNATTRIBUTED).then().statusCode(409)
                .body("error", is("builtin_category"));
    }

    @Test
    void aCategoryInUseCannotBeDeletedAndTheDayStillAddsUp() {
        FakeCollector phone = new FakeCollector().paired(data.device("Phone", "android").code(), "android");
        long s = Ev.lisbon("2026-10-06T10:00");
        phone.ok(Ev.screenOn(s), Ev.unlocked(s), Ev.resumed(s, "com.whatsapp"), Ev.screenOff(s + 30 * Ev.MIN));
        data.derive();
        String messaging = "00000000-0000-0000-0000-000000000012";
        given().contentType(ContentType.JSON).body("{\"categoryId\":\"" + messaging + "\"}")
                .when().put("/api/subjects/app:com.whatsapp/category").then().statusCode(200)
                .body("categoryId", is(messaging)).body("minutes30d", is(30)).body("kind", is("app"));
        given().when().delete("/api/categories/" + messaging).then().statusCode(409)
                .body("error", is("category_in_use")).body("message", org.hamcrest.Matchers.notNullValue());
        given().queryParam("date", "2026-10-06").when().get("/api/day").then().statusCode(200)
                .body("totals.screenMinutes", is(30))
                .body("byCategory.find { it.categoryId == '" + messaging + "' }.minutes", is(30));
    }

    @Test
    void subjectsAreSortedByRecentTimeAndCanBeFiltered() {
        FakeCollector phone = new FakeCollector().paired(data.device("Phone", "android").code(), "android");
        FakeCollector agent = new FakeCollector().paired(data.device("Laptop", "desktop").code(), "desktop");
        long s = Ev.lisbon("2026-10-06T10:00");
        phone.ok(Ev.screenOn(s), Ev.unlocked(s), Ev.resumed(s, "com.whatsapp"), Ev.paused(s + 10 * Ev.MIN, "com.whatsapp"),
                Ev.resumed(s + 10 * Ev.MIN, "com.google.android.youtube"), Ev.screenOff(s + 50 * Ev.MIN));
        agent.ok(Ev.activity(Ev.id(), s, s + Ev.HOUR, 1), Ev.site(Ev.id(), s, s + 5 * Ev.MIN, 1, "linkedin", false));
        data.derive();
        given().when().get("/api/subjects").then().statusCode(200)
                .body("key", is(List.of("app:com.google.android.youtube", "app:com.whatsapp", "site:linkedin")))
                .body("[0].minutes30d", is(40)).body("[0].categoryId", nullValue())
                .body("[2].kind", is("site")).body("[2].label", is("LinkedIn (computer)"));
        given().contentType(ContentType.JSON).body("{\"categoryId\":\"00000000-0000-0000-0000-000000000011\"}")
                .when().put("/api/subjects/app:com.google.android.youtube/category").then().statusCode(200);
        given().queryParam("unmapped", true).when().get("/api/subjects").then()
                .body("key", is(List.of("app:com.whatsapp", "site:linkedin")));
        given().queryParam("q", "WHATS").when().get("/api/subjects").then().body("key", is(List.of("app:com.whatsapp")));
        given().queryParam("limit", 1).when().get("/api/subjects").then().body("", hasSize(1));
        // Mapping back to nothing (or to Uncategorized) unmaps it
        given().contentType(ContentType.JSON).body("{\"categoryId\":null}")
                .when().put("/api/subjects/app:com.google.android.youtube/category").then().statusCode(200)
                .body("categoryId", nullValue());
        given().contentType(ContentType.JSON).body("{\"categoryId\":\"" + Category.UNCATEGORIZED + "\"}")
                .when().put("/api/subjects/app:com.whatsapp/category").then().statusCode(200).body("categoryId", nullValue());
        given().contentType(ContentType.JSON).body("{\"categoryId\":\"" + Category.WORK + "\"}")
                .when().put("/api/subjects/{key}/category", "app:com.google.android.youtube#shorts").then().statusCode(200)
                .body("key", is("app:com.google.android.youtube#shorts")).body("label", is("YouTube Shorts"));
        given().contentType(ContentType.JSON).body("{\"categoryId\":\"" + Category.WORK + "\"}")
                .when().put("/api/subjects/site:example.com/category").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"categoryId\":\"00000000-0000-0000-0000-00000000ffff\"}")
                .when().put("/api/subjects/app:com.whatsapp/category").then().statusCode(404);
    }
}
