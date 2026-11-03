package io.github.cowmoo03.orewatch;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * OreWatch: flags likely x-ray users by comparing valuable ore veins found to stone mined.
 * It only alerts staff; it never punishes anyone on its own.
 */
public final class OreWatchPlugin extends JavaPlugin {
    public static final Component PREFIX = Component.text("[OreWatch] ", NamedTextColor.DARK_AQUA);
    private static final long SAVE_INTERVAL_TICKS = 20L * 60 * 5;

    private Rules rules;
    private Stats stats;
    private PlacedOres placed;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        reloadRules();

        stats = new Stats(getDataFolder(), getLogger());
        placed = new PlacedOres(getDataFolder(), getLogger());
        stats.prune(rules.historyDays);

        getServer().getPluginManager().registerEvents(new MiningListener(this), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("orewatch", "X-ray detection stats", new OreWatchCommand(this)));

        getServer().getScheduler().runTaskTimer(this, () -> {
            stats.prune(rules.historyDays);
            stats.saveIfDirty();
            placed.saveIfDirty();
        }, SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);
    }

    @Override
    public void onDisable() {
        if (stats != null) stats.save();
        if (placed != null) placed.saveIfDirty();
    }

    public void reloadRules() {
        reloadConfig();
        rules = new Rules(getConfig(), getLogger());
    }

    public Rules rules() { return rules; }
    public Stats stats() { return stats; }
    public PlacedOres placed() { return placed; }

    /** 3.456 -> "3.5", infinity -> "∞" */
    public static String formatRate(double rate) {
        if (Double.isInfinite(rate)) return "∞";
        return rate >= 100 ? String.valueOf(Math.round(rate)) : String.format("%.1f", rate);
    }

    /** Appends a timestamped line to a log file in plugins/OreWatch. */
    public void logLine(String fileName, String text) {
        String line = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " | " + text + System.lineSeparator();
        try {
            Path file = getDataFolder().toPath().resolve(fileName);
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            getLogger().warning("Could not write " + fileName + ": " + e.getMessage());
        }
    }
}
