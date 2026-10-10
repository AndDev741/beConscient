package org.beconscient.device;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A physical device the owner uses: one phone, one computer. Installations come and go. */
@Entity
@Table(name = "devices")
public class Device extends PanacheEntityBase {

    public static final String ANDROID = "android";
    public static final String DESKTOP = "desktop";

    @Id
    public UUID id;

    @Column(nullable = false, length = 80)
    public String name;

    @Column(nullable = false, length = 16)
    public String kind;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public boolean isAndroid() {
        return ANDROID.equals(kind);
    }
}
