package org.beconscient.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.beconscient.api.Dto;
import org.beconscient.category.Catalog;
import org.beconscient.category.Category;
import org.beconscient.category.Subjects;
import org.beconscient.common.AppClock;
import org.beconscient.common.Intervals;
import org.beconscient.common.Intervals.Span;
import org.beconscient.common.Jdbc;
import org.beconscient.derive.DerivationService;
import org.beconscient.derive.SegmentDraft;
import org.beconscient.health.HealthService;
import org.beconscient.label.ComputerLabel;
import org.beconscient.label.ComputerLabelService;
import org.beconscient.settings.Settings;
import org.beconscient.settings.SettingsService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads days (docs/design.md, "Reading"): categories, windows and the local day
 * are applied here, never stored. The day total and the headline use the union
 * of all devices; in category and hourly views overlapping time belongs to the
 * phone.
 */
@ApplicationScoped
public class DayService {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final long HOUR_MS = 3_600_000L;
    static final int TOP_SUBJECTS = 10;
    static final int SEGMENTS_PER_HOUR = 20;

    @Inject
    Jdbc jdbc;

    @Inject
    AppClock clock;

    @Inject
    Timelines timelines;

    @Inject
    SettingsService settings;

    @Inject
    HealthService health;

    @Inject
    DerivationService derivation;

    @Inject
    ComputerLabelService labels;

    /** A stored segment as read. */
    public record Seg(long id, UUID deviceId, long start, long end, String kind, String subject) {
        Span span() {
            return new Span(start, end);
        }
    }

    /** Time after classification and phone priority: disjoint across devices. */
    public record Piece(long start, long end, UUID categoryId, String subject, long segId, UUID deviceId) {
        long length() {
            return end - start;
        }
    }

    /** Local day bounds and the awake window that belongs to the day. */
    public record Bounds(long start, long end, long awakeStart, long awakeEnd, Long previousTailEnd) {
    }

    /** What trends need from one day. */
    public record Summary(LocalDate date, long screenMs, long screenMinutes, long awayMinutes,
                          Map<UUID, Long> categoryMs, boolean closed, boolean partial, boolean incomplete) {
        public boolean complete() {
            return closed && !partial && !incomplete;
        }
    }

    // ------------------------------------------------------------------ context

    @Transactional
    public ReadContext context() {
        UserTimeline timeline = timelines.load();
        Settings s = settings.snapshot();
        Catalog catalog = Catalog.load();
        Map<UUID, List<ReadContext.Inst>> installs = new HashMap<>();
        jdbc.list("select id, device_id, created_at, revoked_at from installations order by created_at",
                rs -> installs.computeIfAbsent(Jdbc.uuid(rs, "device_id"), k -> new ArrayList<>())
                        .add(new ReadContext.Inst(Jdbc.uuid(rs, "id"), Jdbc.instant(rs, "created_at"),
                                Jdbc.instant(rs, "revoked_at"))));
        List<ReadContext.DeviceInfo> devices = new ArrayList<>(jdbc.list(
                "select id, name, kind, created_at from devices order by created_at",
                rs -> new ReadContext.DeviceInfo(Jdbc.uuid(rs, "id"), rs.getString("name"), rs.getString("kind"),
                        Jdbc.instant(rs, "created_at"), List.copyOf(installs.getOrDefault(Jdbc.uuid(rs, "id"), List.of())))));
        // Phones first: overlapping time belongs to the phone
        devices.sort(Comparator.comparingInt((ReadContext.DeviceInfo d) -> d.android() ? 0 : 1)
                .thenComparing(ReadContext.DeviceInfo::createdAt));
        Map<UUID, Long> coverage = new HashMap<>();
        jdbc.list("""
                        select c.device_id, c.first_event_at from sensor_coverage c join devices d on d.id = c.device_id
                        where c.first_event_at is not null
                          and ((d.kind = 'android' and c.sensor = 'usage') or (d.kind = 'desktop' and c.sensor = 'activity'))""",
                rs -> coverage.put(Jdbc.uuid(rs, "device_id"), rs.getLong("first_event_at")));
        return new ReadContext(clock.now(), timeline, s, catalog, labels.all(), devices, coverage);
    }

