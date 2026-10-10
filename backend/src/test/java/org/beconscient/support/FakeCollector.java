package org.beconscient.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.beconscient.crypto.Bytes;
import org.beconscient.crypto.Hpke;
import org.beconscient.crypto.HpkeKey;
import org.beconscient.crypto.KeyRing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * Plays a collector against the real endpoints: seals envelopes to the test
 * public key, numbers batches and opens the encrypted answers.
 */
public class FakeCollector {

    private static final SecureRandom RANDOM = new SecureRandom();
    public static final HpkeKey TEST_KEY = loadTestKey();

    public final UUID installationId = UUID.randomUUID();
    public final byte[] secret = new byte[32];
    public long seq;
    public String tz = "Europe/Lisbon";
    public long sentAt = 1760090000123L;

    /** The answer to one request: status, and the opened plaintext for a 200. */
    public record Reply(int status, String body, JsonNode plaintext, byte[] rawBody) {
    }

    public FakeCollector() {
        RANDOM.nextBytes(secret);
    }

    static HpkeKey loadTestKey() {
        try (InputStream in = FakeCollector.class.getClassLoader().getResourceAsStream("hpke/test-keypair.json")) {
            return KeyRing.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).get(0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String envelope(String kid, byte[] publicKey, Hpke.Purpose purpose, byte[] plaintext, byte[][] keyOut) {
        Hpke.Sealed sealed = Hpke.seal(publicKey, kid, purpose, plaintext);
        if (keyOut != null) {
            keyOut[0] = sealed.responseKey();
        }
        ObjectNode env = Ev.JSON.createObjectNode();
        env.put("v", 1);
        env.put("kid", kid);
        env.put("enc", Bytes.b64url(sealed.enc()));
        env.put("ct", Bytes.b64url(sealed.ct()));
        return env.toString();
    }

    /** Posts a sealed plaintext to an ingest path and opens the reply. */
    public static Reply post(String path, Hpke.Purpose purpose, String plaintext) {
        byte[][] key = new byte[1][];
        String env = envelope(TEST_KEY.kid(), TEST_KEY.publicKey(), purpose, plaintext.getBytes(StandardCharsets.UTF_8), key);
        return postEnvelope(path, env, key[0]);
    }

    public static Reply postEnvelope(String path, String envelope, byte[] responseKey) {
        Response r = given().contentType(ContentType.JSON).body(envelope).when().post(path);
        String body = r.asString();
        JsonNode pt = null;
        if (r.statusCode() == 200 && responseKey != null) {
            try {
                JsonNode box = Ev.JSON.readTree(body);
                byte[] opened = Hpke.openResponse(responseKey, Bytes.fromB64url(box.get("nonce").asText()),
                        Bytes.fromB64url(box.get("ct").asText()));
                pt = Ev.JSON.readTree(opened);
            } catch (Exception e) {
                throw new IllegalStateException("response did not open", e);
            }
        }
        return new Reply(r.statusCode(), body, pt, r.asByteArray());
    }

    public String pairingPlaintext(String code, String kind, String name) {
        ObjectNode p = Ev.JSON.createObjectNode();
        p.put("code", code);
        p.put("installation_id", installationId.toString());
        p.put("secret", Bytes.b64url(secret));
        p.put("kind", kind);
        p.put("name", name);
        p.put("client_version", "0.1.0");
        return p.toString();
    }

    public Reply pair(String code, String kind) {
        return post("/ingest/v1/pair", Hpke.Purpose.PAIR, pairingPlaintext(code, kind, "Test " + kind));
    }

    /** Pairs and fails loudly if it does not work. */
    public FakeCollector paired(String code, String kind) {
        Reply r = pair(code, kind);
        if (r.status() != 200) {
            throw new IllegalStateException("pairing failed: " + r.status() + " " + r.body());
        }
        return this;
    }

    public String batchPlaintext(long seq, List<? extends JsonNode> events) {
        return batchPlaintext(seq, Bytes.b64url(secret), events);
    }

    public String batchPlaintext(long seq, String credential, List<? extends JsonNode> events) {
        ObjectNode root = Ev.JSON.createObjectNode();
        ObjectNode h = root.putObject("header");
        h.put("installation_id", installationId.toString());
        h.put("credential", credential);
        h.put("seq", seq);
        h.put("sent_at", sentAt);
        if (tz != null) {
            h.put("tz", tz);
        }
        h.put("tz_offset_min", 60);
        h.put("contract_version", 1);
        h.put("client_version", "0.1.0");
        ArrayNode arr = root.putArray("events");
        events.forEach(arr::add);
        return root.toString();
    }

    /** Freezes the next batch and sends it. */
    public Reply send(List<? extends JsonNode> events) {
        seq++;
        return post("/ingest/v1/batches", Hpke.Purpose.BATCH, batchPlaintext(seq, events));
    }

    public Reply send(JsonNode... events) {
        return send(List.of(events));
    }

    /** Sends with an explicit sequence number (resends and conflicts). */
    public Reply sendSeq(long seq, List<? extends JsonNode> events) {
        return post("/ingest/v1/batches", Hpke.Purpose.BATCH, batchPlaintext(seq, events));
    }

    /** Sends, and fails loudly unless the batch was stored. */
    public JsonNode ok(JsonNode... events) {
        Reply r = send(events);
        if (r.status() != 200) {
            throw new IllegalStateException("batch refused: " + r.status() + " " + r.body());
        }
        return r.plaintext();
    }

    public JsonNode ok(List<? extends JsonNode> events) {
        Reply r = send(events);
        if (r.status() != 200) {
            throw new IllegalStateException("batch refused: " + r.status() + " " + r.body());
        }
        return r.plaintext();
    }

    public static String status(JsonNode receipt, int index) {
        return receipt.get("results").get(index).get("status").asText();
    }
}
