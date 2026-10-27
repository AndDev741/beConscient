package org.beconscient.view;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beconscient.api.Dto;
import org.beconscient.common.Jdbc;
import org.beconscient.ingest.ContractFilter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's pause screens and focus periods (docs/design.md, "Launcher"),
 * counted at read time from raw events: they never become segments. Each event
 * belongs to the local day of its {@code at}, so a period that runs past midnight
 * counts, minutes and all, on the day it started. Suspect events and exclusions
 * are left out, as for notifications.
 */
@ApplicationScoped
public class LauncherService {

    /** Days in the focus series, the shown day included. */
    static final int RECENT_DAYS = 7;

    @Inject
    Jdbc jdbc;

    record Row(String type, long ts, JsonNode p) {
    }

    public Dto.Launcher day(ReadContext ctx, LocalDate date) {
        UserTimeline tl = ctx.timeline;
        LocalDate first = date.minusDays(RECENT_DAYS - 1);
        List<Row> rows = jdbc.list("""
                        select e.type, e.ts, e.payload from raw_events e
                        where e.type in ('android.pause', 'android.focus') and e.ts >= ? and e.ts < ? and not e.suspect
                          and not exists (select 1 from exclusions x where x.device_id = e.device_id
                                          and (x.type is null or x.type = e.type) and e.ts between x.from_ts and x.to_ts)""",
                rs -> new Row(rs.getString("type"), rs.getLong("ts"), DayService.json(rs.getString("payload"))),
                tl.startOf(first), tl.endOf(date));
        List<Dto.FocusPoint> recent = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(date); d = d.plusDays(1)) {
            Dto.FocusPeriods f = focus(within(rows, tl.startOf(d), tl.endOf(d)));
            recent.add(new Dto.FocusPoint(d.toString(), f.periods(), f.minutes()));
        }
        List<Row> shown = within(rows, tl.startOf(date), tl.endOf(date));
        return new Dto.Launcher(focus(shown), pauses(shown), recent);
    }

    private static List<Row> within(List<Row> rows, long from, long to) {
        return rows.stream().filter(r -> r.ts() >= from && r.ts() < to).toList();
    }

    /**
     * Rests count only under {@code byKind}: periods and minutes are the focus
     * rounds, as the phone's home screen counts them. A period without
     * {@code ended_at} still counts, with no minutes.
     */
    static Dto.FocusPeriods focus(List<Row> rows) {
        long periods = 0;
        long manual = 0;
        long scheduled = 0;
        long rest = 0;
        long ms = 0;
        long blocked = 0;
        long held = 0;
        for (Row r : rows) {
            if (!ContractFilter.FOCUS.equals(r.type())) {
                continue;
            }
            switch (r.p().path("kind").asText("")) {
                case "manual" -> manual++;
                case "scheduled" -> scheduled++;
                case "rest" -> rest++;
                default -> {
                }
            }
            if ("rest".equals(r.p().path("kind").asText(""))) {
                continue;
            }
            periods++;
            ms += Math.max(0, r.p().path("ended_at").asLong(r.ts()) - r.ts());
            blocked += Math.max(0, r.p().path("blocked_opens").asLong(0));
            held += Math.max(0, r.p().path("held_notifications").asLong(0));
        }
        return new Dto.FocusPeriods(periods, new Dto.FocusKinds(manual, scheduled, rest), Minutes.of(ms), blocked, held);
    }

    /** The phone sends an intent only with {@code opened}; an opening without one has no reason given. */
    static Dto.Pauses pauses(List<Row> rows) {
        long shown = 0;
        long opened = 0;
        long backedOut = 0;
        long alternative = 0;
        long rest = 0;
        long reply = 0;
        long specific = 0;
        long habit = 0;
        for (Row r : rows) {
            if (!ContractFilter.PAUSE.equals(r.type())) {
                continue;
            }
            shown++;
            switch (r.p().path("outcome").asText("")) {
                case "opened" -> opened++;
                case "backed_out" -> backedOut++;
                case "alternative" -> alternative++;
                case "rest" -> rest++;
                default -> {
                }
            }
            switch (r.p().path("intent").asText("")) {
                case "reply" -> reply++;
                case "specific" -> specific++;
                case "habit" -> habit++;
                default -> {
                }
            }
        }
        return new Dto.Pauses(shown, new Dto.PauseOutcomes(opened, backedOut, alternative, rest),
                new Dto.PauseIntents(reply, specific, habit));
    }
}
