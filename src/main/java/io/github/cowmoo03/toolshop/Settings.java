package io.github.cowmoo03.toolshop;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/** Config values, read on enable and on /toolshop reload. */
public final class Settings {

    /** One tier of an upgrade: the mcMMO level it needs and the items it costs. */
    public record Tier(int level, Map<Material, Integer> cost) {}

    public final boolean effects;
    public final boolean requireSneak;
    public final int lowDurabilityPercent;
    public final boolean requireLevels;
    public final boolean xpOnlyForHitBlock;
    public final Map<Material, Integer> createCost;
    private final Map<Upgrade, List<Tier>> tiers = new EnumMap<>(Upgrade.class);

    public final double drillMaxHardness;
    public final Set<Material> drillNeverBreak;
    public final int[] veinMaxBlocks;
    public final float veinExhaustion;
    public final Set<Material> veinOres;
    public final int[] timberMaxLogs;
    public final int timberSilkLeavesFromTier;
    public final int timberMinLeaves;
    public final float excavatorExhaustion;
    public final int[] durabilityChance;
    public final int[] fireproofSeconds;
    /** Extra seconds a dropped Recovery tool stays before despawning, per tier. */
    public final int[] recoverySeconds;
    public final int[] soulboundSaves;
    /** True = saves can be refilled whenever below max; false = only once all are used up. */
    public final boolean soulboundRechargeAnytime;
    public final boolean announcementEnabled;
    public final String announcementId;
    public final int announcementDelaySeconds;
    public final List<String> announcementLines;
    /** Blocks broken -> rank name, sorted. */
    public final TreeMap<Long, String> ranks = new TreeMap<>();

    public Settings(FileConfiguration c, Logger log) {
        effects = c.getBoolean("effects", true);
        requireSneak = c.getBoolean("require-sneak", true);
        lowDurabilityPercent = Math.max(0, c.getInt("low-durability-warning-percent", 10));
        requireLevels = c.getBoolean("mcmmo.require-levels", true);
        xpOnlyForHitBlock = c.getBoolean("mcmmo.xp-only-for-hit-block", true);
        createCost = readCost(c.getConfigurationSection("create-cost"), "create-cost", log);

        for (Upgrade u : Upgrade.values()) {
            List<Tier> list = new ArrayList<>();
            List<Map<?, ?>> raw = new ArrayList<>(c.getMapList("upgrades." + u.id()));
            // An older config may list fewer tiers than this version has: take the missing ones from the defaults.
            if (raw.size() < u.maxTier() && c.getDefaults() != null) {
                List<Map<?, ?>> defaults = c.getDefaults().getMapList("upgrades." + u.id());
                for (int i = raw.size(); i < Math.min(u.maxTier(), defaults.size()); i++) raw.add(defaults.get(i));
            }
            for (Map<?, ?> entry : raw) {
                int level = entry.get("level") instanceof Number n ? n.intValue() : 0;
                Map<Material, Integer> cost = new LinkedHashMap<>();
                if (entry.get("cost") instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        Material mat = Material.matchMaterial(String.valueOf(e.getKey()));
                        int amount = e.getValue() instanceof Number n ? n.intValue() : 0;
                        if (mat == null || !mat.isItem()) {
                            log.warning("Unknown item '" + e.getKey() + "' in upgrades." + u.id());
                        } else if (amount > 0) {
                            cost.put(mat, amount);
                        }
                    }
                }
                list.add(new Tier(level, Collections.unmodifiableMap(cost)));
            }
            if (list.size() < u.maxTier()) {
                log.warning("upgrades." + u.id() + " needs " + u.maxTier() + " tiers; the missing ones have no requirements.");
                while (list.size() < u.maxTier()) list.add(new Tier(0, Map.of()));
            }
            tiers.put(u, List.copyOf(list.subList(0, u.maxTier())));
        }

