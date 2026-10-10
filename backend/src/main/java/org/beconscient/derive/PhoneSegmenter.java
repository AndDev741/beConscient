package org.beconscient.derive;

import org.beconscient.common.Intervals;
import org.beconscient.ingest.ContractFilter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The phone rules of docs/design.md for one block (one device, one UTC day).
 *
 * The sweep walks the events in time order from the state the previous block
 * ended in. Between two events exactly one owner holds the time: nothing (screen
 * off, locked or unknown), the Unattributed bucket (screen on and unlocked with no
 * attributable app) or one app (the most recently resumed one, so split screen
 * never double counts). Pure: no database, so the rules are tested directly.
 */
public final class PhoneSegmenter {

    public static final String SYSTEM_UI = "com.android.systemui";
    public static final String YOUTUBE = "com.google.android.youtube";
    public static final String SHORTS_SUBJECT = "app:" + YOUTUBE + "#shorts";
    public static final long MERGE_GAP_MS = 2_000;
    public static final long FIRST_APP_WINDOW_MS = 60_000;

    /** Used only while no health record has named the launcher yet (back-filled history). */
    static final Set<String> KNOWN_LAUNCHERS = Set.of(
            "com.google.android.apps.nexuslauncher", "com.android.launcher3", "com.android.launcher",
            "com.sec.android.app.launcher", "com.miui.home", "com.mi.android.globallauncher",
            "com.huawei.android.launcher", "net.oneplus.launcher", "com.oneplus.launcher",
            "com.motorola.launcher3", "com.teslacoilsw.launcher", "com.microsoft.launcher",
            "com.nothing.launcher", "com.oppo.launcher", "com.bbk.launcher2", "com.realme.launcher",
            "app.lawnchair", "com.google.android.apps.pixel.launcher");

    /** Same-instant ordering: what turns time on comes before what uses it, closing comes last. */
    static final Map<String, Integer> ORDER = Map.of(
            "DEVICE_STARTUP", 0, "SCREEN_INTERACTIVE", 1, "KEYGUARD_HIDDEN", 2, "KEYGUARD_SHOWN", 3,
            "ACTIVITY_PAUSED", 4, "ACTIVITY_STOPPED", 4, "ACTIVITY_RESUMED", 5,
            "SCREEN_NON_INTERACTIVE", 7, "DEVICE_SHUTDOWN", 8);

    public record Input(PhoneState start, List<EventRow> events, long blockStart, long blockEnd, long cap) {
    }

    public record Unlock(long at, String firstApp, long eventId) {
    }

    public record Result(List<SegmentDraft> segments, List<Intervals.Span> raw, List<Unlock> unlocks, PhoneState end,
                         List<String> violations) {
    }

    private PhoneSegmenter() {
    }

    public static Comparator<EventRow> eventOrder() {
        return Comparator.comparingLong(EventRow::ts)
                .thenComparingInt(e -> ContractFilter.USAGE.equals(e.type()) ? ORDER.getOrDefault(e.str("kind"), 6) : 6)
                .thenComparingLong(EventRow::id);
    }

    /** Launcher and System UI are not apps. */
    static boolean excluded(String pkg, String home) {
        if (SYSTEM_UI.equals(pkg)) {
            return true;
        }
        return home != null ? home.equals(pkg) : KNOWN_LAUNCHERS.contains(pkg);
    }

    private record Owner(String kind, String subject, String rule) {
    }

    private static Owner owner(PhoneState st) {
        if (!st.active()) {
            return null;
        }
        PhoneState.Resumed top = st.top();
        if (top == null) {
            return new Owner(SegmentDraft.UNATTRIBUTED, null, "no_resumed_app");
        }
        if (excluded(top.pkg(), st.home)) {
            return new Owner(SegmentDraft.UNATTRIBUTED, null,
                    SYSTEM_UI.equals(top.pkg()) ? "systemui_not_an_app" : "launcher_not_an_app");
        }
        if (YOUTUBE.equals(top.pkg()) && st.shorts) {
            return new Owner(SegmentDraft.APP, SHORTS_SUBJECT, "shorts_marker");
        }
        return new Owner(SegmentDraft.APP, "app:" + top.pkg(), "app_interval");
    }

