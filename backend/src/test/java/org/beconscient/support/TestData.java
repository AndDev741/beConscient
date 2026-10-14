package org.beconscient.support;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beconscient.auth.GlobalRateLimiter;
import org.beconscient.common.AppClock;
import org.beconscient.common.Jdbc;
import org.beconscient.derive.DerivationService;
import org.beconscient.device.DeviceService;
import org.beconscient.api.Dto;

import java.time.Instant;

/** Puts the shared test database back to a known state between tests. */
@ApplicationScoped
public class TestData {

    @Inject
    Jdbc jdbc;

    @Inject
    AppClock clock;

    @Inject
    GlobalRateLimiter limiter;

    @Inject
    DeviceService devices;

    @Inject
    DerivationService derivation;

    public void reset() {
        clock.set(null);
        limiter.reset();
        QuarkusTransaction.requiringNew().run(() -> {
            jdbc.update("""
                    truncate raw_events, quarantine, interval_conflicts, device_counters, dirty_blocks, sensor_coverage,
                      zone_points, batch_log, installations, pairing_codes, blocks, segments, raw_intervals, unlocks,
                      rebuild_blocks, subject_mappings, non_workdays, validation_inputs, redactions, exclusions,
                      computer_labels, devices
                    restart identity cascade""");
            jdbc.update("update pairing_state set consecutive_failures = 0, locked_out = false, locked_out_at = null");
            jdbc.update("""
                    update derivation_state set version = ?, rebuilding = false, target_version = null, total_blocks = 0,
                      done_blocks = 0 where id = 1""", DerivationService.VERSION);
            jdbc.update("delete from window_settings where valid_from <> '2000-01-01'");
            jdbc.update("update window_settings set days = '1,2,3,4,5', start_time = '09:00', end_time = '18:00' where kind = 'work'");
            jdbc.update("update window_settings set days = null, start_time = '07:00', end_time = '23:00' where kind = 'awake'");
            jdbc.update("delete from categories where id::text not like '00000000-0000-0000-0000-0000000000%'");
            jdbc.update("drop trigger if exists fail_batch on batch_log");
        });
    }

    public void at(String isoInstant) {
        clock.set(Instant.parse(isoInstant));
    }

    public Dto.CreatedDevice device(String name, String kind) {
        return devices.create(new Dto.NewDeviceRequest(name, kind, true));
    }

    public Jdbc jdbc() {
        return jdbc;
    }

    public long count(String sql, Object... params) {
        return QuarkusTransaction.requiringNew().call(() -> jdbc.count(sql, params));
    }

    public int update(String sql, Object... params) {
        return QuarkusTransaction.requiringNew().call(() -> jdbc.update(sql, params));
    }

    public void derive() {
        derivation.runUntilClean(20);
    }
}
