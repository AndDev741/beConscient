package org.beconscient.device;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;

import java.time.Instant;

/** The single row counting consecutive failed pairing attempts. */
@Entity
@Table(name = "pairing_state")
public class PairingState extends PanacheEntityBase {

    @Id
    public Integer id;

    @Column(name = "consecutive_failures", nullable = false)
    public int consecutiveFailures;

    @Column(name = "locked_out", nullable = false)
    public boolean lockedOut;

    @Column(name = "locked_out_at")
    public Instant lockedOutAt;

    public static PairingState current() {
        return findById(1);
    }

    public static PairingState forUpdate() {
        return findById(1, LockModeType.PESSIMISTIC_WRITE);
    }
}
