package org.beconscient.device;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One paired collector install. Revoking it keeps its past events. */
@Entity
@Table(name = "installations")
public class Installation extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "device_id", nullable = false)
    public UUID deviceId;

    @Column(name = "credential_hash", nullable = false)
    public byte[] credentialHash;

    @Column(nullable = false, length = 9)
    public String fingerprint;

    @Column(name = "client_name", length = 80)
    public String clientName;

    @Column(name = "client_version", length = 32)
    public String clientVersion;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "last_seen_at")
    public Instant lastSeenAt;

    @Column(name = "revoked_at")
    public Instant revokedAt;

    @Column(name = "clone_suspected", nullable = false)
    public boolean cloneSuspected;

    @Column(name = "last_seq", nullable = false)
    public long lastSeq;

    @Column(name = "last_tz", length = 64)
    public String lastTz;

    public boolean isActive() {
        return revokedAt == null;
    }

    public static List<Installation> ofDevice(UUID deviceId) {
        return list("deviceId = ?1 order by createdAt", deviceId);
    }
}
