package org.beconscient.view;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.beconscient.api.Dto;
import org.beconscient.common.ApiException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Day, week (Monday first) and month trends. A period in progress is compared
 * with the same elapsed span of the previous one. Days that are not closed,
 * partial or incomplete are left out of both sides (pairwise, so the comparison
 * stays like for like) and flagged in the series. {@code previous} is null when
 * the previous span is not entirely made of closed, covered days.
 */
@ApplicationScoped
public class TrendsService {

    @Inject
    DayService days;

    @Transactional
    public Dto.Trends trends(String period, LocalDate anchor) {
        ReadContext ctx = days.context();
        LocalDate a = anchor != null ? anchor : ctx.today;
        LocalDate pStart;
        LocalDate pEnd;
        LocalDate prevStart;
        LocalDate prevEnd;
        switch (period == null ? "week" : period) {
            case "day" -> {
                pStart = a;
                pEnd = a;
                prevStart = a.minusDays(1);
                prevEnd = prevStart;
            }
            case "week" -> {
                pStart = a.with(DayOfWeek.MONDAY);
                pEnd = pStart.plusDays(6);
                prevStart = pStart.minusWeeks(1);
                prevEnd = prevStart.plusDays(6);
            }
            case "month" -> {
                pStart = a.withDayOfMonth(1);
                pEnd = a.withDayOfMonth(a.lengthOfMonth());
                prevStart = pStart.minusMonths(1);
                prevEnd = prevStart.withDayOfMonth(prevStart.lengthOfMonth());
            }
            default -> throw ApiException.badRequest("period must be day, week or month.");
        }
        LocalDate spanEnd = pEnd.isAfter(ctx.today) ? ctx.today : pEnd;
        int length = spanEnd.isBefore(pStart) ? 0 : (int) ChronoUnit.DAYS.between(pStart, spanEnd) + 1;
        int prevLength = Math.min(length, (int) ChronoUnit.DAYS.between(prevStart, prevEnd) + 1);

        // One read of the segments for both spans, with a day of margin for awake windows
        LocalDate first = length == 0 ? pStart : prevStart;
        long from = ctx.timeline.startOf(first.minusDays(1));
        long to = ctx.timeline.startOf((length == 0 ? pStart : spanEnd).plusDays(2));
        List<DayService.Seg> segs = days.segments(from, to);

        List<DayService.Summary> cur = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            cur.add(days.summary(ctx, pStart.plusDays(i), segs));
        }
        List<DayService.Summary> prev = new ArrayList<>();
        for (int i = 0; i < prevLength; i++) {
            prev.add(days.summary(ctx, prevStart.plusDays(i), segs));
        }

        boolean previousExists = prevLength > 0 && prev.stream().allMatch(s -> s.closed() && !s.partial());
        List<DayService.Summary> curIn = new ArrayList<>();
        List<DayService.Summary> prevIn = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            DayService.Summary c = cur.get(i);
            if (!c.complete()) {
                continue;
            }
            if (previousExists) {
                if (i >= prevLength || !prev.get(i).complete()) {
                    continue;
                }
                prevIn.add(prev.get(i));
            }
            curIn.add(c);
        }

        List<Dto.SeriesPoint> series = new ArrayList<>();
        for (DayService.Summary s : cur) {
            series.add(new Dto.SeriesPoint(s.date().toString(), s.screenMinutes(), s.awayMinutes(), s.complete()));
        }
        Dto.Span current = span(ctx, pStart, length == 0 ? pStart : spanEnd, curIn);
        Dto.Span previous = previousExists ? span(ctx, prevStart, prevStart.plusDays(prevLength - 1), prevIn) : null;
        return new Dto.Trends(period == null ? "week" : period, current, previous, series);
    }

    private Dto.Span span(ReadContext ctx, LocalDate from, LocalDate to, List<DayService.Summary> included) {
        long screen = 0;
        long away = 0;
        Map<UUID, Long> ms = new LinkedHashMap<>();
        for (DayService.Summary s : included) {
            screen += s.screenMinutes();
            away += s.awayMinutes();
            s.categoryMs().forEach((k, v) -> ms.merge(k, v, Long::sum));
        }
        return new Dto.Span(from.toString(), to.toString(), screen, away, days.categoryMinutes(ctx, ms));
    }
}
