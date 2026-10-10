package org.beconscient.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.beconscient.crypto.Bytes;
import org.beconscient.crypto.Hpke;
import org.beconscient.support.Ev;
import org.beconscient.support.FakeCollector;
import org.beconscient.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class IngestResourceTest {

    static final String NOW = "2026-10-10T12:00:00Z";

    @Inject
    TestData data;

    FakeCollector phone;
    FakeCollector agent;

    @BeforeEach
    void reset() {
        data.reset();
        data.at(NOW);
        phone = new FakeCollector().paired(data.device("Phone", "android").code(), "android");
        agent = new FakeCollector().paired(data.device("Laptop", "desktop").code(), "desktop");
    }

    long rows() {
        return data.count("select count(*) from raw_events");
    }

    long dirty() {
        return data.count("select count(*) from dirty_blocks");
    }

    @Test
    void aValidBatchStoresEveryEventAndAnswersWithAnEncryptedReceipt() {
        long t = Ev.utc("2026-10-10T09:00:00Z");
        List<ObjectNode> events = List.of(Ev.screenOn(t), Ev.unlocked(t + 500), Ev.resumed(t + 1000, "com.whatsapp"));
        FakeCollector.Reply r = phone.send(events);
        assertEquals(200, r.status());
        JsonNode receipt = r.plaintext();
        assertEquals(phone.installationId.toString(), receipt.get("installation_id").asText());
        assertEquals(1, receipt.get("seq").asLong());
        assertEquals(3, receipt.get("results").size());
        for (int i = 0; i < 3; i++) {
            assertEquals(events.get(i).get("id").asText(), receipt.get("results").get(i).get("id").asText());
            assertEquals("accepted", FakeCollector.status(receipt, i));
        }
        assertEquals(3, rows());
        assertEquals(1, dirty(), "one block (device, UTC day) marked");
        // The body is ciphertext only
        assertFalse(r.body().contains("accepted"));
        assertTrue(r.body().contains("\"nonce\""));
    }

    @Test
    void eventsFromAFlightKeepTheirOwnTimeApartFromTheReceiveTime() {
        long threeHoursAgo = Instant.parse(NOW).toEpochMilli() - 3 * Ev.HOUR;
        phone.ok(Ev.screenOn(threeHoursAgo));
        assertEquals(threeHoursAgo, data.count("select ts from raw_events"));
        assertEquals(Instant.parse(NOW).toEpochMilli(),
                data.count("select (extract(epoch from received_at) * 1000)::bigint from raw_events"));
        assertEquals(Instant.parse(NOW).toEpochMilli() - phone.sentAt,
                data.count("select clock_skew_ms from batch_log where seq = 1 and installation_id = ?", phone.installationId));
    }

    @Test
    void theSameFrozenBatchSentTwiceGetsTheSameReceiptAndNoNewRows() {
        List<ObjectNode> events = List.of(Ev.screenOn(Ev.utc("2026-10-10T09:00:00Z")), Ev.screenOff(Ev.utc("2026-10-10T09:10:00Z")));
        FakeCollector.Reply first = phone.send(events);
        FakeCollector.Reply again = phone.sendSeq(1, events);
        assertEquals(200, again.status());
        assertEquals(first.plaintext(), again.plaintext());
        assertNotEquals(first.body(), again.body(), "newly encrypted");
        assertEquals(2, rows());
        assertEquals(1, data.count("select count(*) from batch_log"));
    }

    @Test
    void aReplayADayLaterChangesNoRowAndNotTheLastContact() {
        List<ObjectNode> events = List.of(Ev.screenOn(Ev.utc("2026-10-10T09:00:00Z")));
        phone.ok(events);
        long seen = data.count("select (extract(epoch from last_seen_at) * 1000)::bigint from installations where id = ?",
                phone.installationId);
        data.at("2026-10-11T12:00:00Z");
        assertEquals(200, phone.sendSeq(1, events).status());
        assertEquals(seen, data.count("select (extract(epoch from last_seen_at) * 1000)::bigint from installations where id = ?",
                phone.installationId));
        assertEquals(1, rows());
    }

    @Test
    void aLowerSequenceWithADifferentListIsAConflictAndFlagsAClone() {
        phone.ok(Ev.screenOn(Ev.utc("2026-10-10T09:00:00Z")));
        phone.ok(Ev.screenOff(Ev.utc("2026-10-10T09:05:00Z")));
        FakeCollector.Reply r = phone.sendSeq(1, List.of(Ev.screenOn(Ev.utc("2026-10-10T09:06:00Z"))));
        assertEquals(409, r.status());
        assertEquals("{\"error\":\"sequence_conflict\"}", r.body());
        assertEquals(1, data.count("select count(*) from installations where clone_suspected"));
        assertEquals(2, rows());
        // Same seq as the last one, different list: also a conflict
        assertEquals(409, phone.sendSeq(2, List.of(Ev.screenOn(Ev.utc("2026-10-10T09:07:00Z")))).status());
    }

    @Test
    void aGapInTheSequenceIsRecordedButNotRefused() {
        phone.ok(Ev.screenOn(Ev.utc("2026-10-10T09:00:00Z")));
        assertEquals(200, phone.sendSeq(5, List.of(Ev.screenOff(Ev.utc("2026-10-10T09:05:00Z")))).status());
        assertEquals(3, data.count("select gap_before from batch_log where seq = 5"));
    }

    @Test
    void aNaturalKeyRepeatedUnderANewIdIsADuplicate() {
        long t = Ev.utc("2026-10-10T09:00:00Z");
        ObjectNode a = Ev.resumed(t, "com.whatsapp");
        ObjectNode b = a.deepCopy();
        b.put("id", Ev.id());
        JsonNode receipt = phone.ok(a, b);
        assertEquals("accepted", FakeCollector.status(receipt, 0));
        assertEquals("duplicate", FakeCollector.status(receipt, 1));
        assertEquals(1, rows());
        // A post is keyed by ref and time: the same post re-read is a duplicate, an update is not
        ObjectNode post = Ev.posted(t, "com.whatsapp", "r-1");
        ObjectNode reread = post.deepCopy();
        reread.put("id", Ev.id());
        ObjectNode update = Ev.posted(t + 5_000, "com.whatsapp", "r-1");
        update.put("is_update", true);
        JsonNode n = phone.ok(post, reread, update);
        assertEquals("accepted", FakeCollector.status(n, 0));
        assertEquals("duplicate", FakeCollector.status(n, 1));
        assertEquals("accepted", FakeCollector.status(n, 2));
        // A removal is keyed by ref alone
        JsonNode rm = phone.ok(Ev.removed(t + 9_000, "com.whatsapp", "r-1", "click"), Ev.removed(t + 9_500, "com.whatsapp", "r-1", "click"));
        assertEquals("duplicate", FakeCollector.status(rm, 1));
        // A repeated id is a duplicate too
        assertEquals("duplicate", FakeCollector.status(phone.ok(a), 0));
        assertEquals(4, data.count("select duplicates from device_counters"));
    }

    @Test
    void intervalRevisionsReplaceTheEndEitherWayAndKeepTheStart() {
        String id = Ev.id();
        long s = Ev.utc("2026-10-09T23:00:00Z");
        agent.ok(Ev.activity(id, s, s + 3 * Ev.HOUR, 1));
        data.update("delete from dirty_blocks");
        // A higher revision with an earlier end (idle trim) marks both the old and the new range
        JsonNode r = agent.ok(Ev.activity(id, s, s + 30 * Ev.MIN, 2));
        assertEquals("accepted", FakeCollector.status(r, 0));
        assertEquals(s + 30 * Ev.MIN, data.count("select end_ts from raw_events"));
        assertEquals(2, data.count("select count(distinct day) from dirty_blocks"));
        // Lower or equal revision: duplicate, ignored
        JsonNode low = agent.ok(Ev.activity(id, s, s + 5 * Ev.HOUR, 2), Ev.activity(id, s, s + 5 * Ev.HOUR, 1));
        assertEquals("duplicate", FakeCollector.status(low, 0));
        assertEquals("duplicate", FakeCollector.status(low, 1));
        assertEquals(s + 30 * Ev.MIN, data.count("select end_ts from raw_events"));
        // A different start is a conflict, recorded and ignored
        JsonNode moved = agent.ok(Ev.activity(id, s + Ev.MIN, s + 2 * Ev.HOUR, 9));
        assertEquals("duplicate", FakeCollector.status(moved, 0));
        assertEquals(1, data.count("select count(*) from interval_conflicts"));
        assertEquals(1, data.count("select interval_conflicts from device_counters"));
        assertEquals(s, data.count("select ts from raw_events"));
        assertEquals(2, data.count("select rev from raw_events"));
    }

    @Test
    void aRejectedEventGoesToQuarantineAndTheRestOfTheBatchIsStored() {
        long t = Ev.utc("2026-10-10T09:00:00Z");
        ObjectNode bad = Ev.activity(Ev.id(), t + Ev.HOUR, t, 1);
        JsonNode r = agent.ok(Ev.activity(Ev.id(), t, t + Ev.HOUR, 1), bad);
        assertEquals("accepted", FakeCollector.status(r, 0));
        assertEquals("rejected", FakeCollector.status(r, 1));
        assertEquals("end_before_start", r.get("results").get(1).get("reason").asText());
        assertEquals(1, rows());
        assertEquals(1, data.count("select count(*) from quarantine where reason = 'end_before_start'"));
        assertEquals(1, data.count("select rejected from device_counters"));
    }

    @Test
    void aNotificationTitleIsDroppedAndCounted() {
        ObjectNode n = Ev.posted(Ev.utc("2026-10-10T20:14:00Z"), "com.whatsapp", "ref-1");
        n.put("title", "Maria");
        n.put("text", "hi");
        n.put("key", "0|com.whatsapp|1|x|10123");
        phone.ok(n);
        assertEquals(0, data.count("select count(*) from raw_events where payload::text like '%Maria%' or payload::text like '%10123%'"));
        assertEquals(3, data.count("select dropped_fields from device_counters"));
    }

    @Test
    void otherSitesAreRejectedAndLinkedinTitlesDropped() {
        long t = Ev.utc("2026-10-10T09:00:00Z");
        ObjectNode other = Ev.site(Ev.id(), t, t + Ev.MIN, 1, "youtube", false);
        other.put("site", "example.com");
        ObjectNode linkedin = Ev.site(Ev.id(), t, t + Ev.MIN, 1, "linkedin", false);
        linkedin.put("title", "Jane Doe | LinkedIn");
        JsonNode r = agent.ok(other, linkedin);
        assertEquals("rejected", FakeCollector.status(r, 0));
        assertEquals("invalid_enum", r.get("results").get(0).get("reason").asText());
        assertEquals("accepted", FakeCollector.status(r, 1));
        assertEquals(0, data.count("select count(*) from raw_events where payload::text like '%Jane%'"));
        // The quarantine never holds the forbidden site name either
        assertEquals(0, data.count("select count(*) from quarantine where payload::text like '%example.com%'"));
    }

    @Test
    void theContractFixturesBehaveAsListed() throws Exception {
        Path contract = Path.of(System.getProperty("beconscient.contract.dir", "../contract"));
        JsonNode valid = Ev.JSON.readTree(Files.readString(contract.resolve("fixtures/valid.json")));
        List<JsonNode> events = new ArrayList<>();
        valid.forEach(events::add);
        JsonNode receipt = phone.ok(events);
        for (int i = 0; i < events.size(); i++) {
            assertEquals("accepted", FakeCollector.status(receipt, i), events.get(i).toString());
        }
        assertEquals(0, data.count("select coalesce(sum(dropped_fields), 0) from device_counters"));
        // Stored as is: every type-specific field survives unchanged
        for (JsonNode e : events) {
            JsonNode stored = Ev.JSON.readTree(data.jdbc().one("select payload::text from raw_events where event_id = ?::uuid",
                    rs -> rs.getString(1), e.get("id").asText()).orElseThrow());
            e.fields().forEachRemaining(f -> {
                if (!Arrays.asList("id", "type", "at", "start", "end", "rev", "tz", "tz_offset_min").contains(f.getKey())) {
                    assertEquals(f.getValue(), stored.get(f.getKey()), e.get("type") + "." + f.getKey());
                }
            });
        }

        JsonNode forbidden = Ev.JSON.readTree(Files.readString(contract.resolve("fixtures/forbidden.json")));
        for (JsonNode c : forbidden) {
            JsonNode r = agent.ok(c.get("event"));
            JsonNode result = r.get("results").get(0);
            String what = c.get("case").asText();
            switch (c.get("expect").asText()) {
                case "accepted_without" -> {
                    assertEquals("accepted", result.get("status").asText(), what);
                    String payload = data.jdbc().one("select payload::text from raw_events where event_id = ?::uuid",
                            rs -> rs.getString(1), c.get("event").get("id").asText()).orElseThrow();
                    for (JsonNode f : c.get("fields")) {
                        assertFalse(Ev.JSON.readTree(payload).has(f.asText()), what + ": " + f.asText());
                    }
                }
                case "rejected" -> {
                    assertEquals("rejected", result.get("status").asText(), what);
                    assertEquals(c.get("reason").asText(), result.get("reason").asText(), what);
                }
                default -> throw new AssertionError("unknown expectation " + c);
            }
        }
    }

    @Test
    void aWrongCredentialOrUnknownInstallationWritesNothing() {
        phone.ok(Ev.screenOn(Ev.utc("2026-10-10T09:00:00Z")));
        long before = rows();
        long marks = dirty();
        long seen = data.count("select (extract(epoch from last_seen_at) * 1000)::bigint from installations where id = ?",
                phone.installationId);
        data.at("2026-10-10T13:00:00Z");
        String wrong = Bytes.b64url(new byte[32]);
        FakeCollector.Reply r = FakeCollector.post("/ingest/v1/batches", Hpke.Purpose.BATCH,
                phone.batchPlaintext(2, wrong, List.of(Ev.screenOff(Ev.utc("2026-10-10T09:05:00Z")))));
        assertEquals(401, r.status());
        assertEquals("{\"error\":\"unauthorized\"}", r.body());
        FakeCollector stranger = new FakeCollector();
        assertEquals(401, stranger.send(Ev.screenOff(Ev.utc("2026-10-10T09:05:00Z"))).status());
        assertEquals(before, rows());
        assertEquals(marks, dirty());
        assertEquals(seen, data.count("select (extract(epoch from last_seen_at) * 1000)::bigint from installations where id = ?",
                phone.installationId));
        assertEquals(1, data.count("select count(*) from batch_log"));
    }

    @Test
    void aBodyOverOneMebibyteIsRefusedBeforeDecrypting() {
        byte[] big = new byte[1024 * 1024 + 1];
        Arrays.fill(big, (byte) 'a');
        given().contentType(ContentType.JSON).body(big).when().post("/ingest/v1/batches")
                .then().statusCode(413).body(org.hamcrest.Matchers.equalTo("{\"error\":\"too_large\"}"));
        given().contentType(ContentType.JSON).body(big).when().post("/ingest/v1/pair")
                .then().statusCode(413).body(org.hamcrest.Matchers.equalTo("{\"error\":\"too_large\"}"));
        // Refused before decrypting: nothing was counted against the pairing limiter
        assertEquals(0, data.count("select consecutive_failures from pairing_state"));
    }

    @Test
    void moreThanTwoThousandEventsIsTooLarge() {
        List<ObjectNode> events = new ArrayList<>();
        long t = Ev.utc("2026-10-10T09:00:00Z");
        for (int i = 0; i < 2001; i++) {
            ObjectNode e = Ev.JSON.createObjectNode();
            e.put("id", Ev.id());
            e.put("type", "android.usage");
            e.put("at", t + i);
            e.put("kind", "KEYGUARD_SHOWN");
            events.add(e);
        }
        assertEquals(413, phone.send(events).status());
        assertEquals(0, rows());
    }

    @Test
    void aFailedCommitAnswers503AndLeavesNothingBehind() {
        data.update("""
                create or replace function fail_batch() returns trigger language plpgsql as $$
                begin raise exception 'simulated failure'; end $$""");
        data.update("create trigger fail_batch before insert on batch_log for each row execute function fail_batch()");
        FakeCollector.Reply r = phone.send(Ev.screenOn(Ev.utc("2026-10-10T09:00:00Z")),
                Ev.activity(Ev.id(), Ev.utc("2026-10-10T09:00:00Z"), Ev.utc("2026-10-10T10:00:00Z"), 1));
        assertEquals(503, r.status());
        assertEquals("{\"error\":\"unavailable\"}", r.body());
        assertEquals(0, rows());
        assertEquals(0, dirty());
        assertEquals(0, data.count("select count(*) from quarantine"));
        data.update("drop trigger fail_batch on batch_log");
        // The collector resends the same frozen batch and it goes through
        assertEquals(200, phone.sendSeq(1, List.of(Ev.screenOn(Ev.utc("2026-10-10T09:00:00Z")))).status());
    }

    @Test
    void suspectEventsAreStoredButNeverMarkBlocks() {
        JsonNode r = phone.ok(Ev.screenOn(Ev.utc("2026-10-12T13:00:00Z")), Ev.screenOn(0L));
        assertEquals("accepted", FakeCollector.status(r, 0));
        assertEquals("accepted", FakeCollector.status(r, 1));
        assertEquals(2, data.count("select count(*) from raw_events where suspect"));
        assertEquals(0, dirty());
        // A week of back-filled history is not suspect
        phone.ok(Ev.screenOn(Ev.utc("2026-10-03T13:00:00Z")));
        assertEquals(1, data.count("select count(*) from raw_events where not suspect"));
    }

    @Test
    void anUnknownKidOrGarbageIsABadEnvelope() {
        String env = FakeCollector.envelope("prod-2026-10", FakeCollector.TEST_KEY.publicKey(), Hpke.Purpose.BATCH,
                phone.batchPlaintext(1, List.of()).getBytes(), null);
        given().contentType(ContentType.JSON).body(env).when().post("/ingest/v1/batches")
                .then().statusCode(400).body(org.hamcrest.Matchers.equalTo("{\"error\":\"bad_envelope\"}"));
        given().contentType(ContentType.JSON).body("{\"v\":2,\"kid\":\"test-0001\",\"enc\":\"AA\",\"ct\":\"AA\"}")
                .when().post("/ingest/v1/batches").then().statusCode(400);
        given().contentType(ContentType.JSON).body("not json").when().post("/ingest/v1/batches").then().statusCode(400);
        assertEquals(0, rows());
    }

    @Test
    void thePhonesZoneComesFromHeadersAndMarkers() {
        phone.tz = "America/Sao_Paulo";
        phone.ok(Ev.screenOn(Ev.utc("2026-10-10T09:00:00Z")),
                Ev.tz(Ev.utc("2026-10-10T08:00:00Z"), "America/Sao_Paulo", -180));
        assertEquals(2, data.count("select count(*) from zone_points"));
        // The agent's header is not the user's timeline
        agent.tz = "Asia/Tokyo";
        agent.ok(Ev.desktopHealth(Ev.utc("2026-10-10T09:00:00Z"), false));
        assertEquals(0, data.count("select count(*) from zone_points where zone = 'Asia/Tokyo'"));
    }
}
