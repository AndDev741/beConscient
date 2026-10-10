package org.beconscient.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.beconscient.common.AppClock;
import org.beconscient.common.Jdbc;
import org.beconscient.crypto.Bytes;
import org.beconscient.derive.DirtyMarks;
import org.beconscient.device.Device;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Stores one batch (envelope.md, events.md). Order of work:
 * credential (constant time) before any event, then the sequence and resend
 * rules, then every event through the contract filter. Events, interval
 * revisions, dirty marks, counters and the batch log share one transaction; the
 * receipt goes out only after it commits.
 */
@ApplicationScoped
public class BatchService {

    static final long DAY_MS = DirtyMarks.DAY_MS;
    static final long SUSPECT_PAST_MS = 10 * DAY_MS;
    /** Compared against when the installation is unknown, so the timing does not reveal it. */
    private static final byte[] DUMMY_HASH = Bytes.sha256(Bytes.utf8("no such installation"));

    @Inject
    Jdbc jdbc;

    @Inject
    AppClock clock;

    @Inject
    DirtyMarks dirty;

    @ConfigProperty(name = "beconscient.ingest.max-events", defaultValue = "2000")
    int maxEvents;

    public sealed interface Outcome permits Stored, Unauthorized, SequenceConflict, BadRequest, TooLarge {
    }

    /** {@code receipt} is the receipt plaintext; {@code replay} is true for a resend of a logged batch. */
    public record Stored(String receipt, boolean replay) implements Outcome {
    }

    public record Unauthorized() implements Outcome {
    }

    public record SequenceConflict() implements Outcome {
    }

    public record BadRequest() implements Outcome {
    }

    public record TooLarge() implements Outcome {
    }

    private record Inst(UUID id, UUID deviceId, String deviceKind, byte[] credentialHash, boolean revoked, long lastSeq,
                        String lastTz) {
    }

    private record Existing(long rowId, long ts, Long end, int rev, boolean suspect) {
    }

    @Transactional
    public Outcome process(JsonNode root) {
        if (root == null || !root.isObject() || !root.path("header").isObject()) {
            return new BadRequest();
        }
        JsonNode header = root.get("header");
        UUID installationId = ContractFilter.parseUuid(header.path("installation_id").asText(null));
        byte[] credential = decode(header.path("credential").asText(null));

        // 1. Credential, in constant time, before anything else is read
        Inst inst = installationId == null ? null : jdbc.one("""
                        select i.id, i.device_id, d.kind, i.credential_hash, i.revoked_at, i.last_seq, i.last_tz
                        from installations i join devices d on d.id = i.device_id
                        where i.id = ? for update of i""",
                rs -> new Inst(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs, "device_id"), rs.getString("kind"),
                        rs.getBytes("credential_hash"), rs.getTimestamp("revoked_at") != null, rs.getLong("last_seq"),
                        rs.getString("last_tz")),
                installationId).orElse(null);
        boolean match = Bytes.constantTimeEquals(Bytes.sha256(credential), inst != null ? inst.credentialHash : DUMMY_HASH);
        if (inst == null || !match || inst.revoked || credential.length == 0) {
            return new Unauthorized();
        }

        // 2. Header and size
        Long seq = ContractFilter.integral(header.get("seq"));
        Long contractVersion = ContractFilter.integral(header.get("contract_version"));
        JsonNode events = root.get("events");
        if (seq == null || seq < 1 || contractVersion == null || contractVersion != ContractFilter.CONTRACT_VERSION
                || events == null || !events.isArray()) {
            return new BadRequest();
        }
        if (events.size() > maxEvents) {
            return new TooLarge();
        }

        // 3. Frozen-batch resend rule
        byte[] listHash = listHash(events);
        Optional<String[]> logged = jdbc.one("select list_hash, receipt from batch_log where installation_id = ? and seq = ?",
                rs -> new String[]{Bytes.b64url(rs.getBytes("list_hash")), rs.getString("receipt")}, inst.id, seq);
        if (logged.isPresent()) {
            if (logged.get()[0].equals(Bytes.b64url(listHash))) {
                return new Stored(logged.get()[1], true);
            }
            flagClone(inst.id);
            return new SequenceConflict();
        }
        if (seq <= inst.lastSeq) {
            flagClone(inst.id);
            return new SequenceConflict();
        }

        // 4. Events
        Instant now = clock.now();
        long nowMs = now.toEpochMilli();
        boolean android = Device.ANDROID.equals(inst.deviceKind);
        Map<String, Instant> firstReported = new HashMap<>();
        jdbc.list("select sensor, first_reported_at from sensor_coverage where device_id = ?",
                rs -> firstReported.put(rs.getString("sensor"), Jdbc.instant(rs, "first_reported_at")), inst.deviceId);
        Map<String, Long> coverageMin = new HashMap<>();
        Set<LocalDate> dirtyDays = new TreeSet<>();
        long duplicates = 0;
        long rejected = 0;
        long dropped = 0;
        long conflicts = 0;
        ArrayNode results = JsonNodeFactory.instance.arrayNode();

