package org.beconscient.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.beconscient.auth.GlobalRateLimiter;
import org.beconscient.common.AppClock;
import org.beconscient.crypto.Hpke;
import org.beconscient.crypto.HpkeKey;
import org.beconscient.crypto.KeyRing;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The two collector endpoints. Both take an HPKE envelope and answer 200 only
 * with an encrypted body; errors are plain {"error":"<code>"} without detail.
 * Nothing decrypted is ever logged.
 */
@Path("/ingest/v1")
public class IngestResource {

    private static final Logger LOG = Logger.getLogger(IngestResource.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    KeyRing keys;

    @Inject
    PairingService pairing;

    @Inject
    BatchService batches;

    @Inject
    GlobalRateLimiter limiter;

    @Inject
    AppClock clock;

    @ConfigProperty(name = "beconscient.ingest.max-body-bytes", defaultValue = "1048576")
    int maxBodyBytes;

    @POST
    @Path("/pair")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response pair(byte[] body) {
        if (body == null || body.length > maxBodyBytes) {
            return error(413, "too_large");
        }
        if (!limiter.tryAcquire()) {
            return error(429, "rate_limited");
        }
        Hpke.Opened opened = open(body, Hpke.Purpose.PAIR);
        if (opened == null) {
            limiter.failure();
            return error(400, "bad_envelope");
        }
        PairingService.Outcome outcome;
        try {
            outcome = pairing.pair(opened.plaintext());
        } catch (RuntimeException e) {
            LOG.error("Pairing failed to commit", e);
            return error(503, "unavailable");
        }
        return switch (outcome) {
            case PairingService.Paired p -> {
                limiter.success();
                yield sealed(opened, p.response());
            }
            case PairingService.Refused r -> {
                limiter.failure();
                yield error(401, "unauthorized");
            }
            case PairingService.Malformed m -> {
                limiter.failure();
                yield error(400, "bad_envelope");
            }
        };
    }

    @POST
    @Path("/batches")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response batch(byte[] body) {
        if (body == null || body.length > maxBodyBytes) {
            return error(413, "too_large");
        }
        Hpke.Opened opened = open(body, Hpke.Purpose.BATCH);
        if (opened == null) {
            return error(400, "bad_envelope");
        }
        JsonNode root;
        try {
            root = JSON.readTree(opened.plaintext());
        } catch (Exception e) {
            return error(400, "bad_envelope");
        }
        BatchService.Outcome outcome;
        try {
            outcome = batches.process(root);
        } catch (RuntimeException e) {
            // The transaction rolled back: nothing of this batch is stored
            LOG.errorf("Batch failed to commit: %s", e.getClass().getSimpleName());
            return error(503, "unavailable");
        }
        return switch (outcome) {
            case BatchService.Stored s -> sealed(opened, s.receipt());
            case BatchService.Unauthorized u -> error(401, "unauthorized");
            case BatchService.SequenceConflict c -> error(409, "sequence_conflict");
            case BatchService.BadRequest b -> error(400, "bad_envelope");
            case BatchService.TooLarge t -> error(413, "too_large");
        };
    }

    /** Opens an envelope for one purpose, or returns null for anything that does not open. */
    private Hpke.Opened open(byte[] body, Hpke.Purpose purpose) {
        try {
            Envelope.Request req = Envelope.parse(body);
            HpkeKey key = keys.find(req.kid(), clock.now()).orElse(null);
            if (key == null) {
                return null;
            }
            return Hpke.open(key.privateKey(), req.kid(), purpose, req.enc(), req.ct());
        } catch (Envelope.Malformed | Hpke.OpenFailed e) {
            return null;
        }
    }

    private static Response sealed(Hpke.Opened opened, String plaintext) {
        String body = Envelope.seal(opened.responseKey(), plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Response.ok(body, MediaType.APPLICATION_JSON).header("Cache-Control", "no-store").build();
    }

    static Response error(int status, String code) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(Envelope.error(code)).build();
    }
}
