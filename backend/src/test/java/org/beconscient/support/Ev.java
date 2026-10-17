package org.beconscient.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/** Event builders following contract/events.md, and time helpers. */
public final class Ev {

    public static final ObjectMapper JSON = new ObjectMapper();
    public static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");
    public static final long MIN = 60_000L;
    public static final long HOUR = 60 * MIN;

    private Ev() {
    }

    public static long utc(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    /** "2026-10-06T10:00" read in Lisbon. */
    public static long lisbon(String local) {
        return LocalDateTime.parse(local).atZone(LISBON).toInstant().toEpochMilli();
    }

    public static long local(String local, String zone) {
        return LocalDateTime.parse(local).atZone(ZoneId.of(zone)).toInstant().toEpochMilli();
    }

    public static String id() {
        return UUID.randomUUID().toString();
    }

    private static ObjectNode point(String type, long at) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", id());
        n.put("type", type);
        n.put("at", at);
        return n;
    }

    public static ObjectNode usage(long at, String kind) {
        ObjectNode n = point("android.usage", at);
        n.put("kind", kind);
        return n;
    }

    public static ObjectNode usage(long at, String kind, String pkg) {
        ObjectNode n = usage(at, kind);
        n.put("package", pkg);
        n.put("class", pkg + ".Main");
        return n;
    }

    public static ObjectNode screenOn(long at) {
        return usage(at, "SCREEN_INTERACTIVE");
    }

    public static ObjectNode screenOff(long at) {
        return usage(at, "SCREEN_NON_INTERACTIVE");
    }

    public static ObjectNode unlocked(long at) {
        return usage(at, "KEYGUARD_HIDDEN");
    }

    public static ObjectNode locked(long at) {
        return usage(at, "KEYGUARD_SHOWN");
    }

    public static ObjectNode resumed(long at, String pkg) {
        return usage(at, "ACTIVITY_RESUMED", pkg);
    }

    public static ObjectNode paused(long at, String pkg) {
        return usage(at, "ACTIVITY_PAUSED", pkg);
    }

    public static ObjectNode stopped(long at, String pkg) {
        return usage(at, "ACTIVITY_STOPPED", pkg);
    }

    public static ObjectNode shutdown(long at) {
        return usage(at, "DEVICE_SHUTDOWN");
    }

    public static ObjectNode startup(long at) {
        return usage(at, "DEVICE_STARTUP");
    }

    public static ObjectNode tz(long at, String zone, int offset) {
        ObjectNode n = point("android.tz_change", at);
        n.put("zone", zone);
        n.put("offset_min", offset);
        n.put("reason", "TIMEZONE_CHANGED");
        return n;
    }

    public static ObjectNode shorts(long at, String state, String version) {
        ObjectNode n = point("android.shorts", at);
        n.put("state", state);
        n.put("youtube_version", version);
        n.put("youtube_version_code", 1);
        n.put("rules_version", 1);
        return n;
    }

    public static ObjectNode pause(long at, String pkg, String outcome) {
        ObjectNode n = point("android.pause", at);
        n.put("package", pkg);
        n.put("outcome", outcome);
        n.put("wait_ms", 5000);
        return n;
    }

    public static ObjectNode posted(long at, String pkg, String ref) {
        ObjectNode n = point("android.notification_posted", at);
        n.put("package", pkg);
        n.put("ref", ref);
        n.put("is_update", false);
        n.put("is_group_summary", false);
        n.put("is_ongoing", false);
        return n;
    }

    public static ObjectNode removed(long at, String pkg, String ref, String reason) {
        ObjectNode n = point("android.notification_removed", at);
        n.put("package", pkg);
        n.put("ref", ref);
        n.put("reason", reason);
        return n;
    }

    /** A phone health record with all sensors ok. */
    public static ObjectNode phoneHealth(long at, long watermark) {
        ObjectNode n = point("health", at);
        n.put("client_version", "0.1.0");
        ObjectNode s = n.putObject("sensors");
        for (String name : new String[]{"usage", "notifications", "shorts", "battery_exemption"}) {
            s.putObject(name).put("state", "ok");
        }
        n.put("watermark", watermark);
        n.put("queue_depth", 0);
        n.put("oldest_unsent_age_ms", 0);
        n.put("home_package", "com.google.android.apps.nexuslauncher");
        return n;
    }

    /** A desktop health record with all sensors ok. */
    public static ObjectNode desktopHealth(long at, boolean expectedStop) {
        ObjectNode n = point("health", at);
        n.put("client_version", "0.1.0");
        ObjectNode s = n.putObject("sensors");
        for (String name : new String[]{"activity", "extension_chrome", "extension_brave"}) {
            s.putObject(name).put("state", "ok");
        }
        n.put("queue_depth", 0);
        n.put("oldest_unsent_age_ms", 0);
        if (expectedStop) {
            n.put("expected_stop", true);
        }
        return n;
    }

    public static ObjectNode activity(String id, long start, long end, int rev) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", id);
        n.put("type", "desktop.activity");
        n.put("start", start);
        n.put("end", end);
        n.put("rev", rev);
        n.put("reason", "input");
        return n;
    }

    public static ObjectNode site(String id, long start, long end, int rev, String site, boolean playing) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", id);
        n.put("type", "browser.site");
        n.put("start", start);
        n.put("end", end);
        n.put("rev", rev);
        n.put("site", site);
        n.put("browser", "chrome");
        n.put("playing", playing);
        if ("youtube".equals(site)) {
            n.put("title", "A video title");
        } else {
            n.put("page_kind", "feed");
        }
        return n;
    }
}
