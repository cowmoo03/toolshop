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
    /** mcmmo.skill-multiplier, keyed by lower-case mcMMO skill name. */
    public final java.util.Map<String, Double> skillMultiplier = new java.util.HashMap<>();
    public final boolean timberXpForAllLogs;
    /** mcmmo.pushed-logs-give-xp: grown logs a piston moves still give Woodcutting XP. */
    public final boolean pushedLogsGiveXp;
    /** mcmmo.disable-tree-feller: mcMMO's Tree Feller is switched off (the Legendary Axe's Timber replaces it). */
    public final boolean disableTreeFeller;
    /** synergy.*: legendary items boost their mcMMO skill (XP and super ability cooldown) per upgrade tier. */
    public boolean synergyEnabled;
    public int synergyXpPercentPerTier;
    public int synergyMaxXpPercent;
    public int synergyCooldownPercentPerTier;
    public int synergyMaxCooldownPercent;
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
    public final boolean skillsMenuEnabled;
    public final boolean skillsReplaceCommands;
    public final boolean skillsTidyCommands;
    public final boolean announcementEnabled;
    public final String announcementId;
    public final int announcementDelaySeconds;
    public final List<String> announcementLines;
    /** Blocks broken -> rank name, sorted. */
    public final TreeMap<Long, String> ranks = new TreeMap<>();
    /** Kills -> rank name, for swords. */
    public final TreeMap<Long, String> swordRanks = new TreeMap<>();
    public int[] lifestealPercent;
    public int[] beheadingChance;
    public int[] bountyPercent;
    /** Sword, trident and crossbow abilities against players are multiplied by this (PvP). */
    public double swordPvpMultiplier;
    public boolean harvestXpForAllCrops;
    public int[] growthAuraRadius;
    public int[] growthAuraChance;
    /** Tidal Pull strength per tier, in tenths of a block per tick. */
    public int[] tidalPullStrength;
    public int[] stormCallerCooldown;
    public double stormCallerDamage;
    public int[] arrowSaverChance;
    public int[] volleyArrows;
    public int[] blastBoltsDamage;
    public double blastBoltsRadius;
    public int[] seekerRadius;
    public int[] seekerTurn;
    public int[] quickDrawPower;
    public int[] frostSeconds;
    public int[] boostCharges;
    public double boostStrength;
    public int[] rocketSaverChance;

    public Settings(FileConfiguration c, Logger log) {
        effects = c.getBoolean("effects", true);
        requireSneak = c.getBoolean("require-sneak", true);
        lowDurabilityPercent = Math.max(0, c.getInt("low-durability-warning-percent", 10));
        requireLevels = c.getBoolean("mcmmo.require-levels", true);
        xpOnlyForHitBlock = c.getBoolean("mcmmo.xp-only-for-hit-block", true);
        pushedLogsGiveXp = c.getBoolean("mcmmo.pushed-logs-give-xp", true);
        disableTreeFeller = c.getBoolean("mcmmo.disable-tree-feller", true);
        synergyEnabled = c.getBoolean("synergy.enabled", true);
        synergyXpPercentPerTier = Math.max(0, c.getInt("synergy.xp-percent-per-tier", 2));
        synergyMaxXpPercent = Math.max(0, c.getInt("synergy.max-xp-percent", 30));
        synergyCooldownPercentPerTier = Math.max(0, c.getInt("synergy.cooldown-percent-per-tier", 4));
        synergyMaxCooldownPercent = Math.max(0, Math.min(90, c.getInt("synergy.max-cooldown-percent", 50)));
        org.bukkit.configuration.ConfigurationSection mults = c.getConfigurationSection("mcmmo.skill-multiplier");
        if (mults != null) {
            for (String key : mults.getKeys(false)) {
                double m = mults.getDouble(key, 1.0);
                if (m > 0) skillMultiplier.put(key.toLowerCase(java.util.Locale.ROOT), m);
            }
        }
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
        timberXpForAllLogs = c.getBoolean("timber.xp-for-all-logs", true);
        excavatorExhaustion = (float) Math.max(0, c.getDouble("excavator.exhaustion-per-block", 0.05));
        durabilityChance = readTierInts(c.getIntegerList("durability.chance"), new int[]{5, 10, 20});
        fireproofSeconds = readTierInts(c.getIntegerList("fireproof.seconds"), new int[]{60, 180, 300});
        recoverySeconds = readTierInts(c.getIntegerList("recovery.extra-seconds"), new int[]{60, 180, 300});
        soulboundSaves = readTierInts(c.getIntegerList("soulbound.saves"), new int[]{3, 5, 8});
        soulboundRechargeAnytime = "anytime".equalsIgnoreCase(c.getString("soulbound.recharge", "empty"));

        skillsMenuEnabled = c.getBoolean("skills-menu.enabled", true);
        skillsReplaceCommands = c.getBoolean("skills-menu.replace-mcmmo-commands", true);
        skillsTidyCommands = c.getBoolean("skills-menu.tidy-command-list", true);

        announcementEnabled = c.getBoolean("join-announcement.enabled", true);
        announcementId = c.getString("join-announcement.id", "");
        announcementDelaySeconds = Math.max(0, c.getInt("join-announcement.delay-seconds", 3));
        announcementLines = List.copyOf(c.getStringList("join-announcement.lines"));

        ConfigurationSection swordRankSection = c.getConfigurationSection("sword-ranks");
        if (swordRankSection != null) {
            for (String key : swordRankSection.getKeys(false)) {
                try {
                    swordRanks.put(Long.parseLong(key), swordRankSection.getString(key, key));
                } catch (NumberFormatException e) {
                    log.warning("sword-ranks: '" + key + "' isn't a number");
                }
            }
        }
        if (!swordRanks.containsKey(0L)) swordRanks.put(0L, "Rusty");
        lifestealPercent = readTierInts(c.getIntegerList("lifesteal.percent"), new int[]{5, 10, 15});
        beheadingChance = readTierInts(c.getIntegerList("beheading.chance"), new int[]{3, 6, 10});
        bountyPercent = readTierInts(c.getIntegerList("bounty.extra-percent"), new int[]{25, 50, 100});
        swordPvpMultiplier = Math.max(0, Math.min(1, c.getDouble("sword-pvp-multiplier", 0.5)));
        harvestXpForAllCrops = c.getBoolean("harvest.xp-for-all-crops", true);
        growthAuraRadius = readTierInts(c.getIntegerList("growth-aura.radius"), new int[]{4, 6, 8});
        growthAuraChance = readTierInts(c.getIntegerList("growth-aura.chance-per-second"), new int[]{4, 6, 8});
        tidalPullStrength = readTierInts(c.getIntegerList("tidal-pull.strength"), new int[]{6, 10, 14});
        stormCallerCooldown = readTierInts(c.getIntegerList("storm-caller.cooldown-seconds"), new int[]{30, 20, 10});
        stormCallerDamage = Math.max(0, c.getDouble("storm-caller.damage", 5));
        arrowSaverChance = readTierInts(c.getIntegerList("arrow-saver.chance"), new int[]{20, 35, 50});
        volleyArrows = readTierInts(c.getIntegerList("volley.extra-arrows"), new int[]{1, 2, 4});
        blastBoltsDamage = readTierInts(c.getIntegerList("blast-bolts.damage"), new int[]{2, 3, 5});
        blastBoltsRadius = Math.max(1, Math.min(6, c.getDouble("blast-bolts.radius", 2.5)));
        seekerRadius = readTierInts(c.getIntegerList("seeker.radius"), new int[]{6, 9, 12});
        seekerTurn = readTierInts(c.getIntegerList("seeker.turn-percent"), new int[]{15, 25, 35});
        quickDrawPower = readTierInts(c.getIntegerList("quick-draw.min-power-percent"), new int[]{70, 85, 100});
        frostSeconds = readTierInts(c.getIntegerList("frost-arrows.seconds"), new int[]{2, 3, 4});
        boostCharges = readTierInts(c.getIntegerList("boost.charges"), new int[]{1, 2, 3});
        boostStrength = Math.max(0.2, Math.min(3, c.getDouble("boost.strength", 1.0)));
        rocketSaverChance = readTierInts(c.getIntegerList("rocket-saver.chance"), new int[]{25, 50, 75});
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

    /** Rank for a tool: swords by kills, the others by blocks broken. */
    public String rankFor(ToolKind kind, long count) {
        if (kind != null && kind.countsKills()) return swordRanks.floorEntry(Math.max(0, count)).getValue();
        return rankFor(count);
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
