package org.beconscient.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.beconscient.common.AppClock;
import org.beconscient.crypto.Bytes;
import org.beconscient.crypto.Crockford;
import org.beconscient.device.Device;
import org.beconscient.device.DeviceService;
import org.beconscient.device.Fingerprint;
import org.beconscient.device.Installation;
import org.beconscient.device.PairingCode;
import org.beconscient.device.PairingState;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;

/**
 * The collector side of pairing (envelope.md). Failures are returned, not
 * thrown, so the failure counter commits even though the caller answers 401.
 */
@ApplicationScoped
public class PairingService {

    private static final Logger LOG = Logger.getLogger(PairingService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int LOCKOUT_AFTER = 10;

    @Inject
    AppClock clock;

    public sealed interface Outcome permits Paired, Refused, Malformed {
    }

    /** {@code response} is the pairing response plaintext. */
    public record Paired(UUID deviceId, String fingerprint, String response) implements Outcome {
    }

    public record Refused() implements Outcome {
    }

    public record Malformed() implements Outcome {
    }

    @Transactional
    public Outcome pair(byte[] plaintext) {
        Instant now = clock.now();
        // Without an open code there is nothing to guess: fail fast
        if (PairingCode.countOpen(now) == 0) {
            return new Refused();
        }
        JsonNode req;
        try {
            req = JSON.readTree(plaintext);
        } catch (Exception e) {
            return new Malformed();
        }
        if (req == null || !req.isObject()) {
            return new Malformed();
        }
        String kind = text(req, "kind");
        UUID installationId = uuid(text(req, "installation_id"));
        byte[] secret = secret(text(req, "secret"));
        String name = text(req, "name");
        String clientVersion = text(req, "client_version");
        if (installationId == null || secret == null
                || !(Device.ANDROID.equals(kind) || Device.DESKTOP.equals(kind))) {
            return new Malformed();
        }

        String normalized = Crockford.normalizeCode(text(req, "code"));
        PairingCode code = normalized == null ? null
                : PairingCode.find("codeHash", DeviceService.codeHash(normalized))
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        Device device = code == null ? null : Device.findById(code.deviceId);
        if (code == null || !code.isOpen(now) || device == null || !device.kind.equals(kind)
                || Installation.findById(installationId) != null) {
            failure(now);
            return new Refused();
        }

        code.usedAt = now;
        // Relinking: a new installation replaces the older ones of this device; their events stay
        Installation.update("revokedAt = ?1 where deviceId = ?2 and revokedAt is null", now, device.id);
        byte[] secretHash = Bytes.sha256(secret);
        Installation inst = new Installation();
        inst.id = installationId;
        inst.deviceId = device.id;
        inst.credentialHash = secretHash;
        inst.fingerprint = Fingerprint.of(installationId, secretHash);
        inst.clientName = ContractFilter.truncate(name, 80);
        inst.clientVersion = ContractFilter.truncate(clientVersion, 32);
        inst.createdAt = now;
        inst.persist();

        PairingState state = PairingState.forUpdate();
        state.consecutiveFailures = 0;
        state.lockedOut = false;
        LOG.infof("Paired installation %s to device %s", installationId, device.id);

        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("device_id", device.id.toString());
        response.put("device_name", device.name);
        response.put("fingerprint", inst.fingerprint);
        return new Paired(device.id, inst.fingerprint, response.toString());
    }

    /** Counts a failed attempt; every tenth consecutive one invalidates all open codes. */
    private void failure(Instant now) {
        PairingState state = PairingState.forUpdate();
        state.consecutiveFailures++;
        if (state.consecutiveFailures % LOCKOUT_AFTER == 0) {
            long n = PairingCode.update("invalidatedAt = ?1 where usedAt is null and invalidatedAt is null", now);
            state.lockedOut = true;
            state.lockedOutAt = now;
            LOG.warnf("%d consecutive failed pairing attempts: invalidated %d open codes", state.consecutiveFailures, n);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static UUID uuid(String s) {
        return ContractFilter.parseUuid(s);
    }

    private static byte[] secret(String s) {
        if (s == null) {
            return null;
        }
        try {
            byte[] b = Bytes.fromB64url(s);
            return b.length == 32 ? b : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
