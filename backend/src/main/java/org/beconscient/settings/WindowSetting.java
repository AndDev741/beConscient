package org.beconscient.settings;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** One version of the work or awake window, valid from a local date onwards. */
@Entity
@Table(name = "window_settings")
public class WindowSetting extends PanacheEntityBase {

    public static final String WORK = "work";
    public static final String AWAKE = "awake";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 8)
    public String kind;

    @Column(name = "valid_from", nullable = false)
    public LocalDate validFrom;

    /** ISO days (1 = Monday), comma separated; work window only. */
    @Column(length = 20)
    public String days;

    @Column(name = "start_time", nullable = false, length = 5)
    public String startTime;

    @Column(name = "end_time", nullable = false, length = 5)
    public String endTime;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
