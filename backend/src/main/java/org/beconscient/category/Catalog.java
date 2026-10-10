package org.beconscient.category;

import org.beconscient.api.Dto;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A snapshot of categories and mappings for one read. Must be built inside a transaction. */
public final class Catalog {

    private final Map<UUID, Category> categories = new LinkedHashMap<>();
    private final Map<String, UUID> mappings = new HashMap<>();

    public static Catalog load() {
        Catalog c = new Catalog();
        List<Category> all = Category.listAll();
        all.sort(Comparator.comparingInt((Category x) -> x.sortOrder).thenComparing(x -> x.name));
        for (Category cat : all) {
            c.categories.put(cat.id, cat);
        }
        for (SubjectMapping m : SubjectMapping.<SubjectMapping>listAll()) {
            c.mappings.put(m.subjectKey, m.categoryId);
        }
        return c;
    }

    /** Unmapped subjects (and orphaned mappings) fall to Uncategorized. */
    public UUID categoryOf(String subject) {
        UUID id = subject == null ? null : mappings.get(subject);
        return id != null && categories.containsKey(id) ? id : Category.UNCATEGORIZED;
    }

    public UUID mappingOf(String subject) {
        return mappings.get(subject);
    }

    public Category get(UUID id) {
        return categories.get(id);
    }

    public List<Category> all() {
        return List.copyOf(categories.values());
    }

    public int order(UUID id) {
        Category c = categories.get(id);
        return c == null ? Integer.MAX_VALUE : c.sortOrder;
    }

    public Dto.CategoryMinutes minutes(UUID id, long minutes) {
        Category c = categories.get(id);
        return new Dto.CategoryMinutes(id.toString(), c == null ? "?" : c.name, c == null ? "#999999" : c.color, minutes);
    }

    public static Dto.CategoryView view(Category c) {
        return new Dto.CategoryView(c.id.toString(), c.name, c.color, c.builtin);
    }
}
