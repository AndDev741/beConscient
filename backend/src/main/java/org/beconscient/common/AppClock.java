package org.beconscient.common;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;

/**
 * The server's notion of "now". Tests pin it to replay a day as it was;
 * production never touches it.
 */
@ApplicationScoped
public class AppClock {

    private volatile Instant fixed;

    public Instant now() {
        Instant f = fixed;
        return f != null ? f : Instant.now();
    }

    public long nowMs() {
        return now().toEpochMilli();
    }

    /** Test hook: freeze the clock at an instant, or pass null to follow real time again. */
    public void set(Instant instant) {
        this.fixed = instant;
    }
}
