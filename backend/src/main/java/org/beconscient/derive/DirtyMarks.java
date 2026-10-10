package org.beconscient.derive;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beconscient.common.Jdbc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/** Dirty marks are rows with ids; whoever writes events marks the UTC days they touch. */
@ApplicationScoped
public class DirtyMarks {

    public static final long DAY_MS = 86_400_000L;

    @Inject
    Jdbc jdbc;

    public void mark(UUID deviceId, Collection<LocalDate> days) {
        List<Object[]> rows = new ArrayList<>();
        for (LocalDate d : new TreeSet<>(days)) {
            rows.add(new Object[]{deviceId, d});
        }
        jdbc.batch("insert into dirty_blocks (device_id, day) values (?, ?)", rows);
    }

    public static LocalDate utcDay(long ms) {
        return Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toLocalDate();
    }

    public static long dayStart(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** Every UTC day an interval [start, end) touches; a point touches its own day. */
    public static SortedSet<LocalDate> daysCovering(long start, long end) {
        SortedSet<LocalDate> out = new TreeSet<>();
        LocalDate first = utcDay(start);
        LocalDate last = utcDay(Math.max(start, end - 1));
        // Bounded: suspect events (far past or future) never reach here
        for (LocalDate d = first; !d.isAfter(last) && out.size() < 400; d = d.plusDays(1)) {
            out.add(d);
        }
        return out;
    }
}
