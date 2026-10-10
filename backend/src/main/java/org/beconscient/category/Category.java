package org.beconscient.category;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** A category shared by every device. The four built-ins have fixed ids. */
@Entity
@Table(name = "categories")
public class Category extends PanacheEntityBase {

    public static final UUID WORK = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID PERSONAL_COMPUTER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID UNCATEGORIZED = UUID.fromString("00000000-0000-0000-0000-000000000003");
    public static final UUID UNATTRIBUTED = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Id
    public UUID id;

    @Column(nullable = false, unique = true, length = 60)
    public String name;

    @Column(nullable = false, length = 9)
    public String color;

    @Column(nullable = false)
    public boolean builtin;

    @Column(name = "sort_order", nullable = false)
    public int sortOrder;
}