        drillMaxHardness = c.getDouble("drill.max-hardness", 5.0);
        drillNeverBreak = readMaterials(c.getStringList("drill.never-break"), "drill.never-break", log);
        drillNeverBreak.add(Material.ANCIENT_DEBRIS);
        veinMaxBlocks = readTierInts(c.getIntegerList("vein.max-blocks"), new int[]{8, 16, 32});
        veinExhaustion = (float) Math.max(0, c.getDouble("vein.exhaustion-per-block", 0.15));
        veinOres = readMaterials(c.getStringList("vein.ores"), "vein.ores", log);
        veinOres.remove(Material.ANCIENT_DEBRIS);
        timberMaxLogs = readTierInts(c.getIntegerList("timber.max-logs"), new int[]{16, 64, 150});
        timberSilkLeavesFromTier = c.getInt("timber.silk-leaves-from-tier", 3);
        timberMinLeaves = Math.max(0, c.getInt("timber.min-natural-leaves", 3));
        excavatorExhaustion = (float) Math.max(0, c.getDouble("excavator.exhaustion-per-block", 0.05));
        durabilityChance = readTierInts(c.getIntegerList("durability.chance"), new int[]{5, 10, 20});
        fireproofSeconds = readTierInts(c.getIntegerList("fireproof.seconds"), new int[]{60, 180, 300});
        recoverySeconds = readTierInts(c.getIntegerList("recovery.extra-seconds"), new int[]{60, 180, 300});
        soulboundSaves = readTierInts(c.getIntegerList("soulbound.saves"), new int[]{3, 5, 8});
        soulboundRechargeAnytime = "anytime".equalsIgnoreCase(c.getString("soulbound.recharge", "empty"));

        announcementEnabled = c.getBoolean("join-announcement.enabled", true);
        announcementId = c.getString("join-announcement.id", "");
        announcementDelaySeconds = Math.max(0, c.getInt("join-announcement.delay-seconds", 3));
        announcementLines = List.copyOf(c.getStringList("join-announcement.lines"));

        ConfigurationSection rankSection = c.getConfigurationSection("ranks");
        if (rankSection != null) {
            for (String key : rankSection.getKeys(false)) {
                try {
                    ranks.put(Long.parseLong(key), rankSection.getString(key, key));
                } catch (NumberFormatException e) {
                    log.warning("ranks: '" + key + "' isn't a number");
                }
            }
        }
        if (!ranks.containsKey(0L)) ranks.put(0L, "Rusty");
    }

    /** Requirements for reaching {@code tier} (1-based). */
    public Tier tier(Upgrade upgrade, int tier) {
        return tiers.get(upgrade).get(tier - 1);
    }

    public String rankFor(long blocks) {
        return ranks.floorEntry(Math.max(0, blocks)).getValue();
    }

    /** Value for a tier (1-based) from a per-tier list, clamped to the list. */
    public static int forTier(int[] values, int tier) {
        return values[Math.max(0, Math.min(values.length - 1, tier - 1))];
    }

    /** "16 iron ingots, 4 ender pearls" */
    public static String describeCost(Map<Material, Integer> cost) {
        if (cost.isEmpty()) return "free";
        List<String> parts = new ArrayList<>();
        cost.forEach((m, n) -> parts.add(n + " " + itemName(m, n)));
        return String.join(", ", parts);
    }

    public static String itemName(Material m, int amount) {
        String name = m.name().toLowerCase().replace('_', ' ');
        return amount == 1 || name.endsWith("s") ? name : name + "s";
    }

    private static Map<Material, Integer> readCost(ConfigurationSection s, String path, Logger log) {
        Map<Material, Integer> cost = new LinkedHashMap<>();
        if (s == null) return cost;
        for (String key : s.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            int amount = s.getInt(key);
            if (m == null || !m.isItem()) log.warning("Unknown item '" + key + "' in " + path);
            else if (amount > 0) cost.put(m, amount);
        }
        return cost;
    }

    private static Set<Material> readMaterials(List<String> names, String path, Logger log) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String name : names) {
            Material m = Material.matchMaterial(name);
            if (m == null) log.warning("Unknown block '" + name + "' in " + path);
            else out.add(m);
        }
        return out;
    }

    private static int[] readTierInts(List<Integer> values, int[] fallback) {
        if (values == null || values.isEmpty()) return fallback;
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = Math.max(1, values.get(i));
        return out;
    }
}
