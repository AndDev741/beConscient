package org.beconscient.derive;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.beconscient.support.Ev;
import org.beconscient.support.FakeCollector;
import org.beconscient.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.beconscient.support.Ev.MIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DerivationServiceTest {

    static final String WA = "com.whatsapp";

    @Inject
    TestData data;

    @Inject
    DerivationService derivation;

    @Inject
    RebuildJob rebuild;

    FakeCollector phone;
    FakeCollector agent;
    UUID phoneId;
    UUID agentId;

    @BeforeEach
    void reset() {
        data.reset();
        data.at("2026-10-10T12:00:00Z");
        var p = data.device("Phone", "android");
        phoneId = UUID.fromString(p.device().id());
        phone = new FakeCollector().paired(p.code(), "android");
        var a = data.device("Laptop", "desktop");
        agentId = UUID.fromString(a.device().id());
        agent = new FakeCollector().paired(a.code(), "desktop");
    }

    long appMinutes(String day, String subject) {
        return data.count("""
                select coalesce(sum(end_ts - start_ts), 0) / 60000 from segments
                where block_day = ?::date and subject = ?""", day, subject);
    }

    long kindMinutes(String day, String kind) {
        return data.count("""
                select coalesce(sum(end_ts - start_ts), 0) / 60000 from segments
                where block_day = ?::date and kind = ?""", day, kind);
    }

    @Test
    void aPassDerivesMarkedBlocksAndDeletesExactlyItsMarks() {
        long t = Ev.utc("2026-10-08T10:00:00Z");
        phone.ok(Ev.screenOn(t), Ev.unlocked(t), Ev.resumed(t, WA), Ev.paused(t + 20 * MIN, WA), Ev.screenOff(t + 20 * MIN));
        assertEquals(1, data.count("select count(*) from dirty_blocks"));
        data.derive();
        assertEquals(0, data.count("select count(*) from dirty_blocks"));
        assertEquals(20, appMinutes("2026-10-08", "app:" + WA));
        assertEquals(1, data.count("select count(*) from blocks where device_id = ?", phoneId));
        assertEquals(1, data.count("select count(*) from unlocks"));
        assertEquals(DerivationService.VERSION, data.count("select min(version) from segments"));
    }

    @Test
    void aSegmentAcrossMidnightIsSplitAndALatePauseFixesBothDays() {
        long s = Ev.utc("2026-10-08T23:50:00Z");
        phone.ok(Ev.screenOn(s), Ev.unlocked(s), Ev.resumed(s, WA),
                Ev.paused(s + 20 * MIN, WA), Ev.screenOff(s + 20 * MIN));
        data.derive();
        assertEquals(10, appMinutes("2026-10-08", "app:" + WA));
        assertEquals(10, appMinutes("2026-10-09", "app:" + WA));

        // A pause read late shortens it: WhatsApp really closed at 23:55
        phone.ok(Ev.paused(s + 5 * MIN, WA));
        data.derive();
        assertEquals(5, appMinutes("2026-10-08", "app:" + WA));
        assertEquals(0, appMinutes("2026-10-09", "app:" + WA), "the next block follows the changed end state");
        assertEquals(10, kindMinutes("2026-10-09", "unattributed"));
    }

    @Test
    void aComputerIntervalArrivingAfterTheDayWasDerivedDerivesItAgain() {
        long s = Ev.utc("2026-10-08T09:00:00Z");
        agent.ok(Ev.activity(Ev.id(), s, s + Ev.HOUR, 1));
        data.derive();
        assertEquals(60, kindMinutes("2026-10-08", "computer"));
        agent.ok(Ev.activity(Ev.id(), s + 3 * Ev.HOUR, s + 4 * Ev.HOUR, 1));
        data.derive();
        assertEquals(120, kindMinutes("2026-10-08", "computer"));
    }

    @Test
    void anIntervalRevisionMovingTheEndBackShrinksTheSegments() {
        String id = Ev.id();
        long s = Ev.utc("2026-10-08T22:00:00Z");
        agent.ok(Ev.activity(id, s, s + 4 * Ev.HOUR, 1));
        data.derive();
        assertEquals(120, kindMinutes("2026-10-08", "computer"));
        assertEquals(120, kindMinutes("2026-10-09", "computer"));
        agent.ok(Ev.activity(id, s, s + Ev.HOUR, 2));
        data.derive();
        assertEquals(60, kindMinutes("2026-10-08", "computer"));
        assertEquals(0, kindMinutes("2026-10-09", "computer"));
    }

    @Test
    void anEventCommittedDuringAPassIsPickedUpByTheNextPass() {
        long t = Ev.utc("2026-10-08T10:00:00Z");
        phone.ok(Ev.screenOn(t), Ev.unlocked(t), Ev.resumed(t, WA), Ev.screenOff(t + 10 * MIN));
        QuarkusTransaction.requiringNew().run(() -> {
            derivation.deriveDevice(phoneId);
            // Another request commits while the pass is still open
            phone.ok(Ev.screenOn(t + Ev.HOUR), Ev.unlocked(t + Ev.HOUR), Ev.resumed(t + Ev.HOUR, WA),
                    Ev.screenOff(t + Ev.HOUR + 5 * MIN));
        });
        assertEquals(1, data.count("select count(*) from dirty_blocks"), "the new mark survived the pass");
        assertEquals(10, appMinutes("2026-10-08", "app:" + WA));
        data.derive();
        assertEquals(15, appMinutes("2026-10-08", "app:" + WA));
    }

    @Test
    void suspectEventsStayOutOfDerivation() {
        long t = Ev.utc("2026-10-08T10:00:00Z");
        phone.ok(Ev.screenOn(t), Ev.unlocked(t), Ev.resumed(t, WA), Ev.screenOff(t + 10 * MIN),
                Ev.resumed(0L, "com.example.epoch"), Ev.screenOff(Ev.utc("2026-10-12T10:00:00Z")));
        data.derive();
        assertEquals(10, appMinutes("2026-10-08", "app:" + WA));
        assertEquals(0, data.count("select count(*) from segments where block_day < '2026-01-01'"));
        assertEquals(0, data.count("select count(*) from blocks where day <> '2026-10-08'"));
    }

    @Test
    void aDayInProgressStopsAtTheLastFactAndGrowsWithLaterFacts() {
        data.at("2026-10-10T12:00:00Z");
        long t = Ev.utc("2026-10-10T10:00:00Z");
        phone.ok(Ev.screenOn(t), Ev.unlocked(t), Ev.resumed(t, WA), Ev.phoneHealth(t + 15 * MIN, t));
        data.derive();
        assertEquals(15, appMinutes("2026-10-10", "app:" + WA), "never counted beyond what the phone reported");
        phone.ok(Ev.phoneHealth(t + 30 * MIN, t));
        data.derive();
        assertEquals(30, appMinutes("2026-10-10", "app:" + WA));
    }

    @Test
    void aVersionBumpRebuildsResumablyToTheSameResult() {
        long t = Ev.utc("2026-10-07T21:00:00Z");
        for (int d = 0; d < 3; d++) {
            long s = t + d * 24 * Ev.HOUR;
            phone.ok(Ev.screenOn(s), Ev.unlocked(s), Ev.resumed(s, WA), Ev.paused(s + 40 * MIN, WA),
                    Ev.resumed(s + 40 * MIN, "com.spotify.music"), Ev.screenOff(s + 4 * Ev.HOUR));
            agent.ok(Ev.activity(Ev.id(), s, s + 2 * Ev.HOUR, 1));
        }
        data.derive();
        List<String> before = snapshot();
        assertFalse(before.isEmpty());

        // The stored version is older than the code: a rebuild starts
        data.update("update derivation_state set version = 0");
        data.update("update segments set version = 0");
        rebuild.startIfNeeded();
        DerivationService.State running = derivation.state();
        assertTrue(running.rebuilding());
        assertTrue(running.total() > 0);
        assertEquals(1, rebuild.step(1));
        DerivationService.State half = derivation.state();
        assertTrue(half.progress() > 0 && half.progress() < 1, "progress " + half.progress());

        // "Restart": a new start finds the rebuild running and resumes it
        rebuild.startIfNeeded();
        assertEquals(1, derivation.state().done());
        while (rebuild.step(2) > 0) {
            // keep stepping
        }
        DerivationService.State done = derivation.state();
        assertFalse(done.rebuilding());
        assertEquals(DerivationService.VERSION, done.version());
        assertEquals(1.0, done.progress());
        data.derive();
        assertEquals(before, snapshot());
        assertEquals(0, data.count("select count(*) from segments where version < ?", DerivationService.VERSION));
        assertEquals(0, data.count("select count(*) from rebuild_blocks"));
    }

    @Test
    void aProcessWithAnOlderVersionRefusesToWrite() {
        long t = Ev.utc("2026-10-08T10:00:00Z");
        phone.ok(Ev.screenOn(t), Ev.unlocked(t), Ev.resumed(t, WA), Ev.screenOff(t + 10 * MIN));
        data.update("update derivation_state set version = ?", DerivationService.VERSION + 1);
        assertEquals(0, derivation.runPass());
        assertEquals(0, data.count("select count(*) from segments"));
        assertEquals(1, data.count("select count(*) from dirty_blocks"), "the marks wait for a current process");
        data.update("update derivation_state set version = ?", DerivationService.VERSION);
        data.derive();
        assertEquals(10, appMinutes("2026-10-08", "app:" + WA));
    }

    @Test
    void segmentsNeverOverlapWithinADevice() {
        long t = Ev.utc("2026-10-08T10:00:00Z");
        phone.ok(Ev.screenOn(t), Ev.unlocked(t), Ev.resumed(t, WA), Ev.resumed(t + MIN, "com.android.chrome"),
                Ev.paused(t + 2 * MIN, WA), Ev.paused(t + 3 * MIN, "com.android.chrome"), Ev.screenOff(t + 5 * MIN));
        data.derive();
        assertEquals(0, data.count("""
                select count(*) from segments a join segments b on a.device_id = b.device_id and a.id < b.id
                and a.start_ts < b.end_ts and b.start_ts < a.end_ts"""));
        assertEquals(0, data.count("select coalesce(sum(invariant_violations), 0) from blocks"));
    }

    private List<String> snapshot() {
        return data.jdbc().list("""
                        select device_id, block_day, start_ts, end_ts, kind, coalesce(subject, '') as subject from segments
                        order by device_id, start_ts""",
                rs -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getLong(3) + "|" + rs.getLong(4) + "|"
                        + rs.getString(5) + "|" + rs.getString(6));
    }
}
