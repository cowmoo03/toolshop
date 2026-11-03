package io.github.cowmoo03.orewatch;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Per-player mining statistics: lifetime totals plus hourly buckets for recent history, and the set of staff who
 * turned off find notifications. Saved to plugins/OreWatch/data.yml.
 */
public final class Stats {
    public static final long HOUR_MS = 3_600_000L;

    public static final class PlayerData {
        public String name = "?";
        public final Counts lifetime = new Counts();
        /** Hour index (epoch hours) -> counts in that hour. */
        public final TreeMap<Long, Counts> hours = new TreeMap<>();

        /** Totals for the last {@code hoursBack} hours. */
        public Counts recent(int hoursBack) {
            long from = currentHour() - hoursBack + 1;
            Counts out = new Counts();
            for (Counts c : hours.tailMap(from, true).values()) out.add(c);
            return out;
        }

        Counts bucket() {
            return hours.computeIfAbsent(currentHour(), h -> new Counts());
        }
    }

    private final File file;
    private final Logger log;
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final Set<UUID> notifyOff = new HashSet<>();
    private boolean dirty;

    public Stats(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "data.yml");
        this.log = log;
        load();
    }

    public static long currentHour() {
        return System.currentTimeMillis() / HOUR_MS;
    }

    public PlayerData get(UUID id) {
        return players.get(id);
    }

    public PlayerData getOrCreate(UUID id, String name) {
        PlayerData d = players.computeIfAbsent(id, k -> new PlayerData());
        if (name != null) d.name = name;
        return d;
    }

    public Map<UUID, PlayerData> all() {
        return players;
    }

    /** Finds a tracked player by name (case-insensitive). */
    public UUID findByName(String name) {
        for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
            if (e.getValue().name.equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    public void addFiller(UUID id, String name, String group) {
        PlayerData d = getOrCreate(id, name);
        d.lifetime.filler.merge(group, 1L, Long::sum);
        d.bucket().filler.merge(group, 1L, Long::sum);
        dirty = true;
    }

    public void addOre(UUID id, String name, String resource, boolean newVein) {
        PlayerData d = getOrCreate(id, name);
        d.lifetime.ores.merge(resource, 1L, Long::sum);
        d.bucket().ores.merge(resource, 1L, Long::sum);
        if (newVein) {
            d.lifetime.veins.merge(resource, 1L, Long::sum);
            d.bucket().veins.merge(resource, 1L, Long::sum);
        }
        dirty = true;
    }

    public boolean reset(UUID id) {
        boolean had = players.remove(id) != null;
        if (had) dirty = true;
        return had;
    }

    public boolean notifyOff(UUID id) {
        return notifyOff.contains(id);
    }

    /** Toggles find notifications for a staff member. Returns true if they're now ON. */
    public boolean toggleNotify(UUID id) {
        dirty = true;
        if (notifyOff.remove(id)) return true; // was off, now on
        notifyOff.add(id);
        return false;
    }

    /** Drops hourly history older than {@code days}. */
    public void prune(int days) {
        long oldest = currentHour() - days * 24L;
        for (PlayerData d : players.values()) d.hours.headMap(oldest).clear();
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                UUID id;
                try {
                    id = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                ConfigurationSection p = ps.getConfigurationSection(key);
                if (p == null) continue;
                PlayerData d = getOrCreate(id, p.getString("name", "?"));
                readCounts(p.getConfigurationSection("lifetime"), d.lifetime);
                ConfigurationSection hours = p.getConfigurationSection("hours");
                if (hours != null) {
                    for (String h : hours.getKeys(false)) {
                        try {
                            Counts c = new Counts();
                            readCounts(hours.getConfigurationSection(h), c);
                            d.hours.put(Long.parseLong(h), c);
                        } catch (NumberFormatException ignored) {
                            // skip
                        }
                    }
                }
            }
        }
        for (String s : y.getStringList("notify-off")) {
            try {
                notifyOff.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // skip
            }
        }
    }

    private static void readCounts(ConfigurationSection s, Counts into) {
        if (s == null) return;
        readMap(s.getConfigurationSection("filler"), into.filler);
        readMap(s.getConfigurationSection("veins"), into.veins);
        readMap(s.getConfigurationSection("ores"), into.ores);
    }

    private static void readMap(ConfigurationSection s, Map<String, Long> into) {
        if (s == null) return;
        for (String k : s.getKeys(false)) into.put(k, s.getLong(k));
    }

    public void saveIfDirty() {
        if (dirty) save();
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        players.forEach((id, d) -> {
            String base = "players." + id;
            y.set(base + ".name", d.name);
            writeCounts(y, base + ".lifetime", d.lifetime);
            d.hours.forEach((h, c) -> {
                if (!c.isEmpty()) writeCounts(y, base + ".hours." + h, c);
            });
        });
        List<String> off = new ArrayList<>();
        notifyOff.forEach(u -> off.add(u.toString()));
        y.set("notify-off", off);
        try {
            file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            log.warning("Couldn't save data.yml: " + e.getMessage());
        }
    }

    private static void writeCounts(YamlConfiguration y, String path, Counts c) {
        c.filler.forEach((k, v) -> y.set(path + ".filler." + k, v));
        c.veins.forEach((k, v) -> y.set(path + ".veins." + k, v));
        c.ores.forEach((k, v) -> y.set(path + ".ores." + k, v));
    }
}
