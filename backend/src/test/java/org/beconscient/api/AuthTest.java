package org.beconscient.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.beconscient.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page is reachable from the internet, so "unauthenticated gets nothing" is
 * a property worth a test rather than a property worth assuming.
 */
@QuarkusTest
class AuthTest {

    @Inject
    TestData data;

    @BeforeEach
    void reset() {
        data.reset();
    }

    @Test
    void everyApiPathIsClosedWithoutASession() {
        String[] gets = {"/api/session", "/api/day?date=2026-10-10", "/api/trends?period=week", "/api/categories",
                "/api/subjects", "/api/devices", "/api/pairing-codes/state", "/api/settings", "/api/backstage",
                "/api/backstage/trace?segmentId=1", "/api/validation", "/api/corrections", "/api/anything-else"};
        for (String path : gets) {
            given().when().get(path).then().statusCode(401);
        }
        given().contentType(ContentType.JSON).body("{\"name\":\"x\",\"kind\":\"android\"}")
                .when().post("/api/devices").then().statusCode(401);
        given().contentType(ContentType.JSON).body("{\"name\":\"x\",\"color\":\"#ffffff\"}")
                .when().post("/api/categories").then().statusCode(401);
        given().when().delete("/api/installations/00000000-0000-0000-0000-000000000000").then().statusCode(401);
        given().contentType(ContentType.JSON).body("{\"minutes\":1}")
                .when().put("/api/validation/2026-10-01").then().statusCode(401);
    }

    @Test
    void healthAndIngestStayOpen() {
        given().when().get("/q/health/ready").then().statusCode(200);
        given().when().get("/q/health/live").then().statusCode(200);
        // Reached the handler, not the auth wall
        given().contentType(ContentType.JSON).body("{}").when().post("/ingest/v1/batches")
                .then().statusCode(400).body("error", is("bad_envelope"));
    }

    @Test
    void signingInOpensTheApiAndTheCookieCarriesIt() {
        String cookie = login("beconscient-dev").then().statusCode(200).extract().cookie("beconscient-session");
        given().cookie("beconscient-session", cookie)
                .when().get("/api/session")
                .then().statusCode(200).body("username", is("owner"));
        given().cookie("beconscient-session", cookie).when().get("/api/categories").then().statusCode(200);
    }

    @Test
    void theCookieIsOutOfReachOfScriptsAndLogoutExpiresIt() {
        var cookie = login("beconscient-dev").then().statusCode(200).extract().detailedCookie("beconscient-session");
        assertTrue(cookie.isHttpOnly(), "the session cookie must not be readable by scripts");
        var cleared = given().cookie("beconscient-session", cookie.getValue())
                .when().post("/api/logout")
                .then().statusCode(204)
                .extract().detailedCookie("beconscient-session");
        assertEquals("", cleared.getValue());
        assertEquals(0, cleared.getMaxAge());
    }

    @Test
    void wrongPasswordIsRejected() {
        login("not-the-password").then().statusCode(401);
    }

    @Test
    void loginBacksOffAfterFailuresAndForwardedHeadersDoNotResetIt() {
        for (int i = 0; i < 3; i++) {
            given().header("X-Forwarded-For", "10.0.0." + i)
                    .contentType(ContentType.URLENC).formParam("j_username", "owner").formParam("j_password", "wrong")
                    .when().post("/api/login").then().statusCode(401);
        }
        // The backoff has started: even the right password from a "new" address waits
        given().header("X-Forwarded-For", "192.168.1.1").header("X-Real-IP", "192.168.1.1")
                .contentType(ContentType.URLENC).formParam("j_username", "owner").formParam("j_password", "beconscient-dev")
                .when().post("/api/login")
                .then().statusCode(429).body("error", is("rate_limited"));
    }

    private static io.restassured.response.Response login(String password) {
        return given().contentType(ContentType.URLENC)
                .formParam("j_username", "owner")
                .formParam("j_password", password)
                .when().post("/api/login");
    }
}
