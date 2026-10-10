package org.beconscient.derive;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beconscient.common.AppClock;
import org.beconscient.common.Jdbc;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The background rebuild after a derivation version bump. The blocks to derive
 * are written down once (rebuild_blocks) and ticked off one transaction at a
 * time, per device in day order, so a restart resumes where it stopped and the
 * result equals a derivation from scratch.
 */
@ApplicationScoped
public class RebuildJob {

    private static final Logger LOG = Logger.getLogger(RebuildJob.class);

    @Inject
    Jdbc jdbc;

    @Inject
    DerivationService derivation;

    @Inject
    AppClock clock;

    private record Item(UUID deviceId, LocalDate day) {
    }

    /** Starts a rebuild when the stored version is older than the code. Idempotent. */
    public void startIfNeeded() {
        QuarkusTransaction.requiringNew().run(() -> {
            DerivationService.State s = jdbc.one(
                    "select version, rebuilding, target_version, total_blocks, done_blocks from derivation_state where id = 1 for update",
                    rs -> new DerivationService.State(rs.getInt("version"), rs.getBoolean("rebuilding"),
                            Jdbc.nullableInt(rs, "target_version"), rs.getInt("total_blocks"), rs.getInt("done_blocks")))
                    .orElseThrow();
            if (s.rebuilding() || s.version() >= DerivationService.VERSION) {
                return;
            }
            jdbc.update("delete from rebuild_blocks");
            jdbc.update("""
                    insert into rebuild_blocks (device_id, day)
                    select distinct r.device_id, d::date
                    from raw_events r,
                         generate_series(
                           (to_timestamp(r.ts / 1000.0) at time zone 'UTC')::date,
                           (to_timestamp(greatest(r.ts, coalesce(r.end_ts, r.ts) - 1) / 1000.0) at time zone 'UTC')::date,
                           interval '1 day') d
                    where not r.suspect and r.type in ('android.usage', 'android.shorts', 'health', 'desktop.activity', 'browser.site')
                    union
                    select device_id, day from blocks
                    on conflict do nothing""");
            long total = jdbc.count("select count(*) from rebuild_blocks");
            if (total == 0) {
                jdbc.update("""
                        update derivation_state set version = ?, rebuilding = false, target_version = null,
                          total_blocks = 0, done_blocks = 0, finished_at = ? where id = 1""",
                        DerivationService.VERSION, clock.now());
                return;
            }
            jdbc.update("""
                    update derivation_state set rebuilding = true, target_version = ?, total_blocks = ?, done_blocks = 0,
                      started_at = ?, finished_at = null where id = 1""",
                    DerivationService.VERSION, (int) total, clock.now());
            LOG.infof("Derivation version %d: rebuilding %d blocks", DerivationService.VERSION, total);
        });
    }

    /** Derives up to {@code max} blocks of a running rebuild; finishes it when nothing is left. */
    public int step(int max) {
        DerivationService.State s = derivation.state();
        if (!s.rebuilding() || s.targetVersion() == null || s.targetVersion() != DerivationService.VERSION) {
            return 0;
        }
        List<Item> items = jdbc.list("select device_id, day from rebuild_blocks where not done order by device_id, day limit ?",
                rs -> new Item(Jdbc.uuid(rs, "device_id"), Jdbc.date(rs, "day")), max);
        int done = 0;
        for (Item it : items) {
            QuarkusTransaction.requiringNew().run(() -> {
                derivation.rebuildBlock(it.deviceId(), it.day());
                jdbc.update("update rebuild_blocks set done = true where device_id = ? and day = ?", it.deviceId(), it.day());
                jdbc.update("update derivation_state set done_blocks = done_blocks + 1 where id = 1");
            });
            done++;
        }
        if (items.size() < max) {
            finish();
        }
        return done;
    }

    public void runToEnd() {
        startIfNeeded();
        while (step(100) == 100) {
            // keep going
        }
    }

    private void finish() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (jdbc.count("select count(*) from rebuild_blocks where not done") > 0) {
                return;
            }
            int target = DerivationService.VERSION;
            jdbc.update("delete from segments where version < ?", target);
            jdbc.update("delete from unlocks where version < ?", target);
            jdbc.update("delete from raw_intervals where version < ?", target);
            jdbc.update("delete from rebuild_blocks");
            jdbc.update("""
                    update derivation_state set version = ?, rebuilding = false, target_version = null,
                      done_blocks = total_blocks, finished_at = ? where id = 1""", target, clock.now());
            LOG.infof("Derivation rebuild to version %d finished", target);
        });
    }
}
