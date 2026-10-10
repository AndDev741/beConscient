package org.beconscient.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beconscient.common.Jdbc;
import org.beconscient.view.ReadContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Health records and what follows from them (contract/events.md, docs/design.md):
 * gaps, sensor states and whether an installation has caught up with a day.
 * Everything uses the device timestamp of the records, never the receive time,
 * so a replayed envelope cannot hide a dead sensor.
 */
@ApplicationScoped
public class HealthService {

    public static final long PHONE_PERIOD_MS = 15 * 60_000L;
    public static final long AGENT_PERIOD_MS = 5 * 60_000L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    Jdbc jdbc;

    public record Record(long rowId, long ts, boolean expectedStop, Long queueDepth, Long watermark, JsonNode sensors) {
    }

    /** A stretch without health records longer than three periods. {@code sensors} were alive before it. */
    public record Gap(UUID deviceId, long from, long to, boolean expected, List<String> sensors) {
        public long overlap(long a, long b) {
            return Math.max(0, Math.min(to, b) - Math.max(from, a));
        }
    }

    public static long period(ReadContext.DeviceInfo d) {
        return d.android() ? PHONE_PERIOD_MS : AGENT_PERIOD_MS;
    }

    public static long threshold(ReadContext.DeviceInfo d) {
        return 3 * period(d);
    }

    private Record record(java.sql.ResultSet rs) throws java.sql.SQLException {
        JsonNode p;
        try {
            p = JSON.readTree(rs.getString("payload"));
        } catch (Exception e) {
            p = JSON.createObjectNode();
        }
        return new Record(rs.getLong("id"), rs.getLong("ts"), p.path("expected_stop").asBoolean(false),
                p.hasNonNull("queue_depth") ? p.get("queue_depth").asLong() : null,
                p.hasNonNull("watermark") ? p.get("watermark").asLong() : null,
                p.path("sensors"));
    }

    public Optional<Record> latest(UUID deviceId) {
        return jdbc.one("""
                        select id, ts, payload from raw_events where device_id = ? and type = 'health' and not suspect
                        order by ts desc, id desc limit 1""", this::record, deviceId);
    }

    /** Gaps of one device that touch [from, to), never past now. */
    public List<Gap> gaps(ReadContext.DeviceInfo d, long from, long to, long now) {
        List<Gap> out = new ArrayList<>();
        if (!d.paired()) {
            return out;
        }
        long threshold = threshold(d);
        long windowStart = from;
        long firstInstall = Long.MAX_VALUE;
        long lastRevoke = Long.MIN_VALUE;
        boolean anyActive = false;
        for (ReadContext.Inst i : d.installations()) {
            firstInstall = Math.min(firstInstall, i.createdAt().toEpochMilli());
            if (i.active()) {
                anyActive = true;
            } else {
                lastRevoke = Math.max(lastRevoke, i.revokedAt().toEpochMilli());
            }
        }
        // The device's own health history may predate this server's pairing record (restore)
        Long firstHealth = jdbc.one("select min(ts) from raw_events where device_id = ? and type = 'health' and not suspect",
                rs -> Jdbc.nullableLong(rs, "min"), d.id()).orElse(null);
        long since = firstHealth == null ? firstInstall : Math.min(firstInstall, firstHealth);
        windowStart = Math.max(windowStart, since);
        long windowEnd = Math.min(to, now);
        if (!anyActive) {
            windowEnd = Math.min(windowEnd, lastRevoke);
        }
        if (windowEnd <= windowStart) {
            return out;
        }
        Record anchor = jdbc.one("""
                        select id, ts, payload from raw_events where device_id = ? and type = 'health' and not suspect and ts < ?
                        order by ts desc, id desc limit 1""", this::record, d.id(), windowStart).orElse(null);
        List<Record> inside = jdbc.list("""
                        select id, ts, payload from raw_events where device_id = ? and type = 'health' and not suspect
                        and ts >= ? and ts < ? order by ts, id""", this::record, d.id(), windowStart, windowEnd);
        Record prev = anchor;
        long prevTs = anchor != null ? anchor.ts() : windowStart;
        for (Record r : inside) {
            if (r.ts() - prevTs > threshold) {
                out.add(gap(d, prev, prevTs, r.ts()));
            }
            prev = r;
            prevTs = r.ts();
        }
        if (windowEnd - prevTs > threshold) {
            out.add(gap(d, prev, prevTs, windowEnd));
        }
        return out;
    }

    private Gap gap(ReadContext.DeviceInfo d, Record before, long from, long to) {
        boolean expected = before != null && before.expectedStop();
        if (!expected && d.android()) {
            // Shutdown and startup mark an expected gap on the phone
            expected = jdbc.count("""
                            select count(*) from raw_events where device_id = ? and type = 'android.usage' and not suspect
                            and ts >= ? and ts <= ? and payload->>'kind' in ('DEVICE_SHUTDOWN', 'DEVICE_STARTUP')""",
                    d.id(), from, to) > 0;
        }
        List<String> sensors = new ArrayList<>();
        if (before != null && before.sensors() != null && before.sensors().isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = before.sensors().fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if ("ok".equals(e.getValue().path("state").asText())) {
                    sensors.add(e.getKey());
                }
            }
        }
        if (sensors.isEmpty()) {
            sensors.add(d.primarySensor());
        }
        return new Gap(d.id(), from, to, expected, sensors);
    }

    /**
     * Whether an installation has caught up with an instant: its acknowledged
     * watermark (phone) or latest health record (computer) passed it, or its last
     * health record before it announced an expected stop with an empty queue.
     */
    public boolean caughtUp(ReadContext ctx, ReadContext.Inst inst, boolean android, long end) {
        long[] reach = (long[]) ctx.cacheGet("reach:" + inst.id(), () -> jdbc.one("""
                        select max((payload->>'watermark')::bigint) as wm, max(ts) as last from raw_events
                        where installation_id = ? and type = 'health' and not suspect""",
                rs -> new long[]{rs.getObject("wm") == null ? Long.MIN_VALUE : rs.getLong("wm"),
                        rs.getObject("last") == null ? Long.MIN_VALUE : rs.getLong("last")},
                inst.id()).orElse(new long[]{Long.MIN_VALUE, Long.MIN_VALUE}));
        long point = android ? (reach[0] != Long.MIN_VALUE ? reach[0] : reach[1]) : reach[1];
        if (point >= end) {
            return true;
        }
        Record last = jdbc.one("""
                        select id, ts, payload from raw_events where installation_id = ? and type = 'health' and not suspect
                        and ts < ? order by ts desc, id desc limit 1""", this::record, inst.id(), end).orElse(null);
        return last != null && last.expectedStop() && last.queueDepth() != null && last.queueDepth() == 0;
    }

    /** A past local day is closed when every active installation (only phones, for validation) caught up. */
    public boolean closed(ReadContext ctx, long dayEnd, boolean phonesOnly) {
        for (ReadContext.DeviceInfo d : ctx.devices) {
            if (phonesOnly && !d.android()) {
                continue;
            }
            for (ReadContext.Inst i : d.installations()) {
                if (i.active() && !caughtUp(ctx, i, d.android(), dayEnd)) {
                    return false;
                }
            }
        }
        return true;
    }
}
