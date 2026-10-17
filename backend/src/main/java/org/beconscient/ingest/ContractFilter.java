package org.beconscient.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Enforces contract/events.md (contract version 1) on one event: only the listed
 * fields survive, strings are truncated to their maximum length, and the
 * validation rules decide between accepting and rejecting.
 *
 * Every field that is not listed, or that a type-specific rule forbids (a
 * LinkedIn title, a YouTube page kind), is dropped and counted. A rejected event
 * keeps only its valid allowed fields, so the quarantine never holds what the
 * contract forbids.
 */
public final class ContractFilter {

    public static final int CONTRACT_VERSION = 1;

    // Rejection reasons, as they appear in receipts
    public static final String MISSING_ID = "missing_id";
    public static final String INVALID_ID = "invalid_id";
    public static final String MISSING_TYPE = "missing_type";
    public static final String UNKNOWN_TYPE = "unknown_type";
    public static final String MISSING_TIME = "missing_time";
    public static final String MISSING_REV = "missing_rev";
    public static final String END_BEFORE_START = "end_before_start";
    public static final String INVALID_ENUM = "invalid_enum";
    public static final String MISSING_FIELD = "missing_field";
    public static final String NOT_AN_OBJECT = "not_an_object";

    public static final String USAGE = "android.usage";
    public static final String TZ_CHANGE = "android.tz_change";
    public static final String NOTIFICATION_POSTED = "android.notification_posted";
    public static final String NOTIFICATION_REMOVED = "android.notification_removed";
    public static final String SHORTS = "android.shorts";
    public static final String PAUSE = "android.pause";
    public static final String ACTIVITY = "desktop.activity";
    public static final String SITE = "browser.site";
    public static final String HEALTH = "health";

    public static final Set<String> TYPES = Set.of(USAGE, TZ_CHANGE, NOTIFICATION_POSTED, NOTIFICATION_REMOVED, SHORTS,
            PAUSE, ACTIVITY, SITE, HEALTH);
    public static final Set<String> INTERVAL_TYPES = Set.of(ACTIVITY, SITE);

    static final Set<String> USAGE_KINDS = Set.of("ACTIVITY_RESUMED", "ACTIVITY_PAUSED", "ACTIVITY_STOPPED",
            "SCREEN_INTERACTIVE", "SCREEN_NON_INTERACTIVE", "KEYGUARD_SHOWN", "KEYGUARD_HIDDEN", "DEVICE_SHUTDOWN",
            "DEVICE_STARTUP");
    static final Set<String> ACTIVITY_KINDS = Set.of("ACTIVITY_RESUMED", "ACTIVITY_PAUSED", "ACTIVITY_STOPPED");
    static final Set<String> TZ_REASONS = Set.of("INITIAL", "TIMEZONE_CHANGED", "TIME_SET", "BOOT");
    static final Set<String> REMOVAL_REASONS = Set.of("click", "cancel", "cancel_all", "app_cancel", "other");
    static final Set<String> SHORTS_STATES = Set.of("enter", "exit", "unclassified");
    static final Set<String> PAUSE_OUTCOMES = Set.of("opened", "backed_out", "alternative");
    static final Set<String> ACTIVITY_REASONS = Set.of("input", "inhibitor", "playing");
    static final Set<String> SITES = Set.of("youtube", "linkedin");
    static final Set<String> BROWSERS = Set.of("chrome", "brave", "other");
    static final Set<String> PAGE_KINDS = Set.of("feed", "messaging", "profile", "jobs", "other");
    static final Set<String> SENSOR_STATES = Set.of("ok", "stopped", "disabled", "unknown");
    static final Set<String> SENSOR_NAMES = Set.of("usage", "notifications", "shorts", "battery_exemption",
            "activity", "extension_chrome", "extension_brave");

    private enum Kind { STRING, INT, BOOL, ENUM, SENSORS }

    private record Field(String name, Kind kind, int maxLength, Set<String> values, boolean required) {
    }

    private static Field str(String name, int max) {
        return new Field(name, Kind.STRING, max, null, false);
    }

    private static Field reqStr(String name, int max) {
        return new Field(name, Kind.STRING, max, null, true);
    }

