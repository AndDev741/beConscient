package org.beconscient.view;

import org.beconscient.category.Catalog;
import org.beconscient.label.ComputerLabel;
import org.beconscient.settings.Settings;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Everything one read needs, loaded once: timeline, settings, categories, labels, devices and coverage. */
public final class ReadContext {

    public record Inst(UUID id, Instant createdAt, Instant revokedAt) {
        public boolean active() {
            return revokedAt == null;
        }
    }

    public record DeviceInfo(UUID id, String name, String kind, Instant createdAt, List<Inst> installations) {
        public boolean android() {
            return "android".equals(kind);
        }

        /** Has at least one installation that is not revoked. */
        public boolean active() {
            return installations.stream().anyMatch(Inst::active);
        }

        public boolean paired() {
            return !installations.isEmpty();
        }

        public String primarySensor() {
            return android() ? "usage" : "activity";
        }
    }

    public final Instant now;
    public final UserTimeline timeline;
    public final Settings settings;
    public final Catalog catalog;
    /** Computer time marked by hand, by start; never overlapping. */
    public final List<ComputerLabel> labels;
    /** Phones first, then computers; overlapping time belongs to the first. */
    public final List<DeviceInfo> devices;
    /** Earliest fact of each device's time sensor (usage or activity). */
    public final Map<UUID, Long> coverageStart;
    public final LocalDate today;
    final Map<String, Object> cache = new HashMap<>();

    public ReadContext(Instant now, UserTimeline timeline, Settings settings, Catalog catalog, List<ComputerLabel> labels,
                       List<DeviceInfo> devices, Map<UUID, Long> coverageStart) {
        this.now = now;
        this.timeline = timeline;
        this.settings = settings;
        this.catalog = catalog;
        this.labels = labels;
        this.devices = devices;
        this.coverageStart = coverageStart;
        this.today = timeline.today(now);
    }

    public long nowMs() {
        return now.toEpochMilli();
    }

    /** Per-read memo, so repeated questions about the same installation hit the database once. */
    public Object cacheGet(String key, java.util.function.Supplier<Object> load) {
        return cache.computeIfAbsent(key, k -> load.get());
    }

    public DeviceInfo device(UUID id) {
        return devices.stream().filter(d -> d.id().equals(id)).findFirst().orElse(null);
    }
}
