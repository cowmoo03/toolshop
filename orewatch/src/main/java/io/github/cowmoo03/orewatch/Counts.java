package io.github.cowmoo03.orewatch;

import java.util.HashMap;
import java.util.Map;

/**
 * Totals for one period: filler blocks mined per group; veins, buried veins and ore blocks found per resource;
 * and cave breakthrough events.
 */
public final class Counts {
    public static final String BREAKTHROUGHS = "breakthroughs";
    public static final String PRODUCTIVE = "productive";

    public final Map<String, Long> filler = new HashMap<>();
    /** All veins found (buried + cave). */
    public final Map<String, Long> veins = new HashMap<>();
    /** Veins whose first block was buried: reached only through the player's own digging. */
    public final Map<String, Long> buried = new HashMap<>();
    public final Map<String, Long> ores = new HashMap<>();
    /** Breakthroughs into natural caves, and how many led to a valuable find. */
    public final Map<String, Long> events = new HashMap<>();

    public long filler(String group) { return filler.getOrDefault(group, 0L); }
    public long veins(String resource) { return veins.getOrDefault(resource, 0L); }
    public long buried(String resource) { return buried.getOrDefault(resource, 0L); }
    public long cave(String resource) { return Math.max(0, veins(resource) - buried(resource)); }
    public long ores(String resource) { return ores.getOrDefault(resource, 0L); }
    public long breakthroughs() { return events.getOrDefault(BREAKTHROUGHS, 0L); }
    public long productive() { return events.getOrDefault(PRODUCTIVE, 0L); }

    public void add(Counts other) {
        other.filler.forEach((k, v) -> filler.merge(k, v, Long::sum));
        other.veins.forEach((k, v) -> veins.merge(k, v, Long::sum));
        other.buried.forEach((k, v) -> buried.merge(k, v, Long::sum));
        other.ores.forEach((k, v) -> ores.merge(k, v, Long::sum));
        other.events.forEach((k, v) -> events.merge(k, v, Long::sum));
    }

    public boolean isEmpty() {
        return filler.isEmpty() && veins.isEmpty() && buried.isEmpty() && ores.isEmpty() && events.isEmpty();
    }

    /** Veins found per 1000 filler blocks. Infinite if veins were found without mining any filler. */
    public static double rate(long veins, long filler) {
        if (veins == 0) return 0;
        if (filler == 0) return Double.POSITIVE_INFINITY;
        return veins * 1000.0 / filler;
    }

    /** Percent of breakthroughs that led to a valuable find. */
    public double productivePercent() {
        long b = breakthroughs();
        return b == 0 ? 0 : productive() * 100.0 / b;
    }
}