    private static Field integer(String name) {
        return new Field(name, Kind.INT, 0, null, false);
    }

    private static Field bool(String name) {
        return new Field(name, Kind.BOOL, 0, null, false);
    }

    private static Field enumOf(String name, Set<String> values, boolean required) {
        return new Field(name, Kind.ENUM, 0, values, required);
    }

    private static final Map<String, List<Field>> FIELDS = Map.of(
            USAGE, List.of(enumOf("kind", USAGE_KINDS, true), str("package", 255), str("class", 255), integer("instance")),
            TZ_CHANGE, List.of(reqStr("zone", 64), integer("offset_min"), enumOf("reason", TZ_REASONS, false)),
            NOTIFICATION_POSTED, List.of(reqStr("package", 255), reqStr("ref", 64), bool("is_update"),
                    bool("is_group_summary"), bool("is_ongoing"), integer("importance"), str("category", 32)),
            NOTIFICATION_REMOVED, List.of(reqStr("package", 255), reqStr("ref", 64),
                    enumOf("reason", REMOVAL_REASONS, true), integer("posted_at")),
            SHORTS, List.of(enumOf("state", SHORTS_STATES, true), str("youtube_version", 64),
                    integer("youtube_version_code"), integer("rules_version")),
            PAUSE, List.of(reqStr("package", 255), enumOf("outcome", PAUSE_OUTCOMES, true), str("block", 60),
                    str("alternative", 255), integer("wait_ms")),
            ACTIVITY, List.of(enumOf("reason", ACTIVITY_REASONS, true)),
            SITE, List.of(enumOf("site", SITES, true), enumOf("browser", BROWSERS, true), bool("playing"),
                    str("title", 300), enumOf("page_kind", PAGE_KINDS, false)),
            HEALTH, List.of(str("client_version", 32), new Field("sensors", Kind.SENSORS, 0, null, false),
                    integer("watermark"), integer("queue_depth"), integer("oldest_unsent_age_ms"),
                    str("home_package", 255), bool("expected_stop")));

    private static final Set<String> COMMON_POINT = Set.of("id", "type", "at", "tz", "tz_offset_min");
    private static final Set<String> COMMON_INTERVAL = Set.of("id", "type", "start", "end", "rev", "tz", "tz_offset_min");

    /** The outcome for one event. */
    public sealed interface Result permits Accepted, Rejected {
        String idText();

        int dropped();
    }

    /** A clean event, ready to store. {@code payload} holds only the type-specific fields. */
    public record Accepted(String idText, UUID id, String type, boolean interval, long ts, Long end, Integer rev,
                           String tz, Integer tzOffsetMin, ObjectNode payload, String naturalKey, int dropped)
            implements Result {
    }

    /** {@code stripped} is what goes to quarantine. */
    public record Rejected(String idText, String type, String reason, Long ts, ObjectNode stripped, int dropped)
            implements Result {
    }

    private ContractFilter() {
    }

    public static Result apply(JsonNode raw) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (raw == null || !raw.isObject()) {
            return new Rejected(null, null, NOT_AN_OBJECT, null, f.objectNode(), 0);
        }
        String idText = raw.path("id").isTextual() ? truncate(raw.get("id").asText(), 64) : null;
        String type = raw.path("type").isTextual() ? raw.get("type").asText() : null;

        if (type == null) {
            return reject(idText, null, MISSING_TYPE, raw, Set.of("id"), null);
        }
        if (!TYPES.contains(type)) {
            // Nothing but the common fields is known for an unknown type
            String safeType = truncate(type, 64);
            return reject(idText, safeType, UNKNOWN_TYPE, raw, COMMON_POINT, integral(raw.get("at")));
        }
        boolean interval = INTERVAL_TYPES.contains(type);
        Set<String> common = interval ? COMMON_INTERVAL : COMMON_POINT;
        List<Field> spec = FIELDS.get(type);
        Long time = interval ? integral(raw.get("start")) : integral(raw.get("at"));

