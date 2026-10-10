package org.beconscient.correction;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.beconscient.api.Dto;
import org.beconscient.common.ApiException;
import org.beconscient.common.AppClock;
import org.beconscient.common.Jdbc;
import org.beconscient.derive.DirtyMarks;
import org.beconscient.ingest.ContractFilter;
import org.beconscient.view.Timelines;
import org.beconscient.view.UserTimeline;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Two tools for data that should not have come in (KTD21). A redaction deletes a
 * field, or whole events, of one device and type over a period; it is destructive
 * and audited without content. An exclusion hides events from derivation and can
 * be undone. Both mark the affected blocks dirty.
 */
@ApplicationScoped
public class CorrectionService {

    private static final Logger LOG = Logger.getLogger(CorrectionService.class);

    @Inject
    Jdbc jdbc;

    @Inject
    AppClock clock;

    @Inject
    DirtyMarks dirty;

    @Inject
    Timelines timelines;

    @Inject
    SecurityIdentity identity;

    private record Range(UUID deviceId, String type, long from, long to) {
    }

    @Transactional
    public Dto.Corrections list() {
        List<Dto.RedactionView> redactions = jdbc.list("""
                        select id, device_id, type, from_ts, to_ts, field, affected, created_at
                        from redactions order by created_at desc""",
                rs -> new Dto.RedactionView(Jdbc.uuid(rs, "id").toString(), Jdbc.uuid(rs, "device_id").toString(),
                        rs.getString("type"), rs.getLong("from_ts"), rs.getLong("to_ts"), rs.getString("field"),
                        rs.getInt("affected"), Jdbc.instant(rs, "created_at").toEpochMilli()));
        List<Dto.ExclusionView> exclusions = jdbc.list("""
                        select id, device_id, type, from_ts, to_ts, note, created_at from exclusions order by created_at desc""",
                this::exclusion);
        return new Dto.Corrections(redactions, exclusions);
    }

    private Dto.ExclusionView exclusion(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Dto.ExclusionView(Jdbc.uuid(rs, "id").toString(), Jdbc.uuid(rs, "device_id").toString(),
                rs.getString("type"), rs.getLong("from_ts"), rs.getLong("to_ts"), rs.getString("note"),
                Jdbc.instant(rs, "created_at").toEpochMilli());
    }

