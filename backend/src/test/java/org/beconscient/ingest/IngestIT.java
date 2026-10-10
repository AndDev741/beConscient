package org.beconscient.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.beconscient.crypto.Bytes;
import org.beconscient.crypto.Hpke;
import org.beconscient.crypto.HpkeKey;
import org.beconscient.support.Ev;
import org.beconscient.support.FakeCollector;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Black-box run against the packaged application in the production profile (the
 * native binary under -Dnative): real secrets, a freshly generated key, bcrypt
 * login, the encrypted envelope both ways, background derivation and Jackson on
 * every page DTO. The test key must be refused here.
 */
@QuarkusIntegrationTest
@TestProfile(IngestIT.ProductionLike.class)
class IngestIT {

    static final String PASSWORD = "an it password that is not a dev value";
    static final HpkeKey KEY = freshKey();

    static HpkeKey freshKey() {
        byte[] sk = new byte[32];
        new SecureRandom().nextBytes(sk);
        return HpkeKey.of("it-" + System.nanoTime(), sk, false, null);
    }

    public static class ProductionLike implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "beconscient.session-key", "it-session-key-9f8e7d6c5b4a39281706f5e4d3c2b1a0",
                    "beconscient.owner-password", PASSWORD,
                    "beconscient.hpke.keys", "[{\"kid\":\"" + KEY.kid() + "\",\"private_key\":\""
                            + Bytes.b64url(KEY.privateKey()) + "\"}]",
                    "beconscient.derivation.every", "1s");
        }
    }

    static FakeCollector.Reply post(String path, Hpke.Purpose purpose, String plaintext) {
        byte[][] key = new byte[1][];
        String env = FakeCollector.envelope(KEY.kid(), KEY.publicKey(), purpose, plaintext.getBytes(StandardCharsets.UTF_8), key);
        return FakeCollector.postEnvelope(path, env, key[0]);
    }

    @Test
    void aCollectorPairsSendsAndTheDayShowsItsTime() throws Exception {
        given().when().get("/q/health/ready").then().statusCode(200);
        given().when().get("/api/day").then().statusCode(401);
        String cookie = given().contentType(ContentType.URLENC).formParam("j_username", "owner")
                .formParam("j_password", PASSWORD).when().post("/api/login")
                .then().statusCode(200).extract().cookie("beconscient-session");

        String code = given().cookie("beconscient-session", cookie).contentType(ContentType.JSON)
                .body("{\"name\":\"Laptop\",\"kind\":\"desktop\"}").when().post("/api/devices")
                .then().statusCode(200).extract().path("code");
        FakeCollector agent = new FakeCollector();
        FakeCollector.Reply paired = post("/ingest/v1/pair", Hpke.Purpose.PAIR, agent.pairingPlaintext(code, "desktop", "Laptop"));
        assertEquals(200, paired.status(), paired.body());
        assertEquals("Laptop", paired.plaintext().get("device_name").asText());

        long now = Instant.now().toEpochMilli();
        ArrayNode events = Ev.JSON.createArrayNode();
        events.add(Ev.activity(Ev.id(), now - 2 * Ev.HOUR, now - Ev.HOUR, 1));
        events.add(Ev.desktopHealth(now, false));
        agent.sentAt = now;
        String batch = agent.batchPlaintext(1, java.util.List.of(events.get(0), events.get(1)));
        FakeCollector.Reply stored = post("/ingest/v1/batches", Hpke.Purpose.BATCH, batch);
        assertEquals(200, stored.status(), stored.body());
        assertEquals("accepted", stored.plaintext().at("/results/0/status").asText());

        // The public test key is refused in production
        JsonNode vector = Ev.JSON.readTree(Files.readString(Path.of("../contract/vectors/go-reference.json"))).get(0);
        given().contentType(ContentType.JSON).body(vector.get("request").toString()).when().post("/ingest/v1/batches")
                .then().statusCode(400).body(org.hamcrest.Matchers.equalTo("{\"error\":\"bad_envelope\"}"));

        // Background derivation picks the batch up; with no phone the local day is Lisbon's
        java.time.ZoneId lisbon = java.time.ZoneId.of("Europe/Lisbon");
        java.util.Set<String> dates = new java.util.TreeSet<>(java.util.List.of(
                Instant.ofEpochMilli(now - 2 * Ev.HOUR).atZone(lisbon).toLocalDate().toString(),
                Instant.ofEpochMilli(now - Ev.HOUR - 1).atZone(lisbon).toLocalDate().toString()));
        JsonPath day = null;
        int minutes = 0;
        for (int i = 0; i < 40 && minutes != 60; i++) {
            Thread.sleep(500);
            minutes = 0;
            for (String date : dates) {
                day = given().cookie("beconscient-session", cookie).queryParam("date", date).when().get("/api/day")
                        .then().statusCode(200).extract().jsonPath();
                minutes += day.getInt("totals.screenMinutes");
            }
        }
        assertEquals(60, minutes);
        assertNotNull(day);
        assertEquals("Laptop", day.getString("totals.byDevice[0].name"));

        // Every page DTO serialises in the packaged app
        for (String path : new String[]{"/api/session", "/api/trends?period=week", "/api/categories", "/api/subjects",
                "/api/devices", "/api/pairing-codes/state", "/api/settings", "/api/backstage",
                "/api/validation", "/api/corrections"}) {
            given().cookie("beconscient-session", cookie).when().get(path).then().statusCode(200);
        }
        String segment = day.getString("hours.find { it.segments.size() > 0 }.segments[0].id");
        given().cookie("beconscient-session", cookie).queryParam("segmentId", segment).when().get("/api/backstage/trace")
                .then().statusCode(200).body("segment.id", is(segment));
        ObjectNode site = Ev.site(Ev.id(), now - 2 * Ev.HOUR, now - Ev.HOUR, 1, "youtube", true);
        given().cookie("beconscient-session", cookie).contentType(ContentType.JSON)
                .body("{\"deviceId\":\"" + paired.plaintext().get("device_id").asText() + "\",\"type\":\"browser.site\","
                        + "\"from\":" + site.get("start").asLong() + ",\"to\":" + now + ",\"note\":\"it\"}")
                .when().post("/api/corrections/exclusions").then().statusCode(200);
    }
}