        // Clean copy of the type-specific fields; invalid values never survive into it
        ObjectNode payload = f.objectNode();
        int dropped = 0;
        String enumProblem = null;
        String missing = null;
        for (Iterator<Map.Entry<String, JsonNode>> it = raw.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String name = e.getKey();
            if (common.contains(name)) {
                continue;
            }
            Field field = spec.stream().filter(x -> x.name.equals(name)).findFirst().orElse(null);
            if (field == null) {
                dropped++;
                continue;
            }
            JsonNode v = e.getValue();
            if (v == null || v.isNull()) {
                continue;
            }
            switch (field.kind) {
                case STRING -> {
                    if (v.isTextual()) {
                        payload.put(name, truncate(v.asText(), field.maxLength));
                    } else {
                        dropped++;
                    }
                }
                case INT -> {
                    Long n = integral(v);
                    if (n != null) {
                        payload.put(name, n);
                    } else {
                        dropped++;
                    }
                }
                case BOOL -> {
                    if (v.isBoolean()) {
                        payload.put(name, v.asBoolean());
                    } else {
                        dropped++;
                    }
                }
                case ENUM -> {
                    if (v.isTextual() && field.values.contains(v.asText())) {
                        payload.put(name, v.asText());
                    } else {
                        enumProblem = INVALID_ENUM;
                    }
                }
                case SENSORS -> {
                    SensorResult s = sensors(v);
                    dropped += s.dropped;
                    if (s.invalidEnum) {
                        enumProblem = INVALID_ENUM;
                    }
                    payload.set(name, s.node);
                }
            }
        }

        // Type-specific rules
        if (USAGE.equals(type) && payload.has("kind") && ACTIVITY_KINDS.contains(payload.get("kind").asText())
                && !payload.has("package")) {
            missing = MISSING_FIELD;
        }
        if (SITE.equals(type) && payload.has("site")) {
            boolean linkedin = "linkedin".equals(payload.get("site").asText());
            if (linkedin && payload.has("title")) {
                payload.remove("title");
                dropped++;
            }
            if (!linkedin && payload.has("page_kind")) {
                payload.remove("page_kind");
                dropped++;
            }
        }
        for (Field field : spec) {
            // A required enum with a bad value is already reported as invalid_enum
            boolean badEnum = field.kind == Kind.ENUM && raw.hasNonNull(field.name);
            if (field.required && !payload.has(field.name) && !badEnum) {
                missing = MISSING_FIELD;
            }
        }

        String tz = raw.path("tz").isTextual() ? truncate(raw.get("tz").asText(), 64) : null;
        Integer tzOffset = integral(raw.get("tz_offset_min")) != null ? integral(raw.get("tz_offset_min")).intValue() : null;
        if (raw.has("tz") && !raw.get("tz").isNull() && tz == null) {
            dropped++;
        }

