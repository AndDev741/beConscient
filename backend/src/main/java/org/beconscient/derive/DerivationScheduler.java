package org.beconscient.derive;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Runs derivation in the background. Each job skips a tick while its previous run
 * is still going, so derivation never overlaps itself; the deployment uses the
 * Recreate strategy so two pods never derive at once.
 */
@ApplicationScoped
public class DerivationScheduler {

    private static final Logger LOG = Logger.getLogger(DerivationScheduler.class);

    @Inject
    DerivationService derivation;

    @Inject
    RebuildJob rebuild;

    @ConfigProperty(name = "beconscient.rebuild.blocks-per-step", defaultValue = "20")
    int blocksPerStep;

    /** Writes down a pending version rebuild once the service is up; the steps run below. */
    void onStart(@Observes @Priority(100) StartupEvent event) {
        try {
            rebuild.startIfNeeded();
        } catch (RuntimeException e) {
            LOG.error("Could not check the derivation version", e);
        }
    }

    @Scheduled(every = "${beconscient.derivation.every}", delayed = "5s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void derive() {
        derivation.runUntilClean(10);
    }

    @Scheduled(every = "${beconscient.rebuild.every}", delayed = "3s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void rebuildStep() {
        rebuild.startIfNeeded();
        rebuild.step(blocksPerStep);
    }
}
