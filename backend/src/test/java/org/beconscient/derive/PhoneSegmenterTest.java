package org.beconscient.derive;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beconscient.common.Intervals;
import org.beconscient.support.Ev;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.beconscient.support.Ev.MIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The phone rules of docs/design.md on hand-made event sequences. */
class PhoneSegmenterTest {

    static final long DS = Ev.utc("2026-10-06T00:00:00Z");
    static final long DE = DS + 24 * Ev.HOUR;
    static final String WA = "com.whatsapp";
    static final String YT = PhoneSegmenter.YOUTUBE;
    static final String HOME = "com.google.android.apps.nexuslauncher";

    static long at(String hhmm) {
        return Ev.utc("2026-10-06T" + hhmm + ":00Z");
    }

    static long at(String hhmm, int seconds) {
        return at(hhmm) + seconds * 1000L;
    }

    static List<EventRow> rows(ObjectNode... events) {
        List<EventRow> out = new ArrayList<>();
        long id = 1;
        for (ObjectNode e : events) {
            out.add(new EventRow(id++, e.get("type").asText(), e.get("at").asLong(), null, null, e));
        }
        return out;
    }

    static PhoneSegmenter.Result derive(PhoneState start, long cap, ObjectNode... events) {
        return PhoneSegmenter.derive(new PhoneSegmenter.Input(start, rows(events), DS, DE, cap));
    }

    static PhoneSegmenter.Result derive(ObjectNode... events) {
        PhoneState s = PhoneState.initial();
        s.home = HOME;
        return derive(s, DE, events);
    }

    static long minutes(List<SegmentDraft> segs, String subject) {
        return segs.stream().filter(s -> subject == null ? s.subject == null : subject.equals(s.subject))
                .mapToLong(SegmentDraft::length).sum() / MIN;
    }

    static ObjectNode[] on(String hhmm) {
        return new ObjectNode[]{Ev.screenOn(at(hhmm)), Ev.unlocked(at(hhmm))};
    }

