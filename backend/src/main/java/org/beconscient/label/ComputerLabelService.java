package org.beconscient.label;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.beconscient.api.Dto;
import org.beconscient.category.Category;
import org.beconscient.common.ApiException;
import org.beconscient.common.AppClock;
import org.beconscient.common.Jdbc;
import org.beconscient.settings.SettingsService;
import org.beconscient.view.Timelines;
import org.beconscient.view.UserTimeline;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Stretches of computer time marked by hand. The agent reports only when the
 * computer was in use, so this is how the owner says what that time was. A label
 * is entered as a local date and two times in the user timeline; an end at or
 * before the start belongs to the next day, like the awake window.
 */
@ApplicationScoped
public class ComputerLabelService {

    @Inject
    Jdbc jdbc;

    @Inject
    AppClock clock;

    @Inject
    Timelines timelines;

    /** Every label, oldest first. Read once per page read; there are few. */
    @Transactional
    public List<ComputerLabel> all() {
        return jdbc.list("select id, from_ts, to_ts, category_id from computer_labels order by from_ts",
                rs -> new ComputerLabel(Jdbc.uuid(rs, "id"), rs.getLong("from_ts"), rs.getLong("to_ts"),
                        Jdbc.uuid(rs, "category_id")));
    }

    @Transactional
    public Dto.ComputerLabelView create(Dto.ComputerLabelRequest req) {
        if (req == null) {
            throw ApiException.badRequest("date, start, end and categoryId are required.");
        }
        LocalDate date = SettingsService.date(req.date());
        LocalTime start = LocalTime.parse(SettingsService.time(req.start()));
        LocalTime end = LocalTime.parse(SettingsService.time(req.end()));
        if (start.equals(end)) {
            throw ApiException.badRequest("The stretch needs a start and an end that differ.");
        }
        UUID category = category(req.categoryId());
        UserTimeline tl = timelines.load();
        long from = tl.instantOf(date, start);
        long to = end.isAfter(start) ? tl.instantOf(date, end) : tl.instantOf(date.plusDays(1), end);
        if (jdbc.count("select count(*) from computer_labels where from_ts < ? and to_ts > ?", to, from) > 0) {
            throw ApiException.conflict("label_overlap", "That stretch overlaps one already marked. Remove that one first.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into computer_labels (id, from_ts, to_ts, category_id, created_at) values (?, ?, ?, ?, ?)",
                id, from, to, category, clock.now());
        return new Dto.ComputerLabelView(id.toString(), category.toString(), from, to);
    }

    @Transactional
    public void remove(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Label");
        }
        if (jdbc.update("delete from computer_labels where id = ?", uuid) == 0) {
            throw ApiException.notFound("Label");
        }
    }

    private static UUID category(String id) {
        if (id == null) {
            throw ApiException.badRequest("categoryId is required.");
        }
        Category c;
        try {
            c = Category.findById(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            c = null;
        }
        if (c == null) {
            throw ApiException.notFound("Category");
        }
        if (Category.UNCATEGORIZED.equals(c.id) || Category.UNATTRIBUTED.equals(c.id)) {
            throw ApiException.badRequest("Marked computer time needs a category of its own, not " + c.name + ".");
        }
        return c.id;
    }
}