    public Bounds bounds(ReadContext ctx, LocalDate date) {
        UserTimeline tl = ctx.timeline;
        long s = tl.startOf(date);
        long e = tl.startOf(date.plusDays(1));
        Settings.Awake aw = ctx.settings.awakeFor(date);
        long as = tl.instantOf(date, aw.start());
        long ae = aw.crossesMidnight() ? tl.instantOf(date.plusDays(1), aw.end()) : tl.instantOf(date, aw.end());
        Settings.Awake prev = ctx.settings.awakeFor(date.minusDays(1));
        Long tail = prev.crossesMidnight() ? tl.instantOf(date, prev.end()) : null;
        return new Bounds(s, e, as, ae, tail);
    }

    public List<Seg> segments(long from, long to) {
        return jdbc.list("""
                        select id, device_id, start_ts, end_ts, kind, subject from segments
                        where start_ts < ? and end_ts > ? order by start_ts, id""",
                rs -> new Seg(rs.getLong("id"), Jdbc.uuid(rs, "device_id"), rs.getLong("start_ts"), rs.getLong("end_ts"),
                        rs.getString("kind"), rs.getString("subject")),
                to, from);
    }

    // ------------------------------------------------------------------ classification

    /** Work-window spans of every local date touching [from, to): Work inside, Personal computer outside. */
    List<Span> workSpans(ReadContext ctx, long from, long to) {
        UserTimeline tl = ctx.timeline;
        List<Span> out = new ArrayList<>();
        LocalDate last = tl.dateOf(Math.max(from, to - 1));
        for (LocalDate d = tl.dateOf(from).minusDays(1); !d.isAfter(last); d = d.plusDays(1)) {
            if (!ctx.settings.isWorkday(d)) {
                continue;
            }
            Settings.Work w = ctx.settings.workFor(d);
            long s = tl.instantOf(d, w.start());
            long e = w.end().isAfter(w.start()) ? tl.instantOf(d, w.end()) : tl.instantOf(d.plusDays(1), w.end());
            if (e > s) {
                out.add(new Span(s, e));
            }
        }
        return Intervals.union(out);
    }

    /**
     * Categorised pieces of one device's segments, before priority. Computer time
     * inside a stretch marked by hand takes the stretch's category; the rest is
     * Work inside the work window and Personal computer outside it.
     */
    List<Piece> classify(ReadContext ctx, List<Seg> segs, List<Span> work) {
        List<Piece> out = new ArrayList<>();
        for (Seg s : segs) {
            switch (s.kind()) {
                case SegmentDraft.COMPUTER -> {
                    List<Span> one = List.of(s.span());
                    List<Piece> parts = new ArrayList<>();
                    for (ComputerLabel l : ctx.labels) {
                        if (l.from() >= s.end()) {
                            break;
                        }
                        List<Span> marked = Intervals.intersect(one, List.of(l.span()));
                        for (Span m : marked) {
                            parts.add(new Piece(m.start(), m.end(), l.categoryId(), null, s.id(), s.deviceId()));
                        }
                        one = Intervals.subtract(one, marked);
                    }
                    for (Span w : Intervals.intersect(one, work)) {
                        parts.add(new Piece(w.start(), w.end(), Category.WORK, null, s.id(), s.deviceId()));
                    }
                    for (Span p : Intervals.subtract(one, work)) {
                        parts.add(new Piece(p.start(), p.end(), Category.PERSONAL_COMPUTER, null, s.id(), s.deviceId()));
                    }
                    parts.sort(Comparator.comparingLong(Piece::start));
                    out.addAll(parts);
                }
                case SegmentDraft.UNATTRIBUTED ->
                        out.add(new Piece(s.start(), s.end(), Category.UNATTRIBUTED, null, s.id(), s.deviceId()));
                default -> out.add(new Piece(s.start(), s.end(), ctx.catalog.categoryOf(s.subject()), s.subject(),
                        s.id(), s.deviceId()));
            }
        }
        return out;
    }

