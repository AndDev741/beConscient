package org.beconscient.derive;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A derived segment before it is stored. {@code kind} is app, unattributed,
 * computer or site; {@code subject} is the subject key for app and site.
 * {@code sources} are raw event row ids and {@code rules} the rule names that
 * produced it, for the backstage trace.
 */
public final class SegmentDraft {

    public static final String APP = "app";
    public static final String UNATTRIBUTED = "unattributed";
    public static final String COMPUTER = "computer";
    public static final String SITE = "site";

    static final int MAX_SOURCES = 64;

    public long start;
    public long end;
    public final String kind;
    public final String subject;
    public final Set<Long> sources = new LinkedHashSet<>();
    public final Set<String> rules = new LinkedHashSet<>();

    public SegmentDraft(long start, long end, String kind, String subject) {
        this.start = start;
        this.end = end;
        this.kind = kind;
        this.subject = subject;
    }

    public long length() {
        return end - start;
    }

    public boolean sameOwner(SegmentDraft other) {
        return kind.equals(other.kind) && Objects.equals(subject, other.subject);
    }

    public SegmentDraft source(Long id) {
        if (id != null && sources.size() < MAX_SOURCES) {
            sources.add(id);
        }
        return this;
    }

    public SegmentDraft rule(String rule) {
        rules.add(rule);
        return this;
    }

    void absorb(SegmentDraft other) {
        for (Long id : other.sources) {
            source(id);
        }
        rules.addAll(other.rules);
    }

    public long[] sourceArray() {
        long[] out = new long[sources.size()];
        int i = 0;
        for (Long s : sources) {
            out[i++] = s;
        }
        return out;
    }

    public List<String> ruleList() {
        return new ArrayList<>(rules);
    }

    @Override
    public String toString() {
        return kind + (subject == null ? "" : "(" + subject + ")") + "[" + start + "," + end + ")";
    }
}
