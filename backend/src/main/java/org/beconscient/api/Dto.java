package org.beconscient.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Request and response shapes of the page API (contract/api.md). Field names
 * follow the contract exactly. Kept together: they are one contract. Instants are
 * epoch milliseconds; dates are local dates in the user timeline.
 */
public final class Dto {

    private Dto() {
    }

    // ------------------------------------------------------------- session

    public record Session(String username) {
    }

    // ------------------------------------------------------------- day

    public record Derivation(boolean rebuilding, double progress) {
    }

    public record Headline(long awakeMinutes, long awayMinutes, long screenMinutes, long outsideAwakeScreenMinutes,
                           boolean elapsedOnly, boolean incomplete, List<String> silentSensors) {
    }

    public record DeviceMinutes(String deviceId, String name, String kind, long minutes) {
    }

    public record Totals(long screenMinutes, long overlapMinutes, List<DeviceMinutes> byDevice) {
    }

    public record CategoryMinutes(String categoryId, String name, String color, long minutes) {
    }

    public record HourCategory(String categoryId, long minutes) {
    }

    /** Not in api.md: the segments behind an hour, so the page can open a trace from the chart. */
    public record SegmentRef(String id, String label, long minutes) {
    }

    public record Hour(String label, long start, List<HourCategory> byCategory, List<SegmentRef> segments) {
    }

    public record SubjectMinutes(String subjectKey, String label, String categoryId, long minutes) {
    }

    public record FirstApp(String subjectKey, String label, long count) {
    }

    public record Unlocks(long count, List<FirstApp> firstApps) {
    }

    public record NotificationCount(String subjectKey, String label, long posted, long tapped, long dismissed) {
    }

    /** A stretch marked by hand that touches the day; {@code minutes} is the computer time it took on that day. */
    public record DayLabel(String id, String categoryId, long from, long to, long minutes) {
    }

    public record DayView(String date, String zone, String state, Derivation derivation, Headline headline,
                          Totals totals, List<CategoryMinutes> byCategory, List<Hour> hours,
                          List<SubjectMinutes> topSubjects, Unlocks unlocks, List<NotificationCount> notifications,
                          List<DayLabel> computerLabels) {
    }

    // ------------------------------------------------------------- trends

    public record Span(String from, String to, long screenMinutes, long awayMinutes, List<CategoryMinutes> byCategory) {
    }

    public record SeriesPoint(String date, long screenMinutes, long awayMinutes, boolean complete) {
    }

    public record Trends(String period, Span current, Span previous, List<SeriesPoint> series) {
    }

    // ------------------------------------------------------------- categories and subjects

    public record CategoryView(String id, String name, String color, boolean builtin) {
    }

    public record CategoryRequest(String name, String color) {
    }

    public record SubjectView(String key, String kind, String label, String categoryId, long minutes30d) {
    }

    public record SubjectCategoryRequest(String categoryId) {
    }

    // ------------------------------------------------------------- devices

    public record InstallationView(String id, String fingerprint, long createdAt, Long lastSeenAt, Long revokedAt,
                                   boolean cloneSuspected) {
    }

    public record DeviceView(String id, String name, String kind, List<InstallationView> installations) {
    }

    public record NewDeviceRequest(String name, String kind, Boolean confirmSecondPhone) {
    }

    public record CreatedDevice(DeviceView device, String code, long expiresAt) {
    }

    public record PairingCodeView(String code, long expiresAt) {
    }

    public record PairingStateView(boolean lockedOut, int failures) {
    }

    // ------------------------------------------------------------- settings

    public record WorkWindow(List<Integer> days, String start, String end, String validFrom) {
    }

    public record AwakeWindow(String start, String end, String validFrom) {
    }

    public record HistoryEntry(String kind, Map<String, Object> value, String validFrom) {
    }

    public record SettingsView(WorkWindow workWindow, AwakeWindow awakeWindow, List<HistoryEntry> history,
                               List<String> nonWorkdays) {
    }

    public record WorkWindowRequest(List<Integer> days, String start, String end) {
    }

    public record AwakeWindowRequest(String start, String end) {
    }

    public record NonWorkdayRequest(String date) {
    }

    // ------------------------------------------------------------- backstage

    public record SensorStatus(String name, String state, Long lastContact, String detail) {
    }

    public record DayCount(String date, long count) {
    }

    public record Lag(Long p50, Long p95) {
    }

    public record GapView(String sensor, long from, long to, boolean expected) {
    }

    /** {@code unconfirmedSince} (epoch ms) is an addition to api.md: when the unconfirmed version first appeared. */
    public record ShortsStatus(String unconfirmedSinceVersion, String lastConfirmedVersion, Long unconfirmedSince) {
    }

    public record DeviceHealth(String id, String name, String kind, List<SensorStatus> sensors,
                               List<DayCount> eventsPerDay, Lag lagMs, long duplicates, long rejected,
                               long droppedFields, long intervalConflicts, long invariantViolations,
                               Long clockSkewMs, List<GapView> gaps, ShortsStatus shorts) {
    }

    /** {@code alert} is an addition to api.md: true above 70% of the budget. */
    public record Database(long sizeBytes, long budgetBytes, boolean alert) {
    }

    public record DerivationInfo(int version, boolean rebuilding, double progress) {
    }

    public record Backstage(List<DeviceHealth> devices, Database database, DerivationInfo derivation,
                            List<String> blindSpots) {
    }

    public record TraceSegment(String id, String deviceId, String subjectKey, String label, String categoryId,
                               long start, long end, int derivationVersion) {
    }

    public record Trace(TraceSegment segment, List<JsonNode> events, List<String> rules) {
    }

    // ------------------------------------------------------------- validation

    public record ValidationRow(String date, boolean closed, Long measuredMinutes, Long rawMinutes,
                                Integer settingsMinutes, Double diffPct, boolean flagged) {
    }

    public record ValidationRequest(Integer minutes) {
    }

    // ------------------------------------------------------------- corrections

    public record RedactionView(String id, String deviceId, String type, long from, long to, String field,
                                int affected, long createdAt) {
    }

    public record ExclusionView(String id, String deviceId, String type, long from, long to, String note,
                                long createdAt) {
    }

    public record Corrections(List<RedactionView> redactions, List<ExclusionView> exclusions) {
    }

    // ------------------------------------------------------------- computer labels

    public record ComputerLabelRequest(String date, String start, String end, String categoryId) {
    }

    public record ComputerLabelView(String id, String categoryId, long from, long to) {
    }
}
