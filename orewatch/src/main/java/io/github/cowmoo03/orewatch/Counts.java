package io.github.cowmoo03.orewatch;

import java.util.HashMap;
import java.util.Map;

/** Totals for one period: filler blocks mined per group, and veins and ore blocks found per resource. */
public final class Counts {
    public final Map<String, Long> filler = new HashMap<>();
    public final Map<String, Long> veins = new HashMap<>();
    public final Map<String, Long> ores = new HashMap<>();

    public long filler(String group) { return filler.getOrDefault(group, 0L); }
    public long veins(String resource) { return veins.getOrDefault(resource, 0L); }
    public long ores(String resource) { return ores.getOrDefault(resource, 0L); }

    public void add(Counts other) {
        other.filler.forEach((k, v) -> filler.merge(k, v, Long::sum));
        other.veins.forEach((k, v) -> veins.merge(k, v, Long::sum));
        other.ores.forEach((k, v) -> ores.merge(k, v, Long::sum));
    }

    public boolean isEmpty() {
        return filler.isEmpty() && veins.isEmpty() && ores.isEmpty();
    }

    /** Veins found per 1000 filler blocks. Infinite if veins were found without mining any filler. */
    public static double rate(long veins, long filler) {
        if (veins == 0) return 0;
        if (filler == 0) return Double.POSITIVE_INFINITY;
        return veins * 1000.0 / filler;
    }
}
