package org.beconscient.auth;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;

/**
 * One limiter for login and pairing together. There is a single owner and the
 * client address cannot be trusted behind the proxy, so the limiter is global:
 * after a few free failures each attempt must wait an exponentially growing
 * delay, capped at 30 seconds. A success resets it.
 *
 * An attempt reserves the next slot before it runs, so parallel guesses cannot
 * slip through the same window.
 */
@ApplicationScoped
public class GlobalRateLimiter {

    @ConfigProperty(name = "beconscient.rate-limit.free-failures", defaultValue = "3")
    int freeFailures;

    @ConfigProperty(name = "beconscient.rate-limit.base-delay", defaultValue = "PT0.5S")
    Duration baseDelay;

    @ConfigProperty(name = "beconscient.rate-limit.max-delay", defaultValue = "PT30S")
    Duration maxDelay;

    private int failures;
    private Instant nextAllowed = Instant.EPOCH;

    /** True when an attempt may run now; it then holds the slot until it reports back. */
    public synchronized boolean tryAcquire() {
        Instant now = Instant.now();
        if (now.isBefore(nextAllowed)) {
            return false;
        }
        if (failures >= freeFailures) {
            nextAllowed = now.plus(delayFor(failures));
        }
        return true;
    }

    public synchronized void failure() {
        failures++;
        if (failures >= freeFailures) {
            Instant candidate = Instant.now().plus(delayFor(failures));
            if (candidate.isAfter(nextAllowed)) {
                nextAllowed = candidate;
            }
        }
    }

    public synchronized void success() {
        failures = 0;
        nextAllowed = Instant.EPOCH;
    }

    public synchronized int failures() {
        return failures;
    }

    /** How long until the next attempt may run, for a Retry-After header. */
    public synchronized Duration retryAfter() {
        Duration d = Duration.between(Instant.now(), nextAllowed);
        return d.isNegative() ? Duration.ZERO : d;
    }

    /** Test hook. */
    public synchronized void reset() {
        success();
    }

    Duration delayFor(int failureCount) {
        int exponent = Math.min(30, Math.max(0, failureCount - freeFailures));
        Duration d = baseDelay.multipliedBy(1L << exponent);
        return d.compareTo(maxDelay) > 0 ? maxDelay : d;
    }
}
