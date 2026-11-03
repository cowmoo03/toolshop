package io.github.cowmoo03.orewatch;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Everything read from config.yml. */
public final class Rules {

    /** A tracked resource such as diamond: its ore blocks, which filler it's compared to, and its limits. */
    public record Resource(String id, String displayName, Set<Material> blocks, String filler, int minVeins, double maxPer1000) {}

    public final Map<String, Resource> resources = new LinkedHashMap<>();
    public final Map<String, Set<Material>> fillerGroups = new LinkedHashMap<>();
    private final Map<Material, Resource> resourceByBlock = new EnumMap<>(Material.class);
    private final Map<Material, String> fillerByBlock = new EnumMap<>(Material.class);

    public final int veinDistance;
    public final long alertCooldownMs;
    public final boolean notifyFinds;
    public final int notifyDelaySeconds;
    public final int historyDays;

    /** How long a block a player broke is remembered as "their own digging". */
    public final long dugMemoryMs;

    public final boolean breakthroughsEnabled;
    public final int breakthroughMinCave;
    public final int findRadius;
    public final long findWindowMs;
    public final int minBreakthroughs;
    public final double maxProductivePercent;
    public final Set<String> breakthroughResources = new java.util.HashSet<>();

    public Rules(FileConfiguration c, Logger log) {
        ConfigurationSection filler = c.getConfigurationSection("filler");
        if (filler != null) {
            for (String group : filler.getKeys(false)) {
                Set<Material> blocks = materials(filler.getStringList(group), "filler." + group, log);
                fillerGroups.put(group, blocks);
                for (Material m : blocks) fillerByBlock.put(m, group);
            }
        }

        ConfigurationSection res = c.getConfigurationSection("resources");
        if (res != null) {
            for (String id : res.getKeys(false)) {
                ConfigurationSection r = res.getConfigurationSection(id);
                if (r == null) continue;
                String group = r.getString("filler", "overworld");
                if (!fillerGroups.containsKey(group)) {
                    log.warning("resources." + id + " uses unknown filler group '" + group + "'");
                    continue;
                }
                Resource resource = new Resource(id, pretty(id),
                        materials(r.getStringList("blocks"), "resources." + id + ".blocks", log), group,
                        Math.max(1, r.getInt("min-veins", 4)), Math.max(0.01, r.getDouble("max-per-1000", 10)));
                resources.put(id, resource);
                for (Material m : resource.blocks()) resourceByBlock.put(m, resource);
            }
        }

        veinDistance = Math.max(1, c.getInt("vein-distance", 2));
        alertCooldownMs = Math.max(0, c.getLong("alert-cooldown-minutes", 10)) * 60_000L;
        notifyFinds = c.getBoolean("notify-finds", true);
        notifyDelaySeconds = Math.max(0, c.getInt("notify-delay-seconds", 5));
        historyDays = Math.max(1, c.getInt("history-days", 7));

        dugMemoryMs = Math.max(1, c.getLong("exposure.dug-memory-minutes", 30)) * 60_000L;

        breakthroughsEnabled = c.getBoolean("breakthroughs.enabled", true);
        breakthroughMinCave = Math.max(1, c.getInt("breakthroughs.min-cave-size", 12));
        findRadius = Math.max(1, c.getInt("breakthroughs.find-radius", 30));
        findWindowMs = Math.max(1, c.getLong("breakthroughs.find-window-seconds", 120)) * 1000L;
        minBreakthroughs = Math.max(1, c.getInt("breakthroughs.min-breakthroughs", 6));
        maxProductivePercent = Math.max(0, c.getDouble("breakthroughs.max-productive-percent", 50));
        for (String r : c.getStringList("breakthroughs.resources")) {
            if (resources.containsKey(r)) breakthroughResources.add(r);
            else log.warning("breakthroughs.resources: unknown resource '" + r + "'");
        }
    }

    public Resource resourceOf(Material m) {
        return resourceByBlock.get(m);
    }

    public String fillerOf(Material m) {
        return fillerByBlock.get(m);
    }

    /** "ancient_debris" -> "Ancient Debris" */
    public static String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("[_\\-]")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private static Set<Material> materials(java.util.List<String> names, String path, Logger log) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String name : names) {
            Material m = Material.matchMaterial(name);
            if (m == null || !m.isBlock()) log.warning("Unknown block '" + name + "' in " + path);
            else out.add(m);
        }
        return out;
    }
}
