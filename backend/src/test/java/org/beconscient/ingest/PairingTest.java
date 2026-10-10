package org.beconscient.ingest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.beconscient.api.Dto;
import org.beconscient.auth.GlobalRateLimiter;
import org.beconscient.crypto.Bytes;
import org.beconscient.crypto.Crockford;
import org.beconscient.crypto.Hpke;
import org.beconscient.device.Fingerprint;
import org.beconscient.support.Ev;
import org.beconscient.support.FakeCollector;
import org.beconscient.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestSecurity(user = "owner", roles = "owner")
class PairingTest {

    @Inject
    TestData data;

    @Inject
    GlobalRateLimiter limiter;

    @BeforeEach
    void reset() {
        data.reset();
        data.at("2026-10-10T12:00:00Z");
    }

    @Test
    void aCodeFromThePagePairsTheCollectorAndBothShowTheSameFingerprint() {
        var created = given().contentType(ContentType.JSON)
                .body("{\"name\":\"Phone\",\"kind\":\"android\",\"confirmSecondPhone\":false}")
                .when().post("/api/devices").then().statusCode(200)
                .body("device.name", is("Phone")).body("device.installations", hasSize(0))
                .body("code", notNullValue()).body("expiresAt", is(Instant.parse("2026-10-10T12:15:00Z").toEpochMilli()))
                .extract();
        String code = created.path("code");
        assertTrue(code.matches("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}"), code);

        FakeCollector phone = new FakeCollector();
        FakeCollector.Reply r = phone.pair(code, "android");
        assertEquals(200, r.status());
        String expected = Fingerprint.of(phone.installationId, Bytes.sha256(phone.secret));
        assertEquals(created.path("device.id"), r.plaintext().get("device_id").asText());
        assertEquals("Phone", r.plaintext().get("device_name").asText());
        assertEquals(expected, r.plaintext().get("fingerprint").asText());

        given().when().get("/api/devices").then().statusCode(200)
                .body("[0].installations", hasSize(1))
                .body("[0].installations[0].id", is(phone.installationId.toString()))
                .body("[0].installations[0].fingerprint", is(expected))
                .body("[0].installations[0].revokedAt", nullValue())
                .body("[0].installations[0].cloneSuspected", is(false));
        // The code is never stored in clear
        assertEquals(0, data.count("select count(*) from pairing_codes where encode(code_hash, 'escape') like ?", "%" + code + "%"));
    }

    @Test
    void theFingerprintFollowsTheContractFormula() {
        // SHA-256("beconscient-fp" || installation_id || SHA-256(secret)), first 8 Crockford symbols
        byte[] secret = Bytes.fromB64url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");
        java.util.UUID id = java.util.UUID.fromString("0192f3a0-1c2b-7cde-8f00-112233445566");
        byte[] digest = Bytes.sha256(Bytes.utf8("beconscient-fp"), Bytes.utf8(id.toString()), Bytes.sha256(secret));
        String fp = Fingerprint.of(id, Bytes.sha256(secret));
        assertEquals(Crockford.group(Crockford.encode(digest, 8)), fp);
        assertEquals(9, fp.length());
    }

    @Test
    void typedCodesAreNormalisedLikeTheCollectorsDo() {
        assertEquals("7KQ2-M9XD", Crockford.normalizeCode(" 7kq2m9xd "));
        assertEquals("0111-ABCD", Crockford.normalizeCode("oIl1-abcd"));
        assertEquals(null, Crockford.normalizeCode("7KQ2-M9X"));
        assertEquals(null, Crockford.normalizeCode("7KQ2-M9XU"));

        String code = data.device("Laptop", "desktop").code();
        String sloppy = code.toLowerCase().replace("-", " ").replace('0', 'o').replace('1', 'l');
        assertEquals(200, new FakeCollector().pair(sloppy, "desktop").status());
    }

