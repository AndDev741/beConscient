package org.beconscient.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.beconscient.api.Dto;
import org.beconscient.category.Subjects;
import org.beconscient.common.ApiException;
import org.beconscient.common.Jdbc;
import org.beconscient.derive.DerivationService;
import org.beconscient.view.DayService;
import org.beconscient.view.ReadContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The backstage view: how each sensor is doing and where the data has holes. */
@ApplicationScoped
public class BackstageService {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final int EVENT_DAYS = 14;
    static final long LAG_WINDOW_MS = 7 * 86_400_000L;
    static final long GAP_WINDOW_MS = 7 * 86_400_000L;
    static final double ALERT_SHARE = 0.7;
    public static final List<String> BLIND_SPOTS = List.of(
            "Firefox", "YouTube in the phone browser", "Computer leisure outside the browser");
    static final List<String> ANDROID_SENSORS = List.of("usage", "notifications", "shorts", "battery_exemption");
    static final List<String> DESKTOP_SENSORS = List.of("activity", "extension_chrome", "extension_brave");

    @Inject
    Jdbc jdbc;

    @Inject
    HealthService health;

    @Inject
    DayService days;

    @Inject
    DerivationService derivation;

    @ConfigProperty(name = "beconscient.db-budget-bytes", defaultValue = "10737418240")
    long budgetBytes;

    @Transactional
    public Dto.Backstage backstage() {
        ReadContext ctx = days.context();
        List<Dto.DeviceHealth> devices = new ArrayList<>();
        for (ReadContext.DeviceInfo d : ctx.devices) {
            devices.add(device(ctx, d));
        }
        long size = jdbc.count("select pg_database_size(current_database())");
        DerivationService.State s = derivation.state();
        return new Dto.Backstage(devices, new Dto.Database(size, budgetBytes, alert(size, budgetBytes)),
                new Dto.DerivationInfo(s.version(), s.rebuilding(), s.progress()), BLIND_SPOTS);
    }

