package org.beconscient.category;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Which category an app or site belongs to. Applied when reading, so it fixes the past too. */
@Entity
@Table(name = "subject_mappings")
public class SubjectMapping extends PanacheEntityBase {

    @Id
    @Column(name = "subject_key", length = 300)
    public String subjectKey;

    @Column(name = "category_id", nullable = false)
    public UUID categoryId;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