    @Transactional
    public Dto.RedactionView redact(JsonNode req) {
        Range r = range(req, true);
        String field = req.hasNonNull("field") ? req.get("field").asText() : null;
        if (field != null && !redactable(r.type).contains(field)) {
            throw ApiException.badRequest("That field cannot be redacted for " + r.type + ".");
        }
        List<LocalDate> days = affectedDays(r);
        int affected;
        int quarantined;
        if (field == null) {
            if (ContractFilter.TZ_CHANGE.equals(r.type)) {
                jdbc.update("delete from zone_points where device_id = ? and source = 'marker' and at between ? and ?",
                        r.deviceId, r.from, r.to);
            }
            affected = jdbc.update("delete from raw_events where device_id = ? and type = ? and ts between ? and ?",
                    r.deviceId, r.type, r.from, r.to);
            quarantined = jdbc.update("delete from quarantine where device_id = ? and type = ? and ts between ? and ?",
                    r.deviceId, r.type, r.from, r.to);
        } else if ("tz".equals(field)) {
            affected = jdbc.update("""
                            update raw_events set tz = null, tz_offset_min = null
                            where device_id = ? and type = ? and ts between ? and ? and tz is not null""",
                    r.deviceId, r.type, r.from, r.to);
            quarantined = jdbc.update("""
                            update quarantine set payload = payload - 'tz'
                            where device_id = ? and type = ? and ts between ? and ? and jsonb_exists(payload, 'tz')""",
                    r.deviceId, r.type, r.from, r.to);
        } else {
            affected = jdbc.update("""
                            update raw_events set payload = payload - ?
                            where device_id = ? and type = ? and ts between ? and ? and jsonb_exists(payload, ?)""",
                    field, r.deviceId, r.type, r.from, r.to, field);
            quarantined = jdbc.update("""
                            update quarantine set payload = payload - ?
                            where device_id = ? and type = ? and ts between ? and ? and jsonb_exists(payload, ?)""",
                    field, r.deviceId, r.type, r.from, r.to, field);
        }
        dirty.mark(r.deviceId, days);
        UUID id = UUID.randomUUID();
        Instant now = clock.now();
        String by = identity == null || identity.isAnonymous() ? "system" : identity.getPrincipal().getName();
        jdbc.update("""
                        insert into redactions (id, device_id, type, from_ts, to_ts, field, affected, quarantine_affected,
                          created_at, created_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, r.deviceId, r.type, r.from, r.to, field, affected, quarantined, now, by);
        // The audit names what was removed, never what it contained
        LOG.infof("Redaction %s: %s of %s on device %s, %d events and %d quarantined touched",
                id, field == null ? "events" : "field " + field, r.type, r.deviceId, affected, quarantined);
        return new Dto.RedactionView(id.toString(), r.deviceId.toString(), r.type, r.from, r.to, field, affected,
                now.toEpochMilli());
    }

    @Transactional
    public Dto.ExclusionView exclude(JsonNode req) {
        Range r = range(req, false);
        String note = req.hasNonNull("note") ? ContractFilter.truncate(req.get("note").asText(), 300) : null;
        UUID id = UUID.randomUUID();
        Instant now = clock.now();
        jdbc.update("""
                        insert into exclusions (id, device_id, type, from_ts, to_ts, note, created_at)
                        values (?, ?, ?, ?, ?, ?, ?)""",
                id, r.deviceId, r.type, r.from, r.to, note, now);
        dirty.mark(r.deviceId, affectedDays(r));
        return new Dto.ExclusionView(id.toString(), r.deviceId.toString(), r.type, r.from, r.to, note, now.toEpochMilli());
    }

    @Transactional
    public void removeExclusion(UUID id) {
        Range r = jdbc.one("select device_id, type, from_ts, to_ts from exclusions where id = ?",
                rs -> new Range(Jdbc.uuid(rs, "device_id"), rs.getString("type"), rs.getLong("from_ts"), rs.getLong("to_ts")),
                id).orElseThrow(() -> ApiException.notFound("Exclusion"));
        jdbc.update("delete from exclusions where id = ?", id);
        dirty.mark(r.deviceId, affectedDays(r));
    }

    /** The UTC days the matching events touch, so derivation revisits exactly those blocks. */
    private List<LocalDate> affectedDays(Range r) {
        return jdbc.list("""
                        select distinct d::date as day from raw_events e,
                          generate_series((to_timestamp(e.ts / 1000.0) at time zone 'UTC')::date,
                                          (to_timestamp(greatest(e.ts, coalesce(e.end_ts, e.ts) - 1) / 1000.0) at time zone 'UTC')::date,
                                          interval '1 day') d
                        where e.device_id = ? and (cast(? as text) is null or e.type = ?) and e.ts between ? and ?
                          and not e.suspect""",
                rs -> Jdbc.date(rs, "day"), r.deviceId, r.type, r.type, r.from, r.to);
    }

    private Range range(JsonNode req, boolean typeRequired) {
        if (req == null || !req.isObject()) {
            throw ApiException.badRequest("A JSON object is required.");
        }
        UUID device;
        try {
            device = UUID.fromString(req.path("deviceId").asText(""));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("deviceId is required.");
        }
        if (jdbc.count("select count(*) from devices where id = ?", device) == 0) {
            throw ApiException.notFound("Device");
        }
        String type = req.hasNonNull("type") ? req.get("type").asText() : null;
        if (type == null && typeRequired) {
            throw ApiException.badRequest("type is required.");
        }
        if (type != null && !ContractFilter.TYPES.contains(type)) {
            throw ApiException.badRequest("Unknown event type.");
        }
        UserTimeline tl = timelines.load();
        long from = instant(req.get("from"), tl, false);
        long to = instant(req.get("to"), tl, true);
        if (to < from) {
            throw ApiException.badRequest("to must not be before from.");
        }
        return new Range(device, type, from, to);
    }

    /** Epoch milliseconds, an ISO instant, or a local date (its start for "from", its end for "to"). */
    static long instant(JsonNode v, UserTimeline tl, boolean end) {
        if (v == null || v.isNull()) {
            throw ApiException.badRequest("from and to are required.");
        }
        if (v.isIntegralNumber()) {
            return v.asLong();
        }
        String s = v.asText();
        try {
            if (s.length() == 10) {
                LocalDate d = LocalDate.parse(s);
                return end ? tl.endOf(d) - 1 : tl.startOf(d);
            }
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("from and to are epoch milliseconds, ISO instants or dates.");
        }
    }

    static Set<String> redactable(String type) {
        java.util.Set<String> out = new java.util.HashSet<>(ContractFilter.fieldsOf(type));
        out.add("tz");
        return out;
    }
}
