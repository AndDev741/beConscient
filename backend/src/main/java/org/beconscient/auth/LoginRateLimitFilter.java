package org.beconscient.auth;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpMethod;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Puts the form login behind the global limiter. It runs before the
 * authentication handler (priority 200), so a limited attempt never reaches the
 * password check, and it learns the outcome from the response status.
 */
@ApplicationScoped
public class LoginRateLimitFilter {

    static final int PRIORITY = 250;

    @Inject
    GlobalRateLimiter limiter;

    void register(@Observes Filters filters) {
        filters.register(ctx -> {
            if (ctx.request().method() == HttpMethod.POST && "/api/login".equals(ctx.normalizedPath())) {
                if (!limiter.tryAcquire()) {
                    long seconds = Math.max(1, (limiter.retryAfter().toMillis() + 999) / 1000);
                    ctx.response().setStatusCode(429)
                            .putHeader("Content-Type", "application/json")
                            .putHeader("Retry-After", Long.toString(seconds))
                            .end("{\"error\":\"rate_limited\",\"message\":\"Too many attempts. Wait a moment and try again.\"}");
                    return;
                }
                ctx.addHeadersEndHandler(v -> {
                    int status = ctx.response().getStatusCode();
                    if (status >= 200 && status < 400) {
                        limiter.success();
                    } else if (status == 401 || status == 403) {
                        limiter.failure();
                    }
                });
            }
            ctx.next();
        }, PRIORITY);
    }
}
