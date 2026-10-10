package org.beconscient.device;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A one-time pairing code, stored only as the SHA-256 of its normalised form. */
@Entity
@Table(name = "pairing_codes")
public class PairingCode extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "device_id", nullable = false)
    public UUID deviceId;

    @Column(name = "code_hash", nullable = false, unique = true)
    public byte[] codeHash;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "used_at")
    public Instant usedAt;

    @Column(name = "invalidated_at")
    public Instant invalidatedAt;

    public boolean isOpen(Instant now) {
        return usedAt == null && invalidatedAt == null && now.isBefore(expiresAt);
    }

    public static long countOpen(Instant now) {
        return count("usedAt is null and invalidatedAt is null and expiresAt > ?1", now);
    }
}