    private Dto.DeviceHealth device(ReadContext ctx, ReadContext.DeviceInfo d) {
        long now = ctx.nowMs();
        Optional<HealthService.Record> latest = health.latest(d.id());
        List<Dto.SensorStatus> sensors = sensors(d, latest.orElse(null), now);

        List<Dto.DayCount> perDay = new ArrayList<>();
        for (int i = EVENT_DAYS - 1; i >= 0; i--) {
            LocalDate day = ctx.today.minusDays(i);
            long n = jdbc.count("select count(*) from raw_events where device_id = ? and ts >= ? and ts < ?",
                    d.id(), ctx.timeline.startOf(day), ctx.timeline.endOf(day));
            perDay.add(new Dto.DayCount(day.toString(), n));
        }
        Dto.Lag lag = jdbc.one("""
                        select percentile_cont(0.5) within group (order by lag) as p50,
                               percentile_cont(0.95) within group (order by lag) as p95
                        from (select (extract(epoch from received_at) * 1000)::bigint - coalesce(end_ts, ts) as lag
                              from raw_events where device_id = ? and received_at > ?) l""",
                rs -> new Dto.Lag(rs.getObject("p50") == null ? null : Math.round(rs.getDouble("p50")),
                        rs.getObject("p95") == null ? null : Math.round(rs.getDouble("p95"))),
                d.id(), ctx.now.minusMillis(LAG_WINDOW_MS)).orElse(new Dto.Lag(null, null));
        long[] counters = jdbc.one("""
                        select duplicates, rejected, dropped_fields, interval_conflicts from device_counters where device_id = ?""",
                rs -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}, d.id())
                .orElse(new long[4]);
        long violations = jdbc.count("select coalesce(sum(invariant_violations), 0) from blocks where device_id = ?", d.id());
        Long skew = jdbc.one("""
                        select b.clock_skew_ms from batch_log b join installations i on i.id = b.installation_id
                        where i.device_id = ? order by b.received_at desc limit 1""",
                rs -> Jdbc.nullableLong(rs, "clock_skew_ms"), d.id()).orElse(null);
        List<Dto.GapView> gaps = new ArrayList<>();
        for (HealthService.Gap g : health.gaps(d, now - GAP_WINDOW_MS, now, now)) {
            gaps.add(new Dto.GapView(d.primarySensor(), g.from(), g.to(), g.expected()));
        }
        return new Dto.DeviceHealth(d.id().toString(), d.name(), d.kind(), sensors, perDay, lag,
                counters[0], counters[1], counters[2], counters[3], violations, skew, gaps,
                d.android() ? shorts(d.id()) : null);
    }

    /** The database deserves attention above 70% of its budget. */
    static boolean alert(long sizeBytes, long budgetBytes) {
        return budgetBytes > 0 && sizeBytes > ALERT_SHARE * budgetBytes;
    }

    /** States: active, expected_stop, late, stopped (permission or service off), silent (beyond the gap rule). */
    static List<Dto.SensorStatus> sensors(ReadContext.DeviceInfo d, HealthService.Record latest, long now) {
        Map<String, JsonNode> reported = new LinkedHashMap<>();
        for (String name : d.android() ? ANDROID_SENSORS : DESKTOP_SENSORS) {
            reported.put(name, null);
        }
        if (latest != null && latest.sensors() != null && latest.sensors().isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = latest.sensors().fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                reported.put(e.getKey(), e.getValue());
            }
        }
        List<Dto.SensorStatus> out = new ArrayList<>();
        long period = HealthService.period(d);
        for (Map.Entry<String, JsonNode> e : reported.entrySet()) {
            if (latest == null) {
                out.add(new Dto.SensorStatus(e.getKey(), "silent", null, null));
                continue;
            }
            JsonNode s = e.getValue();
            String detail = s == null ? "not reported by the collector"
                    : s.hasNonNull("detail") ? s.get("detail").asText() : null;
            // A sensor the collector does not report at all is as good as switched off
            String reportedState = s == null ? "stopped" : s.path("state").asText("unknown");
            long age = now - latest.ts();
            String state;
            if (latest.expectedStop() && age > period * 3 / 2) {
                state = "expected_stop";
            } else if (age > 3 * period) {
                state = "silent";
            } else if ("stopped".equals(reportedState) || "disabled".equals(reportedState)) {
                state = "stopped";
            } else if (age > period * 3 / 2 || "unknown".equals(reportedState)) {
                state = "late";
            } else {
                state = "active";
            }
            out.add(new Dto.SensorStatus(e.getKey(), state, latest.ts(), detail));
        }
        return out;
    }

    /**
     * Shorts detection per YouTube version: a version with an "enter" marker is
     * confirmed. When the newest versions have none, the view names the first
     * unconfirmed one and when it appeared.
     */
    Dto.ShortsStatus shorts(UUID deviceId) {
        record V(String version, long firstSeen, boolean confirmed) {
        }
        List<V> versions = jdbc.list("""
                        select payload->>'youtube_version' as version, min(ts) as first_seen,
                               bool_or(payload->>'state' = 'enter') as confirmed
                        from raw_events where device_id = ? and type = 'android.shorts' and not suspect
                          and payload->>'youtube_version' is not null
                        group by payload->>'youtube_version' order by min(ts)""",
                rs -> new V(rs.getString("version"), rs.getLong("first_seen"), rs.getBoolean("confirmed")), deviceId);
        String lastConfirmed = null;
        int lastConfirmedIdx = -1;
        for (int i = 0; i < versions.size(); i++) {
            if (versions.get(i).confirmed()) {
                lastConfirmed = versions.get(i).version();
                lastConfirmedIdx = i;
            }
        }
        String unconfirmed = null;
        Long since = null;
        if (lastConfirmedIdx + 1 < versions.size()) {
            V first = versions.get(lastConfirmedIdx + 1);
            unconfirmed = first.version();
            since = first.firstSeen();
        }
        return new Dto.ShortsStatus(unconfirmed, lastConfirmed, since);
    }

    /** Human-readable names of the rules a segment records. */
    static final Map<String, String> RULES = Map.ofEntries(
            Map.entry("clip_screen_on_unlocked", "Counted only while the screen was on and unlocked"),
            Map.entry("app_interval", "App interval from ACTIVITY_RESUMED to ACTIVITY_PAUSED or ACTIVITY_STOPPED"),
            Map.entry("no_resumed_app", "Screen on and unlocked with no app in the foreground: Unattributed"),
            Map.entry("launcher_not_an_app", "The launcher is not an app: Unattributed"),
            Map.entry("systemui_not_an_app", "System UI is not an app: Unattributed"),
            Map.entry("shorts_marker", "Inside a Shorts enter marker: YouTube Shorts"),
            Map.entry("split_screen_most_recent", "Two apps resumed at once: the most recently resumed one owns the time"),
            Map.entry("carried_state", "Continues the state the previous day ended in"),
            Map.entry("screen_on_inferred_from_activity", "Screen state unknown: a resumed activity means it was on"),
            Map.entry("cut_at_last_known_fact", "Cut at the device's last known fact (still arriving)"),
            Map.entry("merge_within_2s", "Intervals of the same app at most 2 seconds apart merged"),
            Map.entry("site_clipped_to_activity", "Site time clipped to the computer's active time"),
            Map.entry("site_carved_out_of_computer", "Site time taken out of Work or Personal computer"),
            Map.entry("desktop_activity", "Active time reported by the desktop agent"),
            Map.entry("work_or_personal_by_window_at_read",
                    "Work inside the work window valid that day, Personal computer outside it or on a non-working day"));

    @Transactional
    public Dto.Trace trace(long segmentId) {
        record S(DayService.Seg seg, int version, long[] sources, List<String> rules) {
        }
        S s = jdbc.one("""
                        select id, device_id, start_ts, end_ts, kind, subject, version, source_event_ids, rules
                        from segments where id = ?""",
                rs -> new S(new DayService.Seg(rs.getLong("id"), Jdbc.uuid(rs, "device_id"), rs.getLong("start_ts"),
                        rs.getLong("end_ts"), rs.getString("kind"), rs.getString("subject")),
                        rs.getInt("version"), Jdbc.longs(rs, "source_event_ids"), Jdbc.texts(rs, "rules")),
                segmentId).orElseThrow(() -> ApiException.notFound("Segment"));
        ReadContext ctx = days.context();
        DayService.Seg seg = s.seg();
        // The category is a reading decision; computer time takes the one it spends most of the segment in
        Map<UUID, Long> byCategory = new LinkedHashMap<>();
        for (DayService.Piece p : days.pieces(ctx, List.of(seg), seg.start(), seg.end())) {
            byCategory.merge(p.categoryId(), p.end() - p.start(), Long::sum);
        }
        UUID category = byCategory.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(ctx.catalog.categoryOf(seg.subject()));
        org.beconscient.category.Category cat = ctx.catalog.get(category);
        String label = seg.subject() != null ? Subjects.label(seg.subject()) : (cat == null ? "?" : cat.name);
        Dto.TraceSegment out = new Dto.TraceSegment(Long.toString(seg.id()), seg.deviceId().toString(), seg.subject(),
                label, category.toString(), seg.start(), seg.end(), s.version());

        List<JsonNode> events = jdbc.list("""
                        select id, event_id, type, ts, end_ts, rev, tz, payload, suspect, received_at
                        from raw_events where id = any(?) order by ts, id""",
                rs -> {
                    ObjectNode n = JSON.createObjectNode();
                    n.put("id", rs.getString("event_id"));
                    n.put("type", rs.getString("type"));
                    long end = rs.getLong("end_ts");
                    if (rs.wasNull()) {
                        n.put("at", rs.getLong("ts"));
                    } else {
                        n.put("start", rs.getLong("ts"));
                        n.put("end", end);
                        n.put("rev", rs.getInt("rev"));
                    }
                    if (rs.getString("tz") != null) {
                        n.put("tz", rs.getString("tz"));
                    }
                    JsonNode fields = parse(rs.getString("payload"));
                    if (fields.isObject()) {
                        n.setAll((ObjectNode) fields);
                    }
                    n.put("receivedAt", Jdbc.instant(rs, "received_at").toEpochMilli());
                    n.put("rowId", rs.getLong("id"));
                    return n;
                },
                new Jdbc.LongArray(s.sources()));
        List<String> rules = new ArrayList<>();
        for (String r : s.rules()) {
            rules.add(RULES.getOrDefault(r, r));
        }
        rules.add("Stored in UTC; the local day and the category are applied when reading");
        return new Dto.Trace(out, events, rules);
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }
}
