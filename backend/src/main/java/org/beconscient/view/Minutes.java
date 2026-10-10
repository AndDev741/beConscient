package org.beconscient.view;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Milliseconds to whole minutes, keeping parts consistent with their total. */
public final class Minutes {

    private Minutes() {
    }

    public static long of(long ms) {
        return Math.round(ms / 60_000.0);
    }

    /**
     * Rounds each part with the largest-remainder method so the rounded parts add
     * up to the rounded sum: a stacked bar never disagrees with its total.
     */
    public static <K> Map<K, Long> apportion(Map<K, Long> ms) {
        long total = 0;
        for (long v : ms.values()) {
            total += v;
        }
        long target = of(total);
        Map<K, Long> out = new LinkedHashMap<>();
        Map<K, Double> remainder = new HashMap<>();
        long assigned = 0;
        for (Map.Entry<K, Long> e : ms.entrySet()) {
            double exact = e.getValue() / 60_000.0;
            long floor = (long) Math.floor(exact);
            out.put(e.getKey(), floor);
            remainder.put(e.getKey(), exact - floor);
            assigned += floor;
        }
        List<K> order = remainder.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).map(Map.Entry::getKey).toList();
        for (int i = 0; assigned < target && i < order.size(); i++) {
            out.merge(order.get(i), 1L, Long::sum);
            assigned++;
        }
        return out;
    }
}
