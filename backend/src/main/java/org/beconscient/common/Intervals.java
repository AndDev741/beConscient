package org.beconscient.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Half-open millisecond intervals [start, end) and the set operations the
 * derivation and the reading need. Lists returned by union/intersect/subtract are
 * sorted and disjoint.
 */
public final class Intervals {

    public record Span(long start, long end) {
        public Span {
            if (end < start) {
                throw new IllegalArgumentException("end before start");
            }
        }

        public long length() {
            return end - start;
        }

        public boolean overlaps(long s, long e) {
            return start < e && s < end;
        }

        public Span clip(long s, long e) {
            long a = Math.max(start, s);
            long b = Math.min(end, e);
            return b > a ? new Span(a, b) : null;
        }
    }

    private Intervals() {
    }

    public static List<Span> union(List<Span> spans) {
        List<Span> sorted = new ArrayList<>(spans);
        sorted.removeIf(s -> s.length() <= 0);
        sorted.sort(Comparator.comparingLong(Span::start));
        List<Span> out = new ArrayList<>();
        for (Span s : sorted) {
            if (!out.isEmpty() && s.start() <= out.get(out.size() - 1).end()) {
                Span last = out.remove(out.size() - 1);
                out.add(new Span(last.start(), Math.max(last.end(), s.end())));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /** a ∩ b, both sorted and disjoint. */
    public static List<Span> intersect(List<Span> a, List<Span> b) {
        List<Span> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            Span x = a.get(i);
            Span y = b.get(j);
            long s = Math.max(x.start(), y.start());
            long e = Math.min(x.end(), y.end());
            if (e > s) {
                out.add(new Span(s, e));
            }
            if (x.end() < y.end()) {
                i++;
            } else {
                j++;
            }
        }
        return out;
    }

    /** a − b, both sorted and disjoint. */
    public static List<Span> subtract(List<Span> a, List<Span> b) {
        List<Span> out = new ArrayList<>();
        int j = 0;
        for (Span x : a) {
            long cur = x.start();
            while (j < b.size() && b.get(j).end() <= cur) {
                j++;
            }
            int k = j;
            while (k < b.size() && b.get(k).start() < x.end()) {
                Span y = b.get(k);
                if (y.start() > cur) {
                    out.add(new Span(cur, y.start()));
                }
                cur = Math.max(cur, y.end());
                if (cur >= x.end()) {
                    break;
                }
                k++;
            }
            if (cur < x.end()) {
                out.add(new Span(cur, x.end()));
            }
        }
        return out;
    }

    public static List<Span> clip(List<Span> spans, long s, long e) {
        List<Span> out = new ArrayList<>();
        for (Span x : spans) {
            Span c = x.clip(s, e);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    public static long total(List<Span> spans) {
        long t = 0;
        for (Span s : spans) {
            t += s.length();
        }
        return t;
    }

    /** Length of the part of a sorted disjoint list inside [s, e). */
    public static long totalWithin(List<Span> spans, long s, long e) {
        long t = 0;
        for (Span x : spans) {
            if (x.end() <= s) {
                continue;
            }
            if (x.start() >= e) {
                break;
            }
            t += Math.min(x.end(), e) - Math.max(x.start(), s);
        }
        return t;
    }
}
