package org.beconscient.settings;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/** A local date on which computer time is Personal computer whatever the work window says. */
@Entity
@Table(name = "non_workdays")
public class NonWorkday extends PanacheEntityBase {

    @Id
    @Column(name = "day")
    public LocalDate day;
}