    /** Resolves overlaps between devices: earlier devices in the context order (phones) win. */
    public List<Piece> pieces(ReadContext ctx, List<Seg> segs, long from, long to) {
        List<Span> work = workSpans(ctx, from, to);
        Map<UUID, List<Seg>> byDevice = new LinkedHashMap<>();
        for (Seg s : segs) {
            byDevice.computeIfAbsent(s.deviceId(), k -> new ArrayList<>()).add(s);
        }
        List<UUID> order = new ArrayList<>();
        for (ReadContext.DeviceInfo d : ctx.devices) {
            if (byDevice.containsKey(d.id())) {
                order.add(d.id());
            }
        }
        for (UUID id : byDevice.keySet()) {
            if (!order.contains(id)) {
                order.add(id);
            }
        }
        List<Span> covered = new ArrayList<>();
        List<Piece> out = new ArrayList<>();
        for (UUID id : order) {
            List<Seg> mine = byDevice.get(id);
            List<Span> mineSpans = new ArrayList<>();
            List<Piece> classified = classify(ctx, mine, work);
            classified.sort(Comparator.comparingLong(Piece::start));
            out.addAll(subtractCovered(classified, covered));
            for (Seg s : mine) {
                mineSpans.add(s.span());
            }
            List<Span> merged = new ArrayList<>(covered);
            merged.addAll(mineSpans);
            covered = Intervals.union(merged);
        }
        out.sort(Comparator.comparingLong(Piece::start));
        return out;
    }

    /** The parts of sorted, disjoint pieces not already covered by a device with priority. One pass. */
    static List<Piece> subtractCovered(List<Piece> sorted, List<Span> covered) {
        List<Piece> out = new ArrayList<>();
        int j = 0;
        for (Piece p : sorted) {
            long cur = p.start();
            while (j < covered.size() && covered.get(j).end() <= cur) {
                j++;
            }
            for (int k = j; k < covered.size() && covered.get(k).start() < p.end(); k++) {
                Span y = covered.get(k);
                if (y.start() > cur) {
                    out.add(new Piece(cur, y.start(), p.categoryId(), p.subject(), p.segId(), p.deviceId()));
                }
                cur = Math.max(cur, y.end());
                if (cur >= p.end()) {
                    break;
                }
            }
            if (cur < p.end()) {
                out.add(new Piece(cur, p.end(), p.categoryId(), p.subject(), p.segId(), p.deviceId()));
            }
        }
        return out;
    }

    static List<Span> union(List<Seg> segs) {
        List<Span> spans = new ArrayList<>();
        for (Seg s : segs) {
            spans.add(s.span());
        }
        return Intervals.union(spans);
    }

    static Map<UUID, Long> categoryMs(List<Piece> pieces, long from, long to) {
        Map<UUID, Long> out = new LinkedHashMap<>();
        for (Piece p : pieces) {
            long len = Math.min(p.end(), to) - Math.max(p.start(), from);
            if (len > 0) {
                out.merge(p.categoryId(), len, Long::sum);
            }
        }
        return out;
    }

    List<Dto.CategoryMinutes> categoryMinutes(ReadContext ctx, Map<UUID, Long> ms) {
        Map<UUID, Long> minutes = Minutes.apportion(ms);
        List<Dto.CategoryMinutes> out = new ArrayList<>();
        ms.keySet().stream()
                .sorted(Comparator.comparingLong((UUID id) -> -ms.get(id)).thenComparingInt(ctx.catalog::order))
                .forEach(id -> out.add(ctx.catalog.minutes(id, minutes.get(id))));
        return out;
    }

    // ------------------------------------------------------------------ headline

    private record Headline(Dto.Headline dto, long awayMinutes, boolean incomplete) {
    }

