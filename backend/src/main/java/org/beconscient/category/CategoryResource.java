package org.beconscient.category;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.beconscient.api.Dto;
import org.beconscient.common.ApiException;
import org.beconscient.common.AppClock;
import org.beconscient.common.Jdbc;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Categories and the mapping of apps and sites to them. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class CategoryResource {

    private static final Pattern COLOR = Pattern.compile("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})");
    static final long DAYS_30_MS = 30L * 86_400_000L;

    @Inject
    Jdbc jdbc;

    @Inject
    AppClock clock;

    @GET
    @Path("/categories")
    @Transactional
    public List<Dto.CategoryView> list() {
        return Catalog.load().all().stream().map(Catalog::view).toList();
    }

    @Consumes(MediaType.APPLICATION_JSON)
    @POST
    @Path("/categories")
    @ResponseStatus(201)
    @Transactional
    public Dto.CategoryView create(Dto.CategoryRequest req) {
        String name = validName(req);
        String color = validColor(req);
        if (Category.count("lower(name) = ?1", name.toLowerCase(Locale.ROOT)) > 0) {
            throw ApiException.conflict("category_exists", "A category with that name already exists.");
        }
        Category c = new Category();
        c.id = UUID.randomUUID();
        c.name = name;
        c.color = color;
        c.builtin = false;
        c.sortOrder = 100;
        c.persist();
        return Catalog.view(c);
    }

    @Consumes(MediaType.APPLICATION_JSON)
    @PUT
    @Path("/categories/{id}")
    @Transactional
    public Dto.CategoryView update(@PathParam("id") String id, Dto.CategoryRequest req) {
        Category c = find(id);
        String name = validName(req);
        String color = validColor(req);
        if (c.builtin && !c.name.equals(name)) {
            throw ApiException.conflict("builtin_category", "Built-in categories keep their names.");
        }
        if (Category.count("lower(name) = ?1 and id <> ?2", name.toLowerCase(Locale.ROOT), c.id) > 0) {
            throw ApiException.conflict("category_exists", "A category with that name already exists.");
        }
        c.name = name;
        c.color = color;
        return Catalog.view(c);
    }

    @DELETE
    @Path("/categories/{id}")
    @Transactional
    public void delete(@PathParam("id") String id) {
        Category c = find(id);
        if (c.builtin) {
            throw ApiException.conflict("builtin_category", "Built-in categories cannot be deleted.");
        }
        if (SubjectMapping.count("categoryId", c.id) > 0) {
            throw ApiException.conflict("category_in_use", "Apps or sites still map to this category.");
        }
        if (jdbc.count("select count(*) from computer_labels where category_id = ?", c.id) > 0) {
            throw ApiException.conflict("category_in_use", "Computer time marked on the day page still uses this category.");
        }
        c.delete();
    }

    @GET
    @Path("/subjects")
    @Transactional
    public List<Dto.SubjectView> subjects(@QueryParam("q") String q, @QueryParam("unmapped") Boolean unmapped,
                                          @QueryParam("limit") @DefaultValue("200") int limit) {
        long now = clock.nowMs();
        long from = now - DAYS_30_MS;
        Map<String, Long> ms = new HashMap<>();
        jdbc.list("""
                        select subject, sum(least(end_ts, ?) - greatest(start_ts, ?)) as ms from segments
                        where subject is not null and start_ts < ? and end_ts > ? group by subject""",
                rs -> ms.put(rs.getString("subject"), rs.getLong("ms")), now, from, now, from);
        // Every subject ever seen, so an app used long ago can still be classified
        jdbc.list("select distinct subject from segments where subject is not null",
                rs -> ms.putIfAbsent(rs.getString("subject"), 0L));
        Catalog catalog = Catalog.load();
        for (SubjectMapping m : SubjectMapping.<SubjectMapping>listAll()) {
            ms.putIfAbsent(m.subjectKey, 0L);
        }
        String needle = q == null || q.isBlank() ? null : q.toLowerCase(Locale.ROOT).trim();
        List<Dto.SubjectView> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : ms.entrySet()) {
            String key = e.getKey();
            UUID mapped = catalog.mappingOf(key);
            if (Boolean.TRUE.equals(unmapped) && mapped != null) {
                continue;
            }
            String label = Subjects.label(key);
            if (needle != null && !key.toLowerCase(Locale.ROOT).contains(needle)
                    && !label.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            out.add(new Dto.SubjectView(key, Subjects.kind(key), label, mapped == null ? null : mapped.toString(),
                    Math.round(e.getValue() / 60_000.0)));
        }
        out.sort(Comparator.comparingLong(Dto.SubjectView::minutes30d).reversed().thenComparing(Dto.SubjectView::key));
        return out.subList(0, Math.max(0, Math.min(limit, out.size())));
    }

    @Consumes(MediaType.APPLICATION_JSON)
    @PUT
    @Path("/subjects/{key}/category")
    @Transactional
    public Dto.SubjectView map(@PathParam("key") String key, Dto.SubjectCategoryRequest req) {
        if (!Subjects.valid(key)) {
            throw ApiException.badRequest("Unknown subject key.");
        }
        UUID target = null;
        if (req != null && req.categoryId() != null) {
            target = find(req.categoryId()).id;
            if (Category.UNATTRIBUTED.equals(target)) {
                throw ApiException.badRequest("Unattributed is for time without an app.");
            }
        }
        SubjectMapping m = SubjectMapping.findById(key);
        if (target == null || Category.UNCATEGORIZED.equals(target)) {
            if (m != null) {
                m.delete();
            }
            target = null;
        } else if (m == null) {
            m = new SubjectMapping();
            m.subjectKey = key;
            m.categoryId = target;
            m.updatedAt = clock.now();
            m.persist();
        } else {
            m.categoryId = target;
            m.updatedAt = clock.now();
        }
        long now = clock.nowMs();
        long ms = jdbc.count("""
                        select coalesce(sum(least(end_ts, ?) - greatest(start_ts, ?)), 0) from segments
                        where subject = ? and start_ts < ? and end_ts > ?""",
                now, now - DAYS_30_MS, key, now, now - DAYS_30_MS);
        return new Dto.SubjectView(key, Subjects.kind(key), Subjects.label(key), target == null ? null : target.toString(),
                Math.round(ms / 60_000.0));
    }

    private static Category find(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Category");
        }
        Category c = Category.findById(uuid);
        if (c == null) {
            throw ApiException.notFound("Category");
        }
        return c;
    }

    private static String validName(Dto.CategoryRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("A category needs a name.");
        }
        String name = req.name().trim();
        if (name.length() > 60) {
            throw ApiException.badRequest("The name is too long.");
        }
        return name;
    }

    private static String validColor(Dto.CategoryRequest req) {
        if (req == null || req.color() == null || !COLOR.matcher(req.color()).matches()) {
            throw ApiException.badRequest("color must be a hex color such as #5b6cff.");
        }
        return req.color().toLowerCase(Locale.ROOT);
    }
}
