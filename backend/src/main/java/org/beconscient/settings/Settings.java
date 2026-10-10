package org.beconscient.settings;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A read-only snapshot of the versioned windows and the non-workdays. Each local
 * date uses the latest version valid on it; dates before the first version use
 * the first one.
 */
public final class Settings {

    public record Work(Set<Integer> days, LocalTime start, LocalTime end, LocalDate validFrom) {
    }

    public record Awake(LocalTime start, LocalTime end, LocalDate validFrom) {

        /** An awake window may cross midnight; the tail belongs to the day it started. */
        public boolean crossesMidnight() {
            return !end.isAfter(start);
        }
    }

    private final List<Work> work;
    private final List<Awake> awake;
    private final Set<LocalDate> nonWorkdays;

    public Settings(List<Work> work, List<Awake> awake, Set<LocalDate> nonWorkdays) {
        this.work = new ArrayList<>(work);
        this.work.sort(Comparator.comparing(Work::validFrom));
        this.awake = new ArrayList<>(awake);
        this.awake.sort(Comparator.comparing(Awake::validFrom));
        this.nonWorkdays = new TreeSet<>(nonWorkdays);
    }

    static Settings of(List<WindowSetting> rows, List<LocalDate> nonWorkdays) {
        List<Work> w = new ArrayList<>();
        List<Awake> a = new ArrayList<>();
        for (WindowSetting r : rows) {
            if (WindowSetting.WORK.equals(r.kind)) {
                w.add(new Work(parseDays(r.days), LocalTime.parse(r.startTime), LocalTime.parse(r.endTime), r.validFrom));
            } else {
                a.add(new Awake(LocalTime.parse(r.startTime), LocalTime.parse(r.endTime), r.validFrom));
            }
        }
        if (w.isEmpty()) {
            w.add(new Work(Set.of(1, 2, 3, 4, 5), LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.of(2000, 1, 1)));
        }
        if (a.isEmpty()) {
            a.add(new Awake(LocalTime.of(7, 0), LocalTime.of(23, 0), LocalDate.of(2000, 1, 1)));
        }
        return new Settings(w, a, new TreeSet<>(nonWorkdays));
    }

    static Set<Integer> parseDays(String days) {
        if (days == null || days.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(days.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::valueOf)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public Work workFor(LocalDate date) {
        Work found = work.get(0);
        for (Work w : work) {
            if (!w.validFrom().isAfter(date)) {
                found = w;
            }
        }
        return found;
    }

    public Awake awakeFor(LocalDate date) {
        Awake found = awake.get(0);
        for (Awake a : awake) {
            if (!a.validFrom().isAfter(date)) {
                found = a;
            }
        }
        return found;
    }

    public boolean isWorkday(LocalDate date) {
        return !nonWorkdays.contains(date) && workFor(date).days().contains(date.getDayOfWeek().getValue());
    }

    public Set<LocalDate> nonWorkdays() {
        return nonWorkdays;
    }

    public List<Work> workVersions() {
        return work;
    }

    public List<Awake> awakeVersions() {
        return awake;
    }
}