    public static Result derive(Input in) {
        long ds = in.blockStart();
        long de = in.blockEnd();
        long cap = Math.max(ds, Math.min(de, in.cap()));
        List<EventRow> events = new ArrayList<>(in.events());
        events.sort(eventOrder());

        PhoneState st = in.start().copy();
        List<PhoneState.Resumed> rawResumed = new ArrayList<>(st.resumed);
        List<SegmentDraft> pieces = new ArrayList<>();
        List<Intervals.Span> raw = new ArrayList<>();
        List<Unlock> unlocks = new ArrayList<>();

        long cur = ds;
        Long ownerSource = st.top() != null ? st.top().eventId() : null;
        boolean fromCarried = true;
        boolean inferred = false;
        Long pendingAt = null;
        long pendingEvent = 0;
        String pendingFirst = null;

        for (EventRow e : events) {
            long t = Math.max(ds, e.ts());
            if (t >= de) {
                break;
            }
            emit(pieces, raw, st, rawResumed, cur, Math.min(t, cap), ownerSource, fromCarried, inferred, cap, de);
            if (t > cur) {
                cur = Math.min(t, cap);
                fromCarried = false;
            }
            boolean wasActive = st.active();
            Owner before = owner(st);
            boolean nowInferred = apply(st, rawResumed, e);
            inferred = nowInferred || (inferred && st.active());
            Owner after = owner(st);
            if (after != null && (before == null || !after.equals(before))) {
                ownerSource = e.id();
            }
            // An open Shorts marker closes when YouTube leaves the foreground or the screen turns off
            if (st.shorts && !ContractFilter.SHORTS.equals(e.type())
                    && (!st.active() || st.top() == null || !YOUTUBE.equals(st.top().pkg()))) {
                st.shorts = false;
            }
            // Unlocks: each entry into screen-on-and-unlocked
            if (!wasActive && st.active()) {
                if (pendingAt != null) {
                    unlocks.add(new Unlock(pendingAt, pendingFirst, pendingEvent));
                }
                pendingAt = e.ts();
                pendingEvent = e.id();
                pendingFirst = null;
            }
            if (pendingAt != null && pendingFirst == null && isResume(e) && !excluded(e.str("package"), st.home)
                    && e.ts() - pendingAt <= FIRST_APP_WINDOW_MS) {
                pendingFirst = e.str("package");
            }
            if (pendingAt != null && !st.active()) {
                unlocks.add(new Unlock(pendingAt, pendingFirst, pendingEvent));
                pendingAt = null;
            }
        }
        emit(pieces, raw, st, rawResumed, cur, cap, ownerSource, fromCarried, inferred, cap, de);
        PhoneState end = st.copy();

        // An unlock just before the block edge may find its first app in the next block
        if (pendingAt != null && pendingFirst == null) {
            PhoneState look = st.copy();
            List<PhoneState.Resumed> lookRaw = new ArrayList<>(rawResumed);
            for (EventRow e : events) {
                if (e.ts() < de) {
                    continue;
                }
                if (e.ts() - pendingAt > FIRST_APP_WINDOW_MS) {
                    break;
                }
                apply(look, lookRaw, e);
                if (!look.active()) {
                    break;
                }
                if (isResume(e) && !excluded(e.str("package"), look.home)) {
                    pendingFirst = e.str("package");
                    break;
                }
            }
        }
        if (pendingAt != null) {
            unlocks.add(new Unlock(pendingAt, pendingFirst, pendingEvent));
        }

        List<SegmentDraft> segments = mergeShortGaps(mergeAdjacent(pieces));
        return new Result(segments, Intervals.union(raw), unlocks, end, invariants(segments, ds, de));
    }

    private static boolean isResume(EventRow e) {
        return ContractFilter.USAGE.equals(e.type()) && "ACTIVITY_RESUMED".equals(e.str("kind")) && e.str("package") != null;
    }

