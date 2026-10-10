package org.beconscient.derive;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beconscient.support.Ev;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.beconscient.support.Ev.MIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopSegmenterTest {

    static final long DS = Ev.utc("2026-10-06T00:00:00Z");
    static final long DE = DS + 24 * Ev.HOUR;

    static long at(String hhmm) {
        return Ev.utc("2026-10-06T" + hhmm + ":00Z");
    }

    static EventRow row(long id, ObjectNode e) {
        return new EventRow(id, e.get("type").asText(), e.get("start").asLong(), e.get("end").asLong(), e.get("rev").asInt(), e);
    }

    static long minutes(List<SegmentDraft> segs, String kind, String subject) {
        return segs.stream().filter(s -> s.kind.equals(kind) && (subject == null || subject.equals(s.subject)))
                .mapToLong(SegmentDraft::length).sum() / MIN;
    }

    @Test
    void siteTimeIsCarvedOutOfComputerTime() {
        var segs = DesktopSegmenter.derive(
                List.of(row(1, Ev.activity(Ev.id(), at("13:30"), at("15:00"), 1))),
                List.of(row(2, Ev.site(Ev.id(), at("14:00"), at("14:20"), 1, "youtube", true))), DS, DE);
        assertEquals(70, minutes(segs, SegmentDraft.COMPUTER, null));
        assertEquals(20, minutes(segs, SegmentDraft.SITE, "site:youtube"));
        assertTrue(PhoneSegmenter.invariants(segs, DS, DE).isEmpty());
    }

    @Test
    void aSiteVisibleWhileTheSessionIsLockedDoesNotCount() {
        // The agent's interval ends at the lock; the tab stayed "visible" for another hour
        var segs = DesktopSegmenter.derive(
                List.of(row(1, Ev.activity(Ev.id(), at("20:00"), at("20:30"), 2))),
                List.of(row(2, Ev.site(Ev.id(), at("20:15"), at("21:30"), 3, "linkedin", false))), DS, DE);
        assertEquals(15, minutes(segs, SegmentDraft.SITE, "site:linkedin"));
        assertEquals(15, minutes(segs, SegmentDraft.COMPUTER, null));
        assertEquals(30, segs.stream().mapToLong(SegmentDraft::length).sum() / MIN);
    }

    @Test
    void overlappingActivityIntervalsCountOnceAndTheNewerSiteWins() {
        var segs = DesktopSegmenter.derive(
                List.of(row(1, Ev.activity(Ev.id(), at("10:00"), at("11:00"), 1)),
                        row(2, Ev.activity(Ev.id(), at("10:30"), at("11:30"), 1))),
                List.of(row(3, Ev.site(Ev.id(), at("10:00"), at("10:40"), 1, "youtube", true)),
                        row(4, Ev.site(Ev.id(), at("10:20"), at("10:50"), 1, "linkedin", false))), DS, DE);
        assertEquals(90, segs.stream().mapToLong(SegmentDraft::length).sum() / MIN);
        assertEquals(20, minutes(segs, SegmentDraft.SITE, "site:youtube"));
        assertEquals(30, minutes(segs, SegmentDraft.SITE, "site:linkedin"));
        assertTrue(PhoneSegmenter.invariants(segs, DS, DE).isEmpty());
    }

    @Test
    void intervalsAreClippedToTheBlock() {
        var segs = DesktopSegmenter.derive(
                List.of(row(1, Ev.activity(Ev.id(), DS - 30 * MIN, DS + 30 * MIN, 1))), List.of(), DS, DE);
        assertEquals(1, segs.size());
        assertEquals(DS, segs.get(0).start);
        assertEquals(30, minutes(segs, SegmentDraft.COMPUTER, null));
    }
}
