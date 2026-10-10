package org.beconscient.derive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beconscient.common.AppClock;
import org.beconscient.common.Intervals;
import org.beconscient.common.Jdbc;
import org.beconscient.device.Device;
import org.beconscient.ingest.ContractFilter;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Turns raw events into segments, block by block (docs/design.md, KTD3).
 *
 * A pass claims a device's dirty-mark ids, derives the marked UTC days in order,
 * writes their segments and deletes exactly the claimed ids, all in one
 * transaction under a per-device advisory lock. A mark written meanwhile has a new
 * id and survives for the next pass. Each block keeps the state it starts and ends
 * in; a changed end state marks the next block dirty.
 */
@ApplicationScoped
public class DerivationService {

    private static final Logger LOG = Logger.getLogger(DerivationService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Bump when a rule changes: a background rebuild then re-derives every block. */
    public static final int VERSION = 1;
    /** Namespace of the per-device advisory lock. */
    static final int LOCK_NAMESPACE = 0x6263_0001;

    static final List<String> PHONE_TYPES = List.of(ContractFilter.USAGE, ContractFilter.SHORTS, ContractFilter.HEALTH);

    @Inject
    Jdbc jdbc;

    @Inject
    AppClock clock;

    @Inject
    DirtyMarks dirty;

    public record State(int version, boolean rebuilding, Integer targetVersion, int total, int done) {
        public double progress() {
            return rebuilding && total > 0 ? Math.min(1.0, (double) done / total) : 1.0;
        }
    }

    private record Exclusion(String type, long from, long to) {
    }

    /** Per-device context of one pass. */
    private record Ctx(UUID deviceId, boolean android, Long knownUntil, List<Exclusion> exclusions, LocalDate today) {
    }

    private record Block(LocalDate day, JsonNode startState, JsonNode endState, Long cappedAt) {
    }

    public State state() {
        return jdbc.one("select version, rebuilding, target_version, total_blocks, done_blocks from derivation_state where id = 1",
                rs -> new State(rs.getInt("version"), rs.getBoolean("rebuilding"), Jdbc.nullableInt(rs, "target_version"),
                        rs.getInt("total_blocks"), rs.getInt("done_blocks"))).orElseThrow();
    }

    /** A process whose code is older than the stored version refuses to write. */
    boolean writable() {
        State s = state();
        int effective = s.rebuilding() && s.targetVersion() != null ? Math.max(s.version(), s.targetVersion()) : s.version();
        if (effective > VERSION) {
            LOG.warnf("Derivation version %d is older than the stored %d: not writing", VERSION, effective);
            return false;
        }
        return true;
    }

    /** Passes until no dirty mark is left, at most {@code maxPasses}. Returns the blocks derived. */
    public int runUntilClean(int maxPasses) {
        int total = 0;
        for (int i = 0; i < maxPasses; i++) {
            int n = runPass();
            total += n;
            if (n == 0) {
                break;
            }
        }
        return total;
    }

    /** One pass over every device with dirty marks, one transaction per device. */
    public int runPass() {
        List<UUID> devices = jdbc.list("select distinct device_id from dirty_blocks", rs -> Jdbc.uuid(rs, "device_id"));
        int derived = 0;
        for (UUID d : devices) {
            try {
                derived += QuarkusTransaction.requiringNew().call(() -> deriveDevice(d));
            } catch (RuntimeException e) {
                LOG.errorf(e, "Derivation of device %s failed; its marks stay for the next pass", d);
            }
        }
        return derived;
    }

    int deriveDevice(UUID deviceId) {
        if (!writable()) {
            return 0;
        }
        lock(deviceId);
        List<long[]> claimed = jdbc.list("select id, day from dirty_blocks where device_id = ? order by day",
                rs -> new long[]{rs.getLong("id"), Jdbc.date(rs, "day").toEpochDay()}, deviceId);
        if (claimed.isEmpty()) {
            return 0;
        }
        Ctx ctx = context(deviceId);
        if (ctx == null) {
            return 0;
        }
        TreeSet<LocalDate> days = new TreeSet<>();
        for (long[] c : claimed) {
            days.add(LocalDate.ofEpochDay(c[1]));
        }
        if (ctx.android && ctx.knownUntil != null) {
            // Blocks cut at the last known fact are derived again once later facts exist
            days.addAll(jdbc.list("select day from blocks where device_id = ? and capped_at is not null and capped_at < ?",
                    rs -> Jdbc.date(rs, "day"), deviceId, ctx.knownUntil));
        }
        List<LocalDate> chained = new ArrayList<>();
        for (LocalDate day : days) {
            LocalDate next = deriveBlock(ctx, day);
            if (next != null && !days.tailSet(day, false).contains(next)) {
                chained.add(next);
            }
        }
        long[] ids = claimed.stream().mapToLong(c -> c[0]).toArray();
        jdbc.update("delete from dirty_blocks where id = any(?)", new Jdbc.LongArray(ids));
        dirty.mark(deviceId, chained);
        return days.size();
    }

    /** Derives one block outside a pass (the version rebuild). Must run in a transaction. */
    public void rebuildBlock(UUID deviceId, LocalDate day) {
        if (!writable()) {
            throw new IllegalStateException("derivation version is older than the stored one");
        }
        lock(deviceId);
        Ctx ctx = context(deviceId);
        if (ctx == null) {
            return;
        }
        LocalDate next = deriveBlock(ctx, day);
        if (next != null) {
            dirty.mark(deviceId, List.of(next));
        }
    }

    private void lock(UUID deviceId) {
        jdbc.one("select pg_advisory_xact_lock(?, ?)", rs -> 1, LOCK_NAMESPACE, deviceId.hashCode());
    }

    private Ctx context(UUID deviceId) {
        Optional<String> kind = jdbc.one("select kind from devices where id = ?", rs -> rs.getString(1), deviceId);
        if (kind.isEmpty()) {
            return null;
        }
        Long knownUntil = jdbc.one("select max(ts) from raw_events where device_id = ? and not suspect",
                rs -> Jdbc.nullableLong(rs, "max"), deviceId).orElse(null);
        List<Exclusion> ex = jdbc.list("select type, from_ts, to_ts from exclusions where device_id = ?",
                rs -> new Exclusion(rs.getString("type"), rs.getLong("from_ts"), rs.getLong("to_ts")), deviceId);
        return new Ctx(deviceId, Device.ANDROID.equals(kind.get()), knownUntil, ex,
                DirtyMarks.utcDay(clock.nowMs()));
    }

    /** Returns the day that must be derived next because this block's end state changed, if any. */
    private LocalDate deriveBlock(Ctx ctx, LocalDate day) {
        return ctx.android ? derivePhone(ctx, day) : deriveDesktop(ctx, day);
    }

    private LocalDate derivePhone(Ctx ctx, LocalDate day) {
        long ds = DirtyMarks.dayStart(day);
        long de = ds + DirtyMarks.DAY_MS;
        long cap = Math.min(de, Math.max(ds, ctx.knownUntil == null ? ds : ctx.knownUntil));

        Block prev = jdbc.one("""
                        select day, start_state, end_state, capped_at from blocks
                        where device_id = ? and day < ? order by day desc limit 1""",
                this::block, ctx.deviceId, day).orElse(null);
        PhoneState start = prev != null ? PhoneState.fromJson(prev.endState) : PhoneState.initial();
        if (start.home == null) {
            start.home = homeAt(ctx.deviceId, ds);
        }
        List<EventRow> events = events(ctx, PHONE_TYPES, ds, de + PhoneSegmenter.FIRST_APP_WINDOW_MS);
        PhoneSegmenter.Result r = PhoneSegmenter.derive(new PhoneSegmenter.Input(start, events, ds, de, cap));

        writeSegments(ctx.deviceId, day, r.segments());
        jdbc.update("delete from raw_intervals where device_id = ? and block_day = ?", ctx.deviceId, day);
        List<Object[]> raws = new ArrayList<>();
        for (Intervals.Span s : r.raw()) {
            raws.add(new Object[]{ctx.deviceId, day, s.start(), s.end(), VERSION});
        }
        jdbc.batch("insert into raw_intervals (device_id, block_day, start_ts, end_ts, version) values (?, ?, ?, ?, ?)", raws);
        jdbc.update("delete from unlocks where device_id = ? and block_day = ?", ctx.deviceId, day);
        List<Object[]> unlocks = new ArrayList<>();
        for (PhoneSegmenter.Unlock u : r.unlocks()) {
            unlocks.add(new Object[]{ctx.deviceId, day, u.at(), u.firstApp(), u.eventId(), VERSION});
        }
        jdbc.batch("insert into unlocks (device_id, block_day, at, first_app, source_event_id, version) values (?, ?, ?, ?, ?, ?)",
                unlocks);
        boolean capped = cap < de;
        writeBlock(ctx.deviceId, day, start.toJson().toString(), r.end().toJson().toString(), capped ? cap : null,
                r.segments().size(), r.violations());

        // Chain: a state that keeps counting runs into the next day; otherwise the next
        // existing block must start from this end state
        LocalDate following = day.plusDays(1);
        if (!capped && r.end().active() && !following.isAfter(ctx.today)) {
            return following;
        }
        Block next = jdbc.one("""
                        select day, start_state, end_state, capped_at from blocks
                        where device_id = ? and day > ? order by day limit 1""",
                this::block, ctx.deviceId, day).orElse(null);
        if (next != null && !PhoneState.fromJson(next.startState).sameAs(r.end())) {
            return next.day;
        }
        return null;
    }

    private LocalDate deriveDesktop(Ctx ctx, LocalDate day) {
        long ds = DirtyMarks.dayStart(day);
        long de = ds + DirtyMarks.DAY_MS;
        List<EventRow> activity = intervals(ctx, ContractFilter.ACTIVITY, ds, de);
        List<EventRow> sites = intervals(ctx, ContractFilter.SITE, ds, de);
        List<SegmentDraft> segs = DesktopSegmenter.derive(activity, sites, ds, de);
        writeSegments(ctx.deviceId, day, segs);
        writeBlock(ctx.deviceId, day, null, null, null, segs.size(), PhoneSegmenter.invariants(segs, ds, de));
        return null;
    }

    private Block block(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Block(Jdbc.date(rs, "day"), json(rs.getString("start_state")), json(rs.getString("end_state")),
                Jdbc.nullableLong(rs, "capped_at"));
    }

    /** The launcher named by the latest health record before the instant, else the first one ever. */
    private String homeAt(UUID deviceId, long at) {
        return jdbc.one("""
                        select payload->>'home_package' as home from raw_events
                        where device_id = ? and type = 'health' and not suspect and payload->>'home_package' is not null
                        order by (ts < ?) desc, case when ts < ? then -ts else ts end limit 1""",
                rs -> rs.getString("home"), deviceId, at, at).orElse(null);
    }

    private List<EventRow> events(Ctx ctx, List<String> types, long from, long to) {
        List<EventRow> rows = jdbc.list("""
                        select id, type, ts, end_ts, rev, payload from raw_events
                        where device_id = ? and type = any(?) and ts >= ? and ts < ? and not suspect
                        order by ts, id""",
                this::row, ctx.deviceId, new Jdbc.TextArray(types), from, to);
        rows.removeIf(e -> excluded(ctx, e));
        return rows;
    }

    private List<EventRow> intervals(Ctx ctx, String type, long ds, long de) {
        List<EventRow> rows = jdbc.list("""
                        select id, type, ts, end_ts, rev, payload from raw_events
                        where device_id = ? and type = ? and ts > ? and ts < ? and end_ts > ? and not suspect
                        order by ts, id""",
                this::row, ctx.deviceId, type, ds - 14 * DirtyMarks.DAY_MS, de, ds);
        rows.removeIf(e -> excluded(ctx, e));
        return rows;
    }

    private static boolean excluded(Ctx ctx, EventRow e) {
        for (Exclusion x : ctx.exclusions) {
            if ((x.type == null || x.type.equals(e.type())) && e.ts() >= x.from && e.ts() <= x.to) {
                return true;
            }
        }
        return false;
    }

    private EventRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EventRow(rs.getLong("id"), rs.getString("type"), rs.getLong("ts"), Jdbc.nullableLong(rs, "end_ts"),
                Jdbc.nullableInt(rs, "rev"), json(rs.getString("payload")));
    }