    /** Applies one event; returns true when it made the screen count as on by inference. */
    static boolean apply(PhoneState st, List<PhoneState.Resumed> rawResumed, EventRow e) {
        switch (e.type()) {
            case ContractFilter.USAGE -> {
                String kind = e.str("kind");
                if (kind == null) {
                    return false;
                }
                switch (kind) {
                    case "SCREEN_INTERACTIVE" -> st.screen = PhoneState.Screen.ON;
                    case "SCREEN_NON_INTERACTIVE" -> {
                        // An open interval closes at screen off
                        st.screen = PhoneState.Screen.OFF;
                        st.resumed.clear();
                        st.shorts = false;
                    }
                    case "KEYGUARD_SHOWN" -> st.locked = true;
                    case "KEYGUARD_HIDDEN" -> {
                        st.locked = false;
                        if (st.screen == PhoneState.Screen.UNKNOWN) {
                            st.screen = PhoneState.Screen.ON;
                            return true;
                        }
                    }
                    case "ACTIVITY_RESUMED" -> {
                        String pkg = e.str("package");
                        String cls = e.str("class");
                        Long inst = e.payload().hasNonNull("instance") ? e.payload().get("instance").asLong() : null;
                        st.resumed.removeIf(r -> r.sameActivity(pkg, cls, inst));
                        st.resumed.add(new PhoneState.Resumed(pkg, cls, inst, e.ts(), e.id()));
                        rawResumed.removeIf(r -> r.sameActivity(pkg, cls, inst));
                        rawResumed.add(new PhoneState.Resumed(pkg, cls, inst, e.ts(), e.id()));
                        if (st.screen == PhoneState.Screen.UNKNOWN) {
                            // A resumed activity means the screen is on
                            st.screen = PhoneState.Screen.ON;
                            return true;
                        }
                    }
                    case "ACTIVITY_PAUSED", "ACTIVITY_STOPPED" -> {
                        String pkg = e.str("package");
                        String cls = e.str("class");
                        Long inst = e.payload().hasNonNull("instance") ? e.payload().get("instance").asLong() : null;
                        st.resumed.removeIf(r -> r.sameActivity(pkg, cls, inst));
                        rawResumed.removeIf(r -> r.sameActivity(pkg, cls, inst));
                    }
                    case "DEVICE_SHUTDOWN" -> {
                        st.screen = PhoneState.Screen.OFF;
                        st.locked = false;
                        st.resumed.clear();
                        st.shorts = false;
                        rawResumed.clear();
                    }
                    case "DEVICE_STARTUP" -> {
                        st.screen = PhoneState.Screen.UNKNOWN;
                        st.locked = false;
                        st.resumed.clear();
                        st.shorts = false;
                        rawResumed.clear();
                    }
                    default -> {
                    }
                }
            }
            case ContractFilter.SHORTS -> {
                String state = e.str("state");
                if ("enter".equals(state)) {
                    st.shorts = true;
                } else if ("exit".equals(state) || "unclassified".equals(state)) {
                    // Unclassified time stays as YouTube
                    st.shorts = false;
                }
            }
            case ContractFilter.HEALTH -> {
                String home = e.str("home_package");
                if (home != null && !home.isBlank()) {
                    st.home = home;
                }
            }
            default -> {
            }
        }
        return false;
    }

    private static void emit(List<SegmentDraft> pieces, List<Intervals.Span> raw, PhoneState st,
                             List<PhoneState.Resumed> rawResumed, long a, long b, Long source, boolean fromCarried,
                             boolean inferred, long cap, long de) {
        if (b <= a) {
            return;
        }
        // Raw: the app interval itself, before clipping to screen-on-and-unlocked
        for (int i = rawResumed.size() - 1; i >= 0; i--) {
            if (!excluded(rawResumed.get(i).pkg(), st.home)) {
                raw.add(new Intervals.Span(a, b));
                break;
            }
        }
        Owner o = owner(st);
        if (o == null) {
            return;
        }
        SegmentDraft d = new SegmentDraft(a, b, o.kind(), o.subject()).source(source)
                .rule("clip_screen_on_unlocked").rule(o.rule());
        if (st.resumed.size() > 1 && SegmentDraft.APP.equals(o.kind())) {
            d.rule("split_screen_most_recent");
        }
        if (fromCarried) {
            d.rule("carried_state");
        }
        if (inferred) {
            d.rule("screen_on_inferred_from_activity");
        }
        if (b == cap && cap < de) {
            d.rule("cut_at_last_known_fact");
        }
        pieces.add(d);
    }

    static List<SegmentDraft> mergeAdjacent(List<SegmentDraft> pieces) {
        List<SegmentDraft> out = new ArrayList<>();
        for (SegmentDraft p : pieces) {
            SegmentDraft last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.end == p.start && last.sameOwner(p)) {
                last.end = p.end;
                last.absorb(p);
            } else {
                out.add(p);
            }
        }
        return out;
    }

    /** Intervals of the same package separated by at most 2 seconds of nothing attributable merge. */
    static List<SegmentDraft> mergeShortGaps(List<SegmentDraft> segs) {
        List<SegmentDraft> out = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) {
            SegmentDraft s = segs.get(i);
            SegmentDraft last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && SegmentDraft.APP.equals(last.kind) && i + 1 < segs.size()) {
                SegmentDraft next = segs.get(i + 1);
                if (SegmentDraft.UNATTRIBUTED.equals(s.kind) && s.length() <= MERGE_GAP_MS
                        && last.end == s.start && s.end == next.start && last.sameOwner(next)) {
                    last.end = next.end;
                    last.absorb(next);
                    last.rule("merge_within_2s");
                    i++;
                    continue;
                }
            }
            out.add(s);
        }
        return out;
    }

    /** No overlap within the device, everything inside the block, at most the block's length. */
    static List<String> invariants(List<SegmentDraft> segs, long ds, long de) {
        List<String> v = new ArrayList<>();
        long total = 0;
        long prevEnd = Long.MIN_VALUE;
        for (SegmentDraft s : segs) {
            if (s.start < ds || s.end > de || s.end <= s.start) {
                v.add("segment outside its block or empty at " + s.start);
            }
            if (s.start < prevEnd) {
                v.add("overlapping segments at " + s.start);
            }
            prevEnd = Math.max(prevEnd, s.end);
            total += s.length();
        }
        if (total > de - ds) {
            v.add("more time than the day has: " + total + " ms");
        }
        return v;
    }
}
