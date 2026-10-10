package org.beconscient.ingest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.Optional;

/** Refuses an announced oversize ingest body before it is read, let alone decrypted. */
public class IngestLimits {

    @ConfigProperty(name = "beconscient.ingest.max-body-bytes", defaultValue = "1048576")
    int maxBodyBytes;

    @ServerRequestFilter(preMatching = true)
    public Optional<Response> capBody(ContainerRequestContext ctx) {
        if (ctx.getUriInfo().getPath().startsWith("/ingest/") && ctx.getLength() > maxBodyBytes) {
            return Optional.of(IngestResource.error(413, "too_large"));
        }
        return Optional.empty();
    }
}
