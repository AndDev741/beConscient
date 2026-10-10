package org.beconscient.view;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The owner's local time (docs/design.md, KTD8): each instant takes the phone's
 * zone in effect at that time, from android.tz_change markers and batch headers.
 * Before the first known point the first known zone applies; with no phone data
 * at all, the fallback zone (Europe/Lisbon). Segments stay in UTC; this is applied
 * only when reading, so a late marker changes the local day without re-deriving.
 */
public final class UserTimeline {

    public record Point(long at, ZoneId zone) {
    }

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final long[] ats;
    private final ZoneId[] zones;
    private final ZoneId fallback;

    private UserTimeline(List<Point> points, ZoneId fallback) {
        List<Point> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingLong(Point::at));
        // Consecutive points in the same zone add nothing
        List<Point> compact = new ArrayList<>();
        for (Point p : sorted) {
            if (compact.isEmpty() || !compact.get(compact.size() - 1).zone().equals(p.zone())) {
                compact.add(p);
            }
        }
        this.ats = new long[compact.size()];
        this.zones = new ZoneId[compact.size()];
        for (int i = 0; i < compact.size(); i++) {
            ats[i] = compact.get(i).at();
            zones[i] = compact.get(i).zone();
        }
        this.fallback = fallback;
    }

    public static UserTimeline of(List<Point> points, ZoneId fallback) {
        return new UserTimeline(points, fallback);
    }

    public ZoneId zoneAt(long t) {
        if (ats.length == 0) {
            return fallback;
        }
        int lo = 0;
        int hi = ats.length - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (ats[mid] <= t) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found < 0 ? zones[0] : zones[found];
    }

    public LocalDate dateOf(long t) {
        return Instant.ofEpochMilli(t).atZone(zoneAt(t)).toLocalDate();
    }

    /** The instant a local date begins, in the zone in effect at that instant. */
    public long startOf(LocalDate date) {
        return instantOf(LocalDateTime.of(date, LocalTime.MIDNIGHT));
    }

    public long endOf(LocalDate date) {
        return startOf(date.plusDays(1));
    }

    public long instantOf(LocalDate date, LocalTime time) {
        return instantOf(LocalDateTime.of(date, time));
    }

    /** A local date-time to an instant; the zone is found iteratively since it depends on the instant. */
    public long instantOf(LocalDateTime local) {
        ZoneId zone = zoneAt(local.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli());
        long t = local.atZone(zone).toInstant().toEpochMilli();
        for (int i = 0; i < 3; i++) {
            ZoneId z = zoneAt(t);
            if (z.equals(zone)) {
                break;
            }
            zone = z;
            t = local.atZone(zone).toInstant().toEpochMilli();
        }
        return t;
    }

    public String label(long t) {
        return Instant.ofEpochMilli(t).atZone(zoneAt(t)).format(HH_MM);
    }

    public ZoneId zoneOf(LocalDate date) {
        return zoneAt(startOf(date));
    }

    public LocalDate today(Instant now) {
        return dateOf(now.toEpochMilli());
    }
}