    private Headline headline(ReadContext ctx, LocalDate date, Bounds b, List<Span> union) {
        boolean today = date.equals(ctx.today);
        boolean future = date.isAfter(ctx.today);
        long from = b.awakeStart();
        long to = b.awakeEnd();
        if (today) {
            to = Math.max(from, Math.min(to, ctx.nowMs()));
        }
        if (future) {
            to = from;
        }
        long awakeMin = Minutes.of(to - from);
        long screenMin = Minutes.of(Intervals.totalWithin(union, from, to));
        long away = Math.max(0, awakeMin - screenMin);

        List<Span> notOutside = new ArrayList<>();
        notOutside.add(new Span(b.awakeStart(), b.awakeEnd()));
        if (b.previousTailEnd() != null && b.previousTailEnd() > b.start()) {
            notOutside.add(new Span(b.start(), b.previousTailEnd()));
        }
        long outside = Intervals.total(Intervals.subtract(Intervals.clip(union, b.start(), b.end()),
                Intervals.union(notOutside)));

        boolean incomplete = false;
        Set<String> silent = new LinkedHashSet<>();
        if (to > from) {
            for (ReadContext.DeviceInfo d : ctx.devices) {
                for (HealthService.Gap g : health.gaps(d, from, to, ctx.nowMs())) {
                    if (!g.expected() && g.overlap(from, to) > HealthService.threshold(d)) {
                        incomplete = true;
                        for (String sensor : g.sensors()) {
                            silent.add(sensor + " (" + d.name() + ")");
                        }
                    }
                }
            }
        }
        Dto.Headline dto = new Dto.Headline(awakeMin, away, screenMin, Minutes.of(outside), today, incomplete,
                new ArrayList<>(silent));
        return new Headline(dto, away, incomplete);
    }

    String state(ReadContext ctx, LocalDate date, Bounds b) {
        if (!date.isBefore(ctx.today)) {
            return "open";
        }
        return health.closed(ctx, b.end(), false) ? "closed" : "arriving";
    }

