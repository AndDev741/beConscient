package org.beconscient.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beconscient.view.ReadContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A browser the owner does not use must not show up as a late sensor forever. */
class ExtensionSensorTest {

    static final long NOW = 1_760_090_000_000L;
    final ObjectMapper json = new ObjectMapper();

    List<String> states(String sensorsJson) throws Exception {
        var device = new ReadContext.DeviceInfo(UUID.randomUUID(), "Laptop", "desktop", Instant.ofEpochMilli(NOW), List.of());
        var record = new HealthService.Record(1, NOW - 60_000, false, 0L, null, json.readTree(sensorsJson));
        return BackstageService.sensors(device, record, NOW).stream().map(s -> s.name() + "=" + s.state()).toList();
    }

    @Test
    void extensionThatNeverSentAHeartbeatIsLeftOut() throws Exception {
        assertEquals(List.of("activity=active", "extension_chrome=active"), states("""
                { "activity": { "state": "ok" },
                  "extension_chrome": { "state": "ok", "last_event_at": 1760089900000 },
                  "extension_brave": { "state": "unknown", "detail": "no heartbeat yet" } }"""));
    }

    @Test
    void extensionThatWentQuietAfterReportingStaysVisible() throws Exception {
        assertEquals(List.of("activity=active", "extension_chrome=late"), states("""
                { "activity": { "state": "ok" },
                  "extension_chrome": { "state": "unknown", "last_event_at": 1760080000000 } }"""));
    }
}
