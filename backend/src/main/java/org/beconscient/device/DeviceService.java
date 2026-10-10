package org.beconscient.device;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.beconscient.api.Dto;
import org.beconscient.common.ApiException;
import org.beconscient.common.AppClock;
import org.beconscient.crypto.Bytes;
import org.beconscient.crypto.Crockford;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The page side of devices: create, list, pairing codes and revocation. */
@ApplicationScoped
public class DeviceService {

    public static final Duration CODE_TTL = Duration.ofMinutes(15);

    @Inject
    AppClock clock;

    /** SHA-256 of the normalised XXXX-XXXX form; the only form a code is ever stored in. */
    public static byte[] codeHash(String normalized) {
        return Bytes.sha256(Bytes.utf8(normalized));
    }

    @Transactional
    public Dto.CreatedDevice create(Dto.NewDeviceRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("A device needs a name.");
        }
        String kind = req.kind();
        if (!Device.ANDROID.equals(kind) && !Device.DESKTOP.equals(kind)) {
            throw ApiException.badRequest("kind must be android or desktop.");
        }
        String name = req.name().trim();
        if (name.length() > 80) {
            throw ApiException.badRequest("The name is too long.");
        }
        if (Device.ANDROID.equals(kind) && !Boolean.TRUE.equals(req.confirmSecondPhone()) && activeAndroidExists()) {
            throw ApiException.conflict("second_phone",
                    "Another Android device is already paired. Confirm to pair a second phone.");
        }
        Device d = new Device();
        d.id = UUID.randomUUID();
        d.name = name;
        d.kind = kind;
        d.createdAt = clock.now();
        d.persist();
        Dto.PairingCodeView code = issueCode(d.id);
        return new Dto.CreatedDevice(view(d), code.code(), code.expiresAt());
    }

    private boolean activeAndroidExists() {
        return Installation.count(
                "revokedAt is null and deviceId in (select d.id from Device d where d.kind = ?1)", Device.ANDROID) > 0;
    }

    @Transactional
    public Dto.PairingCodeView newCode(UUID deviceId) {
        if (Device.findById(deviceId) == null) {
            throw ApiException.notFound("Device");
        }
        return issueCode(deviceId);
    }

    /**
     * Shown once and never stored in clear. Older open codes of the device are
     * invalidated, and creating a code clears the ten-failure lockout banner.
     */
    private Dto.PairingCodeView issueCode(UUID deviceId) {
        Instant now = clock.now();
        PairingCode.update("invalidatedAt = ?1 where deviceId = ?2 and usedAt is null and invalidatedAt is null",
                now, deviceId);
        String code = Crockford.newPairingCode();
        PairingCode pc = new PairingCode();
        pc.id = UUID.randomUUID();
        pc.deviceId = deviceId;
        pc.codeHash = codeHash(Crockford.normalizeCode(code));
        pc.createdAt = now;
        pc.expiresAt = now.plus(CODE_TTL);
        pc.persist();
        PairingState state = PairingState.forUpdate();
        state.lockedOut = false;
        return new Dto.PairingCodeView(code, pc.expiresAt.toEpochMilli());
    }

    @Transactional
    public List<Dto.DeviceView> list() {
        List<Device> devices = Device.list("order by createdAt");
        return devices.stream().map(this::view).toList();
    }

    public Dto.DeviceView view(Device d) {
        List<Dto.InstallationView> installs = Installation.ofDevice(d.id).stream()
                .map(i -> new Dto.InstallationView(i.id.toString(), i.fingerprint, i.createdAt.toEpochMilli(),
                        i.lastSeenAt == null ? null : i.lastSeenAt.toEpochMilli(),
                        i.revokedAt == null ? null : i.revokedAt.toEpochMilli(),
                        i.cloneSuspected))
                .toList();
        return new Dto.DeviceView(d.id.toString(), d.name, d.kind, installs);
    }

    @Transactional
    public void revoke(UUID installationId) {
        Installation i = Installation.findById(installationId);
        if (i == null) {
            throw ApiException.notFound("Installation");
        }
        if (i.revokedAt == null) {
            i.revokedAt = clock.now();
        }
    }

    @Transactional
    public Dto.PairingStateView pairingState() {
        PairingState s = PairingState.current();
        return new Dto.PairingStateView(s.lockedOut, s.consecutiveFailures);
    }
}