    boolean partial(ReadContext ctx, Bounds b) {
        for (ReadContext.DeviceInfo d : ctx.devices) {
            Long c = ctx.coverageStart.get(d.id());
            if (d.active() && c != null && b.start() < c) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ day

    @Transactional
    public Dto.DayView day(LocalDate date) {
        ReadContext ctx = context();
        Bounds b = bounds(ctx, date);
        long qFrom = Math.min(b.start(), b.awakeStart());
        long qTo = Math.max(b.end(), b.awakeEnd());
        List<Seg> segs = segments(qFrom, qTo);
        List<Span> union = union(segs);
        List<Piece> pieces = pieces(ctx, segs, qFrom, qTo);

        // Totals: union across devices, and each device on its own
        long unionDay = Intervals.totalWithin(union, b.start(), b.end());
        List<Dto.DeviceMinutes> byDevice = new ArrayList<>();
        long sumDevices = 0;
        for (ReadContext.DeviceInfo d : ctx.devices) {
            List<Seg> mine = segs.stream().filter(s -> s.deviceId().equals(d.id())).toList();
            if (!d.paired() && mine.isEmpty()) {
                continue;
            }
            long ms = Intervals.totalWithin(union(mine), b.start(), b.end());
            sumDevices += ms;
            byDevice.add(new Dto.DeviceMinutes(d.id().toString(), d.name(), d.kind(), Minutes.of(ms)));
        }
        long overlapMinutes = Math.max(0, Minutes.of(sumDevices) - Minutes.of(unionDay));

        List<Dto.CategoryMinutes> byCategory = categoryMinutes(ctx, categoryMs(pieces, b.start(), b.end()));
        List<Dto.Hour> hours = hours(ctx, b, pieces);
        Headline headline = headline(ctx, date, b, union);

        return new Dto.DayView(date.toString(), ctx.timeline.zoneOf(date).getId(), state(ctx, date, b),
                derivationDto(), headline.dto(),
                new Dto.Totals(Minutes.of(unionDay), overlapMinutes, byDevice),
                byCategory, hours, topSubjects(ctx, pieces, b), unlocks(b), notifications(b),
                computerLabels(ctx, pieces, b));
    }

    /** The marked stretches touching the day, with the computer time each took on it. */
    private static List<Dto.DayLabel> computerLabels(ReadContext ctx, List<Piece> pieces, Bounds b) {
        List<Dto.DayLabel> out = new ArrayList<>();
        for (ComputerLabel l : ctx.labels) {
            if (l.to() <= b.start() || l.from() >= b.end()) {
                continue;
            }
            long from = Math.max(l.from(), b.start());
            long to = Math.min(l.to(), b.end());
            long ms = 0;
            for (Piece p : pieces) {
                // Inside a stretch, computer pieces are the ones without a subject in its category
                if (p.subject() == null && p.categoryId().equals(l.categoryId())) {
                    ms += Math.max(0, Math.min(p.end(), to) - Math.max(p.start(), from));
                }
            }
            out.add(new Dto.DayLabel(l.id().toString(), l.categoryId().toString(), l.from(), l.to(), Minutes.of(ms)));
        }
        return out;
    }

    public Dto.Derivation derivationDto() {
        DerivationService.State s = derivation.state();
        return new Dto.Derivation(s.rebuilding(), s.progress());
    }

    private List<Dto.Hour> hours(ReadContext ctx, Bounds b, List<Piece> pieces) {
        List<Dto.Hour> out = new ArrayList<>();
        for (long h = b.start(); h < b.end(); h += HOUR_MS) {
            long he = Math.min(b.end(), h + HOUR_MS);
            Map<UUID, Long> ms = categoryMs(pieces, h, he);
            Map<UUID, Long> minutes = Minutes.apportion(ms);
            List<Dto.HourCategory> cats = new ArrayList<>();
            ms.keySet().stream().sorted(Comparator.comparingInt(ctx.catalog::order))
                    .forEach(id -> cats.add(new Dto.HourCategory(id.toString(), minutes.get(id))));
            Map<Long, Long> bySeg = new LinkedHashMap<>();
            Map<Long, Piece> sample = new HashMap<>();
            for (Piece p : pieces) {
                long len = Math.min(p.end(), he) - Math.max(p.start(), h);
                if (len > 0) {
                    bySeg.merge(p.segId(), len, Long::sum);
                    sample.putIfAbsent(p.segId(), p);
                }
            }
            List<Dto.SegmentRef> refs = bySeg.entrySet().stream()
                    .sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                    .limit(SEGMENTS_PER_HOUR)
                    .map(e -> new Dto.SegmentRef(Long.toString(e.getKey()), label(ctx, sample.get(e.getKey())),
                            Minutes.of(e.getValue())))
                    .toList();
            out.add(new Dto.Hour(ctx.timeline.label(h), h, cats, refs));
        }
        return out;
    }

    private static String label(ReadContext ctx, Piece p) {
        if (p.subject() != null) {
            return Subjects.label(p.subject());
        }
        Category c = ctx.catalog.get(p.categoryId());
        return c == null ? "?" : c.name;
    }

    private List<Dto.SubjectMinutes> topSubjects(ReadContext ctx, List<Piece> pieces, Bounds b) {
        Map<String, Long> ms = new HashMap<>();
        for (Piece p : pieces) {
            long len = Math.min(p.end(), b.end()) - Math.max(p.start(), b.start());
            if (p.subject() != null && len > 0) {
                ms.merge(p.subject(), len, Long::sum);
            }
        }
        return ms.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                .limit(TOP_SUBJECTS)
                .map(e -> new Dto.SubjectMinutes(e.getKey(), Subjects.label(e.getKey()),
                        ctx.catalog.categoryOf(e.getKey()).toString(), Minutes.of(e.getValue())))
                .toList();
    }

    private Dto.Unlocks unlocks(Bounds b) {
        Map<String, Long> first = new LinkedHashMap<>();
        long[] count = {0};
        jdbc.list("select at, first_app from unlocks where at >= ? and at < ? order by at",
                rs -> {
                    count[0]++;
                    String app = rs.getString("first_app");
                    if (app != null) {
                        first.merge(app, 1L, Long::sum);
                    }
                    return null;
                }, b.start(), b.end());
        List<Dto.FirstApp> apps = first.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                .map(e -> new Dto.FirstApp("app:" + e.getKey(), e.getKey(), e.getValue()))
                .toList();
        return new Dto.Unlocks(count[0], apps);
    }

    private record Exclusion(UUID deviceId, String type, long from, long to) {
    }

    /**
     * Counted at read time: updates, group summaries and ongoing notifications are
     * not "posted"; a removal by click is tapped, cancel and cancel_all are dismissed.
     */
    private List<Dto.NotificationCount> notifications(Bounds b) {
        List<Exclusion> exclusions = jdbc.list("select device_id, type, from_ts, to_ts from exclusions",
                rs -> new Exclusion(Jdbc.uuid(rs, "device_id"), rs.getString("type"), rs.getLong("from_ts"),
                        rs.getLong("to_ts")));
        record N(UUID device, String type, long ts, JsonNode p) {
        }
        List<N> rows = new ArrayList<>(jdbc.list("""
                        select device_id, type, ts, payload from raw_events
                        where type in ('android.notification_posted', 'android.notification_removed')
                        and ts >= ? and ts < ? and not suspect""",
                rs -> new N(Jdbc.uuid(rs, "device_id"), rs.getString("type"), rs.getLong("ts"), json(rs.getString("payload"))),
                b.start(), b.end()));
        rows.removeIf(n -> exclusions.stream().anyMatch(x -> x.deviceId().equals(n.device())
                && (x.type() == null || x.type().equals(n.type())) && n.ts() >= x.from() && n.ts() <= x.to()));

        // Removals of summaries and ongoing notifications are not interactions either
        List<String> refs = rows.stream().filter(n -> n.type().endsWith("removed")).map(n -> n.p().path("ref").asText()).toList();
        Set<String> ignoredRefs = new java.util.HashSet<>();
        if (!refs.isEmpty()) {
            jdbc.list("""
                            select payload->>'ref' from raw_events where type = 'android.notification_posted'
                            and payload->>'ref' = any(?) and ((payload->>'is_group_summary')::boolean or (payload->>'is_ongoing')::boolean)""",
                    rs -> ignoredRefs.add(rs.getString(1)), new Jdbc.TextArray(refs));
        }
        Map<String, long[]> byPkg = new LinkedHashMap<>();
        for (N n : rows) {
            String pkg = n.p().path("package").asText(null);
            if (pkg == null) {
                continue;
            }
            long[] c = byPkg.computeIfAbsent(pkg, k -> new long[3]);
            if (n.type().endsWith("posted")) {
                if (!n.p().path("is_update").asBoolean(false) && !n.p().path("is_group_summary").asBoolean(false)
                        && !n.p().path("is_ongoing").asBoolean(false)) {
                    c[0]++;
                }
            } else if (!ignoredRefs.contains(n.p().path("ref").asText())) {
                switch (n.p().path("reason").asText("")) {
                    case "click" -> c[1]++;
                    case "cancel", "cancel_all" -> c[2]++;
                    default -> {
                    }
                }
            }
        }
        return byPkg.entrySet().stream()
                .filter(e -> e.getValue()[0] + e.getValue()[1] + e.getValue()[2] > 0)
                .sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]))
                .map(e -> new Dto.NotificationCount("app:" + e.getKey(), e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[2]))
                .toList();
    }

    // ------------------------------------------------------------------ summaries for trends

    /** Summary of one day from preloaded segments (which must cover the day and its awake window). */
    public Summary summary(ReadContext ctx, LocalDate date, List<Seg> loaded) {
        Bounds b = bounds(ctx, date);
        long qFrom = Math.min(b.start(), b.awakeStart());
        long qTo = Math.max(b.end(), b.awakeEnd());
        List<Seg> segs = loaded.stream().filter(s -> s.start() < qTo && s.end() > qFrom).toList();
        List<Span> union = union(segs);
        List<Piece> pieces = pieces(ctx, segs, qFrom, qTo);
        long screenMs = Intervals.totalWithin(union, b.start(), b.end());
        boolean closed = date.isBefore(ctx.today) && health.closed(ctx, b.end(), false);
        Headline h = headline(ctx, date, b, union);
        return new Summary(date, screenMs, Minutes.of(screenMs), h.awayMinutes(), categoryMs(pieces, b.start(), b.end()),
                closed, partial(ctx, b), h.incomplete());
    }

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    public Instant now() {
        return clock.now();
    }
}
