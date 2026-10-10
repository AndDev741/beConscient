package org.beconscient.validation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.beconscient.api.Dto;
import org.beconscient.common.ApiException;
import org.beconscient.common.AppClock;
import org.beconscient.common.Intervals;
import org.beconscient.common.Intervals.Span;
import org.beconscient.common.Jdbc;
import org.beconscient.health.HealthService;
import org.beconscient.view.DayService;
import org.beconscient.view.Minutes;
import org.beconscient.view.ReadContext;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compares the phone total with what Android Settings shows (KTD18). Measured is
 * the phone's app time clipped to screen-on-and-unlocked; raw is the app
 * intervals before clipping. The 10% flag is recomputed on every read. Only past
 * days the phone has caught up with accept input; days before the phone's usage
 * coverage are skipped.
 */
@ApplicationScoped
public class ValidationService {

    static final double THRESHOLD_PCT = 10.0;
    static final int MAX_DAYS = 366;

    @Inject
    Jdbc jdbc;

    @Inject
    DayService days;

    @Inject
    HealthService health;

    @Inject
    AppClock clock;

    @Transactional
    public List<Dto.ValidationRow> rows(LocalDate from, LocalDate to) {
        ReadContext ctx = days.context();
        LocalDate t = to != null ? to : ctx.today.minusDays(1);
        LocalDate f = from != null ? from : t.minusDays(29);
        if (t.isBefore(f)) {
            throw ApiException.badRequest("from must not be after to.");
        }
        if (ChronoUnit.DAYS.between(f, t) >= MAX_DAYS) {
            throw ApiException.badRequest("At most " + MAX_DAYS + " days at a time.");
        }
        Long coverage = phoneCoverage(ctx);
        Map<LocalDate, Integer> inputs = inputs();
        List<Dto.ValidationRow> out = new ArrayList<>();
        if (coverage == null) {
            return out;
        }
        for (LocalDate d = f; !d.isAfter(t); d = d.plusDays(1)) {
            long s = ctx.timeline.startOf(d);
            if (s < coverage) {
                continue;
            }
            out.add(row(ctx, d, inputs.get(d)));
        }
        return out;
    }

    @Transactional
    public Dto.ValidationRow set(LocalDate date, Integer minutes) {
        if (minutes == null || minutes < 0 || minutes > 1500) {
            throw ApiException.badRequest("minutes must be between 0 and 1500.");
        }
        ReadContext ctx = days.context();
        if (!closed(ctx, date)) {
            throw ApiException.conflict("day_not_closed", "Only past days the phone has caught up with accept a value.");
        }
        jdbc.update("""
                insert into validation_inputs (day, minutes, entered_at) values (?, ?, ?)
                on conflict (day) do update set minutes = excluded.minutes, entered_at = excluded.entered_at""",
                date, minutes, clock.now());
        return row(ctx, date, minutes);
    }

    private boolean closed(ReadContext ctx, LocalDate date) {
        return date.isBefore(ctx.today) && health.closed(ctx, ctx.timeline.endOf(date), true);
    }

    private Dto.ValidationRow row(ReadContext ctx, LocalDate d, Integer settings) {
        long s = ctx.timeline.startOf(d);
        long e = ctx.timeline.endOf(d);
        List<UUID> phones = ctx.devices.stream().filter(ReadContext.DeviceInfo::android).map(ReadContext.DeviceInfo::id).toList();
        List<Span> measured = new ArrayList<>();
        List<Span> raw = new ArrayList<>();
        if (!phones.isEmpty()) {
            UUID[] ids = phones.toArray(new UUID[0]);
            jdbc.list("""
                            select start_ts, end_ts from segments where kind = 'app' and device_id = any(?)
                            and start_ts < ? and end_ts > ?""",
                    rs -> measured.add(new Span(rs.getLong(1), rs.getLong(2))), (Object) ids, e, s);
            jdbc.list("select start_ts, end_ts from raw_intervals where device_id = any(?) and start_ts < ? and end_ts > ?",
                    rs -> raw.add(new Span(rs.getLong(1), rs.getLong(2))), (Object) ids, e, s);
        }
        long measuredMin = Minutes.of(Intervals.totalWithin(Intervals.union(measured), s, e));
        long rawMin = Minutes.of(Intervals.totalWithin(Intervals.union(raw), s, e));
        Double diff = null;
        boolean flagged = false;
        if (settings != null && settings > 0) {
            // Signed: positive when the measured total is above what Settings shows
            double exact = (measuredMin - settings) * 100.0 / settings;
            diff = Math.round(exact * 10.0) / 10.0;
            flagged = Math.abs(exact) > THRESHOLD_PCT;
        }
        return new Dto.ValidationRow(d.toString(), closed(ctx, d), measuredMin, rawMin, settings, diff, flagged);
    }

    private Long phoneCoverage(ReadContext ctx) {
        Long min = null;
        for (ReadContext.DeviceInfo d : ctx.devices) {
            Long c = ctx.coverageStart.get(d.id());
            if (d.android() && c != null) {
                min = min == null ? c : Math.min(min, c);
            }
        }
        return min;
    }

    private Map<LocalDate, Integer> inputs() {
        Map<LocalDate, Integer> out = new HashMap<>();
        jdbc.list("select day, minutes from validation_inputs", rs -> out.put(Jdbc.date(rs, "day"), rs.getInt("minutes")));
        return out;
    }
}