        for (JsonNode raw : events) {
            ContractFilter.Result r = ContractFilter.apply(raw);
            dropped += r.dropped();
            ObjectNode result = results.addObject();
            result.put("id", r.idText());
            if (r instanceof ContractFilter.Rejected rej) {
                jdbc.update("""
                                insert into quarantine (device_id, installation_id, event_id, type, ts, reason, payload, received_at)
                                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?)""",
                        inst.deviceId, inst.id, rej.idText(), rej.type(), rej.ts(), rej.reason(), rej.stripped().toString(), now);
                rejected++;
                result.put("status", "rejected");
                result.put("reason", rej.reason());
                continue;
            }
            ContractFilter.Accepted a = (ContractFilter.Accepted) r;
            String sensor = ContractFilter.sensorOf(a.type(), a.payload());
            Instant anchor = firstReported.computeIfAbsent(sensor, s -> now);
            boolean suspect = a.ts() > nowMs + DAY_MS
                    || (a.end() != null && a.end() > nowMs + DAY_MS)
                    || a.ts() < anchor.toEpochMilli() - SUSPECT_PAST_MS;

            boolean stored;
            if (a.interval()) {
                Existing ex = jdbc.one("""
                                select id, ts, end_ts, rev, suspect from raw_events
                                where device_id = ? and event_id = ? for update""",
                        rs -> new Existing(rs.getLong("id"), rs.getLong("ts"), Jdbc.nullableLong(rs, "end_ts"),
                                rs.getInt("rev"), rs.getBoolean("suspect")),
                        inst.deviceId, a.id()).orElse(null);
                if (ex == null) {
                    insert(inst, a, suspect, now, seq);
                    stored = true;
                    if (!suspect) {
                        dirtyDays.addAll(DirtyMarks.daysCovering(a.ts(), a.end()));
                    }
                } else if (ex.ts != a.ts()) {
                    // The start never changes: record the conflict and ignore the rewrite
                    jdbc.update("""
                                    insert into interval_conflicts (device_id, event_id, stored_start, sent_start, sent_rev, received_at)
                                    values (?, ?, ?, ?, ?, ?)""",
                            inst.deviceId, a.id(), ex.ts, a.ts(), a.rev(), now);
                    conflicts++;
                    stored = false;
                } else if (a.rev() > ex.rev) {
                    jdbc.update("""
                                    update raw_events set end_ts = ?, rev = ?, payload = ?::jsonb, tz = ?, tz_offset_min = ?,
                                    suspect = ?, received_at = ?, batch_seq = ? where id = ?""",
                            a.end(), a.rev(), a.payload().toString(), a.tz(), a.tzOffsetMin(), suspect, now, seq, ex.rowId);
                    stored = true;
                    // Both the old and the new end are dirty, whichever way the end moved.
                    // A suspect version never widens the range.
                    long oldEnd = ex.end == null ? a.ts() : ex.end;
                    long reach = suspect ? (ex.suspect ? a.ts() : oldEnd)
                            : (ex.suspect ? a.end() : Math.max(a.end(), oldEnd));
                    if (!suspect || !ex.suspect) {
                        dirtyDays.addAll(DirtyMarks.daysCovering(a.ts(), reach));
                    }
                } else {
                    stored = false;
                }
            } else {
                stored = insert(inst, a, suspect, now, seq) != null;
                if (stored && !suspect && affectsDerivation(a, android)) {
                    dirtyDays.add(DirtyMarks.utcDay(a.ts()));
                }
                if (stored && !suspect && ContractFilter.TZ_CHANGE.equals(a.type())) {
                    zonePoint(inst.deviceId, a.ts(), a.payload().path("zone").asText(null), "marker");
                }
            }

            if (stored) {
                result.put("status", "accepted");
                if (!suspect) {
                    coverageMin.merge(sensor, a.ts(), Math::min);
                } else {
                    coverageMin.putIfAbsent(sensor, null);
                }
            } else {
                duplicates++;
                result.put("status", "duplicate");
            }
        }