    @Test
    void aCodeWorksOnceAndExpiresAfterFifteenMinutes() {
        String code = data.device("Laptop", "desktop").code();
        assertEquals(200, new FakeCollector().pair(code, "desktop").status());
        limiter.reset();
        assertEquals(401, new FakeCollector().pair(code, "desktop").status(), "single use");

        String late = data.device("Laptop 2", "desktop").code();
        data.at("2026-10-10T12:15:01Z");
        limiter.reset();
        FakeCollector.Reply r = new FakeCollector().pair(late, "desktop");
        assertEquals(401, r.status());
        assertEquals("{\"error\":\"unauthorized\"}", r.body());
    }

    @Test
    void withoutAnOpenCodePairingFailsFastAndCountsNothing() {
        FakeCollector.Reply r = new FakeCollector().pair("7KQ2-M9XD", "android");
        assertEquals(401, r.status());
        given().when().get("/api/pairing-codes/state").then().statusCode(200)
                .body("failures", is(0)).body("lockedOut", is(false));
    }

    @Test
    void tenConsecutiveFailuresInvalidateEveryOpenCodeAndShowOnThePage() {
        String code = data.device("Phone", "android").code();
        for (int i = 0; i < 10; i++) {
            limiter.reset();
            assertEquals(401, new FakeCollector().pair("AAAA-AAA" + i, "android").status());
        }
        given().when().get("/api/pairing-codes/state").then().statusCode(200)
                .body("failures", is(10)).body("lockedOut", is(true));
        limiter.reset();
        assertEquals(401, new FakeCollector().pair(code, "android").status(), "the real code was invalidated too");

        // A new code clears the banner and works
        String fresh = given().when().post("/api/devices/" + data.jdbc().one("select id from devices", rs -> rs.getString(1)).orElseThrow()
                + "/pairing-code").then().statusCode(200).extract().path("code");
        given().when().get("/api/pairing-codes/state").then().body("lockedOut", is(false));
        limiter.reset();
        assertEquals(200, new FakeCollector().pair(fresh, "android").status());
        given().when().get("/api/pairing-codes/state").then().body("failures", is(0));
    }

    @Test
    void pairingSharesTheGlobalLimiterWithLogin() {
        data.device("Phone", "android");
        for (int i = 0; i < 3; i++) {
            assertEquals(401, new FakeCollector().pair("ZZZZ-ZZZ" + i, "android").status());
        }
        FakeCollector.Reply r = new FakeCollector().pair("ZZZZ-ZZZ9", "android");
        assertEquals(429, r.status());
        assertEquals("{\"error\":\"rate_limited\"}", r.body());
        given().contentType(ContentType.URLENC).formParam("j_username", "owner").formParam("j_password", "beconscient-dev")
                .when().post("/api/login").then().statusCode(429);
    }

    @Test
    void relinkingRevokesTheOlderInstallationAndKeepsItsEvents() {
        Dto.CreatedDevice d = data.device("Phone", "android");
        FakeCollector first = new FakeCollector().paired(d.code(), "android");
        first.ok(Ev.screenOn(Ev.utc("2026-10-10T08:00:00Z")));

        String code = given().when().post("/api/devices/" + d.device().id() + "/pairing-code")
                .then().statusCode(200).body("expiresAt", notNullValue()).extract().path("code");
        FakeCollector second = new FakeCollector().paired(code, "android");

        given().when().get("/api/devices").then().statusCode(200)
                .body("[0].installations", hasSize(2))
                .body("[0].installations.find { it.id == '" + first.installationId + "' }.revokedAt", notNullValue())
                .body("[0].installations.find { it.id == '" + second.installationId + "' }.revokedAt", nullValue());
        assertEquals(1, data.count("select count(*) from raw_events"));
        // The revoked installation can no longer send
        assertEquals(401, first.send(Ev.screenOff(Ev.utc("2026-10-10T08:05:00Z"))).status());
        assertEquals(200, second.send(Ev.screenOff(Ev.utc("2026-10-10T08:05:00Z"))).status());
    }