    private void writeSegments(UUID deviceId, LocalDate day, List<SegmentDraft> segs) {
        jdbc.update("delete from segments where device_id = ? and block_day = ?", deviceId, day);
        List<Object[]> rows = new ArrayList<>();
        for (SegmentDraft s : segs) {
            rows.add(new Object[]{deviceId, day, s.start, s.end, s.kind, s.subject, new Jdbc.LongArray(s.sourceArray()),
                    new Jdbc.TextArray(s.ruleList()), VERSION});
        }
        jdbc.batch("""
                insert into segments (device_id, block_day, start_ts, end_ts, kind, subject, source_event_ids, rules, version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows);
    }

    private void writeBlock(UUID deviceId, LocalDate day, String startState, String endState, Long cappedAt,
                            int segments, List<String> violations) {
        if (!violations.isEmpty()) {
            LOG.warnf("Invariant violations in block %s/%s: %s", deviceId, day, violations);
        }
        String detail = violations.isEmpty() ? null : ContractFilter.truncate(String.join("; ", violations), 300);
        jdbc.update("""
                        insert into blocks (device_id, day, version, start_state, end_state, capped_at, segment_count,
                          invariant_violations, violation_detail, derived_at)
                        values (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
                        on conflict (device_id, day) do update set version = excluded.version,
                          start_state = excluded.start_state, end_state = excluded.end_state, capped_at = excluded.capped_at,
                          segment_count = excluded.segment_count, invariant_violations = excluded.invariant_violations,
                          violation_detail = excluded.violation_detail, derived_at = excluded.derived_at""",
                deviceId, day, VERSION, startState, endState, cappedAt, segments, violations.size(), detail, clock.now());
    }

    private static JsonNode json(String s) {
        if (s == null) {
            return null;
        }
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