        // 5. Everything else the batch changes, in the same transaction
        dirty.mark(inst.deviceId, dirtyDays);
        for (Map.Entry<String, Long> c : coverageMin.entrySet()) {
            jdbc.update("""
                            insert into sensor_coverage (device_id, sensor, first_reported_at, first_event_at) values (?, ?, ?, ?)
                            on conflict (device_id, sensor) do update
                            set first_event_at = least(sensor_coverage.first_event_at, excluded.first_event_at)""",
                    inst.deviceId, c.getKey(), now, c.getValue());
        }
        jdbc.update("""
                        insert into device_counters (device_id, duplicates, rejected, dropped_fields, interval_conflicts)
                        values (?, ?, ?, ?, ?)
                        on conflict (device_id) do update set
                          duplicates = device_counters.duplicates + excluded.duplicates,
                          rejected = device_counters.rejected + excluded.rejected,
                          dropped_fields = device_counters.dropped_fields + excluded.dropped_fields,
                          interval_conflicts = device_counters.interval_conflicts + excluded.interval_conflicts""",
                inst.deviceId, duplicates, rejected, dropped, conflicts);

        String tz = header.path("tz").isTextual() ? ContractFilter.truncate(header.get("tz").asText(), 64) : null;
        Long sentAt = ContractFilter.integral(header.get("sent_at"));
        Long tzOffset = ContractFilter.integral(header.get("tz_offset_min"));
        String clientVersion = header.path("client_version").isTextual()
                ? ContractFilter.truncate(header.get("client_version").asText(), 32) : null;
        if (android && tz != null && validZone(tz) && !tz.equals(inst.lastTz)) {
            zonePoint(inst.deviceId, sentAt != null ? sentAt : nowMs, tz, "header");
        }

        ObjectNode receipt = JsonNodeFactory.instance.objectNode();
        receipt.put("installation_id", inst.id.toString());
        receipt.put("seq", seq);
        receipt.set("results", results);
        String receiptJson = receipt.toString();

        jdbc.update("""
                        insert into batch_log (installation_id, seq, list_hash, receipt, received_at, sent_at, clock_skew_ms,
                          tz, tz_offset_min, client_version, contract_version, event_count, gap_before)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                inst.id, seq, listHash, receiptJson, now, sentAt, sentAt == null ? null : nowMs - sentAt,
                tz, tzOffset == null ? null : tzOffset.intValue(), clientVersion, contractVersion.intValue(),
                events.size(), Math.max(0, seq - inst.lastSeq - 1));
        jdbc.update("""
                        update installations set last_seq = greatest(last_seq, ?), last_seen_at = ?,
                          client_version = coalesce(?, client_version), last_tz = coalesce(?, last_tz)
                        where id = ?""",
                seq, now, clientVersion, tz != null && validZone(tz) ? tz : null, inst.id);
        return new Stored(receiptJson, false);
    }

    private Long insert(Inst inst, ContractFilter.Accepted a, boolean suspect, Instant now, long seq) {
        return jdbc.one("""
                        insert into raw_events (device_id, installation_id, event_id, type, ts, end_ts, rev, tz, tz_offset_min,
                          natural_key, payload, suspect, received_at, batch_seq)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                        on conflict do nothing
                        returning id""",
                rs -> rs.getLong(1),
                inst.deviceId, inst.id, a.id(), a.type(), a.ts(), a.end(), a.rev(), a.tz(), a.tzOffsetMin(),
                a.naturalKey(), a.payload().toString(), suspect, now, seq).orElse(null);
    }

    /** Which point events change derived segments. Notifications and zones are read-time only. */
    static boolean affectsDerivation(ContractFilter.Accepted a, boolean android) {
        return switch (a.type()) {
            case ContractFilter.USAGE, ContractFilter.SHORTS -> true;
            case ContractFilter.HEALTH -> android && a.payload().has("home_package");
            default -> false;
        };
    }

    private void zonePoint(UUID deviceId, long at, String zone, String source) {
        if (zone != null && validZone(zone)) {
            jdbc.update("insert into zone_points (device_id, at, zone, source) values (?, ?, ?, ?)", deviceId, at, zone, source);
        }
    }

    private void flagClone(UUID installationId) {
        jdbc.update("update installations set clone_suspected = true where id = ?", installationId);
    }

    static boolean validZone(String zone) {
        try {
            ZoneId.of(zone);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] decode(String credential) {
        if (credential == null) {
            return new byte[0];
        }
        try {
            return Bytes.fromB64url(credential);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    /** The frozen batch's identity: its event ids with revisions, order-insensitive. */
    static byte[] listHash(JsonNode events) {
        List<String> entries = new ArrayList<>();
        for (JsonNode e : events) {
            String id = e.path("id").isTextual() ? e.get("id").asText() : "";
            Long rev = ContractFilter.integral(e.get("rev"));
            entries.add(id + ":" + (rev == null ? 0 : rev));
        }
        entries.sort(null);
        return Bytes.sha256(Bytes.utf8(String.join("\n", entries)));
    }
}