    @Test
    void revokingAnInstallationKeepsItsEvents() {
        Dto.CreatedDevice d = data.device("Laptop", "desktop");
        FakeCollector agent = new FakeCollector().paired(d.code(), "desktop");
        agent.ok(Ev.desktopHealth(Ev.utc("2026-10-10T08:00:00Z"), false));
        given().when().delete("/api/installations/" + agent.installationId).then().statusCode(204);
        assertEquals(401, agent.send(Ev.desktopHealth(Ev.utc("2026-10-10T08:05:00Z"), false)).status());
        assertEquals(1, data.count("select count(*) from raw_events"));
        given().when().get("/api/devices").then().body("[0].installations[0].revokedAt", notNullValue());
    }

    @Test
    void aSecondPhoneNeedsConfirmation() {
        String code = given().contentType(ContentType.JSON).body("{\"name\":\"Phone\",\"kind\":\"android\"}")
                .when().post("/api/devices").then().statusCode(200).extract().path("code");
        new FakeCollector().paired(code, "android");
        given().contentType(ContentType.JSON).body("{\"name\":\"Old phone\",\"kind\":\"android\",\"confirmSecondPhone\":false}")
                .when().post("/api/devices").then().statusCode(409).body("error", is("second_phone")).body("message", notNullValue());
        given().contentType(ContentType.JSON).body("{\"name\":\"Old phone\",\"kind\":\"android\",\"confirmSecondPhone\":true}")
                .when().post("/api/devices").then().statusCode(200);
        // A computer never needs it
        given().contentType(ContentType.JSON).body("{\"name\":\"Laptop\",\"kind\":\"desktop\"}")
                .when().post("/api/devices").then().statusCode(200);
    }

    @Test
    void aCodeForAComputerDoesNotPairAPhone() {
        String code = data.device("Laptop", "desktop").code();
        assertEquals(401, new FakeCollector().pair(code, "android").status());
    }

    @Test
    void aPairingEnvelopeNeverOpensAsABatchAndViceVersa() {
        String code = data.device("Laptop", "desktop").code();
        FakeCollector c = new FakeCollector();
        FakeCollector.Reply r = FakeCollector.post("/ingest/v1/batches", Hpke.Purpose.PAIR, c.pairingPlaintext(code, "desktop", "x"));
        assertEquals(400, r.status());
        assertEquals("{\"error\":\"bad_envelope\"}", r.body());
        limiter.reset();
        FakeCollector.Reply b = FakeCollector.post("/ingest/v1/pair", Hpke.Purpose.BATCH, c.pairingPlaintext(code, "desktop", "x"));
        assertEquals(400, b.status());
        limiter.reset();
        // The code survived both
        assertEquals(200, c.pair(code, "desktop").status());
    }

    @Test
    void theResponseOnlyOpensWithTheRequestsOwnKey() {
        String code = data.device("Laptop", "desktop").code();
        FakeCollector c = new FakeCollector();
        byte[][] key = new byte[1][];
        String env = FakeCollector.envelope(FakeCollector.TEST_KEY.kid(), FakeCollector.TEST_KEY.publicKey(),
                Hpke.Purpose.PAIR, c.pairingPlaintext(code, "desktop", "x").getBytes(), key);
        FakeCollector.Reply r = FakeCollector.postEnvelope("/ingest/v1/pair", env, key[0]);
        assertNotNull(r.plaintext());
        byte[] otherKey = Hpke.seal(FakeCollector.TEST_KEY.publicKey(), "test-0001", Hpke.Purpose.PAIR, new byte[1]).responseKey();
        var box = r.body();
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> {
            var n = Ev.JSON.readTree(box);
            Hpke.openResponse(otherKey, Bytes.fromB64url(n.get("nonce").asText()), Bytes.fromB64url(n.get("ct").asText()));
        });
    }
}
