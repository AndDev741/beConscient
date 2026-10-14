package org.beconscient.settings;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.beconscient.api.Dto;
import org.beconscient.common.ApiException;
import org.beconscient.common.AppClock;
import org.beconscient.view.Timelines;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Work and awake windows are versioned by the local date they became valid. An
 * edit creates a version valid from today; a second edit the same day replaces
 * that day's version; past days keep their own.
 */
@ApplicationScoped
public class SettingsService {

    private static final Pattern HH_MM = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    @Inject
    AppClock clock;

    @Inject
    Timelines timelines;

    @Transactional
    public Settings snapshot() {
        List<WindowSetting> rows = WindowSetting.listAll();
        List<LocalDate> days = NonWorkday.<NonWorkday>listAll().stream().map(n -> n.day).toList();
        return Settings.of(rows, days);
    }

    LocalDate today() {
        return timelines.load().today(clock.now());
    }

    @Transactional
    public Dto.SettingsView view() {
        Settings s = snapshot();
        LocalDate today = today();
        Settings.Work w = s.workFor(today);
        Settings.Awake a = s.awakeFor(today);
        List<Dto.HistoryEntry> history = new ArrayList<>();
        for (Settings.Work v : s.workVersions()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("days", new ArrayList<>(v.days()));
            value.put("start", v.start().toString());
            value.put("end", v.end().toString());
            history.add(new Dto.HistoryEntry(WindowSetting.WORK, value, v.validFrom().toString()));
        }
        for (Settings.Awake v : s.awakeVersions()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("start", v.start().toString());
            value.put("end", v.end().toString());
            history.add(new Dto.HistoryEntry(WindowSetting.AWAKE, value, v.validFrom().toString()));
        }
        history.sort(Comparator.comparing(Dto.HistoryEntry::validFrom).reversed().thenComparing(Dto.HistoryEntry::kind));
        return new Dto.SettingsView(
                new Dto.WorkWindow(new ArrayList<>(w.days()), w.start().toString(), w.end().toString(), w.validFrom().toString()),
                new Dto.AwakeWindow(a.start().toString(), a.end().toString(), a.validFrom().toString()),
                history,
                s.nonWorkdays().stream().map(LocalDate::toString).toList());
    }

    @Transactional
    public Dto.SettingsView setWork(Dto.WorkWindowRequest req) {
        if (req == null || req.days() == null) {
            throw ApiException.badRequest("days, start and end are required.");
        }
        TreeSet<Integer> days = new TreeSet<>(req.days());
        if (days.stream().anyMatch(d -> d == null || d < 1 || d > 7)) {
            throw ApiException.badRequest("days use ISO numbering, 1 = Monday to 7 = Sunday.");
        }
        String start = time(req.start());
        String end = time(req.end());
        if (start.equals(end)) {
            throw ApiException.badRequest("The work window needs a start and an end that differ.");
        }
        save(WindowSetting.WORK, days.stream().map(String::valueOf).collect(Collectors.joining(",")), start, end);
        return view();
    }

    @Transactional
    public Dto.SettingsView setAwake(Dto.AwakeWindowRequest req) {
        if (req == null) {
            throw ApiException.badRequest("start and end are required.");
        }
        String start = time(req.start());
        String end = time(req.end());
        if (start.equals(end)) {
            throw ApiException.badRequest("The awake window needs a start and an end that differ.");
        }
        save(WindowSetting.AWAKE, null, start, end);
        return view();
    }

    /** Creates the version valid from today, or replaces today's version. */
    private void save(String kind, String days, String start, String end) {
        LocalDate today = today();
        WindowSetting row = WindowSetting.find("kind = ?1 and validFrom = ?2", kind, today).firstResult();
        if (row == null) {
            row = new WindowSetting();
            row.kind = kind;
            row.validFrom = today;
        }
        row.days = days;
        row.startTime = start;
        row.endTime = end;
        row.updatedAt = clock.now();
        row.persist();
    }

    @Transactional
    public Dto.SettingsView addNonWorkday(Dto.NonWorkdayRequest req) {
        LocalDate day = date(req == null ? null : req.date());
        if (NonWorkday.findById(day) == null) {
            NonWorkday n = new NonWorkday();
            n.day = day;
            n.persist();
        }
        return view();
    }

    @Transactional
    public Dto.SettingsView removeNonWorkday(String date) {
        NonWorkday.deleteById(date(date));
        return view();
    }

    public static String time(String s) {
        if (s == null || !HH_MM.matcher(s).matches()) {
            throw ApiException.badRequest("Times are HH:MM.");
        }
        return LocalTime.parse(s).toString();
    }

    public static LocalDate date(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest("Dates are YYYY-MM-DD.");
        }
    }
}