    static ObjectNode[] cat(ObjectNode[]... parts) {
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode[] p : parts) {
            out.addAll(List.of(p));
        }
        return out.toArray(new ObjectNode[0]);
    }

    static ObjectNode[] of(ObjectNode... e) {
        return e;
    }

    @Test
    void resumedToPausedOfTheSamePackageIsOneSegment() {
        var r = derive(cat(on("10:00"), of(Ev.resumed(at("10:00"), WA), Ev.paused(at("10:20"), WA),
                Ev.screenOff(at("10:20")))));
        assertEquals(20, minutes(r.segments(), "app:" + WA));
        assertEquals(1, r.segments().size());
        assertTrue(r.segments().get(0).rules.contains("clip_screen_on_unlocked"));
    }

    @Test
    void audioWithTheScreenOffNeverCounts() {
        // Spotify keeps playing in the pocket: the screen goes off and stays off for an hour
        var r = derive(cat(on("09:55"), of(Ev.resumed(at("09:55"), "com.spotify.music"), Ev.screenOff(at("10:00")),
                Ev.resumed(at("10:30"), "com.spotify.music"), Ev.screenOn(at("11:00")), Ev.screenOff(at("11:00")))));
        assertEquals(5, minutes(r.segments(), "app:com.spotify.music"));
        assertEquals(5, r.segments().stream().mapToLong(SegmentDraft::length).sum() / MIN);
    }

    @Test
    void intervalsOneSecondApartMergeAndFiveSecondsApartDoNot() {
        var close = derive(cat(on("10:00"), of(Ev.resumed(at("10:00"), WA), Ev.paused(at("10:10"), WA),
                Ev.resumed(at("10:10", 1), WA), Ev.paused(at("10:20"), WA), Ev.screenOff(at("10:20")))));
        assertEquals(1, close.segments().size());
        assertEquals(20, minutes(close.segments(), "app:" + WA));
        assertTrue(close.segments().get(0).rules.contains("merge_within_2s"));

        var apart = derive(cat(on("10:00"), of(Ev.resumed(at("10:00"), WA), Ev.paused(at("10:10"), WA),
                Ev.resumed(at("10:10", 5), WA), Ev.paused(at("10:20"), WA), Ev.screenOff(at("10:20")))));
        assertEquals(2, apart.segments().stream().filter(s -> ("app:" + WA).equals(s.subject)).count());
        assertEquals(SegmentDraft.UNATTRIBUTED, apart.segments().get(1).kind);
    }

    @Test
    void aDuplicatedPairThreeSecondsLaterDoesNotDoubleTheTime() {
        var r = derive(cat(on("10:00"), of(Ev.resumed(at("10:00"), WA), Ev.paused(at("10:20"), WA),
                Ev.resumed(at("10:00", 3), WA), Ev.paused(at("10:20", 3), WA), Ev.screenOff(at("10:30")))));
        long app = r.segments().stream().filter(s -> ("app:" + WA).equals(s.subject)).mapToLong(SegmentDraft::length).sum();
        assertEquals(20 * MIN, app);
        assertEquals(30 * MIN, r.segments().stream().mapToLong(SegmentDraft::length).sum());
    }

    @Test
    void anOpenIntervalClosesAtScreenOffOrAtShutdown() {
        var off = derive(cat(on("10:00"), of(Ev.resumed(at("10:00"), WA), Ev.screenOff(at("10:15")), Ev.screenOn(at("10:30")),
                Ev.unlocked(at("10:30")), Ev.screenOff(at("10:31")))));
        assertEquals(15, minutes(off.segments(), "app:" + WA), "screen off closed WhatsApp; it did not come back");

        var shutdown = derive(cat(on("10:00"), of(Ev.resumed(at("10:00"), WA), Ev.shutdown(at("10:40")))));
        assertEquals(40, minutes(shutdown.segments(), "app:" + WA));
        assertFalse(shutdown.end().active());
    }

    @Test
    void launcherAndSystemUiAreNotAppsAndUnlockedTimeWithoutAnAppIsUnattributed() {
        var r = derive(cat(on("10:00"), of(Ev.resumed(at("10:00"), HOME), Ev.paused(at("10:05"), HOME),
                Ev.resumed(at("10:05"), PhoneSegmenter.SYSTEM_UI), Ev.paused(at("10:07"), PhoneSegmenter.SYSTEM_UI),
                Ev.screenOff(at("10:10")))));
        assertEquals(0, r.segments().stream().filter(s -> SegmentDraft.APP.equals(s.kind)).count());
        assertEquals(10, minutes(r.segments(), null));
        assertTrue(r.segments().stream().allMatch(s -> SegmentDraft.UNATTRIBUTED.equals(s.kind)));
    }

    @Test
    void theLauncherComesFromTheLatestHealthRecord() {
        ObjectNode health = Ev.phoneHealth(at("09:00"), at("09:00"));
        health.put("home_package", "com.example.launcher");
        PhoneState s = PhoneState.initial();
        var r = derive(s, DE, cat(of(health), on("10:00"), of(Ev.resumed(at("10:00"), "com.example.launcher"),
                Ev.screenOff(at("10:05")))));
        assertEquals(0, minutes(r.segments(), "app:com.example.launcher"));
        assertEquals("com.example.launcher", r.end().home);

        // Before any health record, well-known launchers are still not apps
        var backfill = derive(PhoneState.initial(), DE, cat(on("10:00"), of(Ev.resumed(at("10:00"), HOME), Ev.screenOff(at("10:05")))));
        assertEquals(0, minutes(backfill.segments(), "app:" + HOME));
    }

    @Test
    void lockedTimeNeverCounts() {
        var r = derive(of(Ev.screenOn(at("10:00")), Ev.locked(at("10:00")), Ev.resumed(at("10:00"), WA),
                Ev.unlocked(at("10:10")), Ev.screenOff(at("10:30"))));
        assertEquals(20, minutes(r.segments(), "app:" + WA));
    }

    @Test
    void inSplitScreenTheMostRecentlyResumedAppOwnsTheTime() {
        var r = derive(cat(on("10:00"), of(Ev.resumed(at("10:00"), WA), Ev.resumed(at("10:10"), YT),
                Ev.paused(at("10:20"), YT), Ev.screenOff(at("10:30")))));
        assertEquals(20, minutes(r.segments(), "app:" + WA));
        assertEquals(10, minutes(r.segments(), "app:" + YT));
        assertTrue(PhoneSegmenter.invariants(r.segments(), DS, DE).isEmpty());
        assertTrue(r.segments().stream().anyMatch(s -> s.rules.contains("split_screen_most_recent")));
    }

    @Test
    void aSegmentAcrossMidnightContinuesFromTheCarriedState() {
        // Block of the 6th ends with WhatsApp open; the 7th starts from that state
        var first = derive(cat(on("23:50"), of(Ev.resumed(at("23:50"), WA))));
        assertEquals(10, minutes(first.segments(), "app:" + WA));
        assertTrue(first.end().active());

        long ds2 = DE;
        var second = PhoneSegmenter.derive(new PhoneSegmenter.Input(first.end(),
                rows(Ev.paused(ds2 + 10 * MIN, WA), Ev.screenOff(ds2 + 10 * MIN)), ds2, ds2 + 24 * Ev.HOUR, ds2 + 24 * Ev.HOUR));
        assertEquals(10, minutes(second.segments(), "app:" + WA));
        assertTrue(second.segments().get(0).rules.contains("carried_state"));
        assertEquals(0, second.unlocks().size(), "a carried session is not a new unlock");
    }

    @Test
    void timeStopsAtTheLastKnownFact() {
        PhoneState s = PhoneState.initial();
        s.home = HOME;
        var r = derive(s, at("10:30"), cat(on("10:00"), of(Ev.resumed(at("10:00"), WA))));
        assertEquals(30, minutes(r.segments(), "app:" + WA));
        assertTrue(r.segments().get(0).rules.contains("cut_at_last_known_fact"));
        assertTrue(r.end().active(), "the state is still open for the next derivation");
    }

    @Test
    void anUnlockAndItsFirstApp() {
        var r = derive(cat(on("21:14"), of(Ev.resumed(at("21:14", 10), WA), Ev.screenOff(at("21:20")))));
        assertEquals(1, r.unlocks().size());
        assertEquals(WA, r.unlocks().get(0).firstApp());

        var none = derive(cat(on("21:14"), of(Ev.resumed(at("21:15", 30), WA), Ev.screenOff(at("21:20")))));
        assertNull(none.unlocks().get(0).firstApp(), "no app within 60 seconds");

        var launcherFirst = derive(cat(on("21:14"), of(Ev.resumed(at("21:14", 2), HOME), Ev.resumed(at("21:14", 20), WA),
                Ev.screenOff(at("21:20")))));
        assertEquals(WA, launcherFirst.unlocks().get(0).firstApp(), "the launcher is never the first app");
    }

    @Test
    void anUnlockWithoutKeyguardHiddenStillCounts() {
        // Screen back on within the lock grace period: no KEYGUARD events at all
        var r = derive(cat(on("08:00"), of(Ev.screenOff(at("08:10")), Ev.screenOn(at("08:10", 20)),
                Ev.resumed(at("08:10", 25), WA), Ev.screenOff(at("08:12")))));
        assertEquals(2, r.unlocks().size());
        assertEquals(WA, r.unlocks().get(1).firstApp());
    }

    @Test
    void shortsMarkersSplitYoutubeTime() {
        var r = derive(cat(on("22:00"), of(Ev.resumed(at("22:00"), YT), Ev.shorts(at("22:10"), "enter", "19.40.33"),
                Ev.shorts(at("22:40"), "exit", "19.40.33"), Ev.paused(at("22:50"), YT), Ev.screenOff(at("22:50")))));
        assertEquals(30, minutes(r.segments(), PhoneSegmenter.SHORTS_SUBJECT));
        assertEquals(20, minutes(r.segments(), "app:" + YT));
    }

    @Test
    void anOpenShortsMarkerClosesWhenYoutubeLeavesOrTheScreenTurnsOff() {
        var leaves = derive(cat(on("22:00"), of(Ev.resumed(at("22:00"), YT), Ev.shorts(at("22:00"), "enter", "v"),
                Ev.paused(at("22:10"), YT), Ev.resumed(at("22:10"), WA), Ev.paused(at("22:15"), WA),
                Ev.resumed(at("22:15"), YT), Ev.screenOff(at("22:20")))));
        assertEquals(10, minutes(leaves.segments(), PhoneSegmenter.SHORTS_SUBJECT));
        assertEquals(5, minutes(leaves.segments(), "app:" + YT), "YouTube again, without a new marker, is plain YouTube");

        var off = derive(cat(on("22:00"), of(Ev.resumed(at("22:00"), YT), Ev.shorts(at("22:00"), "enter", "v"),
                Ev.screenOff(at("22:05")), Ev.screenOn(at("22:06")), Ev.unlocked(at("22:06")), Ev.resumed(at("22:06"), YT),
                Ev.screenOff(at("22:10")))));
        assertEquals(5, minutes(off.segments(), PhoneSegmenter.SHORTS_SUBJECT));
        assertEquals(4, minutes(off.segments(), "app:" + YT));
    }

    @Test
    void unclassifiedYoutubeStaysYoutube() {
        var r = derive(cat(on("22:00"), of(Ev.resumed(at("22:00"), YT), Ev.shorts(at("22:01"), "unclassified", "20.01.1"),
                Ev.screenOff(at("22:30")))));
        assertEquals(0, minutes(r.segments(), PhoneSegmenter.SHORTS_SUBJECT));
        assertEquals(30, minutes(r.segments(), "app:" + YT));
    }

    @Test
    void rawIntervalsIgnoreTheKeyguard() {
        var r = derive(of(Ev.screenOn(at("10:00")), Ev.locked(at("10:00")), Ev.resumed(at("10:00"), WA),
                Ev.unlocked(at("10:10")), Ev.paused(at("10:30"), WA), Ev.screenOff(at("10:30"))));
        assertEquals(20, minutes(r.segments(), "app:" + WA));
        assertEquals(30 * MIN, Intervals.total(r.raw()));
    }

    @Test
    void invariantsCatchOverlapsAndOverfullDays() {
        SegmentDraft a = new SegmentDraft(DS, DS + 2 * Ev.HOUR, SegmentDraft.APP, "app:a");
        SegmentDraft b = new SegmentDraft(DS + Ev.HOUR, DS + 3 * Ev.HOUR, SegmentDraft.APP, "app:b");
        assertFalse(PhoneSegmenter.invariants(List.of(a, b), DS, DE).isEmpty());
        SegmentDraft c = new SegmentDraft(DS - Ev.HOUR, DS + Ev.HOUR, SegmentDraft.APP, "app:c");
        assertFalse(PhoneSegmenter.invariants(List.of(c), DS, DE).isEmpty());
        assertTrue(PhoneSegmenter.invariants(List.of(a), DS, DE).isEmpty());
    }

    @Test
    void stateSurvivesTheBlockTable() {
        PhoneState s = PhoneState.initial();
        s.screen = PhoneState.Screen.ON;
        s.home = HOME;
        s.shorts = true;
        s.resumed.add(new PhoneState.Resumed(YT, YT + ".Main", 3L, 5L, 9L));
        PhoneState back = PhoneState.fromJson(s.toJson());
        assertTrue(back.sameAs(s));
        assertEquals(9L, back.top().eventId());
    }
}
