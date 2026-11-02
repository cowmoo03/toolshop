package io.github.cowmoo03.toolshop;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Logger;

/** Config values, read once on enable and on /toolshop reload. */
public final class Settings {
    public final long durationMs;
    public final long graceMs;
    public final Material currency;
    public final boolean unbreakableWhileActive;

    public final boolean timberRequireSneak;
    public final int timberMaxLogs;
    public final int timberMinLeaves;
    public final int timberMaxRadius;

    public final boolean veinRequireSneak;
    public final int veinMaxBlocks;
    public final float veinExhaustionPerBlock;
    public final Set<Material> veinOres;

    private final FileConfiguration config;

    public Settings(FileConfiguration config, Logger log) {
        this.config = config;
        this.durationMs = Math.round(Math.max(0.01, config.getDouble("duration-days", 7)) * 86_400_000L);
        this.graceMs = Math.round(Math.max(0, config.getDouble("grace-hours", 24)) * 3_600_000L);

        Material cur = Material.matchMaterial(config.getString("currency", "NETHERITE_INGOT"));
        if (cur == null || !cur.isItem()) {
            log.warning("Unknown currency in config.yml, using NETHERITE_INGOT");
            cur = Material.NETHERITE_INGOT;
        }
        this.currency = cur;
        this.unbreakableWhileActive = config.getBoolean("unbreakable-while-active", false);

        this.timberRequireSneak = config.getBoolean("timber.require-sneak", true);
        this.timberMaxLogs = Math.max(1, config.getInt("timber.max-logs", 200));
        this.timberMinLeaves = Math.max(0, config.getInt("timber.min-natural-leaves", 3));
        this.timberMaxRadius = Math.max(1, config.getInt("timber.max-radius", 8));

        this.veinRequireSneak = config.getBoolean("vein.require-sneak", true);
        this.veinMaxBlocks = Math.max(1, config.getInt("vein.max-blocks", 64));
        this.veinExhaustionPerBlock = (float) Math.max(0, config.getDouble("vein.exhaustion-per-block", 0.15));

        Set<Material> ores = EnumSet.noneOf(Material.class);
        for (String name : config.getStringList("vein.ores")) {
            Material m = Material.matchMaterial(name);
            if (m == null || !m.isBlock()) {
                log.warning("Unknown ore in config.yml: " + name);
            } else if (m == Material.ANCIENT_DEBRIS) {
                log.warning("ANCIENT_DEBRIS is not allowed in vein.ores and was skipped");
            } else {
                ores.add(m);
            }
        }
        this.veinOres = ores;
    }

    public int upgradePrice(ToolType type) {
        return Math.max(0, config.getInt("prices." + type.id() + ".upgrade", type == ToolType.VEIN ? 3 : 2));
    }

    public int renewPrice(ToolType type) {
        return Math.max(0, config.getInt("prices." + type.id() + ".renew", 1));
    }

    public boolean requireSneak(ToolType type) {
        return type == ToolType.TIMBER ? timberRequireSneak : veinRequireSneak;
    }

    /** e.g. "1 netherite ingot" or "3 netherite ingots". */
    public String currencyAmount(int amount) {
        String name = currency.name().toLowerCase().replace('_', ' ');
        return amount + " " + name + (amount == 1 ? "" : "s");
    }
}