        // Validation that rejects, in the order of contract/events.md
        String reason = null;
        UUID id = null;
        if (idText == null) {
            reason = MISSING_ID;
        } else {
            id = parseUuid(idText);
            if (id == null) {
                reason = INVALID_ID;
            }
        }
        Long end = null;
        Integer rev = null;
        if (reason == null) {
            if (interval) {
                end = integral(raw.get("end"));
                Long r = integral(raw.get("rev"));
                if (time == null || end == null) {
                    reason = MISSING_TIME;
                } else if (end < time) {
                    reason = END_BEFORE_START;
                } else if (r == null || r < 1 || r > Integer.MAX_VALUE) {
                    reason = MISSING_REV;
                } else {
                    rev = r.intValue();
                }
            } else if (time == null) {
                reason = MISSING_TIME;
            }
        }
        if (reason == null && enumProblem != null) {
            reason = enumProblem;
        }
        if (reason == null && missing != null) {
            reason = missing;
        }
        if (reason != null) {
            ObjectNode stripped = commonCopy(raw, common);
            stripped.setAll(payload);
            return new Rejected(idText, type, reason, time, stripped, dropped);
        }
        return new Accepted(idText, id, type, interval, time, end, rev, tz, tzOffset, payload,
                naturalKey(type, time, payload), dropped);
    }

    private record SensorResult(ObjectNode node, int dropped, boolean invalidEnum) {
    }

    private static SensorResult sensors(JsonNode v) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (!v.isObject()) {
            return new SensorResult(out, 1, false);
        }
        int dropped = 0;
        boolean invalid = false;
        for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!SENSOR_NAMES.contains(e.getKey()) || !e.getValue().isObject()) {
                dropped++;
                continue;
            }
            ObjectNode s = JsonNodeFactory.instance.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> si = e.getValue().fields(); si.hasNext(); ) {
                Map.Entry<String, JsonNode> se = si.next();
                JsonNode sv = se.getValue();
                switch (se.getKey()) {
                    case "state" -> {
                        if (sv.isTextual() && SENSOR_STATES.contains(sv.asText())) {
                            s.put("state", sv.asText());
                        } else {
                            invalid = true;
                        }
                    }
                    case "detail" -> {
                        if (sv.isTextual()) {
                            s.put("detail", truncate(sv.asText(), 120));
                        } else if (!sv.isNull()) {
                            dropped++;
                        }
                    }
                    case "last_event_at" -> {
                        Long n = integral(sv);
                        if (n != null) {
                            s.put("last_event_at", n);
                        } else if (!sv.isNull()) {
                            dropped++;
                        }
                    }
                    default -> dropped++;
                }
            }
            out.set(e.getKey(), s);
        }
        return new SensorResult(out, dropped, invalid);
    }

    private static Rejected reject(String idText, String type, String reason, JsonNode raw, Set<String> keep, Long ts) {
        int dropped = 0;
        for (Iterator<String> it = raw.fieldNames(); it.hasNext(); ) {
            if (!keep.contains(it.next())) {
                dropped++;
            }
        }
        return new Rejected(idText, type, reason, ts, commonCopy(raw, keep), dropped);
    }

    private static ObjectNode commonCopy(JsonNode raw, Set<String> keep) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (String k : keep) {
            JsonNode v = raw.get(k);
            if (v == null || v.isNull()) {
                continue;
            }
            if (k.equals("id") || k.equals("tz")) {
                if (v.isTextual()) {
                    out.put(k, truncate(v.asText(), 64));
                }
            } else if (k.equals("type")) {
                if (v.isTextual()) {
                    out.put(k, truncate(v.asText(), 64));
                }
            } else {
                Long n = integral(v);
                if (n != null) {
                    out.put(k, n);
                }
            }
        }
        return out;
    }

    /** Natural keys of contract/events.md, so re-reading a fact under a new id is a duplicate. */
    static String naturalKey(String type, long ts, ObjectNode payload) {
        return switch (type) {
            case USAGE -> String.join("\u001f", payload.path("kind").asText(""), Long.toString(ts),
                    payload.path("package").asText(""), payload.path("class").asText(""));
            // An update reuses the ref with a later "at", so a post is keyed by both
            case NOTIFICATION_POSTED -> payload.path("ref").asText() + "\u001f" + ts;
            case NOTIFICATION_REMOVED -> payload.path("ref").asText();
            default -> null;
        };
    }

    public static Long integral(JsonNode v) {
        if (v == null || !v.isNumber()) {
            return null;
        }
        if (v.isIntegralNumber() && v.canConvertToLong()) {
            return v.asLong();
        }
        if (v.isFloatingPointNumber()) {
            double d = v.asDouble();
            if (d == Math.rint(d) && Math.abs(d) < 9.0E15) {
                return (long) d;
            }
        }
        return null;
    }

    public static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        // Never split a surrogate pair
        int cut = max;
        if (Character.isHighSurrogate(s.charAt(cut - 1))) {
            cut--;
        }
        return s.substring(0, cut);
    }

    public static UUID parseUuid(String text) {
        if (text == null || text.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The type-specific field names of an event type (empty for unknown types). */
    public static List<String> fieldsOf(String type) {
        List<Field> spec = FIELDS.get(type);
        return spec == null ? List.of() : spec.stream().map(Field::name).toList();
    }

    /** The sensor whose coverage an event belongs to. */
    public static String sensorOf(String type, ObjectNode payload) {
        return switch (type) {
            case USAGE, TZ_CHANGE -> "usage";
            case NOTIFICATION_POSTED, NOTIFICATION_REMOVED -> "notifications";
            case SHORTS -> "shorts";
            case PAUSE -> "launcher";
            case ACTIVITY -> "activity";
            case SITE -> "extension_" + payload.path("browser").asText("other");
            case HEALTH -> "health";
            default -> "other";
        };
    }
}
