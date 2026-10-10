package org.beconscient.derive;

import org.beconscient.common.Intervals;
import org.beconscient.common.Intervals.Span;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * The desktop rules of docs/design.md for one block. Counted time is the union of
 * the agent's activity intervals. Site intervals are clipped to it, so the lock
 * cut, idle and the 2-hour cap apply to them too, and the clipped site time is
 * carved out of computer time. Whether computer time is Work or Personal computer
 * is decided when reading, from the versioned work window.
 */
public final class DesktopSegmenter {

    private DesktopSegmenter() {
    }

    public static List<SegmentDraft> derive(List<EventRow> activity, List<EventRow> sites, long ds, long de) {
        List<Span> active = new ArrayList<>();
        for (EventRow a : activity) {
            Span s = span(a, ds, de);
            if (s != null) {
                active.add(s);
            }
        }
        active = Intervals.union(active);

        // Overlapping site intervals (two browsers): the most recently started one owns the time
        List<SegmentDraft> sitePieces = new ArrayList<>();
        List<EventRow> ordered = new ArrayList<>(sites);
        ordered.sort(Comparator.comparingLong(EventRow::ts).thenComparingLong(EventRow::id));
        TreeSet<Long> cuts = new TreeSet<>();
        for (EventRow s : ordered) {
            Span sp = span(s, ds, de);
            if (sp != null) {
                cuts.add(sp.start());
                cuts.add(sp.end());
            }
        }
        Long prev = null;
        for (Long c : cuts) {
            if (prev != null) {
                EventRow owner = null;
                for (EventRow s : ordered) {
                    Span sp = span(s, ds, de);
                    if (sp != null && sp.start() <= prev && sp.end() >= c) {
                        owner = s;
                    }
                }
                if (owner != null) {
                    for (Span part : Intervals.intersect(List.of(new Span(prev, c)), active)) {
                        sitePieces.add(new SegmentDraft(part.start(), part.end(), SegmentDraft.SITE,
                                "site:" + owner.str("site")).source(owner.id())
                                .rule("site_clipped_to_activity").rule("site_carved_out_of_computer"));
                    }
                }
            }
            prev = c;
        }
        sitePieces = PhoneSegmenter.mergeAdjacent(sitePieces);

        List<Span> siteSpans = new ArrayList<>();
        for (SegmentDraft p : sitePieces) {
            siteSpans.add(new Span(p.start, p.end));
        }
        List<SegmentDraft> out = new ArrayList<>(sitePieces);
        for (Span c : Intervals.subtract(active, Intervals.union(siteSpans))) {
            out.add(new SegmentDraft(c.start(), c.end(), SegmentDraft.COMPUTER, null)
                    .rule("desktop_activity").rule("work_or_personal_by_window_at_read"));
        }
        out.sort(Comparator.comparingLong(d -> d.start));
        // Attach the activity intervals behind each piece, for the trace
        for (SegmentDraft d : out) {
            for (EventRow a : activity) {
                Span s = span(a, ds, de);
                if (s != null && s.overlaps(d.start, d.end)) {
                    d.source(a.id());
                }
            }
        }
        return PhoneSegmenter.mergeAdjacent(out);
    }

    private static Span span(EventRow e, long ds, long de) {
        if (e.end() == null) {
            return null;
        }
        long a = Math.max(ds, e.ts());
        long b = Math.min(de, e.end());
        return b > a ? new Span(a, b) : null;
    }
}
